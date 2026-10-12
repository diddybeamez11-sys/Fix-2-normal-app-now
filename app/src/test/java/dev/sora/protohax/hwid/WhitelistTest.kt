package dev.sora.protohax.hwid

import dev.sora.protohax.hwid.pure.FetchResult
import dev.sora.protohax.hwid.pure.HwidList
import dev.sora.protohax.hwid.pure.ListVerdict
import dev.sora.protohax.hwid.pure.SignatureVerifier
import dev.sora.protohax.hwid.pure.WhitelistFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the part of the gate that decides whether a fetched whitelist may be trusted at all: the
 * detached signature, the file formats, and the JSON/plain-text parsing.
 *
 * This is plain JVM code on purpose (see the package `dev.sora.protohax.hwid.pure`), because the rest of
 * the feature needs a device. Run with `./gradlew :app:testDebugUnitTest`.
 *
 * The fixtures under `src/test/resources/hwid` are signed with a throwaway key whose private half is not
 * in this repository; see the README next to them.
 */
class WhitelistTest {

    // --------------------------------------------------------------------------------------------
    // Signatures
    // --------------------------------------------------------------------------------------------

    @Test
    fun `a signature made by the signing tool verifies`() {
        assertNull(
            "the committed fixture signature must verify with the committed public key",
            SignatureVerifier.verify(TEST_PUBLIC_KEY_BASE64, payload(), signature())
        )
    }

    @Test
    fun `one changed byte in the list breaks the signature`() {
        val tampered = payload().copyOf()
        // Flip a bit in the middle of the first HWID: exactly what adding yourself to a list would do.
        val index = tampered.indexOfFirst { it == '3'.code.toByte() }
        tampered[index + 1] = '4'.code.toByte()

        val reason = SignatureVerifier.verify(TEST_PUBLIC_KEY_BASE64, tampered, signature())
        assertNotNull("a modified list must not verify", reason)
        assertTrue("the reason should name the mismatch: $reason", reason!!.contains("does not match"))
    }

    @Test
    fun `a signature made with another key is refused`() {
        assertNotNull(SignatureVerifier.verify(OTHER_PUBLIC_KEY_BASE64, payload(), signature()))
    }

    @Test
    fun `a missing or empty signature is refused`() {
        assertNotNull(SignatureVerifier.verify(TEST_PUBLIC_KEY_BASE64, payload(), null))
        assertNotNull(SignatureVerifier.verify(TEST_PUBLIC_KEY_BASE64, payload(), ByteArray(0)))
    }

    @Test
    fun `no configured public key refuses everything (fail closed)`() {
        val reason = SignatureVerifier.verify("", payload(), signature())
        assertNotNull("with no key configured nothing may be trusted", reason)
        assertTrue("the reason should say what to do: $reason", reason!!.contains("SIGNING_PUBLIC_KEY_BASE64"))
    }

    @Test
    fun `a PEM block with header and footer is accepted as the public key`() {
        val pem = "-----BEGIN PUBLIC KEY-----\n" +
                TEST_PUBLIC_KEY_BASE64.chunked(64).joinToString("\n") +
                "\n-----END PUBLIC KEY-----\n"
        assertNull(SignatureVerifier.verify(pem, payload(), signature()))
    }

    @Test
    fun `a signature file with line breaks in it still verifies`() {
        val wrapped = signature().decodeToString().chunked(40).joinToString("\n").toByteArray()
        assertNull(SignatureVerifier.verify(TEST_PUBLIC_KEY_BASE64, payload(), wrapped))
    }

    // --------------------------------------------------------------------------------------------
    // Parsing
    // --------------------------------------------------------------------------------------------

    @Test
    fun `the JSON envelope is read, version and all`() {
        val parsed = HwidList.parse(payload())
        assertNotNull(parsed)
        assertTrue(parsed!!.versionKnown)
        assertEquals(7L, parsed.version)
        assertTrue(parsed.tokens.contains("3f2c1e8a-5b47-4d0e-9c1a-77bb1e2d4f60"))
        // "NAME  comment" - the entry is the first word, the rest is for the operator
        assertTrue(parsed.tokens.contains("9a0b7c34-2e15-4f7a-8d33-1c2b3a4d5e6f"))
        assertEquals(2, parsed.tokens.size)
    }

    @Test
    fun `a devices dot json envelope is read as well as hwids`() {
        // The operator's file is conventionally called devices.json, so the array inside it is allowed to
        // be called "devices".
        val parsed = HwidList.parse(
            """
            {
              "version": 3,
              "devices": [
                "aabbccdd-1122-3344-5566-778899aabbcc  alice",
                "00112233445566"
              ]
            }
            """.trimIndent().toByteArray()
        )

        assertNotNull(parsed)
        assertTrue(parsed!!.versionKnown)
        assertEquals(3L, parsed.version)
        assertEquals(2, parsed.tokens.size)
        assertTrue(HwidList.contains(parsed, "00112233445566"))
    }

