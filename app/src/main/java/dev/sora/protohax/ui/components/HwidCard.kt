package dev.sora.protohax.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sora.protohax.R
import dev.sora.protohax.hwid.DeviceId
import dev.sora.protohax.hwid.HwidGate
import dev.sora.protohax.hwid.HwidReport
import dev.sora.protohax.util.ContextUtils.toast

/**
 * The dashboard card that makes the approval operable: it shows the identifier this device is approved
 * by (the ANDROID_ID unless [dev.sora.protohax.hwid.LicenseConfig.APPROVAL_ID_MODE] says otherwise), one
 * tap copies it so the user can send it to the operator, and it shows what the gate currently thinks of
 * this device.
 *
 * Two situations this is written for:
 *
 *  * **a new user** - "install it, tap Copy ID, send me the code, then wait for me to add it"; the lock
 *    screen shows exactly the same ID (and the same button), because that is where they will end up;
 *  * **a user who lost access** (removed, or the offline grace period has expired) - the same card says
 *    so and still shows the ID, which is the only thing the operator needs to fix it.
 *
 * The gate's own reason text is shown verbatim; it is written for exactly this purpose (it names the ID,
 * the list version, the grace deadline, ...).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardHwid() {
    val ctx = LocalContext.current
    val approvalId = remember { runCatching { DeviceId.approvalId(ctx) }.getOrDefault("") }
    val secondaryId = remember {
        runCatching { DeviceId.secondaryIds(ctx).firstOrNull { it.isNotBlank() } }.getOrNull().orEmpty()
    }
    var status by remember { mutableStateOf(HwidGate.status()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(18.dp, 10.dp)
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Text(
                stringResource(R.string.dashboard_license),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.size(0.dp, 6.dp))
            Text(
                text = when (status.phase) {
                    HwidGate.Phase.PENDING, HwidGate.Phase.CHECKING -> stringResource(R.string.dashboard_license_checking)
                    HwidGate.Phase.ALLOWED -> stringResource(R.string.dashboard_license_allowed)
                    HwidGate.Phase.OFFLINE -> stringResource(R.string.dashboard_license_offline)
                    HwidGate.Phase.REVOKED -> stringResource(R.string.dashboard_license_revoked)
                    HwidGate.Phase.LOCKED, HwidGate.Phase.FATAL -> stringResource(R.string.dashboard_license_denied)
                },
                color = if (status.locked) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.size(0.dp, 4.dp))
            Text(
                text = status.detail,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!status.signingKeyConfigured) {
                Spacer(modifier = Modifier.size(0.dp, 4.dp))
                Text(
                    text = stringResource(R.string.dashboard_license_no_key),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
            for (notice in status.notices) {
                Spacer(modifier = Modifier.size(0.dp, 4.dp))
                Text(
                    text = notice,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.size(0.dp, 10.dp))
            Text(
                text = "${stringResource(R.string.lock_id_label)} (${DeviceId.approvalIdName()})",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Deliberately NOT wrapped in SelectionContainer: with Compose 1.4.3 a selectable
            // one-line Text crashes while it is being drawn -
            //     java.lang.IllegalArgumentException: lineIndex(-1) is out of bounds [0, 1)
            //     androidx.compose.foundation.text.selection.MultiWidgetSelectionDelegate
            // - which killed the app on the very first frame of the dashboard, on every start,
            // before the user could touch anything. The delegate only exists for a Text inside a
            // SelectionContainer, so leaving it out removes the crash; the "Copy" button right
            // below already puts this ID on the clipboard, which is the only thing the selection
            // was for.
            Text(
                text = approvalId.ifBlank { stringResource(R.string.lock_id_unavailable) },
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.size(0.dp, 4.dp))
            Row {
                TextButton(onClick = {
                    if (HwidReport.copyToClipboard(ctx, approvalId)) {
                        ctx.toast(R.string.lock_id_copied)
                    }
                }, enabled = approvalId.isNotBlank()) {
                    Text(stringResource(R.string.lock_copy_id))
                }
                TextButton(onClick = {
                    status = HwidGate.status()
                    HwidGate.refreshAsync("dashboard")
                    ctx.toast(R.string.hwid_checking)
                }) {
                    Text(stringResource(R.string.hwid_check_again))
                }
            }

            // The other identifier: the install HWID when the list is kept by ANDROID_ID, or the
            // ANDROID_ID when it is kept by HWID. Accepted too when the operator allows either.
            if (secondaryId.isNotBlank() && secondaryId != approvalId) {
                Spacer(modifier = Modifier.size(0.dp, 6.dp))
                Text(
                    text = stringResource(R.string.hwid_label).format(DeviceId.secondaryIdName()),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Not selectable, for the same reason as the primary ID above.
                Text(
                    text = secondaryId,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = {
                    if (HwidReport.copyToClipboard(ctx, secondaryId)) {
                        ctx.toast(R.string.hwid_copied)
                    }
                }) {
                    Text(stringResource(R.string.hwid_copy))
                }
            }
        }
    }
}
