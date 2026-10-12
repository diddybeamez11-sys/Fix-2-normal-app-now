package dev.sora.protohax.hwid.pure

/**
 * What one round of "read the whitelist repository" produced.
 *
 * The three cases are kept strictly apart because they lead to three different outcomes: a list that
 * was fetched but not authentic is an attack (or a captive portal) and refuses to run, a list that
 * could not be fetched at all is what the offline grace period is for, and a fetched, authentic list is
 * simply checked for the device's HWID.
 */
sealed class FetchResult {

    /**
     * Both files were read. [signature] is null when the signature file could not be read - that is
     * *not* the same as offline: it means the list is unauthenticated and will not be trusted when a
     * signature is required.
     */
    class Ok(val payload: ByteArray, val signature: ByteArray?) : FetchResult()

    /** The GET failed: no connectivity, DNS, TLS, a timeout, a non-200 answer, a truncated body. */
    class Offline(val reason: String) : FetchResult()
}

/**
 * The verdict on one fetched list, independent of which device asks.
 */
sealed class ListVerdict {

    /**
     * The list is authentic (signature verified when [LicenseConfig.REQUIRE_SIGNATURE]) and parsed.
     *
     * @param version the `version` field of the list, or 0 when the list has none (plain-text lists).
     * @param versionKnown whether the list carried a version at all. Rollback protection can only be
     *   applied to lists that have one.
     */
    class Trusted(
        val version: Long,
        val versionKnown: Boolean,
        val tokens: Set<String>,
        val signed: Boolean
    ) : ListVerdict()

    /** The list came back but must not be trusted: bad signature, unparseable, older than the last one. */
    class Untrusted(val reason: String) : ListVerdict()
}
