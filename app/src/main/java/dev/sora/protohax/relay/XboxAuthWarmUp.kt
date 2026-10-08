package dev.sora.protohax.relay

import android.os.Handler
import android.os.Looper
import dev.sora.protohax.MyApplication
import dev.sora.protohax.R
import dev.sora.protohax.util.ContextUtils.toast
import dev.sora.relay.session.listener.xbox.RelayListenerXboxLogin
import dev.sora.relay.session.listener.xbox.cache.XboxIdentityTokenCacheFileSystem
import dev.sora.relay.utils.logError
import dev.sora.relay.utils.logInfo
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Performs the Xbox Live login of the current account in the background.
 *
 * ProtoHax authenticates the player *inside* the packet pipeline: the complete login - Microsoft access
 * token, the Xbox Live user / device / title / XSTS tokens and the multiplayer.minecraft.net chain - is
 * fetched when the game sends its LoginPacket ([RelayListenerXboxLogin.onPacketOutbound]), and the relay
 * cannot answer the game until all of that is done. Everything that goes wrong there costs the whole game
 * session:
 *
 *  * a slow network ends in the game's own login timeout ("client disconnect: disconnect.lost"),
 *  * a request that fails - the reported session logged
 *    "login failed: javax.net.ssl.SSLHandshakeException ... SSLV3_ALERT_HANDSHAKE_FAILURE ...
 *    HANDSHAKE_FAILURE_ON_CLIENT_HELLO" - ends in a disconnect as well.
 *
 * Doing the same login here, as soon as the app or the VPN is started, means the Xbox identity token is
 * already in [tokenCacheFile] (the cache the relay reads too, and it is valid for about an hour) and the TLS
 * connections to the login services are already open, so the login packet only needs the fast chain request.
 * A login that fails anyway is logged and (when the user is about to play) shown, instead of leaving them in
 * the game's "connection lost" screen.
 */
object XboxAuthWarmUp {

    /**
     * The cache the relay's own login uses: [XboxIdentityTokenCacheFileSystem] is keyed by the account remark
     * and the device type, so a warm-up of one account never covers another one. It lives in filesDir (and
     * not in cacheDir) because the system may clear the cache directory at any time.
     */
    val tokenCacheFile = File(MyApplication.instance.filesDir, "token_cache.json")

    private const val ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 1000L

    /** One recent warm-up is enough; keeps the app start and the VPN start from doing the same work twice. */
    private const val FRESH_MS = 60_000L

    private val inFlight = AtomicBoolean(false)

    @Volatile
    private var lastSuccessAt = 0L

    /**
     * @param reason what triggered the warm-up (only for the log)
     * @param notifyUser whether a failure is worth a toast - true when the user is about to play
     */
    fun warmUp(reason: String, notifyUser: Boolean) {
        if (System.currentTimeMillis() - lastSuccessAt < FRESH_MS) {
            return
        }

        val account = AccountManager.currentAccount ?: return
        if (!inFlight.compareAndSet(false, true)) return

        thread(name = "xbox-login-warmup") {
            var failure: Throwable? = null

            try {
                val listener = RelayListenerXboxLogin({ account.refresh() }, account.platform).also {
                    it.tokenCache = XboxIdentityTokenCacheFileSystem(tokenCacheFile, account.remark)
                }

                for (attempt in 1..ATTEMPTS) {
                    try {
                        listener.forceFetchChain()
                        failure = null
                        break
                    } catch (t: Throwable) {
                        failure = t
                        logError("xbox login of ${account.remark} failed ($reason, attempt $attempt/$ATTEMPTS)", t)
                        if (attempt < ATTEMPTS) {
                            Thread.sleep(RETRY_DELAY_MS)
                        }
                    }
                }
            } catch (t: Throwable) {
                failure = t
                logError("xbox login warm-up ($reason)", t)
            } finally {
                inFlight.set(false)
            }

            val result = failure
            if (result == null) {
                lastSuccessAt = System.currentTimeMillis()
                logInfo("xbox login ready (${account.remark})")
            } else if (notifyUser) {
                notifyFailure(result)
            }
        }
    }

    private fun notifyFailure(t: Throwable) {
        var root: Throwable = t
        while (true) {
            val next = root.cause
            if (next == null || next === root) break
            root = next
        }
        val message = root.message?.takeIf { it.isNotBlank() } ?: root.javaClass.simpleName

        Handler(Looper.getMainLooper()).post {
            MyApplication.instance.toast(MyApplication.instance.getString(R.string.xbox_login_failed, message))
        }
    }
}