    @Test
    fun `lookups are case insensitive`() {
        val parsed = HwidList.parse(payload())!!
        assertTrue(HwidList.contains(parsed, "3F2C1E8A-5B47-4D0E-9C1A-77BB1E2D4F60"))
        assertTrue(HwidList.contains(parsed, "  3f2c1e8a-5b47-4d0e-9c1a-77bb1e2d4f60  "))
        assertFalse(HwidList.contains(parsed, "11111111-2222-3333-4444-555555555555"))
        assertFalse(HwidList.contains(parsed, ""))
    }

    @Test
    fun `a bare JSON array is read`() {
        val parsed = HwidList.parse("""["AABBCC", "ddeeff"]""".toByteArray())!!
        assertTrue(HwidList.contains(parsed, "aabbcc"))
        assertFalse(parsed.versionKnown)
    }

    @Test
    fun `a plain text list is read, comments and annotations included`() {
        val text = """
            # 2b2t MCPE testers
            3f2c1e8a-5b47-4d0e-9c1a-77bb1e2d4f60

            9a0b7c34-2e15-4f7a-8d33-1c2b3a4d5e6f  bob
            // another comment
        """.trimIndent()
        val parsed = HwidList.parse(text.toByteArray())!!
        assertEquals(2, parsed.tokens.size)
        assertFalse("plain lists carry no version", parsed.versionKnown)
        assertTrue(HwidList.contains(parsed, "9a0b7c34-2e15-4f7a-8d33-1c2b3a4d5e6f"))
    }

    @Test
    fun `a byte order mark does not break the parse`() {
        val withBom = "\uFEFF".toByteArray() + payload()
        assertNotNull(HwidList.parse(withBom))
    }

    @Test
    fun `something that is not a list is not parsed`() {
        assertNull(HwidList.parse(ByteArray(0)))
        assertNull(HwidList.parse("   \n ".toByteArray()))
        assertNull(HwidList.parse("{ this is not json".toByteArray()))
        assertNull(HwidList.parse("""{"hwids": "not-an-array"}""".toByteArray()))
        // A captive portal's login page must never look like a whitelist.
        assertNull(HwidList.parse("<html><body>Sign in to WiFi</body></html>".toByteArray()))
    }

    // --------------------------------------------------------------------------------------------
    // The whole read -> verify -> parse pipeline
    // --------------------------------------------------------------------------------------------

    @Test
    fun `a signed list is trusted`() {
        val verdict = WhitelistFile.evaluate(
            FetchResult.Ok(payload(), signature()),
            requireSignature = true,
            publicKeyBase64 = TEST_PUBLIC_KEY_BASE64,
            lastAcceptedVersion = 0L
        )
        assertTrue(verdict is ListVerdict.Trusted)
        verdict as ListVerdict.Trusted
        assertTrue(verdict.signed)
        assertEquals(7L, verdict.version)
    }

    @Test
    fun `an older list is refused even when it is signed`() {
        // What a replay looks like: an attacker serves a list the device has already seen, from a time
        // when somebody who has since been removed was still on it.
        val verdict = WhitelistFile.evaluate(
            FetchResult.Ok(payload(), signature()),
            requireSignature = true,
            publicKeyBase64 = TEST_PUBLIC_KEY_BASE64,
            lastAcceptedVersion = 8L
        )
        assertTrue(verdict is ListVerdict.Untrusted)
        assertTrue((verdict as ListVerdict.Untrusted).reason.contains("older"))
    }

    @Test
    fun `with signature checking switched off the list is trusted unsigned`() {
        val verdict = WhitelistFile.evaluate(
            FetchResult.Ok(payload(), null),
            requireSignature = false,
            publicKeyBase64 = "",
            lastAcceptedVersion = 0L
        )
        assertTrue(verdict is ListVerdict.Trusted)
        assertFalse((verdict as ListVerdict.Trusted).signed)
    }

    @Test
    fun `a required signature that is missing is refused`() {
        val verdict = WhitelistFile.evaluate(
            FetchResult.Ok(payload(), null),
            requireSignature = true,
            publicKeyBase64 = TEST_PUBLIC_KEY_BASE64,
            lastAcceptedVersion = 0L
        )
        assertTrue(verdict is ListVerdict.Untrusted)
        assertTrue((verdict as ListVerdict.Untrusted).reason.contains("signature"))
    }

    // --------------------------------------------------------------------------------------------

    private fun payload(): ByteArray = resource("/hwid/hwids.json")
    private fun signature(): ByteArray = resource("/hwid/hwids.json.sig")

    private fun resource(path: String): ByteArray {
        val stream = javaClass.getResourceAsStream(path)
            ?: error("missing test resource $path")
        return stream.use { it.readBytes() }
    }

    private companion object {
        /** Public half of the throwaway key that signed the fixtures above. */
        const val TEST_PUBLIC_KEY_BASE64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfMFAVfNpvncUPYwbJkT4HRhnXjduuWa8pqCLh+6BjcUb" +
                    "Pvv11wT9MFTtuZ4nX3FUYS4a9GfouQaft/P/IckjAw=="

        /** An unrelated key: proves a signature from a different keypair is refused. */
        const val OTHER_PUBLIC_KEY_BASE64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEWnPiAkcWW7I8KycYLkvl/joDLYNbmf3zFeGcgyuCS69O" +
                    "r0xvaaG7CPfeewBDTj6kIKakl3mn3RKMiCvCWwEFUw=="
    }
}
