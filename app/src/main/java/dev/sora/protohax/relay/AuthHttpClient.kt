package dev.sora.protohax.relay

import android.os.Build
import dev.sora.relay.utils.HttpUtils
import dev.sora.relay.utils.logError
import dev.sora.relay.utils.logInfo
import dev.sora.relay.utils.logWarn
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.TlsVersion
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.cert.CertificateException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The HTTP client ProtoHax uses for the Xbox Live login (`HttpUtils.client`). It retries a refused TLS
 * handshake with a different ClientHello.
 *
 * The reported sessions failed every request to the Xbox Live auth servers (user / device / sisu / xsts
 * .auth.xboxlive.com) with
 *
 *     SSLHandshakeException ... SSLV3_ALERT_HANDSHAKE_FAILURE ... HANDSHAKE_FAILURE_ON_CLIENT_HELLO
 *
 * on every attempt of every login. That message means the peer answered the very first message of the
 * handshake (the ClientHello) with a `handshake_failure` alert: it does not accept what this client offered.
 * A peer that refuses the same ClientHello once refuses it every time, so retrying the request unchanged (what
 * the jar's `retryAuth` does) cannot help.
 *
 * ProtoHax's client is OkHttp's default: one connection spec, `MODERN_TLS` (TLS 1.3 + 1.2, OkHttp's
 * restricted cipher list). This client tries that first, unchanged. When the handshake is refused it tries the
 * same request again with:
 *
 *  1. [Mode.COMPATIBLE] - TLS 1.3 + 1.2 with every cipher suite the device's TLS library has enabled,
 *  2. [Mode.TLS12] - TLS 1.2 only, every enabled cipher suite. There is no TLS 1.3 part in that ClientHello
 *     (no supported_versions / key_share extension, no TLS 1.3 key exchange groups), which is what TLS
 *     terminators and middleboxes that cannot handle newer ClientHellos most often trip over,
 *  3. the same two without the HTTP proxy, if a system proxy (`http.proxyHost`) is set,
 *  4. [Mode.DOH] / [Mode.DOH_TLS12] - the host's address looked up with DNS-over-HTTPS ([DohDns]) instead of
 *     the phone's DNS, without a proxy.
 *
 * Mode 4 is the one most likely to help. Probed from a normal network (GitHub Actions, 2026-10-09), every one
 * of these hosts accepts TLS 1.3 as well as TLS 1.2 with ECDHE-RSA-AES-GCM, which is exactly what the stock
 * client offers. The xboxlive.com auth servers do not even answer a ClientHello they dislike with a
 * `handshake_failure` alert: they reset the connection. So the alert in the report most likely did not come
 * from Microsoft but from whatever the phone's DNS sent the connection to (a DNS filter's block page, a
 * hijacking resolver, a proxy). The game itself is not affected because the VPN gives it 8.8.8.8 as its DNS
 * server, while this app uses the phone's DNS (Private DNS, an ad-blocking DNS app, the carrier's resolver).
 *
 * Each of these is a separate connection, not OkHttp's built-in fallback: that one adds TLS_FALLBACK_SCSV,
 * which a TLS 1.3 server answers with `inappropriate_fallback`.
 *
 * The mode that worked is remembered per host, so only the first request to a host pays for the refused
 * handshakes (they are refused right away, so that only takes a few milliseconds). When every mode is refused,
 * the log says which IP address the host resolved to and which proxy was used. That is what the next step of
 * the diagnosis needs: a DNS filter or a network that blocks the host shows up there.
 */
object AuthHttpClient {

	/** The User-Agent ProtoHax's own client sends (HttpUtils.DEFAULT_AGENT). */
	private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
		"(KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1788.0"

