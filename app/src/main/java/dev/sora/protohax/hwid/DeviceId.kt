package dev.sora.protohax.hwid

import android.content.Context
import android.provider.Settings
import dev.sora.protohax.util.ContextUtils.getPackageInfo
import dev.sora.relay.utils.logError
import dev.sora.relay.utils.logWarn
import java.security.MessageDigest
import java.util.UUID

/**
 * The identifiers this app can be approved by, and the identity it keeps locally.
 *
 * There are two, and [LicenseConfig.APPROVAL_ID_MODE] picks which one the operator's list is expected to
 * contain ([approvalId]), while [secondaryIds] is the other one - accepted too when
 * [LicenseConfig.ACCEPT_IDS_EITHER_WAY] is on.
 *
 *  * **ANDROID_ID** ([androidId]) - `Settings.Secure.ANDROID_ID`, the OS's own identifier for this
 *    device *for this app's signing key* and user. No permission, no prompt, stable across
 *    uninstall/reinstall, changes on factory reset. It is the identifier a user can also read from the
 *    game's own "device id" screens, which is what makes it easy for them to send you.
 *  * **install HWID** ([hwid]) - a random UUIDv4 this app generates on first start and keeps in
 *    [HwidStore] (encrypted preferences, master key in the Android keystore). Identifies one
 *    *installation*: a reinstall gets a new one and has to be approved again. It needs no permission and
 *    prompts the user for nothing, unlike IMEI/serial/`READ_PHONE_STATE`, which Android restricts and
 *    which would put a permission prompt in front of every user for a value that has been unreadable
 *    since Android 10 anyway.
 *
 * Both are shown on the lock screen and both can be copied there; the operator decides which one the
 * list holds.
 *
 * **Why the ANDROID_ID needs no local secret:** it comes from the OS, so there is nothing for this app
 * to store or protect for it to work. The install HWID does need the encrypted store - see below.
 *
 * **Why not a hardware serial:** nothing a normal (non-rooted, non-privileged) app can read is a stable
 * hardware identifier any more. So the whitelist does not identify hardware, it identifies *this app
 * installation*: the UUID is a secret shared between this device and the operator's repository.
 *
 * **The ANDROID_ID as a second signal:** `Settings.Secure.ANDROID_ID` is a 64-bit value scoped to
 * (app signing key, user, device) and changes on factory reset. A salted-and-truncated hash of it
 * ([androidIdCheck]) is stored next to the UUID for one purpose: to notice that the encrypted state was
 * carried over to *different* hardware (a cloud backup restore, a device transfer) - the stored hash
 * then no longer matches and a fresh UUID is issued, so a copied installation does not silently inherit
 * somebody's place on the list. It is *not* itself the HWID and it is not on the list.
 *
 * **Moving a device's identity:** the encrypted store's master key is non-exportable and lives in the
 * device keystore, so copying the app's data directory to another device does not copy the HWID - the
 * copy generates a new one and has to be added by the operator. (If the keystore is unusable and the
 * app fell back to plain preferences, that protection is gone; see HWID_WHITELIST.md.)
 */
internal object DeviceId {

    private const val KEY_HWID = "hwid"
    private const val KEY_ANDROID_ID_CHECK = "android_id_check"

    /** The ANDROID_ID value some very old/broken devices returned for every app. Not usable. */
    private const val BROKEN_ANDROID_ID = "9774d56d682e549c"

    @Volatile
    private var cachedHwid: String? = null

    /**
     * The ANDROID_ID of this device for this app's signing key, lower-cased. `""` when the device does
     * not expose a usable one (some ROMs return null, some very old devices return a constant that every
     * app on the device shares - [BROKEN_ANDROID_ID]).
     *
     * No permission is involved; this is readable by every app on the device and is *not* a secret. It is
     * an identifier, not an authentication factor - see the threat model in HWID_WHITELIST.md.
     */
    fun androidId(context: Context): String = (readAndroidId(context) ?: "").lowercase()

    /**
     * The identifier the operator's list is expected to contain, i.e. what the lock screen shows and
     * copies. Selected by [LicenseConfig.APPROVAL_ID_MODE].
     *
     * Returns `""` when the selected identifier cannot be produced (an unusable ANDROID_ID); the gate
     * treats that as a refusal rather than as "no id, let it through".
     */
    fun approvalId(context: Context): String = when (LicenseConfig.APPROVAL_ID_MODE) {
        ApprovalIdMode.ANDROID_ID -> androidId(context)
        ApprovalIdMode.INSTALL_HWID -> hwid(context)
    }

