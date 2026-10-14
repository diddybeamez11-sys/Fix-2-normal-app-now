package dev.sora.protohax.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sora.protohax.R
import dev.sora.protohax.hwid.DeviceId
import dev.sora.protohax.hwid.HwidGate
import dev.sora.protohax.hwid.HwidReport
import dev.sora.protohax.hwid.LicenseConfig
import dev.sora.protohax.ui.theme.MyApplicationTheme
import dev.sora.protohax.util.ContextUtils.toast
import dev.sora.relay.utils.logError
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The screen a device that is not approved gets.
 *
 * This is the whole point of the flow: the app cannot simply die on an unapproved device, because then
 * the user has no way to read the identifier they are supposed to send. So instead the UI is replaced by
 * this activity, which shows the ID ([DeviceId.approvalId] - the ANDROID_ID by default), copies it with
 * one button, and asks the list again every [LicenseConfig.LOCK_RETRY_INTERVAL_MS] while it is open. As
 * soon as the operator has added the ID, the next answer is "yes" and the screen reopens the dashboard by
 * itself - no restart, no "clear data".
 *
 * Escape hatches are deliberately closed: the task is cleared when this screen is started, so the back
 * button cannot return to the dashboard, and the relay refuses to bind until the verdict is "yes"
 * (see [dev.sora.protohax.relay.MinecraftRelay]). Back only sends the app to the background, which is
 * what a locked screen should do.
 *
 * The status line and the verdict text come from [HwidGate] unchanged; everything else is localized.
 */
class LicenseLockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LicenseLock.noteShowing(true)

        setContent {
            MyApplicationTheme {
                BackHandler { moveTaskToBack(true) }
                LockScreen(onUnlocked = { unlock() })
            }
        }
    }

    override fun onDestroy() {
        LicenseLock.noteShowing(false)
        super.onDestroy()
    }

    /** The verdict changed to "yes": back to the app, replacing this screen. */
    private fun unlock() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        } catch (t: Throwable) {
            logError("${LicenseConfig.LOG_TAG}: could not reopen the app after unlocking", t)
        }
        finish()
    }
}

/**
 * Brings [LicenseLockActivity] up for a verdict that says "no", and remembers whether it is already up.
 *
 * Every call is safe from any thread and any number of times: it does nothing unless the current verdict
 * is a refusal, nothing while the lock screen is showing, and nothing within
 * [LicenseConfig.LOCK_RELAUNCH_DEBOUNCE_MS] of the last launch (so a dashboard that polls its own status
 * cannot relaunch the screen over and over).
 */
internal object LicenseLock {

    @Volatile
    private var showing = false

    @Volatile
    private var lastStartMs = 0L

    internal fun noteShowing(value: Boolean) {
        showing = value
    }

    /**
     * Puts the lock screen on top when [status] is a refusal.
     *
     * `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK` is what makes this a lock rather than another
     * screen the user can back out of: the dashboard (and whatever else was open) is dropped from the
     * task.
     */
    fun start(context: Context, status: HwidGate.Status) {
        if (!status.locked) return
        if (showing) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastStartMs < LicenseConfig.LOCK_RELAUNCH_DEBOUNCE_MS) return
        lastStartMs = now

        onMain {
            try {
                context.startActivity(
                    Intent(context, LicenseLockActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            } catch (t: Throwable) {
                logError("${LicenseConfig.LOG_TAG}: could not show the lock screen", t)
            }
        }
    }

    /** For activities that are being (re)started: locks immediately when the verdict says "no". */
    fun startIfLocked(context: Context) {
        val status = HwidGate.status()
        if (status.locked) start(context, status)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else Handler(Looper.getMainLooper()).post { block() }
    }
}

// ----------------------------------------------------------------------------------------------------
// The screen
// ----------------------------------------------------------------------------------------------------

@Composable
private fun LockScreen(onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf(HwidGate.status()) }
    var lastRetryMs by remember { mutableStateOf(0L) }

