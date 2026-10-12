package dev.sora.protohax.hwid

import dev.sora.protohax.hwid.pure.FetchResult
import dev.sora.protohax.hwid.pure.GatePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of the gate, exercised as a pure function: listed -> run, not listed -> refuse, unreachable
 * -> the grace period, unverifiable -> refuse, clock moved backwards -> refuse.
 *
 * Everything here is the same code the app runs (`dev.sora.protohax.hwid.pure.GatePolicy`); only the
 * Android side around it (threads, preferences, the kill) is device-only.
 */
class GatePolicyTest {

    private val day = 24L * 60L * 60L * 1000L
    private val grace = 7L * day
    private val tolerance = 6L * 60L * 60L * 1000L

    private val listedHwid = "3f2c1e8a-5b47-4d0e-9c1a-77bb1e2d4f60"
    private val otherHwid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    // --------------------------------------------------------------------------------------------
    // A list was read
    // --------------------------------------------------------------------------------------------

    @Test
    fun `a listed device runs`() {
        val decision = decide(now = NOW, hwid = listedHwid, fetch = signedList())

        assertTrue(decision.reason, decision.allow)
        assertFalse(decision.offline)
        assertTrue("a real verification must move the timestamp forward", decision.persist)
        assertNull(decision.validUntilMs)
    }

    @Test
    fun `an unlisted device is refused immediately`() {
        val decision = decide(now = NOW, hwid = otherHwid, fetch = signedList())

        assertFalse("no grace period for a device that is on the network and simply not listed", decision.allow)
        assertTrue(decision.reason, decision.reason.contains("not on the list"))
        assertTrue(decision.reason, decision.reason.contains(otherHwid))
        assertTrue("a removal has to be remembered locally, so it holds while the app is offline", decision.revoked)
        assertFalse("a policy refusal locks the UI, it does not kill the process", decision.fatal)
    }

    @Test
    fun `a listed device whose list fails verification is refused, not granted grace`() {
        val tampered = listBytes().copyOf().also { it[40] = 'X'.code.toByte() }
        val decision = decide(now = NOW, hwid = listedHwid, fetch = FetchResult.Ok(tampered, signatureBytes()))

        assertFalse(decision.allow)
        assertTrue(decision.reason, decision.reason.contains("signature"))
    }

    @Test
    fun `tamper handling can be downgraded to the offline rules on request`() {
        val tampered = listBytes().copyOf().also { it[40] = 'X'.code.toByte() }
        val decision = decide(
            now = NOW, hwid = listedHwid,
            fetch = FetchResult.Ok(tampered, signatureBytes()),
            tamperFailsClosed = false,
            lastVerifiedAt = NOW - day
        )

        assertTrue("TAMPER_FAILS_CLOSED=false falls back to the grace period", decision.allow)
        assertTrue(decision.offline)
    }

    @Test
    fun `a device that never verified cannot run on an unverifiable list`() {
        val tampered = listBytes().copyOf().also { it[40] = 'X'.code.toByte() }
        val decision = decide(
            now = NOW, hwid = listedHwid,
            fetch = FetchResult.Ok(tampered, signatureBytes()),
            tamperFailsClosed = false,
            lastVerifiedAt = null
        )

        assertFalse(decision.allow)
        assertTrue(decision.reason, decision.reason.contains("never completed a verification"))
    }

    // --------------------------------------------------------------------------------------------
    // The list could not be fetched at all
    // --------------------------------------------------------------------------------------------

    @Test
    fun `offline inside the grace period keeps the app running`() {
        val lastVerified = NOW - 2L * day
        val decision = decide(now = NOW, hwid = listedHwid, fetch = offline(), lastVerifiedAt = lastVerified)

        assertTrue(decision.reason, decision.allow)
        assertTrue(decision.offline)
        assertFalse("an offline run proves nothing and must not move the timestamp", decision.persist)
        assertNotNull(decision.validUntilMs)
        org.junit.Assert.assertEquals(lastVerified + grace, decision.validUntilMs)
    }

