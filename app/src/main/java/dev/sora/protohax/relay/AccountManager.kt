package dev.sora.protohax.relay

import com.google.gson.*
import com.google.gson.annotations.SerializedName
import dev.sora.protohax.MyApplication
import dev.sora.protohax.util.ContextUtils.readString
import dev.sora.protohax.util.ContextUtils.writeString
import dev.sora.relay.session.listener.xbox.XboxDeviceInfo
import dev.sora.relay.utils.logError
import java.io.File
import java.io.IOException
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

object AccountManager {

    private const val KEY_CURRENT_MICROSOFT_REFRESH_TOKEN =
        "MICROSOFT_REFRESH_TOKEN"

    val accounts = mutableListOf<Account>()

    private var currentRefreshToken: String?
        get() = MyApplication.instance
            .readString(KEY_CURRENT_MICROSOFT_REFRESH_TOKEN)
            ?.ifEmpty { null }

        set(value) = MyApplication.instance
            .writeString(
                KEY_CURRENT_MICROSOFT_REFRESH_TOKEN,
                value ?: ""
            )

    var currentAccount: Account?
        get() = currentRefreshToken?.let { token ->
            accounts.find { it.refreshToken == token }
        }

        set(value) {
            if (value == null) {
                currentRefreshToken = null
            } else if (accounts.contains(value)) {
                currentRefreshToken = value.refreshToken
            }
        }

    private val storeFile =
        File(MyApplication.instance.filesDir, "credentials.json")

    private val gson = GsonBuilder()
        .registerTypeAdapter(
            XboxDeviceInfo::class.java,
            DeviceInfoAdapter()
        )
        .create()

    init {
        load()
    }

    /**
     * Reads the stored accounts into [accounts].
     *
     * This object is initialised while the application is starting up (`MyApplication.onCreate` ->
     * `XboxAuthWarmUp`), so anything that throws here is an `ExceptionInInitializerError` in the
     * app's start-up: the app dies on open, and because a Kotlin object is only ever initialised
     * once, every later access throws `NoClassDefFoundError` - the app stays dead until its data
     * is cleared. That is exactly what an unreadable `credentials.json` used to do:
     *
     *  * an empty or whitespace-only file makes `gson.fromJson` return null, and
     *    `addAll(null)` is a NullPointerException;
     *  * a truncated file (the app killed in the middle of a save), or one written by an older
     *    build, is a JsonSyntaxException.
     *
     * Neither is worth that. An unreadable file is moved aside - not deleted, so it can still be
     * looked at - and the app starts with no account, i.e. with the "no account selected" card the
     * user can act on.
     */
    fun load() {
        accounts.clear()

        try {
            if (!storeFile.exists()) {
                currentRefreshToken = null
                return
            }

            val stored = readStored()
            if (stored == null) {
                quarantine()
                currentRefreshToken = null
                return
            }

            accounts.addAll(stored)
            cleanupCurrentRefreshToken()
        } catch (t: Throwable) {
            // Last resort: never let the account store take the start-up down with it.
            logError("load the stored accounts", t)
            accounts.clear()
            currentRefreshToken = null
        }
    }

    /** @return the stored accounts, or null when the file could not be read or parsed. */
    private fun readStored(): List<Account>? = try {
        storeFile.reader(Charsets.UTF_8).use {
            // null, not an exception, is what Gson returns for a file with nothing in it
            gson.fromJson(it, Array<Account>::class.java)?.toList()
        }
    } catch (t: Throwable) {
        logError("read ${storeFile.name}", t)
        null
    }

    /** Moves the unreadable file aside instead of destroying it. */
    private fun quarantine() {
        val bad = File(storeFile.parentFile, storeFile.name + ".bad")
        try {
            if (bad.exists()) bad.delete()
            if (storeFile.renameTo(bad)) {
                logError(
                    "the stored accounts could not be read, moved to ${bad.name}",
                    IllegalStateException("unreadable account file")
                )
            } else {
                storeFile.delete()
                logError(
                    "the stored accounts could not be read and could not be moved aside, removed",
                    IllegalStateException("unreadable account file")
                )
            }
        } catch (t: Throwable) {
            logError("move the unreadable account file aside", t)
        }
    }

    /**
     * Writes the accounts back.
     *
     * The JSON goes to a temporary file that is then renamed over the real one: a rename inside
     * one directory is atomic, so an app that is killed in the middle of a save leaves the
     * previous file behind rather than a half-written one that cannot be read on the next start.
     */
    fun save() {
        try {
            val json = gson.toJson(accounts.toTypedArray(), Array<Account>::class.java)
            val parent = storeFile.parentFile ?: return
            val tmp = File(parent, storeFile.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(storeFile)) {
                // some file systems refuse to rename over an existing file; fall back to a copy
                storeFile.writeText(json)
                tmp.delete()
            }
        } catch (t: Throwable) {
            logError("save the accounts", t)
        }
    }