    // The ID the operator approves, and the other one, both read once: they cannot change while the app
    // runs (the ANDROID_ID is fixed, and the install HWID is created on first use).
    val approvalId = remember { runCatching { DeviceId.approvalId(ctx) }.getOrDefault("") }
    val secondaryId = remember {
        runCatching { DeviceId.secondaryIds(ctx).firstOrNull { it.isNotBlank() } }.getOrNull().orEmpty()
    }

    LaunchedEffect(Unit) {
        while (true) {
            val current = HwidGate.status()
            status = current

            // Only a positive verdict unlocks: leaving the screen on "no verdict yet" would let a device
            // out of the lock screen before it was ever approved.
            if (current.checked && current.allowed) {
                onUnlocked()
                return@LaunchedEffect
            }

            val now = System.currentTimeMillis()
            if (now - lastRetryMs >= LicenseConfig.LOCK_RETRY_INTERVAL_MS) {
                lastRetryMs = now
                HwidGate.refreshAsync("lock screen")
            }

            delay(LicenseConfig.LOCK_POLL_INTERVAL_MS)
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(42.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.lock_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = headlineText(status.phase),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = status.detail,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(18.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "${stringResource(R.string.lock_id_label)} (${DeviceId.approvalIdName()})",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    // Not wrapped in SelectionContainer: a selectable one-line Text crashes in
                    // Compose 1.4.3 while it is being drawn ("lineIndex(-1) is out of bounds"), and
                    // this screen is the one place a user who is not approved yet must be able to
                    // read. The Copy ID button right below does the same job.
                    Text(
                        text = approvalId.ifBlank { stringResource(R.string.lock_id_unavailable) },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            if (HwidReport.copyToClipboard(ctx, approvalId)) {
                                ctx.toast(R.string.lock_id_copied)
                            }
                        }, enabled = approvalId.isNotBlank()) {
                            Text(stringResource(R.string.lock_copy_id))
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                HwidGate.refreshAsync("lock screen button")
                                ctx.toast(R.string.lock_retrying)
                            },
                            enabled = !status.checking
                        ) {
                            Text(stringResource(R.string.lock_retry))
                        }
                    }

                    // The other identifier, for a build where the list is kept by HWID, or where the
                    // operator has approved one but not the other. Small, because it is the fallback.
                    if (secondaryId.isNotBlank() && secondaryId != approvalId) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.lock_secondary_label)
                                    .format(DeviceId.secondaryIdName()),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Not selectable, for the same reason as the primary ID above.
                        Text(
                            text = secondaryId,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = {
                            if (HwidReport.copyToClipboard(ctx, secondaryId)) {
                                ctx.toast(R.string.lock_id_copied)
                            }
                        }) {
                            Text(stringResource(R.string.lock_copy_secondary))
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.lock_body),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(12.dp))

            if (status.checking) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.lock_status_checking),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text = statusText(status),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            status.checkedAtMs?.let { checkedAt ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.lock_last_check).format(formatTime(checkedAt)),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.lock_first_run_note),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun headlineText(phase: HwidGate.Phase): String = when (phase) {
    HwidGate.Phase.REVOKED -> stringResource(R.string.lock_headline_revoked)
    HwidGate.Phase.LOCKED -> stringResource(R.string.lock_headline_unapproved)
    HwidGate.Phase.FATAL -> stringResource(R.string.lock_headline_fatal)
    HwidGate.Phase.ALLOWED, HwidGate.Phase.OFFLINE -> stringResource(R.string.lock_headline_allowed)
    HwidGate.Phase.PENDING, HwidGate.Phase.CHECKING -> stringResource(R.string.lock_status_checking)
}

@Composable
private fun statusText(status: HwidGate.Status): String = when {
    status.phase == HwidGate.Phase.REVOKED -> stringResource(R.string.lock_status_revoked)
    status.phase == HwidGate.Phase.LOCKED -> stringResource(R.string.lock_status_denied)
    status.phase == HwidGate.Phase.FATAL -> stringResource(R.string.lock_status_refused)
    status.allowed -> stringResource(R.string.lock_status_allowed)
    else -> stringResource(R.string.lock_status_never)
}

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