    @Test
    fun `offline after the grace period is refused`() {
        val decision = decide(
            now = NOW, hwid = listedHwid, fetch = offline(),
            lastVerifiedAt = NOW - grace - 1L
        )

        assertFalse(decision.allow)
        assertTrue(decision.reason, decision.reason.contains("grace period ended"))
    }

    @Test
    fun `a device that never verified has no grace period`() {
        val decision = decide(now = NOW, hwid = listedHwid, fetch = offline(), lastVerifiedAt = null)

        assertFalse("offline on a first run must fail closed", decision.allow)
        assertTrue(decision.reason, decision.reason.contains("never completed a verification"))
    }

    @Test
    fun `a grace period of zero is strict online-only`() {
        val decision = decide(
            now = NOW, hwid = listedHwid, fetch = offline(),
            lastVerifiedAt = NOW - 1000L, graceMs = 0L
        )

        assertFalse(decision.allow)
        assertTrue(decision.reason, decision.reason.contains("strict online-only"))
    }

    // --------------------------------------------------------------------------------------------
    // A device the operator removed
    // --------------------------------------------------------------------------------------------

    @Test
    fun `a removed device is refused even when the list cannot be read`() {
        val lastVerified = NOW - 2L * day
        val decision = decide(
            now = NOW, hwid = listedHwid, fetch = offline(),
            lastVerifiedAt = lastVerified, revokedAt = NOW - day
        )

        assertFalse("turning the network off must not undo a removal", decision.allow)
        assertFalse(decision.offline)
        assertTrue(decision.reason, decision.reason.contains("removed from the list"))
        assertTrue("the removal stays on file for the next start", decision.revoked)
    }

    @Test
    fun `a removed device that was never verified is refused too`() {
        val decision = decide(now = NOW, hwid = listedHwid, fetch = offline(), lastVerifiedAt = null, revokedAt = NOW - day)

        assertFalse(decision.allow)
        assertTrue(decision.reason, decision.reason.contains("removed from the list"))
    }

    @Test
    fun `a removal is not undone by a list that fails to verify either`() {
        val tampered = listBytes().copyOf().also { it[40] = 'X'.code.toByte() }
        val decision = decide(
            now = NOW, hwid = listedHwid, fetch = FetchResult.Ok(tampered, signatureBytes()),
            lastVerifiedAt = NOW - 2L * day, revokedAt = NOW - day, tamperFailsClosed = false
        )

        assertFalse("TAMPER_FAILS_CLOSED=false is the offline rules, and the offline rules see the removal", decision.allow)
        assertTrue(decision.reason, decision.reason.contains("removed from the list"))
    }

    @Test
    fun `being added back after a removal works`() {
        val decision = decide(
            now = NOW, hwid = listedHwid, fetch = signedList(),
            lastVerifiedAt = NOW - 2L * day, revokedAt = NOW - day
        )

        assertTrue(decision.reason, decision.allow)
        assertTrue("approving the device again is a successful verification", decision.persist)
    }

    // --------------------------------------------------------------------------------------------
    // The other identifier of the same device
    // --------------------------------------------------------------------------------------------

    @Test
    fun `the secondary identifier is accepted when the operator allows either`() {
        val decision = decide(
            now = NOW, hwid = otherHwid, fetch = signedList(), alsoAcceptable = listOf(listedHwid)
        )

        assertTrue(decision.reason, decision.allow)
        assertTrue(decision.reason, decision.reason.contains("secondary identifier"))
    }

    @Test
    fun `the secondary identifier is ignored when it is not passed in`() {
        val decision = decide(now = NOW, hwid = otherHwid, fetch = signedList())

        assertFalse(decision.allow)
        assertTrue(decision.revoked)
    }

    @Test
    fun `blank secondary identifiers do not match anything`() {
        val decision = decide(
            now = NOW, hwid = otherHwid, fetch = signedList(), alsoAcceptable = listOf("", "   ")
        )

        assertFalse(decision.allow)
    }

