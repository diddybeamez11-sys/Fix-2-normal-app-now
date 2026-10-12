package dev.sora.protohax.hwid.pure

/**
 * The whole policy of the gate, as a pure function.
 *
 * Keeping it here - no Android types, no I/O, no clock of its own - is what makes the interesting part
 * of the feature testable on the JVM (`app/src/test/java/dev/sora/protohax/hwid/GatePolicyTest.kt`),
 * which matters because it cannot be tested on a device in this project's CI.
 */
object GatePolicy {

    /**
     * @param allow the app may run
     * @param offline true when this was an offline grace-period run, i.e. the list could not be read
     *   this time and the decision rests on the last successful verification
     * @param reason the operator/user facing explanation, always filled in (also when [allow] is true)
     * @param validUntilMs for an offline run, the wall-clock instant the grace period ends, so the UI
     *   can say when the app will stop working
     * @param persist whether this run counts as a successful verification and should move the
     *   "last verified" timestamp forward
     * @param acceptedVersion the list `version` this run accepted, to be remembered for the rollback
     *   protection of the *next* run; null when the run did not accept a versioned list
     * @param fatal whether the refusal is a blanket one, i.e. the binary itself is not one the operator
     *   produced (a repackaged or debugged APK). Fatal refusals terminate the process; everything else
     *   locks the UI and refuses the relay, because a user who is not approved yet has to be able to
     *   read their ID off a screen.
     * @param revoked whether this refusal is "this device was removed from the list by the operator".
     *   The gate remembers that fact locally, so a revoked device stays revoked while it is offline -
     *   otherwise unplugging the network would undo a removal for up to the grace period.
     */
    class Decision(
        val allow: Boolean,
        val offline: Boolean,
        val reason: String,
        val validUntilMs: Long?,
        val persist: Boolean,
        val acceptedVersion: Long? = null,
        val fatal: Boolean = false,
        val revoked: Boolean = false
    )

    /**
     * @param nowMs wall clock (`System.currentTimeMillis()`)
     * @param lastVerifiedAtMs when this device last completed a verification it accepted, or null
     * @param lastAcceptedVersion the highest list `version` this device has accepted so far
     * @param highestWallClockSeenMs the largest `nowMs` ever recorded on this device by the gate
     * @param hwid the identifier this device is approved by ([LicenseConfig.APPROVAL_ID_MODE]) - the one
     *   the lock screen shows - i.e. normally the ANDROID_ID
     * @param alsoAcceptable the other identifier of this device, accepted too when the operator allows
     *   either way. May be empty, and empty entries are ignored.
     * @param revokedAtMs when this device was last seen to be *removed* from the list, or null if that
     *   has never happened (or a later successful check cleared it)
     * @param fetch what the HTTPS GET produced
     * @param requireSignature must the file carry a valid signature
     * @param publicKeyBase64 the signing key compiled into the app
     * @param tamperFailsClosed whether a list that fails verification is fatal (see LicenseConfig)
     * @param graceMs the offline grace period, 0 for strict online-only
     * @param clockToleranceMs how far the clock may jump back before it is called tampering
     */
    fun decide(
        nowMs: Long,
        lastVerifiedAtMs: Long?,
        lastAcceptedVersion: Long,
        highestWallClockSeenMs: Long,
        hwid: String,
        fetch: FetchResult,
        requireSignature: Boolean,
        publicKeyBase64: String,
        tamperFailsClosed: Boolean,
        graceMs: Long,
        clockToleranceMs: Long,
        alsoAcceptable: List<String> = emptyList(),
        revokedAtMs: Long? = null
    ): Decision {
        // 1. A clock that moved backwards extends the grace period, so it is checked first and hard.
        if (highestWallClockSeenMs > 0L && nowMs + clockToleranceMs < highestWallClockSeenMs) {
            return Decision(
                allow = false,
                offline = false,
                reason = "the device clock jumped backwards (now ${formatInstant(nowMs)}, " +
                        "this device has already seen ${formatInstant(highestWallClockSeenMs)}); " +
                        "the offline grace period is measured in wall-clock time, so this cannot be trusted",
                validUntilMs = null,
                persist = false
            )
        }

        // 2. Could not read the list at all -> grace period, measured from the last successful check
        //    (and never for a device the operator has removed).
        if (fetch is FetchResult.Offline) {
            return offlineDecision(
                nowMs, lastVerifiedAtMs, revokedAtMs, graceMs,
                "the list could not be fetched (${fetch.reason})"
            )
        }

        // 3. Read the list -> authenticate it, then look the device up in it.
        val verdict = WhitelistFile.evaluate(fetch, requireSignature, publicKeyBase64, lastAcceptedVersion)

        if (verdict is ListVerdict.Untrusted) {
            if (tamperFailsClosed) {
                return Decision(
                    allow = false,
                    offline = false,
                    reason = verdict.reason,
                    validUntilMs = null,
                    persist = false
                )
            }
            // Configured to treat unverifiable data as "offline" rather than as an attack.
            return offlineDecision(
                nowMs, lastVerifiedAtMs, revokedAtMs, graceMs,
                verdict.reason + " (TAMPER_FAILS_CLOSED is off, falling back to the offline rules)"
            )
        }

        verdict as ListVerdict.Trusted

        // The device is approved by its primary identifier, or by the other one when the operator
        // allows either. The reason always names the primary one, because that is the string the user
        // reads off the screen and sends.
        val primaryListed = HwidList.contains(verdict.tokens, hwid)
        val alsoListed = !primaryListed && alsoAcceptable.any { it.isNotBlank() && HwidList.contains(verdict.tokens, it) }

        if (!primaryListed && !alsoListed) {
            return Decision(
                allow = false,
                offline = false,
                reason = "this device is not on the list (id $hwid, " +
                        "list version ${versionText(verdict)}); a removal of an approved device is " +
                        "remembered locally and also applies while the app is offline",
                validUntilMs = null,
                persist = false,
                revoked = true
            )
        }

        return Decision(
            allow = true,
            offline = false,
            reason = (if (verdict.signed) {
                "approved by the signed list (version ${versionText(verdict)})"
            } else {
                "approved by the list (version ${versionText(verdict)}, unsigned - " +
                        "signature verification is switched off)"
            }) + if (alsoListed) "; matched the secondary identifier" else "",
            validUntilMs = null,
            persist = true,
            acceptedVersion = if (verdict.versionKnown) verdict.version else null
        )
    }

