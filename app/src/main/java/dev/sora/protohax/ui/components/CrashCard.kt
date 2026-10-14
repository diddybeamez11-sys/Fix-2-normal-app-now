package dev.sora.protohax.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sora.protohax.R
import dev.sora.protohax.hwid.HwidReport
import dev.sora.protohax.util.CrashHandler
import dev.sora.protohax.util.ContextUtils.shareTextAsFile
import dev.sora.protohax.util.ContextUtils.toast

/**
 * Shown on the dashboard while a saved crash report is waiting.
 *
 * The point of [CrashHandler] is that the trace survives the process that produced it; the point
 * of this card is that it survives long enough to be sent. Without it the only way to read the
 * report is `adb shell` into the app's private directory, which nobody reporting a crash on their
 * phone can do. The report is a plain text file with the thread, the device, the build and the
 * full cause chain - Copy puts it on the clipboard, Share hands it to any app (Telegram, mail).
 *
 * Dismissing deletes it, so a crash that was reported does not keep coming back.
 */
@Composable
fun CardCrash() {
    val ctx = LocalContext.current

    // Read once per composition of the dashboard: the file only appears when a previous process
    // died, so there is nothing to observe while this one is alive.
    val report = remember { CrashHandler.lastCrash(ctx) }
    var dismissed by remember { mutableStateOf(false) }

    if (report.isNullOrBlank() || dismissed) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(18.dp, 10.dp)
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Row {
                Icon(
                    Icons.Filled.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(
                        stringResource(R.string.crash_card_title),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = stringResource(R.string.crash_card_body),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.size(6.dp))
            Row {
                TextButton(onClick = {
                    if (HwidReport.copyToClipboard(ctx, report)) {
                        ctx.toast(R.string.crash_copied)
                    }
                }) {
                    Text(stringResource(R.string.crash_copy))
                }
                TextButton(onClick = {
                    ctx.shareTextAsFile(report, ctx.getString(R.string.crash_card_title))
                }) {
                    Text(stringResource(R.string.crash_share))
                }
                TextButton(onClick = {
                    CrashHandler.clear(ctx)
                    dismissed = true
                }) {
                    Text(stringResource(R.string.crash_dismiss))
                }
            }
        }
    }
}
