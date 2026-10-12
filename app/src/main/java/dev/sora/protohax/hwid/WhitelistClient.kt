package dev.sora.protohax.hwid

import dev.sora.relay.utils.HttpUtils
import dev.sora.relay.utils.logInfo
import dev.sora.relay.utils.logWarn
import dev.sora.protohax.hwid.pure.FetchResult
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The only network access this feature has: two HTTPS GETs from the whitelist repository - the list and
 * the detached signature next to it. No API, no backend, no telemetry, nothing else.
 *
 * Rules that the rest of the gate relies on:
 *
 *  * a request that could not be *completed* (no connectivity, DNS, TLS, a timeout, a proxy, a non-200
 *    answer, an empty or oversized body) is [FetchResult.Offline] - this is the case the offline grace
 *    period exists for;
 *  * bytes that arrived but do not verify are *not* an offline case, they are [FetchResult.Ok] with a
 *    signature that will fail its check, i.e. untrusted data (see LicenseConfig.TAMPER_FAILS_CLOSED).
 *
 * Nothing in here throws: a network exception must never surface as a stack trace in the middle of the
 * app's start-up, it is turned into an [FetchResult.Offline] with a reason string.
 */
internal object WhitelistClient {

    private const val USER_AGENT = "ProtoHax-Android-hwid-check"

    /**
     * Reads the list and (when a signature is required) the signature.
     *
     * @return [FetchResult.Ok] when the list itself was read; [FetchResult.Offline] when it was not.
     *   Note that a *missing* signature file is reported as offline as well: not being able to read the
     *   file at all is an availability problem (the operator forgot to commit the .sig), while a
     *   signature that is present and wrong is an authenticity problem. Only the second one is
     *   treated as an attack.
     */
    fun fetch(requireSignature: Boolean): FetchResult {
        val payload = when (val list = get(LicenseConfig.WHITELIST_URL, "the whitelist")) {
            is Get.Ok -> list.body
            is Get.Failed -> return FetchResult.Offline(list.reason)
        }

        if (!requireSignature) {
            return FetchResult.Ok(payload, null)
        }

        return when (val signature = get(LicenseConfig.SIGNATURE_URL, "the signature")) {
            is Get.Ok -> FetchResult.Ok(payload, signature.body)
            is Get.Failed -> FetchResult.Offline(signature.reason)
        }
    }

    private sealed class Get {
        class Ok(val body: ByteArray) : Get()
        class Failed(val reason: String) : Get()
    }

    private fun get(url: String, what: String): Get {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                // A revocation must not sit in an HTTP cache for hours; revalidate every time.
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // includes 404 for a file that was never committed, 403 for a private repo, 5xx
                    return Get.Failed("HTTP ${response.code} ${response.message} for $what")
                }
                val body = response.body ?: return Get.Failed("empty response for $what")
                val bytes = readBounded(body.byteStream(), what)
                if (bytes.isEmpty()) return Get.Failed("empty body for $what")
                logInfo("${LicenseConfig.LOG_TAG}: read $what (${bytes.size} bytes) from $url")
                Get.Ok(bytes)
            }
        } catch (t: Throwable) {
            // Deliberately everything: UnknownHostException, SSLHandshakeException, timeouts, an
            // IllegalStateException from OkHttp, a SecurityException ... none of it may crash the app.
            val name = t.javaClass.simpleName
            val detail = t.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
            logWarn("${LicenseConfig.LOG_TAG}: could not read $what from $url ($name$detail)")
            Get.Failed("$name$detail while reading $what")
        }
    }

    /** Reads at most [LicenseConfig.MAX_DOWNLOAD_BYTES]; a huge or endless body is a failure, not an OOM. */
    private fun readBounded(stream: java.io.InputStream, what: String): ByteArray {
        val out = ByteArrayOutputStream(8 * 1024)
        val buffer = ByteArray(8 * 1024)
        var total = 0
        stream.use {
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                total += read
                if (total > LicenseConfig.MAX_DOWNLOAD_BYTES) {
                    throw IOException("$what is larger than ${LicenseConfig.MAX_DOWNLOAD_BYTES} bytes")
                }
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    /**
     * The app's HTTP client (the one `AuthHttpClient` installs, which retries a refused TLS handshake
     * with a different ClientHello) with this feature's own timeouts. Falls back to a plain client if
     * that one cannot be cloned, so a jar change cannot take the check down with it.
     */
    private val client: OkHttpClient by lazy {
        val template = try {
            HttpUtils.client
        } catch (t: Throwable) {
            logWarn("${LicenseConfig.LOG_TAG}: cannot reuse the app's HTTP client (${t.message}); using a fresh one")
            null
        }
        (template?.newBuilder() ?: OkHttpClient.Builder())
            .connectTimeout(LicenseConfig.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(LicenseConfig.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(LicenseConfig.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
}
