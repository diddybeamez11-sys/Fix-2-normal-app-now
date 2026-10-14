package dev.sora.protohax.util

import android.content.Context
import android.os.Build
import dev.sora.protohax.BuildConfig
import dev.sora.relay.utils.logError
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves the stack trace of anything the app dies of into a file, so it can be read on the next
 * start instead of being lost.
 *
 * Why this exists: an app that closes itself is indistinguishable from an app that was refused
 * (the device approval gate terminates the process on purpose) unless somebody reads the trace,
 * and a phone is not a machine you can attach a debugger to. Every unhandled exception is
 * therefore written to `filesDir/crashes/crash-<timestamp>.log` - thread, device, build and the
 * full cause chain - and the newest few are kept.
 *
 * It does **not** swallow anything: the report is written, whatever handler was installed before
 * this one runs next (Crashlytics installs its own from a ContentProvider, i.e. before
 * `Application.onCreate`), and the process then dies exactly as it would have without this class.
 * Nothing is caught anywhere else in the app because of it, and nothing recovers from a crash -
 * the point is that the *next* start can say what happened.
 *
 * The report is surfaced in two places: the dashboard shows a card while one is waiting (with
 * Copy and Share, which is how it reaches a bug report), and the log screen shows it above the
 * logcat ring buffer.
 */
object CrashHandler {

    private const val TAG = "ProtoHaxCrash"
    private const val DIR_NAME = "crashes"
    private const val FILE_PREFIX = "crash-"
    private const val FILE_SUFFIX = ".log"

    /** How many reports are kept; the oldest beyond this are deleted on the next crash. */
    private const val KEEP = 5

    @Volatile
    private var installed = false

    /**
     * Installs the handler, once. Called first thing in `MyApplication.onCreate`, so a crash in
     * the start-up itself is caught too.
     */
    fun install(context: Context) {
        if (installed) return
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                save(app, thread, throwable)
            } catch (t: Throwable) {
                logError("$TAG: the crash report could not be written", t)
            }
            // Hand over to whoever was there before (Crashlytics), and let the process end.
            try {
                previous?.uncaughtException(thread, throwable)
            } catch (t: Throwable) {
                logError("$TAG: the previous crash handler failed", t)
            }
        }
        installed = true
    }

    /** The newest saved report, or null when the app has not died since it was installed. */
    fun lastCrash(context: Context): String? = try {
        reports(context).firstOrNull()?.readText()
    } catch (t: Throwable) {
        null
    }

    /** Whether a report is waiting to be read (used to decide whether to show the card). */
    fun hasCrash(context: Context): Boolean = try {
        reports(context).isNotEmpty()
    } catch (t: Throwable) {
        false
    }

    /** Called once the report has been dealt with, so the card does not come back. */
    fun clear(context: Context) {
        try {
            reports(context).forEach { it.delete() }
        } catch (t: Throwable) {
            logError("$TAG: the crash reports could not be deleted", t)
        }
    }

    // --------------------------------------------------------------------------------------------
    // Internals
    // --------------------------------------------------------------------------------------------

    private fun save(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            logError("$TAG: cannot create ${dir.absolutePath}", IllegalStateException("mkdirs failed"))
        }
        val file = File(dir, FILE_PREFIX + stamp() + FILE_SUFFIX)
        file.writeText(report(context, thread, throwable))
        logError("$TAG: wrote $file", throwable)
        prune(dir)
    }

    private fun report(context: Context, thread: Thread, throwable: Throwable): String = buildString {
        appendLine("ProtoHax crash report")
        appendLine("time      : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("app       : ${context.packageName} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("device    : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.PRODUCT})")
        appendLine("android   : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("abi       : ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("thread    : ${thread.name} (id ${thread.id})")
        appendLine("files     : ${context.filesDir}")
        appendLine()
        append(trace(throwable))
    }

    /** The exception with its whole cause chain; `printStackTrace` prints the "Caused by" part. */
    private fun trace(throwable: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { throwable.printStackTrace(it) }
        return writer.toString()
    }

    private fun reports(context: Context): List<File> {
        val dir = File(context.filesDir, DIR_NAME)
        return (dir.listFiles()?.filter {
            it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX)
        } ?: emptyList()).sortedByDescending { it.name }
    }

    private fun prune(dir: File) {
        val all = (dir.listFiles()?.filter { it.name.startsWith(FILE_PREFIX) } ?: return)
            .sortedByDescending { it.name }
        all.drop(KEEP).forEach {
            try {
                it.delete()
            } catch (t: Throwable) {
                // an old report that cannot be deleted is not worth failing over
            }
        }
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
