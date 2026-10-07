package dev.sora.protohax.relay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import dev.sora.protohax.MyApplication
import dev.sora.protohax.R
import dev.sora.protohax.relay.MinecraftRelay
import dev.sora.protohax.ui.activities.MainActivity
import dev.sora.protohax.ui.components.screen.settings.Settings
import dev.sora.protohax.util.ContextUtils.getApplicationName
import dev.sora.protohax.util.ContextUtils.toast
import dev.sora.relay.utils.logError
import dev.sora.relay.utils.logInfo
import libmitm.Libmitm
import libmitm.TUN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface


class AppService : VpnService() {

    private lateinit var windowManager: WindowManager

    private var vpnDescriptor: ParcelFileDescriptor? = null
    private var tun: TUN? = null

    override fun onCreate() {
        super.onCreate()
        val notificationManager = getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            notificationManager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, getString(
                    R.string.app_name
                ), NotificationManager.IMPORTANCE_LOW))
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

		MyApplication.overlayManager.currentContext = this
    }

    override fun onDestroy() {
		logInfo("VPN service destroyed")
		stopVPN()
		MyApplication.overlayManager.currentContext = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: return START_NOT_STICKY

        if (ACTION_START == intent.action) {
            try {
                // Promote the service immediately. Starting the VPN/TUN can take long enough
                // that Android may otherwise kill the service before it enters foreground.
                startForeground(1, createNotification())
            } catch (t: Throwable) {
                logError("startForeground", t)
                toastStartFailure(t)
                stopSelf()
                return super.onStartCommand(intent, flags, startId)
            }

            try {
                startVPN()
            } catch (t: Throwable) {
                // This used to be swallowed by a single catch-all around the whole command,
                // which looked exactly like "the GUI does not load": the tunnel never came up,
                // the overlay listeners were never notified and the foreground service stayed
                // around pretending everything was fine. Report it and tear down instead.
                logError("command", t)
                toastStartFailure(t)
                stopVPN()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } else {
            try {
                stopVPN()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (t: Throwable) {
                logError("command", t)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun toastStartFailure(t: Throwable) {
        try {
            toast(getString(R.string.vpn_start_failed, t.message ?: t.javaClass.simpleName))
        } catch (ignored: Throwable) {
            logError("toast", ignored)
        }
    }

    private fun startVPN() {
        val targetPackage = MainActivity.targetPackage
        if (targetPackage.isEmpty()) {
            // addAllowedApplication("") throws, which used to abort the start silently.
            // The caller turns this into a single user visible message.
            throw IllegalStateException("no target application selected")
        }

        val (hasIPv4, hasIPv6) = when(Settings.ipv6Status.getValue(this)) {
			Settings.IPv6Choices.AUTOMATIC -> checkNetState()
			Settings.IPv6Choices.ENABLED -> true to true
			Settings.IPv6Choices.DISABLED -> true to false
			Settings.IPv6Choices.V6ONLY -> false to true
		}

        val builder = Builder()
        builder.setBlocking(true)
        builder.setMtu(VPN_MTU)
        builder.setSession("ProtoHax")
        builder.addAllowedApplication(targetPackage)
        builder.addDnsServer("8.8.8.8")
        // ipv4
        if (hasIPv4) {
            builder.addAddress(PRIVATE_VLAN4_CLIENT, 30)
            builder.addRoute("0.0.0.0", 0)
        }
        // ipv6
        if (hasIPv6) {
            builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)
            builder.addRoute("::", 0)
        }

        val vpnDescriptor = builder.establish()
            ?: throw IllegalStateException("VpnService.Builder.establish() returned null")
        this.vpnDescriptor = vpnDescriptor

        val tun = TUN().apply {
            fileDescriber = vpnDescriptor.fd
            mtu = VPN_MTU
            iPv6Config = when {
                hasIPv4 && hasIPv6 -> Libmitm.IPv6Enable
                hasIPv4 -> Libmitm.IPv6Disable
                hasIPv6 -> Libmitm.IPv6Only
                else -> error("invalid state")
            }
        }
        this.tun = tun
        tun.start()
        logInfo("netstack started")
        isActive = true

        // Bring the in-game overlay up first and keep it isolated from the relay. Both steps
        // used to share one try block, so any relay failure (port already bound, loader thread
        // interrupted, ...) silently suppressed the GUI - and vice versa.
        try {
            serviceListeners.forEach { it.onServiceStarted() }
        } catch (t: Throwable) {
            logError("start callback", t)
        }

        try {
            MinecraftRelay.announceRelayUp()
        } catch (t: Throwable) {
            logError("relay start", t)
            try {
                toast(getString(R.string.relay_start_failed, t.message ?: t.javaClass.simpleName))
            } catch (ignored: Throwable) {
                logError("toast", ignored)
            }
        }
    }

    private fun stopVPN() {
        isActive = false
		vpnDescriptor?.close()
		tun?.let {
			try {
				serviceListeners.forEach { l -> l.onServiceStopped() }
			} catch (t: Throwable) {
				logError("stop callback", t)
			}
			Thread(it::close).start()
		}
    }

    private fun checkNetState(): Pair<Boolean, Boolean> {
        // Detection needs ACCESS_NETWORK_STATE and touches system APIs that can throw.
        // It must never abort the VPN start: falling back to IPv4-only keeps the tunnel
        // (and therefore the in-game overlay GUI) working.
        return try {
            val connectivityManager = this.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return true to false
            val interfaceName = connectivityManager.getLinkProperties(activeNetwork)?.interfaceName
                ?: return true to false

            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val ni = networkInterfaces.nextElement()
                if (ni.name != interfaceName) continue

                var hasIPv4 = false
                var hasIPv6 = false
                for (addr in ni.interfaceAddresses) {
                    if (addr.address is Inet6Address) {
                        hasIPv6 = true
                    } else if (addr.address is Inet4Address) {
                        hasIPv4 = true
                    }
                }
                // an interface reporting no usable address must not disable both stacks
                return if (hasIPv4 || hasIPv6) hasIPv4 to hasIPv6 else true to false
            }

            true to false
        } catch (t: Throwable) {
            logError("checkNetState", t)
            true to false
        }
    }

    /**
     * The selected application may have been uninstalled, in which case resolving its label
     * throws. That used to take the whole foreground notification - and with it the VPN start
     * and the in-game GUI - down.
     */
    private fun targetAppName(): String {
        val targetPackage = MainActivity.targetPackage
        return try {
            packageManager.getApplicationName(targetPackage)
        } catch (t: Throwable) {
            logError("resolve target app name", t)
            targetPackage
        }
    }

    private fun createNotification(): Notification {
        val flag = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val intent = Intent(this, MainActivity::class.java)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        intent.action = Intent.ACTION_MAIN
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flag)

        val stopIntent = Intent(ACTION_STOP)
        stopIntent.setPackage(packageName)
        val pendingIntent1 = PendingIntent.getForegroundService(this, 1, stopIntent, flag)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(
                R.string.proxy_notification, getString(R.string.app_name), targetAppName()))
            .setSmallIcon(R.drawable.notification_icon)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setOngoing(true)
            .setContentIntent(pendingIntent)
			.addAction(R.drawable.notification_icon, getString(R.string.dashboard_fab_disconnect), pendingIntent1)

        return builder.build()
    }

    companion object {
        const val ACTION_START = "dev.sora.libmitm.vpn.start"
        const val ACTION_STOP = "dev.sora.libmitm.vpn.stop"
        const val CHANNEL_ID = "dev.sora.protohax.NOTIFICATION_CHANNEL_ID"

        const val VPN_MTU = 1500
        const val PRIVATE_VLAN4_CLIENT = "10.13.37.1"
        const val PRIVATE_VLAN6_CLIENT = "1337::1"

        var isActive = false
        private val serviceListeners = mutableSetOf<ServiceListener>()

        fun addListener(listener: ServiceListener) {
            serviceListeners.add(listener)
        }

        fun removeListener(listener: ServiceListener) {
            serviceListeners.remove(listener)
        }
    }
}
