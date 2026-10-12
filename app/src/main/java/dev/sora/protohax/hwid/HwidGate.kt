package dev.sora.protohax.hwid

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import dev.sora.protohax.R
import dev.sora.protohax.hwid.pure.FetchResult
import dev.sora.protohax.hwid.pure.GatePolicy
import dev.sora.protohax.relay.service.AppService
import dev.sora.protohax.ui.activities.LicenseLock
import dev.sora.relay.utils.logError
import dev.sora.relay.utils.logInfo
import dev.sora.relay.utils.logWarn
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The gate. One object, three jobs:
 *
 *  * it decides *once per app start* (and then every [LicenseConfig.RECHECK_INTERVAL_MS], and every
 *    [LicenseConfig.LOCK_RETRY_INTERVAL_MS] while the lock screen is up) whether this device may run,
 *    by asking [WhitelistClient] for the list and [GatePolicy] for the verdict;
 *  * it is what the enforcement points call: the app's start-up
 *    ([dev.sora.protohax.MyApplication.onCreate] -> [startAsync]), the relay
 *    ([dev.sora.protohax.relay.MinecraftRelay] -> [awaitAllowed] before modules register and before the
 *    relay socket is bound) and the VPN service ([dev.sora.protohax.relay.service.AppService] ->
 *    [isLocked]);
 *  * it is what keeps the *state* of that decision: the verdict the UI reads ([status]), the timestamp of
 *    the last successful check, and the fact that the operator removed this device (which is what makes a
 *    removal stick while the app is offline).
 *
 * ## What happens when the verdict is "no"
 *
 * Not a crash, and not an exception: [Phase.LOCKED] (or [Phase.REVOKED]) is published, the UI is taken
 * over by [dev.sora.protohax.ui.activities.LicenseLockActivity] - which shows this device's ID and
 * copies it, so the user can send it to the operator - and the relay refuses to register its modules or
 * bind its socket. The app unlocks by itself as soon as a later check finds the ID again: the lock
 * screen asks every [LicenseConfig.LOCK_RETRY_INTERVAL_MS], and the gate re-checks every
 * [LicenseConfig.RECHECK_INTERVAL_MS] in the background.
 *
 * Two refusals are *fatal* instead, and terminate the process deliberately ([abort]): a device whose
 * APK is not the one the operator signed ([LicenseConfig.INTEGRITY_FAILURE_IS_FATAL]), and a device that
 * is refused while it is already running the relay ([LicenseConfig.REVOKE_ENDS_THE_PROCESS_WHEN_RUNNING])
 * - there is a tunnel and a bound socket at that moment, and stopping is the point. Neither prints a
 * stack trace or throws; both log a line, copy the ID, tell the user, and exit.
 *
 * No network call happens outside [WhitelistClient].
 */
internal object HwidGate {

    // ------------------------------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------------------------------

    private const val KEY_LAST_VERIFIED = "last_verified_at"
    private const val KEY_LAST_VERSION = "last_accepted_version"
    private const val KEY_MAX_WALL_CLOCK = "max_wall_clock_seen"
    private const val KEY_REVOKED_AT = "revoked_at"

    /**
     * What the app currently knows. [PENDING] and [CHECKING] both mean "no verdict yet" and never lock
     * anything - the app starts, the check runs, and only a verdict that says "no" takes the UI over.
     */
    enum class Phase {
        PENDING,
        CHECKING,
        ALLOWED,
        OFFLINE,
        /** Not on the list (never approved, or approved and removed while the app was not looking). */
        LOCKED,
        /** The operator removed this device; remembered locally, so it survives being offline. */
        REVOKED,
        /** A blanket refusal: this binary is not the one the operator signed. The process is going away. */
        FATAL
    }

    private val lock = Object()

    /** Guarded by [lock]; [@Volatile] so the UI thread can read the phase without taking the lock. */
    @Volatile
    private var checking = false

    @Volatile
    private var decision: GatePolicy.Decision? = null

    @Volatile
    private var decisionAtMs = 0L

    @Volatile
    private var decisionReason: String = "no check has run yet"

    @Volatile
    private var notices: List<String> = emptyList()

    @Volatile
    private var context: Context? = null

    /** Set once the relay is actually bound, i.e. the app is doing the work a removal has to stop. */
    @Volatile
    private var relayStarted = false

