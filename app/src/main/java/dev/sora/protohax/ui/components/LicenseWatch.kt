package dev.sora.protohax.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import dev.sora.protohax.hwid.LicenseConfig
import dev.sora.protohax.ui.activities.LicenseLock
import kotlinx.coroutines.delay

/**
 * Renders nothing. Watches the approval verdict for as long as the dashboard is composed and puts the
 * [LicenseLock] screen on top the moment the verdict turns to "no".
 *
 * Why this is needed at all, given that the check happens at app start: the verdict can change *while the
 * app is running*. The gate re-checks every [LicenseConfig.RECHECK_INTERVAL_MS], so a device that is
 * removed from the list is refused within one interval - without this watcher the user would just see a
 * dashboard whose relay silently stopped working. The relay itself refuses too (see
 * `MinecraftRelay.announceRelayUp`), and if it was already bound the process is stopped
 * ([LicenseConfig.REVOKE_ENDS_THE_PROCESS_WHEN_RUNNING]); this watcher is what covers "the app is open
 * but the relay is not running".
 *
 * It only locks while the activity is actually in front, so a check that fails in the background never
 * pops a screen over whatever the user is doing - the app locks when they come back to it.
 */
@Composable
fun LicenseWatch() {
    val ctx = LocalContext.current
    val owner = ctx as? LifecycleOwner

    LaunchedEffect(Unit) {
        while (true) {
            val inFront = owner == null || owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (inFront) {
                // Cheap: this reads the gate's field and, at worst, starts one activity. It stays a poll
                // because the verdict is published by a background thread and the UI is not told.
                LicenseLock.startIfLocked(ctx)
            }
            delay(LicenseConfig.LOCK_POLL_INTERVAL_MS)
        }
    }
}
