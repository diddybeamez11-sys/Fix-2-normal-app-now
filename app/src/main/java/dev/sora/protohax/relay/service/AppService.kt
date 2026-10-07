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
import android.os.Handler
import android.os.Looper
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
import kotlin.concurrent.thread


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

        val action = intent.action
        try {
            if (ACTION_START == action) {
                // Promote the service immediately. Starting the VPN/TUN can take long enough
                // that Android may otherwise kill the service before it enters foreground.
                startForeground(1, createNotification())
                startVPN()
            } else {
                stopVPN()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } catch (t: Throwable) {
            logError("command", t)
            if (ACTION_START == action) {
                // Never leave a foreground service (and its notification) running when the VPN
                // could not be started; the user has to know why nothing happened.
                toast(getString(R.string.vpn_start_failed, t.message ?: t.javaClass.simpleName))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startVPN() {
        val targetPackage = MainActivity.targetPackage
        if (targetPackage.isEmpty()) {
            logError("no target application selected, aborting VPN start")
            toast(R.string.dashboard_no_application)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
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
        if (vpnDescriptor == null) {
            // establish() returns null when the VPN consent dialog was never accepted or when
            // the system refuses to create a second tunnel (e.g. an always-on VPN is active).
            logError("establish VPN failed: VPN permission denied or another VPN is active")
            toast(R.string.vpn_permission_denied)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
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

        // The overlay GUI has to be brought up even if the relay fails to start, and a broken
        // overlay must not prevent the relay from running, so both steps are isolated from each
        // other and neither can silently swallow the other one.
        try {
            serviceListeners.forEach { it.onServiceStarted() }
            logInfo("overlay GUI created")
            toast(R.string.overlay_hint)
        } catch (t: Throwable) {
            logError("start overlay", t)
            toast(getString(R.string.overlay_start_failed, t.message ?: t.javaClass.simpleName))
            if (!android.provider.Settings.canDrawOverlays(this)) {
                toast(R.string.request_overlay)
            }
        }

        // announceRelayUp() blocks on MinecraftRelay.loaderThread.join() while every module and
        // Lua script is loaded. Doing that here would block the main thread immediately after the
        // overlay windows were added, starving the Compose recomposer (AndroidUiDispatcher.Main)
        // that is what actually makes the in-game menu interactive. So start it off-thread.
        thread(name = "relay-start") {
            try {
                MinecraftRelay.announceRelayUp()
            } catch (t: Throwable) {
                logError("start relay", t)
                // the overlay is up but no packets will be processed, so say so instead of leaving
                // the user wondering why the cheats do nothing
                Handler(Looper.getMainLooper()).post {
                    toast(getString(R.string.relay_start_failed, t.message ?: t.javaClass.simpleName))
                }
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
        // ConnectivityManager requires ACCESS_NETWORK_STATE (declared in the manifest) and can
        // still fail on some devices, so a lookup error must never abort the VPN startup: fall
        // back to the IPv4-only configuration instead.
        try {
            val connectivityManager = this.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return true to false
            val activeNetworkProperties = connectivityManager.getLinkProperties(activeNetwork) ?: return true to false
            val interfaceName = activeNetworkProperties.interfaceName

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
                return hasIPv4 to hasIPv6
            }
        } catch (t: Throwable) {
            logError("check net state", t)
        }

        return true to false
    }

    /**
     * The selected application may have been uninstalled since it was picked, in which case
     * resolving its label throws NameNotFoundException. That used to take createNotification()
     * and therefore startForeground() - and with it the whole VPN start and the in-game GUI -
     * down with it.
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
