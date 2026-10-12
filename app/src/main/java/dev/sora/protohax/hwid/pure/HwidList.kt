package dev.sora.protohax.hwid.pure

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * Reads the whitelist file and answers "is this HWID in it?".
 *
 * Two shapes are accepted, because the operator's repository is just files and either one is fine to
 * keep there:
 *
 * 1. the JSON envelope (this is what `tools/hwid-sign.mjs` produces and what HWID_WHITELIST.md
 *    documents):
 *
 *    ```json
 *    {
 *      "version": 7,
 *      "issued": "2026-10-11T12:00:00Z",
 *      "hwids": [
 *        "3f2c1e8a-5b47-4d0e-9c1a-77bb1e2d4f60",
 *        "9a0b7c34-2e15-4f7a-8d33-1c2b3a4d5e6f  bob"
 *      ]
 *    }
 *    ```
 *
 *    A bare JSON array of strings (`["uuid", "uuid"]`) works too. `version` is optional; when it is
 *    present the gate refuses to go backwards to an older list (see [ListVerdict.Trusted.version]).
 *
 *    The array may be named `hwids`, `hwid` or `devices` - the last one because a file called
 *    `devices.json` that lists devices should be allowed to say so.
 *
 * 2. a plain text list, one ID per line:
 *
 *    ```
 *    # 2b2t MCPE testers
 *    3f2c1e8a-5b47-4d0e-9c1a-77bb1e2d4f60
 *    9a0b7c34-2e15-4f7a-8d33-1c2b3a4d5e6f  bob
 *    ```
 *
 *    Blank lines and lines starting with `#` or `//` are ignored. On every other line only the first
 *    whitespace-separated token counts, so an entry may be annotated with who it belongs to.
 *
 * Entries are compared case-insensitively, so the HWID may be pasted in any case.
 */
object HwidList {

    /**
     * Parses the file.
     *
     * @return the parsed list, or null when the bytes are neither of the two shapes above.
     */
    fun parse(payload: ByteArray): Parsed? {
        val text = String(payload, Charsets.UTF_8).trimStart('\uFEFF') // a BOM must not break the parse
        if (text.isBlank()) return null

        val start = text.trimStart()
        if (start.startsWith("{") || start.startsWith("[")) {
            return parseJson(text) ?: return null
        }
        // A page that starts with a tag is somebody else's HTML - a captive portal or a proxy answering
        // instead of GitHub. It must never be read as "a list this device is not on".
        if (start.startsWith("<")) return null
        return parsePlainText(text)
    }

    private fun parseJson(text: String): Parsed? {
        val element: JsonElement = try {
            JsonParser.parseString(text)
        } catch (_: Throwable) {
            return null
        }

        val entries = ArrayList<String>()
        var version = 0L
        var versionKnown = false
        var issued: String? = null

        if (element.isJsonArray) {
            element.asJsonArray.forEach { addEntry(entries, it) }
        } else if (element.isJsonObject) {
            val obj = element.asJsonObject
            val list = obj.get("hwids") ?: obj.get("hwid") ?: obj.get("devices") ?: return null
            if (!list.isJsonArray) return null
            list.asJsonArray.forEach { addEntry(entries, it) }

            val versionElement = obj.get("version")
            if (versionElement != null && versionElement.isJsonPrimitive &&
                versionElement.asJsonPrimitive.isNumber
            ) {
                version = versionElement.asLong
                versionKnown = true
            }
            val issuedElement = obj.get("issued")
            if (issuedElement != null && issuedElement.isJsonPrimitive &&
                issuedElement.asJsonPrimitive.isString
            ) {
                issued = issuedElement.asString
            }
        } else {
            return null
        }

        return Parsed(version, versionKnown, normalize(entries), issued)
    }

    private fun addEntry(entries: MutableList<String>, element: JsonElement) {
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            entries.add(element.asString)
        }
    }

    private fun parsePlainText(text: String): Parsed {
        val entries = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .toList()
        return Parsed(0L, false, normalize(entries), null)
    }

    /**
     * One entry becomes one token: the first whitespace-separated word, lower case, quotes stripped.
     * Everything after it on the line is documentation for the operator.
     */
    private fun normalize(entries: List<String>): Set<String> {
        val tokens = LinkedHashSet<String>(entries.size)
        for (entry in entries) {
            val token = entry.trim()
                .removePrefix("\"").removeSuffix("\"")
                .split(' ', '\t')
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.lowercase()
                ?: continue
            if (token.isNotEmpty()) tokens.add(token)
        }
        return tokens
    }

    /** Case-insensitive membership test. [hwid] is normalised the same way as the file's entries. */
    fun contains(parsed: Parsed, hwid: String): Boolean = contains(parsed.tokens, hwid)

    /** Case-insensitive membership test against an already normalised token set. */
    fun contains(tokens: Set<String>, hwid: String): Boolean {
        val needle = hwid.trim().lowercase()
        return needle.isNotEmpty() && tokens.contains(needle)
    }

    class Parsed(
        val version: Long,
        val versionKnown: Boolean,
        val tokens: Set<String>,
        val issued: String?
    )
}

/**
 * Turns one [FetchResult] into a [ListVerdict]: read the file, check the signature, parse it, and
 * refuse lists that are older than the newest one this device has already accepted.
 *
 * Nothing here touches the Android framework; the unit tests in `app/src/test` cover it.
 */
object WhitelistFile {

    /**
     * @param fetch what the HTTPS GET produced
     * @param requireSignature whether a signature is mandatory (from the config)
     * @param publicKeyBase64 the key compiled into the app
     * @param lastAcceptedVersion the highest `version` this device has accepted so far, if any
     */
    fun evaluate(
        fetch: FetchResult,
        requireSignature: Boolean,
        publicKeyBase64: String,
        lastAcceptedVersion: Long
    ): ListVerdict {
        if (fetch is FetchResult.Offline) {
            // Callers handle this case before calling evaluate(); kept for completeness.
            return ListVerdict.Untrusted(fetch.reason)
        }
        fetch as FetchResult.Ok

        val signed: Boolean
        if (requireSignature) {
            val error = SignatureVerifier.verify(publicKeyBase64, fetch.payload, fetch.signature)
            if (error != null) {
                return ListVerdict.Untrusted("the whitelist did not pass its signature check: $error")
            }
            signed = true
        } else {
            signed = false
        }

        val parsed = HwidList.parse(fetch.payload)
            ?: return ListVerdict.Untrusted(
                "the whitelist could not be read (not a JSON list and not one HWID per line)"
            )

        if (signed && parsed.versionKnown && parsed.version < lastAcceptedVersion) {
            return ListVerdict.Untrusted(
                "the whitelist is older than the last one this device accepted " +
                        "(version ${parsed.version} < $lastAcceptedVersion); that is what a replayed list looks like"
            )
        }

        return ListVerdict.Trusted(parsed.version, parsed.versionKnown, parsed.tokens, signed)
    }
}