    private fun offlineDecision(
        nowMs: Long,
        lastVerifiedAtMs: Long?,
        revokedAtMs: Long?,
        graceMs: Long,
        because: String
    ): Decision {
        // A removal outranks the grace period. Without this, a removed user would only have to turn the
        // network off (or wait for a captive portal) to keep running on the last successful check.
        if (revokedAtMs != null && (lastVerifiedAtMs == null || revokedAtMs >= lastVerifiedAtMs)) {
            return Decision(
                allow = false,
                offline = false,
                reason = "$because, and this device was removed from the list by the operator " +
                        "(${formatInstant(revokedAtMs)}), which the offline grace period does not undo",
                validUntilMs = null,
                persist = false,
                revoked = true
            )
        }

        if (lastVerifiedAtMs == null) {
            return Decision(
                allow = false,
                offline = false,
                reason = "$because, and this device has never completed a verification, " +
                        "so there is nothing to fall back on",
                validUntilMs = null,
                persist = false
            )
        }

        val validUntil = lastVerifiedAtMs + graceMs
        if (graceMs <= 0L) {
            return Decision(
                allow = false,
                offline = false,
                reason = "$because, and the grace period is 0 (strict online-only)",
                validUntilMs = null,
                persist = false
            )
        }
        if (nowMs > validUntil) {
            return Decision(
                allow = false,
                offline = false,
                reason = "$because, and the offline grace period ended ${formatInstant(validUntil)} " +
                        "(last successful verification ${formatInstant(lastVerifiedAtMs)})",
                validUntilMs = null,
                persist = false
            )
        }

        return Decision(
            allow = true,
            offline = true,
            reason = "$because; running on the last successful verification " +
                    "(${formatInstant(lastVerifiedAtMs)}), which is valid until ${formatInstant(validUntil)}",
            validUntilMs = validUntil,
            persist = false
        )
    }

    private fun versionText(verdict: ListVerdict.Trusted): String =
        if (verdict.versionKnown) verdict.version.toString() else "unknown"

    /** UTC timestamp for log lines and the UI; deliberately not `DateFormat` (locale dependent). */
    fun formatInstant(epochMs: Long): String {
        return java.time.Instant.ofEpochMilli(epochMs).toString()
    }
}
