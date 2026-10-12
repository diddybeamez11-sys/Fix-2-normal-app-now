package dev.sora.protohax.hwid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import dev.sora.relay.utils.logError
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How a user hands their HWID to the operator, and how a denial leaves a trace behind.
 *
 * The awkward part of this feature is the first-run flow: the app is installed, asks the repository
 * whether it may run, and finds that it may not - because the device is not on the list *yet*. The app
 * then terminates, so the "copy HWID" button in the dashboard is not reachable exactly when it is
 * needed. That is why the abort path puts the HWID in three places before it kills the process:
 *
 * 1. **the clipboard** - the user pastes it to the operator, no permission and no screen needed;
 * 2. **a file next to the app's files** (`Android/data/<package>/files/hwid.txt`), for a support case
 *    with file access;
 * 3. **logcat** (tag `ProtoHax`), for a technical user with `adb logcat -s ProtoHaxHwid`.
 *
 * The dashboard card additionally shows it with a "Copy HWID" button for every allowed user, and it is
 * also where a *new* device's code can be read without any of this.
 */
internal object HwidReport {

    /**
     * Puts the ID this device is approved by on the clipboard, writes the full report to a file and logs
     * it. Called from the abort path, so it must never throw.
     *
     * @param id exactly the string that goes into the operator's list ([DeviceId.approvalId]).
     */
    fun export(context: Context, id: String, reason: String, notices: List<String>) {
        copyToClipboard(context, id)

        val block = buildString {
            append(DeviceId.report(context)).append('\n')
            append("when: ").append(now()).append('\n')
            append("reason: ").append(reason).append('\n')
            append("android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            if (notices.isNotEmpty()) {
                append("notices:\n")
                for (notice in notices) append("  - ").append(notice).append('\n')
            }
        }

        logError("${LicenseConfig.LOG_TAG}: device $id - $reason")
        writeFile(context, block)
    }

    /** A non-fatal report, so denials are visible in the operator's Crashlytics console. */
    fun report(reason: String, notices: List<String>) {
        try {
            val crashlytics = com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
            crashlytics.setCustomKey("hwid_denied_reason", reason.take(400))
            crashlytics.setCustomKey("hwid_notices", notices.joinToString(" | ").take(400))
            crashlytics.recordException(HwidDenied(reason))
        } catch (t: Throwable) {
            // Crashlytics is a convenience here, never a requirement.
            logError("${LicenseConfig.LOG_TAG}: could not report the denial", t)
        }
    }

    /** Copies exactly the UUID, so it can be pasted into `hwids.json` as it is. */
    fun copyToClipboard(context: Context, hwid: String): Boolean = try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ProtoHax HWID", hwid))
        true
    } catch (t: Throwable) {
        logError("${LicenseConfig.LOG_TAG}: could not copy the HWID to the clipboard", t)
        false
    }

    /** Writes `hwid.txt` into the app's external files directory (internal one as a fallback). */
    fun writeFile(context: Context, text: String): File? {
        val directory = try {
            context.getExternalFilesDir(null)
        } catch (_: Throwable) {
            null
        } ?: context.filesDir

        return try {
            if (!directory.exists()) directory.mkdirs()
            File(directory, LicenseConfig.HWID_FILE_NAME).apply { writeText(text) }
        } catch (t: Throwable) {
            logError("${LicenseConfig.LOG_TAG}: could not write ${LicenseConfig.HWID_FILE_NAME}", t)
            null
        }
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())

    class HwidDenied(reason: String) : Exception("HWID gate refused to run: $reason")
}
