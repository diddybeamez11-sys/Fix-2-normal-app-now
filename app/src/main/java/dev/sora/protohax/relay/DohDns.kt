package dev.sora.protohax.relay

import com.google.gson.JsonParser
import dev.sora.relay.utils.logInfo
import dev.sora.relay.utils.logWarn
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves a host with DNS-over-HTTPS (JSON API) instead of the phone's DNS, for [AuthHttpClient]'s last
 * fallback modes.
 *
 * The resolvers are addressed by IP, so the lookup itself does not depend on the phone's DNS, and the
 * certificates of all three name those IPs. AliDNS is in the list for networks where Cloudflare and Google
 * are not reachable. The timeouts are short because this can run while the game waits for the answer to its
 * login packet.
 */
object DohDns : Dns {

	private class Resolver(val name: String, val url: String, val type: String)

	private val resolvers = listOf(
		Resolver("Cloudflare", "https://1.1.1.1/dns-query", "A"),
		Resolver("Google", "https://8.8.8.8/resolve", "A"),
		Resolver("AliDNS", "https://223.5.5.5/resolve", "1"),
	)

	private val client = OkHttpClient.Builder()
		.proxy(Proxy.NO_PROXY)
		.connectTimeout(4, TimeUnit.SECONDS)
		.readTimeout(4, TimeUnit.SECONDS)
		.callTimeout(6, TimeUnit.SECONDS)
		.build()

	private const val MIN_TTL_SECONDS = 60L

	/** host -> (addresses, expiry in elapsed millis) */
	private val cache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()

	/** host -> (error message, expiry): a failed lookup is not repeated right away (it can take seconds) */
	private val failures = ConcurrentHashMap<String, Pair<String, Long>>()
	private const val FAILURE_CACHE_MS = 30_000L

	private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

	override fun lookup(hostname: String): List<InetAddress> {
		cache[hostname]?.let { (addresses, expiry) ->
			if (System.currentTimeMillis() < expiry) return addresses
		}
		failures[hostname]?.let { (message, expiry) ->
			if (System.currentTimeMillis() < expiry) throw UnknownHostException(message)
		}

		val errors = mutableListOf<String>()
		for (resolver in resolvers) {
			try {
				val (addresses, ttl) = query(resolver, hostname)
				if (addresses.isEmpty()) {
					errors += "${resolver.name}: no A record"
					continue
				}
				cache[hostname] = addresses to System.currentTimeMillis() + maxOf(ttl, MIN_TTL_SECONDS) * 1000
				logInfo("DNS-over-HTTPS (${resolver.name}): $hostname -> ${addresses.joinToString(", ") { it.hostAddress ?: "?" }}")
				return addresses
			} catch (t: Throwable) {
				errors += "${resolver.name}: $t"
			}
		}

		val message = "DNS-over-HTTPS lookup of $hostname failed (${errors.joinToString("; ")})"
		failures[hostname] = message to System.currentTimeMillis() + FAILURE_CACHE_MS
		logWarn(message)
		throw UnknownHostException(message)
	}

	/** The addresses DNS-over-HTTPS gives for [hostname], for the diagnostic log. Never throws. */
	fun describe(hostname: String): String {
		return try {
			lookup(hostname).joinToString(", ") { it.hostAddress ?: it.toString() }
		} catch (t: Throwable) {
			"<${t.message}>"
		}
	}

	private fun query(resolver: Resolver, hostname: String): Pair<List<InetAddress>, Long> {
		val url = resolver.url.toHttpUrl().newBuilder()
			.addQueryParameter("name", hostname)
			.addQueryParameter("type", resolver.type)
			.build()
		val request = Request.Builder()
			.url(url)
			.header("Accept", "application/dns-json")
			.build()

		client.newCall(request).execute().use { response ->
			if (response.code != 200) error("HTTP ${response.code}")
			val body = JsonParser.parseString(response.body!!.string()).asJsonObject
			val status = body.get("Status")?.asInt ?: -1
			if (status != 0) error("DNS status $status")

			val addresses = mutableListOf<InetAddress>()
			var ttl = Long.MAX_VALUE
			body.getAsJsonArray("Answer")?.forEach { element ->
				val answer = element.asJsonObject
				// type 1 = A; CNAME records (type 5) on the way are skipped
				if (answer.get("type")?.asInt == 1) {
					val ip = answer.get("data").asString
					if (!IPV4.matches(ip)) error("not an IPv4 address: $ip")
					// an IP literal: InetAddress parses it without any DNS lookup
					addresses += InetAddress.getByName(ip)
					answer.get("TTL")?.asLong?.let { ttl = minOf(ttl, it) }
				}
			}
			return addresses to (if (ttl == Long.MAX_VALUE) MIN_TTL_SECONDS else ttl)
		}
	}
}