    // --------------------------------------------------------------------------------------------
    // The clock
    // --------------------------------------------------------------------------------------------

    @Test
    fun `a clock that jumped backwards is refused`() {
        val decision = decide(
            now = NOW, hwid = listedHwid, fetch = offline(),
            lastVerifiedAt = NOW - day,
            highestWallClockSeenMs = NOW + 2L * day // the device was two days "in the future" before
        )

        assertFalse("setting the clock back must not extend the grace period", decision.allow)
        assertTrue(decision.reason, decision.reason.contains("clock jumped backwards"))
    }

    @Test
    fun `a small clock correction is not treated as tampering`() {
        val decision = decide(
            now = NOW, hwid = listedHwid, fetch = signedList(),
            highestWallClockSeenMs = NOW + tolerance - 1000L
        )

        assertTrue(decision.reason, decision.allow)
    }

    // --------------------------------------------------------------------------------------------
    // Failure modes of the configuration itself
    // --------------------------------------------------------------------------------------------

    @Test
    fun `with no public key compiled in nothing is trusted`() {
        val decision = decide(now = NOW, hwid = listedHwid, fetch = signedList(), publicKeyBase64 = "")

        assertFalse("a build without a key must not accept an unsigned list", decision.allow)
        assertTrue(decision.reason, decision.reason.contains("signature"))
    }

    @Test
    fun `a list older than one the device accepted is refused`() {
        val decision = decide(
            now = NOW, hwid = listedHwid, fetch = signedList(),
            lastAcceptedVersion = 99L, lastVerifiedAt = NOW - day
        )

        assertFalse(decision.allow)
        assertTrue(decision.reason, decision.reason.contains("older"))
    }

    // --------------------------------------------------------------------------------------------

    private fun decide(
        now: Long,
        hwid: String,
        fetch: FetchResult,
        lastVerifiedAt: Long? = null,
        lastAcceptedVersion: Long = 0L,
        highestWallClockSeenMs: Long = 0L,
        publicKeyBase64: String = TEST_PUBLIC_KEY_BASE64,
        tamperFailsClosed: Boolean = true,
        graceMs: Long = grace,
        requireSignature: Boolean = true,
        alsoAcceptable: List<String> = emptyList(),
        revokedAt: Long? = null
    ) = GatePolicy.decide(
        nowMs = now,
        lastVerifiedAtMs = lastVerifiedAt,
        lastAcceptedVersion = lastAcceptedVersion,
        highestWallClockSeenMs = highestWallClockSeenMs,
        hwid = hwid,
        fetch = fetch,
        requireSignature = requireSignature,
        publicKeyBase64 = publicKeyBase64,
        tamperFailsClosed = tamperFailsClosed,
        graceMs = graceMs,
        clockToleranceMs = tolerance,
        alsoAcceptable = alsoAcceptable,
        revokedAtMs = revokedAt
    )

    private fun signedList() = FetchResult.Ok(listBytes(), signatureBytes())

    private fun offline() = FetchResult.Offline("UnknownHostException: raw.githubusercontent.com")

    private fun listBytes(): ByteArray = resource("/hwid/hwids.json")

    private fun signatureBytes(): ByteArray = resource("/hwid/hwids.json.sig")

    private fun resource(path: String): ByteArray {
        val stream = javaClass.getResourceAsStream(path) ?: error("missing test resource $path")
        return stream.use { it.readBytes() }
    }

    private companion object {
        /** A fixed "now": the tests are about the rules, not about the current time. */
        const val NOW = 1_800_000_000_000L // 2027-01-15T08:00:00Z

        const val TEST_PUBLIC_KEY_BASE64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfMFAVfNpvncUPYwbJkT4HRhnXjduuWa8pqCLh+6BjcUb" +
                    "Pvv11wT9MFTtuZ4nX3FUYS4a9GfouQaft/P/IckjAw=="
    }
}