	enum class Mode(val description: String) {
		DEFAULT("OkHttp MODERN_TLS (TLS 1.3/1.2)"),
		COMPATIBLE("TLS 1.3/1.2, all enabled cipher suites"),
		TLS12("TLS 1.2 only, all enabled cipher suites"),
		COMPATIBLE_DIRECT("TLS 1.3/1.2, all enabled cipher suites, no proxy"),
		TLS12_DIRECT("TLS 1.2 only, all enabled cipher suites, no proxy"),
		DOH("TLS 1.3/1.2, all enabled cipher suites, address from DNS-over-HTTPS, no proxy"),
		DOH_TLS12("TLS 1.2 only, all enabled cipher suites, address from DNS-over-HTTPS, no proxy"),
	}

	private val systemProxy: Proxy = systemProxyConfig()

	private val base: OkHttpClient = OkHttpClient.Builder()
		.connectTimeout(10, TimeUnit.SECONDS)
		.readTimeout(15, TimeUnit.SECONDS)
		.writeTimeout(15, TimeUnit.SECONDS)
		.addNetworkInterceptor { chain ->
			chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
		}
		.build()

	private val compatibleSpec = ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
		.tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
		.allEnabledCipherSuites()
		.build()

	private val tls12Spec = ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
		.tlsVersions(TlsVersion.TLS_1_2)
		.allEnabledCipherSuites()
		.build()

	private val clients: Map<Mode, OkHttpClient> = buildMap {
		put(Mode.DEFAULT, base.newBuilder().proxy(systemProxy).build())
		put(Mode.COMPATIBLE, base.newBuilder().proxy(systemProxy).connectionSpecs(listOf(compatibleSpec)).build())
		put(Mode.TLS12, base.newBuilder().proxy(systemProxy).connectionSpecs(listOf(tls12Spec)).build())
		if (systemProxy != Proxy.NO_PROXY) {
			put(Mode.COMPATIBLE_DIRECT, base.newBuilder().proxy(Proxy.NO_PROXY).connectionSpecs(listOf(compatibleSpec)).build())
			put(Mode.TLS12_DIRECT, base.newBuilder().proxy(Proxy.NO_PROXY).connectionSpecs(listOf(tls12Spec)).build())
		}
		put(Mode.DOH, base.newBuilder().proxy(Proxy.NO_PROXY).dns(DohDns).connectionSpecs(listOf(compatibleSpec)).build())
		put(Mode.DOH_TLS12, base.newBuilder().proxy(Proxy.NO_PROXY).dns(DohDns).connectionSpecs(listOf(tls12Spec)).build())
	}

	/** host -> the mode whose handshake that host accepted */
	private val workingMode = ConcurrentHashMap<String, Mode>()

	/** hosts whose successful handshake was already logged (once per host and mode is enough) */
	private val loggedHandshakes = ConcurrentHashMap.newKeySet<String>()

	/**
	 * The client installed as `HttpUtils.client`. It never sends a request itself: its only interceptor runs
	 * the request on the client of the right [Mode].
	 */
	val client: OkHttpClient = base.newBuilder()
		.addInterceptor(Interceptor { chain -> dispatch(chain) })
		.build()

	@Volatile
	var installed = false
		private set

	/**
	 * Makes ProtoHax use [client]. `HttpUtils.client` is a `private static final` field of the jar. ART allows
	 * reflection to set such a field once it is made accessible, and proguard-rules.pro keeps the field's name.
	 * Call it before anything logs in (MyApplication.onCreate).
	 */
	@Synchronized
	fun install() {
		if (installed) return
		try {
			val field = HttpUtils::class.java.getDeclaredField("client")
			field.isAccessible = true
			field.set(null, client)
			if (HttpUtils.client !== client) {
				error("HttpUtils.client still returns the old client")
			}
			installed = true
			logInfo("xbox login http client installed (TLS handshake fallback enabled, proxy: ${describe(systemProxy)})")
		} catch (t: Throwable) {
			logError("install the xbox login http client (the TLS handshake fallback is not active)", t)
		}
	}