    /**
     * The identifier the app is *also* approved by, when [LicenseConfig.ACCEPT_IDS_EITHER_WAY] is on.
     * Empty entries are dropped by the caller.
     */
    fun secondaryIds(context: Context): List<String> = when (LicenseConfig.APPROVAL_ID_MODE) {
        ApprovalIdMode.ANDROID_ID -> listOf(hwid(context))
        ApprovalIdMode.INSTALL_HWID -> listOf(androidId(context))
    }

    /** Human-readable name of the identifier [approvalId] returns, for the lock screen and the logs. */
    fun approvalIdName(): String = when (LicenseConfig.APPROVAL_ID_MODE) {
        ApprovalIdMode.ANDROID_ID -> "ANDROID_ID"
        ApprovalIdMode.INSTALL_HWID -> "HWID"
    }

    /** Name of the other identifier, for the lock screen's secondary copy button. */
    fun secondaryIdName(): String = when (LicenseConfig.APPROVAL_ID_MODE) {
        ApprovalIdMode.ANDROID_ID -> "HWID"
        ApprovalIdMode.INSTALL_HWID -> "ANDROID_ID"
    }

    /**
     * The HWID, stable for the lifetime of the installation. Generates and persists one on first call.
     */
    fun hwid(context: Context): String {
        cachedHwid?.let { return it }
        synchronized(this) {
            cachedHwid?.let { return it }

            val store = HwidStore.open(context)
            val androidId = readAndroidId(context)
            val check = androidId?.let { fingerprint(it) }

            var stored = store.getString(KEY_HWID)
            if (stored != null && !isCanonicalUuid(stored)) {
                logWarn("${LicenseConfig.LOG_TAG}: the stored HWID is not a UUID ('$stored'), issuing a new one")
                stored = null
            }

            val storedCheck = store.getString(KEY_ANDROID_ID_CHECK)
            if (stored != null && check != null && storedCheck != null && storedCheck != check) {
                logWarn(
                    "${LicenseConfig.LOG_TAG}: this installation's state was restored onto different " +
                            "hardware (ANDROID_ID check changed); issuing a new HWID, this device has to " +
                            "be added to the whitelist again"
                )
                stored = null
            }

            if (stored == null) {
                // A new identity must not inherit the previous one's verification state, otherwise the
                // grace period of the old device would carry over to the new UUID.
                store.clear()
                stored = UUID.randomUUID().toString().lowercase()
                store.putString(KEY_HWID, stored)
                if (check != null) store.putString(KEY_ANDROID_ID_CHECK, check)
                logWarn("${LicenseConfig.LOG_TAG}: new device HWID issued: $stored")
            } else if (storedCheck == null && check != null) {
                store.putString(KEY_ANDROID_ID_CHECK, check)
            }

            cachedHwid = stored
            return stored
        }
    }

    /**
     * A truncated, salted-free hash of the ANDROID_ID: enough to tell "same device as before" from
     * "different device", and useless as an identifier anywhere else. Empty when the device does not
     * expose one.
     */
    fun androidIdCheck(context: Context): String {
        val androidId = readAndroidId(context) ?: return ""
        return fingerprint(androidId)
    }

    /**
     * The block the user sends to the operator: both identifiers, labelled, plus context. The lock
     * screen copies a single bare ID (that is what goes into the list); this block is what the abort
     * path writes to `hwid.txt` when something more technical is needed.
     */
    fun report(context: Context): String {
        return buildString {
            val androidId = androidId(context)
            append("ANDROID_ID: ").append(androidId.ifEmpty { "(unavailable)" }).append('\n')
            append("install HWID: ").append(runCatching { hwid(context) }.getOrDefault("(unavailable)")).append('\n')
            append("approved by: ").append(approvalIdName())
            append("  (this build lists ")
            append(if (LicenseConfig.ACCEPT_IDS_EITHER_WAY) "either identifier" else approvalIdName())
            append(")\n")
            append("app: ${context.packageName} ${appVersion(context)}")
        }
    }

    private fun appVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName)
        "${info.versionName} (${info.longVersionCode})"
    } catch (_: Throwable) {
        "unknown"
    }

    private fun readAndroidId(context: Context): String? = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() && it != BROKEN_ANDROID_ID }
    } catch (t: Throwable) {
        logError("${LicenseConfig.LOG_TAG}: cannot read ANDROID_ID", t)
        null
    }

    private fun fingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    private fun isCanonicalUuid(value: String): Boolean = try {
        UUID.fromString(value)
        true
    } catch (_: Throwable) {
        false
    }
}