    private fun cleanupCurrentRefreshToken() {
        val current = currentRefreshToken

        accounts.forEach {
            if (it.refreshToken == current) {
                return
            }
        }

        currentRefreshToken = null
    }

    private class DeviceInfoAdapter :
        JsonSerializer<XboxDeviceInfo>,
        JsonDeserializer<XboxDeviceInfo> {

        override fun serialize(
            src: XboxDeviceInfo,
            typeOf: Type?,
            ctx: JsonSerializationContext?
        ): JsonElement {
            return JsonPrimitive(src.deviceType)
        }

        override fun deserialize(
            json: JsonElement,
            typeOf: Type?,
            ctx: JsonDeserializationContext?
        ): XboxDeviceInfo {
            return XboxDeviceInfo.devices[json.asString]
                ?: XboxDeviceInfo.DEVICE_ANDROID
        }
    }
}

class Account(
    @SerializedName("remark")
    var remark: String,

    @SerializedName("device")
    val platform: XboxDeviceInfo,

    @SerializedName("refresh_token")
    var refreshToken: String
) {

    /**
     * The access token of the last refresh and when it stops being reused.
     *
     * Every Xbox login asks for an access token, and the login asks more than once when a request has to be
     * retried, so the token is kept instead of refreshing it again for every request. It is never written to
     * credentials.json ([Transient]: accounts are created by Gson without calling the constructor).
     */
    @Transient
    @Volatile
    private var cachedAccessToken: Pair<String, Long>? = null

    /**
     * Refresh the Microsoft access token using the saved
     * refresh token.
     *
     * @return accessToken
     */
    fun refresh(): String {
        validCachedAccessToken()?.let { return it }

        // The background warm-up (XboxAuthWarmUp) may be refreshing right now. Wait for it - but never for
        // long, because this is called while the game waits for the answer to its login packet.
        val locked = accessTokenRefreshLock.tryLock(LOCK_WAIT_MS, TimeUnit.MILLISECONDS)
        try {
            validCachedAccessToken()?.let { return it }

            val attempts = if (locked) REFRESH_ATTEMPTS else 1
            var lastError: Throwable? = null

            for (attempt in 1..attempts) {
                try {
                    return doRefresh()
                } catch (t: Throwable) {
                    lastError = t
                    logError("refresh the Microsoft access token (attempt $attempt/$attempts)", t)
                    if (attempt == attempts || !isTransient(t)) break
                    try {
                        Thread.sleep(RETRY_DELAY_MS * attempt)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }

            throw lastError ?: IllegalStateException("failed to refresh the Microsoft access token")
        } finally {
            if (locked) accessTokenRefreshLock.unlock()
        }
    }

    private fun doRefresh(): String {
        val isCurrent =
            AccountManager.currentAccount == this

        val (accessToken, newRefreshToken) =
            platform.refreshAccessToken(refreshToken)

        // Microsoft may rotate the refresh token.
        this.refreshToken = newRefreshToken

        if (isCurrent) {
            AccountManager.currentAccount = this
        }

        AccountManager.save()

        cachedAccessToken = accessToken to (System.currentTimeMillis() + ACCESS_TOKEN_TTL_MS)

        return accessToken
    }

    private fun validCachedAccessToken(): String? =
        cachedAccessToken?.takeIf { System.currentTimeMillis() < it.second }?.first

    /**
     * A refused TLS handshake (the reported "SSLV3_ALERT_HANDSHAKE_FAILURE") or a connection that never came
     * up means the request did not reach Microsoft, so a retry cannot consume the refresh token twice.
     */
    private fun isTransient(t: Throwable): Boolean =
        t is IOException || t is AssertionError

    companion object {
        private const val REFRESH_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 500L
        private const val LOCK_WAIT_MS = 2000L

        /** Microsoft access tokens are valid for an hour; a shorter lifetime keeps a revoked token rare. */
        private const val ACCESS_TOKEN_TTL_MS = 30L * 60L * 1000L
    }
}

/**
 * Serializes the access token refresh, so the relay's login and the background warm-up never rotate the same
 * refresh token at the same time. A file level lock (and not a field of [Account]) because Gson creates the
 * accounts from credentials.json without calling their constructor.
 */
private val accessTokenRefreshLock = ReentrantLock()
