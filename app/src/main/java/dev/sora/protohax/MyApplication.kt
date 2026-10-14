package dev.sora.protohax

import android.annotation.SuppressLint
import android.app.Application
import dev.sora.protohax.hwid.HwidGate
import dev.sora.protohax.relay.AuthHttpClient
import dev.sora.protohax.relay.XboxAuthWarmUp
import dev.sora.protohax.relay.netty.log.NettyLoggerFactory
import dev.sora.protohax.relay.service.AppService
import dev.sora.protohax.ui.overlay.OverlayManager
import dev.sora.protohax.util.CrashHandler
import io.netty.util.internal.logging.InternalLoggerFactory
import dev.sora.relay.utils.logError

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

		// First, before anything in this app can fail: from here on every unhandled exception is
		// written to a file (filesDir/crashes) instead of only being printed to logcat, which is
		// the only way to tell "the app closed itself" apart from "this device was refused" on a
		// phone nobody can attach a debugger to. See dev.sora.protohax.util.CrashHandler.
		CrashHandler.install(this)

		InternalLoggerFactory.setDefaultFactory(NettyLoggerFactory())

		density = resources.displayMetrics.density

        instance = this

		// ProtoHax authenticates with Xbox Live inside the packet pipeline, when the game logs in. Doing the
		// same login here - long before the game connects - means the first session of the day already finds
		// the identity token in the cache and does not have to wait for (or fail at) the whole login. Failures
		// are only logged here; the relay start reports them to the user (XboxAuthWarmUp.warmUp).
		// The Xbox Live auth servers refused every TLS handshake of ProtoHax's stock OkHttp client
		// (HANDSHAKE_FAILURE_ON_CLIENT_HELLO). Swap in the client that retries a refused handshake with
		// another ClientHello - before anything logs in.
		AuthHttpClient.install()

			// The device approval check, layer (a): as early in the process's life as possible, before any
			// activity and before any relay code runs. The check reads the list repository and, when this
			// device is not on it, locks the app: the lock screen shows the device's ID with a Copy ID button
			// (so the user can send it) and unlocks by itself once the ID is on the list. Only a *fatal*
			// refusal terminates the process on purpose - a repackaged or debugged APK, or a refusal that
			// arrives while the relay is already running (HwidGate.abort -> a log line, the ID on the
			// clipboard, then a deliberate process exit - not an accidental crash). It runs off the main
			// thread, so start-up is never blocked on the network; the second layer, inside MinecraftRelay, is
			// what makes the relay unusable until the verdict is in (see MinecraftRelay.announceRelayUp /
			// module loader).
			// Deliberately after AuthHttpClient.install(): the check uses the app's HTTP client.
			HwidGate.startAsync(this, "app start")

		// A warm-up: it makes the *first* login packet of the day fast, and nothing else in the app
		// depends on it. It is the first thing that touches the stored accounts, so a failure here
		// (an account file this install cannot read, for example) must cost the user a slower login
		// inside the game, not the whole app on start-up.
		try {
			XboxAuthWarmUp.warmUp("app started", notifyUser = false)
		} catch (t: Throwable) {
			logError("xbox login warm-up at app start", t)
		}
    }

    companion object {
        lateinit var instance: MyApplication
            private set

		var density: Float = 1f
			private set

		@SuppressLint("StaticFieldLeak")
		val overlayManager = OverlayManager().also {
			AppService.addListener(it)
		}
    }
}
