package dev.sora.protohax.hwid

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.sora.relay.utils.logWarn

/**
 * The key/value store the gate keeps its state in: the device's install HWID, the hash of the ANDROID_ID
 * it was bound to, the timestamp of the last successful check, the highest list version accepted so far,
 * the highest wall clock the gate has ever seen, and - when the operator has removed this device from the
 * list - the moment that was noticed, so the removal is not undone by going offline.
 *
 * It is an [EncryptedSharedPreferences] file (AES-256-GCM values, AES-256-SIV keys, the master key in the
 * Android keystore), so the HWID cannot simply be read - or *changed* - by anybody who can write to the
 * app's private directory on a rooted device or from a backup.
 *
 * Two things it deliberately survives, because a gate that bricks itself is worse than no gate:
 *
 *  * **a corrupted or undecryptable file.** EncryptedSharedPreferences throws when the keystore entry is
 *    gone (restored from another device, "clear data" partly done, a ROM update that resets the keystore).
 *    The file is then deleted and recreated once, which costs the device a fresh HWID - i.e. a re-add by
 *    the operator - instead of a permanent failure to start.
 *  * **no usable keystore at all.** Then the state is kept in a plain preferences file and a warning is
 *    logged; the gate keeps working, with a weaker local story. (The whitelist check itself does not
 *    depend on this - the encrypted store only protects the identity from being *edited* locally.)
 */
internal class HwidStore private constructor(
    private val prefs: SharedPreferences,
    /** true when the encrypted file is in use, false when it fell back to plain preferences. */
    val encrypted: Boolean
) {

    fun getString(key: String): String? = try {
        prefs.getString(key, null)
    } catch (t: Throwable) {
        logWarn("${LicenseConfig.LOG_TAG}: cannot read '$key' from the state store: ${t.message}")
        null
    }

    fun putString(key: String, value: String?) {
        try {
            prefs.edit().putString(key, value).apply()
        } catch (t: Throwable) {
            logWarn("${LicenseConfig.LOG_TAG}: cannot write '$key' to the state store: ${t.message}")
        }
    }

    fun getLong(key: String, defaultValue: Long): Long = try {
        prefs.getLong(key, defaultValue)
    } catch (t: Throwable) {
        logWarn("${LicenseConfig.LOG_TAG}: cannot read '$key' from the state store: ${t.message}")
        defaultValue
    }

    fun putLong(key: String, value: Long) {
        try {
            prefs.edit().putLong(key, value).apply()
        } catch (t: Throwable) {
            logWarn("${LicenseConfig.LOG_TAG}: cannot write '$key' to the state store: ${t.message}")
        }
    }

    fun clear() {
        try {
            prefs.edit().clear().apply()
        } catch (t: Throwable) {
            logWarn("${LicenseConfig.LOG_TAG}: cannot clear the state store: ${t.message}")
        }
    }

    companion object {

        @Volatile
        private var instance: HwidStore? = null

        /** Opens (and caches) the store. Never throws. */
        fun open(context: Context): HwidStore {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val store = openUncached(context.applicationContext)
                instance = store
                return store
            }
        }

        private fun openUncached(context: Context): HwidStore {
            openEncrypted(context)?.let { return HwidStore(it, encrypted = true) }
            return HwidStore(plain(context), encrypted = false)
        }

        private fun openEncrypted(context: Context): SharedPreferences? {
            for (attempt in 1..2) {
                try {
                    val masterKey = MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    return EncryptedSharedPreferences.create(
                        context,
                        LicenseConfig.PREFS_FILE_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                } catch (t: Throwable) {
                    if (attempt == 2) {
                        logWarn(
                            "${LicenseConfig.LOG_TAG}: encrypted preferences are unavailable " +
                                    "(${t.javaClass.simpleName}: ${t.message}), falling back to plain preferences"
                        )
                        return null
                    }
                    // The keystore entry the file was written with is gone or the file is corrupt: the
                    // only recovery is to drop it. The device gets a new HWID and has to be re-added.
                    logWarn(
                        "${LicenseConfig.LOG_TAG}: cannot open the encrypted state store " +
                                "(${t.javaClass.simpleName}: ${t.message}), re-creating it"
                    )
                    try {
                        context.deleteSharedPreferences(LicenseConfig.PREFS_FILE_NAME)
                    } catch (ignored: Throwable) {
                        // nothing else to try
                    }
                }
            }
            return null
        }

        /**
         * Used only when the encrypted store cannot be opened. Not backed up, for the same reason the
         * encrypted one is not: a restored copy on a second device would hand that device the HWID.
         */
        private fun plain(context: Context): SharedPreferences =
            context.getSharedPreferences(PLAIN_PREFS_FILE_NAME, Context.MODE_PRIVATE)

        private const val PLAIN_PREFS_FILE_NAME = LicenseConfig.PREFS_FILE_NAME + "_plain"
    }
}
