package dev.sora.protohax.hwid

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import dev.sora.protohax.BuildConfig
import dev.sora.relay.utils.logWarn
import java.io.File
import java.security.MessageDigest

/**
 * The proportionate anti-tamper layer: an APK signing certificate pin, plus the cheapest checks for
 * "this copy was rebuilt or is being examined".
 *
 * It is deliberately small and has no third-party SDK behind it. It is not a licence-enforcement
 * mechanism - see HWID_WHITELIST.md, "What this does NOT stop" - it only makes the naive paths
 * (apktool/uber-apk-signer repackage, Android Studio debug build, a debugger attached) fail.
 *
 * Every check has a switch in [LicenseConfig] and every finding is either fatal (the app refuses to
 * run) or a warning (logged, shown in the dashboard).
 */
internal object Integrity {

    class Report(
        /** Fatal findings: non-empty means the app must not run. */
        val problems: List<String>,
        /** Non-fatal findings, logged and shown in the dashboard. */
        val warnings: List<String>
    ) {
        val ok: Boolean get() = problems.isEmpty()
    }

    fun evaluate(context: Context): Report {
        val problems = ArrayList<String>()
        val warnings = ArrayList<String>()

        certificatePin(context, problems, warnings)
        debuggable(context, problems, warnings)
        root(problems, warnings)

        for (warning in warnings) logWarn("${LicenseConfig.LOG_TAG}: $warning")
        return Report(problems, warnings)
    }

    // ------------------------------------------------------------------------------------------------
    // 1. The APK signing certificate pin
    // ------------------------------------------------------------------------------------------------

    /**
     * The build derives the SHA-256 of the certificate the release APK is signed with and injects it as
     * BuildConfig.HWID_SIGNING_CERT_SHA256 (app/build.gradle). A repackaged APK is signed with a
     * different key, so this check catches exactly the "unzip, patch, re-sign, ship" path.
     */
    private fun certificatePin(context: Context, problems: MutableList<String>, warnings: MutableList<String>) {
        if (!LicenseConfig.ENFORCE_CERT_PIN) {
            warnings.add("the APK signing certificate pin is switched off in LicenseConfig")
            return
        }

        val expected = try {
            BuildConfig.HWID_SIGNING_CERT_SHA256
        } catch (t: Throwable) {
            ""
        }
        if (expected.isBlank() || expected.equals("UNPINNED", ignoreCase = true)) {
            // The build could not read the keystore it signs with (a fresh CI runner, for example).
            // Refusing to run would break every such APK, so the pin is skipped and only reported.
            warnings.add(
                "this APK does not carry a signing certificate pin " +
                        "(BuildConfig.HWID_CERT_PIN_SOURCE=${BuildConfig.HWID_CERT_PIN_SOURCE}); " +
                        "a repackaged APK is not detected. See HWID_WHITELIST.md"
            )
            return
        }

        val actual = signerSha256(context)
        if (actual == null) {
            problems.add("the signing certificate of this APK could not be read")
            return
        }
        if (!normalize(actual).equals(normalize(expected), ignoreCase = true)) {
            problems.add(
                "this APK is not signed with the expected certificate: " +
                        "expected ${normalize(expected)}, found ${normalize(actual)} " +
                        "(a repackaged APK, or the pin has to be updated for a new signing key)"
            )
        }
    }

    /** SHA-256 of the APK's signing certificate, lowercase hex, plus the current signer. */
    private fun signerSha256(context: Context): String? = try {
        val packageManager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        val signers = info.signingInfo?.apkContentsSigners
        if (signers.isNullOrEmpty()) {
            null
        } else {
            MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()).toHex()
        }
    } catch (t: Throwable) {
        logWarn("${LicenseConfig.LOG_TAG}: cannot read this APK's signing certificate: ${t.message}")
        null
    }

    // ------------------------------------------------------------------------------------------------
    // 2. Debuggable / debugger
    // ------------------------------------------------------------------------------------------------

    private fun debuggable(context: Context, problems: MutableList<String>, warnings: MutableList<String>) {
        if (!LicenseConfig.DEBUGGER_DETECTION_FATAL) return

        val debuggable = try {
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Throwable) {
            false
        }
        val debuggerAttached = try {
            Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        } catch (_: Throwable) {
            false
        }

        if (debuggable && debuggerAttached) {
            problems.add("this APK is debuggable and a debugger is attached")
        } else if (debuggable) {
            // A debug build signed with the same key is a legitimate developer build; only a debuggable
            // *release* APK is suspicious. Reported, not fatal - unless a debugger is actually attached.
            warnings.add("this APK has android:debuggable=\"true\"")
        }
    }

    // ------------------------------------------------------------------------------------------------
    // 3. Root heuristics
    // ------------------------------------------------------------------------------------------------

    private fun root(problems: MutableList<String>, warnings: MutableList<String>) {
        val findings = ArrayList<String>()

        try {
            if (Build.TAGS?.contains("test-keys") == true) {
                findings.add("the ROM is signed with test-keys")
            }
        } catch (_: Throwable) {
            // Build.TAGS is not worth failing over
        }

        for (path in SU_PATHS) {
            try {
                if (File(path).exists()) {
                    findings.add("$path exists")
                    break
                }
            } catch (_: Throwable) {
                // unreadable path: treat as absent
            }
        }

        try {
            val path = System.getenv("PATH") ?: ""
            for (directory in path.split(':')) {
                if (directory.isBlank()) continue
                val su = File(directory, "su")
                if (su.exists() && su.canExecute()) {
                    findings.add("su on PATH ($su)")
                    break
                }
            }
        } catch (_: Throwable) {
            // no PATH: skip
        }

        if (findings.isEmpty()) return

        val description = "this device looks rooted (${findings.joinToString(", ")})"
        if (LicenseConfig.ROOT_DETECTION_FATAL) {
            problems.add(description)
        } else {
            warnings.add(description)
        }
    }

    private fun normalize(value: String): String = value.replace(":", "").trim()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private val SU_PATHS = listOf(
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/system/bin/failsafe/su",
        "/system/sd/xbin/su",
        "/system/xbin/daemonsu",
        "/su/bin/su",
        "/magisk/.core/bin/su",
        "/system/app/Superuser.apk",
        "/data/local/su",
        "/data/local/bin/su",
        "/data/local/xbin/su"
    )
}