	private fun dispatch(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val host = request.url.host

		// a host that accepted a mode before gets that mode first; the others follow in their usual order
		val preferred = workingMode[host]
		val order = buildList {
			if (preferred != null) add(preferred)
			clients.keys.filterTo(this) { it != preferred }
		}

		val failures = mutableListOf<Pair<Mode, IOException>>()
		for (mode in order) {
			val response = try {
				clients.getValue(mode).newCall(request).execute()
			} catch (e: IOException) {
				// a DNS-over-HTTPS mode may also fail because no DoH server is reachable: just try the next mode
				val usesDoh = mode == Mode.DOH || mode == Mode.DOH_TLS12
				if (!isRefusedHandshake(e) && !usesDoh) {
					// not a TLS problem (timeout, no network, DNS, ...): another ClientHello does not help
					failures.forEach { e.addSuppressed(it.second) }
					throw e
				}
				failures += mode to e
				logWarn("TLS handshake with $host refused (${mode.description}): ${e.javaClass.simpleName}: ${firstLine(e.message)}")
				continue
			}

			if (workingMode.put(host, mode) != mode && mode != Mode.DEFAULT) {
				logInfo("TLS handshake with $host works with ${mode.description}, using it from now on")
			}
			if (loggedHandshakes.add("$host/$mode")) {
				response.handshake?.let {
					logInfo("TLS to $host: ${it.tlsVersion.javaName} ${it.cipherSuite.javaName} (${mode.description})")
				}
			}
			return response
		}

		// every ClientHello was refused: log what is needed to find out why, then fail like before
		val primary = failures.firstOrNull { it.second is SSLException }?.second ?: failures.last().second
		logError(
			"TLS handshake with $host refused in all ${failures.size} modes. " +
				"The phone's DNS resolves $host to ${resolve(host)}, DNS-over-HTTPS to ${DohDns.describe(host)}, " +
				"proxy: ${describe(systemProxy)}, " +
				"Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}). " +
				"A refusal of every ClientHello usually means the connection does not reach the real server: " +
				"check Private DNS / ad-blocking DNS, other VPN or firewall apps and the network itself."
		)
		failures.forEach { if (it.second !== primary) primary.addSuppressed(it.second) }
		throw primary
	}

	/**
	 * A TLS failure where a different ClientHello may succeed: the peer refused the handshake, closed the
	 * connection during it, or the protocol negotiation failed. Certificate failures are not retried - a
	 * certificate does not change with the ClientHello, and an untrusted one must never be accepted.
	 */
	private fun isRefusedHandshake(e: IOException): Boolean {
		if (e !is SSLException || e is SSLPeerUnverifiedException) return false
		var cause: Throwable = e
		while (true) {
			if (cause is CertificateException) return false
			val next = cause.cause
			if (next == null || next === cause) return true
			cause = next
		}
	}

	private fun resolve(host: String): String {
		return try {
			InetAddress.getAllByName(host).joinToString(", ") { it.hostAddress ?: it.toString() }
		} catch (t: Throwable) {
			"<DNS lookup failed: $t>"
		}
	}

	private fun describe(proxy: Proxy): String {
		return if (proxy == Proxy.NO_PROXY) "none" else proxy.toString()
	}

	private fun firstLine(message: String?): String {
		return message?.lineSequence()?.firstOrNull()?.trim() ?: ""
	}

	/** The same proxy ProtoHax's own client uses (HttpUtils.getSystemProxyConfig). */
	private fun systemProxyConfig(): Proxy {
		val proxyHost = System.getProperty("http.proxyHost")?.takeIf { it.isNotBlank() } ?: return Proxy.NO_PROXY
		val proxyPort = System.getProperty("http.proxyPort")?.toIntOrNull() ?: return Proxy.NO_PROXY
		return try {
			Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxyHost, proxyPort))
		} catch (t: Throwable) {
			logError("http proxy $proxyHost:$proxyPort", t)
			Proxy.NO_PROXY
		}
	}
}