    private val aborted = AtomicBoolean(false)

    private val recheckScheduled = AtomicBoolean(false)

    private val scheduler: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "hwid-recheck").apply { isDaemon = true }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------------------------------------

    /**
     * Starts the check in the background, once. Called from `MyApplication.onCreate`, i.e. before any
     * activity or service of the app exists.
     *
     * It returns immediately on purpose - blocking the main thread on a network round trip is how you get
     * an ANR - so the *enforcement* of the verdict does not live here alone: [awaitAllowed] is what the
     * relay and the modules go through, and [LicenseLock] is what keeps the UI locked.
     */
    fun startAsync(context: Context, reason: String = "app start") {
        this.context = context.applicationContext
        startCheck(reason)
    }

    /** Triggers a fresh check in the background (the "Check again" buttons, the lock screen's timer). */
    fun refreshAsync(reason: String = "manual refresh") {
        startCheck(reason)
    }

    /** Called by the relay once its socket is bound. From here on a refusal terminates the process. */
    fun markRelayStarted() {
        relayStarted = true
    }

    /**
     * Blocks until a verdict exists (at most [timeoutMs]) and returns whether the app may run.
     *
     * Callers are the relay's module loader and its bind path, both of which already run off the main
     * thread. On "no" this locks the app (or terminates it, see [refuse]), so a caller that ignores the
     * return value still cannot end up running: the relay is never bound.
     *
     * @return true only when the device is allowed. Never returns true after [abort] has run.
     */
    fun awaitAllowed(where: String, timeoutMs: Long = LicenseConfig.VERDICT_WAIT_TIMEOUT_MS): Boolean {
        if (aborted.get()) return false

        val app = context
        if (app == null) {
            // Nothing started the gate. Refusing is the only safe answer.
            abort("the check was never started", R.string.hwid_not_allowed)
            return false
        }

        val deadline = SystemClock.uptimeMillis() + timeoutMs
        synchronized(lock) {
            while (checking) {
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0L) break
                try {
                    lock.wait(minOf(remaining, 500L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        if (aborted.get()) return false

        val current = decision
        if (current == null) {
            // The check never produced a verdict (still running after the timeout, or it never ran).
            // Apply the offline rules directly from the stored state rather than assuming "yes".
            val fallback = offlineFallback(app, "the check did not finish in time")
            publish(fallback)
            if (fallback.allow) {
                logWarn("${LicenseConfig.LOG_TAG}: $where proceeded on the offline rules: ${fallback.reason}")
                return true
            }
            refuse(app, fallback, where)
            return false
        }

        if (!current.allow) {
            refuse(app, current, where)
            return false
        }

        // The verdict is there and it is "yes". If it is old, refresh it in the background - but do not
        // make the user wait: the relay gate is not the place for a 12 second HTTP round trip.
        val ageMs = System.currentTimeMillis() - decisionAtMs
        if (ageMs > LicenseConfig.RECHECK_INTERVAL_MS) {
            logInfo("${LicenseConfig.LOG_TAG}: $where used a verdict that is ${ageMs / 60_000} minutes old, refreshing")
            startCheck("stale verdict at $where")
        }
        return true
    }

    /** True while this device must not be running: the UI locks and the relay refuses. */
    fun isLocked(): Boolean = status().locked

    // ------------------------------------------------------------------------------------------------
    // The check itself
    // ------------------------------------------------------------------------------------------------

    private fun startCheck(reason: String) {
        if (aborted.get()) return

        val app = context
        if (app == null) {
            // Nothing started the gate with a context, so there is nothing to check against. Refusing is
            // the only safe answer.
            publish(refusal("the check has no application context", fatal = true))
            abort(decisionReason, R.string.hwid_not_allowed)
            return
        }

        synchronized(lock) {
            if (checking) return
            checking = true
        }
        val thread = Thread({ runCheck(app, reason) }, "hwid-check")
        thread.isDaemon = true
        thread.start()
    }

    private fun runCheck(app: Context, reason: String) {
        try {
            val store = HwidStore.open(app)
            val now = System.currentTimeMillis()
            val lastVerifiedAt = store.getLong(KEY_LAST_VERIFIED, 0L).takeIf { it > 0L }
            val lastVersion = store.getLong(KEY_LAST_VERSION, 0L)
            val maxWallClock = store.getLong(KEY_MAX_WALL_CLOCK, 0L)
            val revokedAt = store.getLong(KEY_REVOKED_AT, 0L).takeIf { it > 0L }

            // A device the operator removed is locked from the first milliseconds, before any network
            // call: only a successful check that finds the ID again lifts that. This is what makes the
            // removal hold while the app is offline, and it is also why a removed user sees the lock
            // screen (with the Copy ID button) immediately instead of a dashboard flash.
            if (isRevoked(revokedAt, lastVerifiedAt)) {
                publish(
                    refusal(
                        "this device was removed from the list by the operator " +
                                "(${GatePolicy.formatInstant(revokedAt!!)}); checking whether it was added back",
                        fatal = false,
                        revoked = true
                    )
                )
            }

            // 1. Anti-tamper: a repackaged APK, a debugger, a rooted ROM. No network needed.
            val integrity = Integrity.evaluate(app)
            notices = integrity.warnings
            if (!integrity.ok) {
                publish(
                    refusal(
                        integrity.problems.joinToString("; "),
                        fatal = LicenseConfig.INTEGRITY_FAILURE_IS_FATAL
                    )
                )
                return
            }

            // 2. The identifier this device is approved by (the ANDROID_ID unless configured otherwise).
            val approvalId = DeviceId.approvalId(app)
            if (approvalId.isBlank()) {
                publish(
                    refusal(
                        "this device does not expose a usable ${DeviceId.approvalIdName()}, so it cannot be " +
                                "approved; set LicenseConfig.APPROVAL_ID_MODE to the other identifier if this " +
                                "is a device you want to support",
                        fatal = false
                    )
                )
                return
            }
            val secondary = if (LicenseConfig.ACCEPT_IDS_EITHER_WAY) DeviceId.secondaryIds(app) else emptyList()

            // 3. The list, and the decision.
            val fetch = WhitelistClient.fetch(LicenseConfig.REQUIRE_SIGNATURE)
            val result = GatePolicy.decide(
                nowMs = now,
                lastVerifiedAtMs = lastVerifiedAt,
                lastAcceptedVersion = lastVersion,
                highestWallClockSeenMs = maxWallClock,
                hwid = approvalId,
                alsoAcceptable = secondary,
                revokedAtMs = revokedAt,
                fetch = fetch,
                requireSignature = LicenseConfig.REQUIRE_SIGNATURE,
                publicKeyBase64 = LicenseConfig.SIGNING_PUBLIC_KEY_BASE64,
                tamperFailsClosed = LicenseConfig.TAMPER_FAILS_CLOSED,
                graceMs = LicenseConfig.GRACE_PERIOD_MS,
                clockToleranceMs = LicenseConfig.CLOCK_ROLLBACK_TOLERANCE_MS
            )

            // 4. Remember what this run proved: the grace period is measured from it, a removal survives
            //    a restart (and an offline start), and being added back clears the removal.
            if (result.allow) {
                store.putLong(KEY_REVOKED_AT, 0L)
            } else if (result.revoked) {
                store.putLong(KEY_REVOKED_AT, now)
            }
            if (result.persist) {
                store.putLong(KEY_LAST_VERIFIED, now)
                result.acceptedVersion?.let { store.putLong(KEY_LAST_VERSION, it) }
            }
            if (now > maxWallClock) store.putLong(KEY_MAX_WALL_CLOCK, now)

            publish(result)
            logInfo("${LicenseConfig.LOG_TAG}: check ($reason): ${result.reason}")

            // Keep asking while the device is not approved - a lock screen is waiting for the answer to
            // change - and while it is approved online, so a removal arrives on its own. The one case
            // where asking again soon is pointless is an offline run: there is no connection to ask over.
            if (!(result.allow && result.offline)) schedulePeriodicRecheck()
        } catch (t: Throwable) {
            // An unexpected failure must not become an implicit "allow". The device gets the offline
            // rules, i.e. it only runs if it verified recently, and never if it never verified at all.
            logError("${LicenseConfig.LOG_TAG}: the check failed unexpectedly", t)
            val fallback = offlineFallback(app, "the check failed (${t.javaClass.simpleName}: ${t.message})")
            publish(fallback)
        } finally {
            synchronized(lock) {
                checking = false
                lock.notifyAll()
            }
            // Enforce: whoever asked for this check, a device that may not run is locked (or, if it is
            // already running the relay, stopped).
            val current = decision
            if (current != null && !current.allow) {
                refuse(app, current, "the check ($reason)")
            }
        }
    }

    /** The offline rules, applied directly from the stored state (used when no verdict could be produced). */
    private fun offlineFallback(app: Context, because: String): GatePolicy.Decision {
        val store = HwidStore.open(app)
        return GatePolicy.decide(
            nowMs = System.currentTimeMillis(),
            lastVerifiedAtMs = store.getLong(KEY_LAST_VERIFIED, 0L).takeIf { it > 0L },
            lastAcceptedVersion = store.getLong(KEY_LAST_VERSION, 0L),
            highestWallClockSeenMs = store.getLong(KEY_MAX_WALL_CLOCK, 0L),
            hwid = runCatching { DeviceId.approvalId(app) }.getOrDefault(""),
            revokedAtMs = store.getLong(KEY_REVOKED_AT, 0L).takeIf { it > 0L },
            fetch = FetchResult.Offline(because),
            requireSignature = LicenseConfig.REQUIRE_SIGNATURE,
            publicKeyBase64 = LicenseConfig.SIGNING_PUBLIC_KEY_BASE64,
            tamperFailsClosed = LicenseConfig.TAMPER_FAILS_CLOSED,
            graceMs = LicenseConfig.GRACE_PERIOD_MS,
            clockToleranceMs = LicenseConfig.CLOCK_ROLLBACK_TOLERANCE_MS
        )
    }

    /** A removal that a later successful check has not cleared. */
    private fun isRevoked(revokedAtMs: Long?, lastVerifiedAtMs: Long?): Boolean =
        revokedAtMs != null && (lastVerifiedAtMs == null || revokedAtMs >= lastVerifiedAtMs)

    private fun publish(result: GatePolicy.Decision) {
        synchronized(lock) {
            decision = result
            decisionAtMs = System.currentTimeMillis()
            decisionReason = result.reason
        }
    }

    private fun refusal(reason: String, fatal: Boolean, revoked: Boolean = false): GatePolicy.Decision =
        GatePolicy.Decision(
            allow = false,
            offline = false,
            reason = reason,
            validUntilMs = null,
            persist = false,
            fatal = fatal,
            revoked = revoked
        )

    private fun schedulePeriodicRecheck() {
        // One repeating task for the process, no matter how many successful checks happened.
        if (!recheckScheduled.compareAndSet(false, true)) return
        try {
            scheduler.scheduleWithFixedDelay(
                {
                    if (!aborted.get()) startCheck("periodic re-check")
                },
                LicenseConfig.RECHECK_INTERVAL_MS,
                LicenseConfig.RECHECK_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        } catch (t: Throwable) {
            logWarn("${LicenseConfig.LOG_TAG}: cannot schedule the periodic re-check: ${t.message}")
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Enforcement
    // ------------------------------------------------------------------------------------------------

    /**
     * The device may not run. Either the lock screen takes the UI over (the normal case: the user needs
     * to read their ID off it), or the process is terminated ([abort]) because the binary is not the
     * operator's or because the relay is already up and has to stop.
     */
    private fun refuse(app: Context, refusal: GatePolicy.Decision, where: String) {
        val reason = "$where: ${refusal.reason}"
        decisionReason = reason

        if (shouldTerminate(refusal)) {
            abort(reason, if (refusal.revoked) R.string.hwid_revoked else R.string.hwid_not_allowed)
            return
        }

        logError("${LicenseConfig.LOG_TAG}: REFUSING TO RUN: $reason")
        LicenseLock.start(app, status())
    }

    /**
     * Fatal refusals terminate the process:
     *
     *  * [GatePolicy.Decision.fatal] - a repackaged or debugged APK. There is no legitimate reason for
     *    one to exist, and a lock screen in a binary the user built themselves is a joke.
     *  * a refusal that arrives while the relay is bound - there is a tunnel and a socket at that moment,
     *    so the app stops rather than leaving a zombie UI behind a working relay. The user restarts and
     *    gets the lock screen, which is where the Copy ID button lives. Configurable, see
     *    [LicenseConfig.REVOKE_ENDS_THE_PROCESS_WHEN_RUNNING].
     */
    private fun shouldTerminate(refusal: GatePolicy.Decision): Boolean =
        refusal.fatal ||
                (relayStarted && LicenseConfig.REVOKE_ENDS_THE_PROCESS_WHEN_RUNNING)

    /**
     * Terminates the app on purpose.
     *
     * Order matters: the reason is logged, the ID is copied to the clipboard and written to a file (that
     * is how a user gets their code to the operator - the app is about to be gone, so there is no screen
     * left to read it from), the VPN service is stopped, the user is told, and only then is the process
     * killed.
     */
    private fun abort(reason: String, messageRes: Int) {
        if (!aborted.compareAndSet(false, true)) return

        logError("${LicenseConfig.LOG_TAG}: REFUSING TO RUN: $reason")
        decisionReason = reason

        val app = context
        if (app == null) {
            killProcess()
            return
        }

        val id = try {
            DeviceId.approvalId(app)
        } catch (t: Throwable) {
            logError("${LicenseConfig.LOG_TAG}: cannot read this device's ID for the report", t)
            "unknown"
        }

        HwidReport.export(app, id, reason, notices)
        HwidReport.report(reason, notices)

        try {
            app.stopService(Intent(app, AppService::class.java))
        } catch (t: Throwable) {
            logWarn("${LicenseConfig.LOG_TAG}: could not stop the VPN service while aborting: ${t.message}")
        }

        runOnMain {
            try {
                Toast.makeText(app, app.getString(messageRes), Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {
                // a toast is a nicety, not a guarantee
            }
            Handler(Looper.getMainLooper()).postDelayed({ killProcess() }, LicenseConfig.ABORT_KILL_DELAY_MS)
        }
    }

    private fun runOnMain(block: () -> Unit) {
        try {
            Handler(Looper.getMainLooper()).post { block() }
        } catch (t: Throwable) {
            // no main looper (should not happen in an app process): do it right here
            logWarn("${LicenseConfig.LOG_TAG}: no main looper for the abort message: ${t.message}")
            block()
        }
    }

    private fun killProcess() {
        logError("${LicenseConfig.LOG_TAG}: terminating the app process (${android.os.Process.myPid()})")
        try {
            android.os.Process.killProcess(android.os.Process.myPid())
        } catch (_: Throwable) {
            // fall through to the hard exit below
        }
        kotlin.system.exitProcess(1)
    }

    // ------------------------------------------------------------------------------------------------
    // What the UI shows
    // ------------------------------------------------------------------------------------------------

    class Status(
        val phase: Phase,
        /** A check is in flight right now; the phase is still the last verdict (or [Phase.CHECKING]). */
        val checking: Boolean,
        val checked: Boolean,
        val allowed: Boolean,
        val offline: Boolean,
        val headline: String,
        val detail: String,
        val validUntilMs: Long?,
        val checkedAtMs: Long?,
        val signingKeyConfigured: Boolean,
        val notices: List<String>
    ) {
        /** This device may not be used: the lock screen belongs on top and the relay stays refused. */
        val locked: Boolean
            get() = phase == Phase.LOCKED || phase == Phase.REVOKED || phase == Phase.FATAL
    }

    fun status(): Status {
        val current = decision
        val checkingNow = checking
        val phase = when {
            aborted.get() -> Phase.FATAL
            current == null -> if (checkingNow) Phase.CHECKING else Phase.PENDING
            current.allow && current.offline -> Phase.OFFLINE
            current.allow -> Phase.ALLOWED
            current.fatal -> Phase.FATAL
            current.revoked -> Phase.REVOKED
            else -> Phase.LOCKED
        }

        return Status(
            phase = phase,
            checking = checkingNow,
            checked = current != null,
            allowed = current?.allow == true,
            offline = current?.offline == true,
            headline = when (phase) {
                Phase.PENDING, Phase.CHECKING -> "Checking the list..."
                Phase.ALLOWED -> "Allowed"
                Phase.OFFLINE -> "Allowed (offline)"
                Phase.REVOKED -> "Access removed"
                Phase.LOCKED -> "Not allowed"
                Phase.FATAL -> "Refused"
            },
            detail = decisionReason,
            validUntilMs = current?.validUntilMs,
            checkedAtMs = if (current != null) decisionAtMs else null,
            signingKeyConfigured = LicenseConfig.SIGNING_PUBLIC_KEY_BASE64.isNotBlank(),
            notices = notices
        )
    }
}
