package dev.sora.protohax.hwid.pure

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Detached-signature verification for the whitelist file.
 *
 * Algorithm: **ECDSA P-256 with SHA-256**, i.e. `SHA256withECDSA` in JCA terms. It is used instead of
 * Ed25519 for one boring reason: the JCA on Android only knows `Ed25519` from API 33 up, and this app
 * supports API 28. `SHA256withECDSA` is available on every Android version this app runs on, needs no
 * third-party crypto library, and is signed with a single `openssl` command on the operator's PC.
 *
 * The signature is a DER-encoded `ECDSA-Sig-Value`, which is exactly what `openssl dgst -sha256 -sign`
 * and `node tools/hwid-sign.mjs` produce and what JCA's `SHA256withECDSA` expects. The `.sig` file in
 * the repository carries it base64-encoded (one line, newlines are tolerated).
 *
 * Nothing in this file touches the Android framework, so it is covered by plain JVM unit tests
 * (`app/src/test/java/dev/sora/protohax/hwid/`).
 */
object SignatureVerifier {

    /**
     * Verifies [signatureFileBytes] (base64, or raw DER) over the exact [payload] bytes with the
     * public key in [publicKeyBase64].
     *
     * @return `null` when the signature is good, otherwise a human readable reason. Never throws.
     */
    @JvmStatic
    fun verify(publicKeyBase64: String, payload: ByteArray, signatureFileBytes: ByteArray?): String? {
        if (signatureFileBytes == null || signatureFileBytes.isEmpty()) {
            return "the signature file is missing or empty"
        }

        val publicKey = try {
            decodePublicKey(publicKeyBase64)
        } catch (t: Throwable) {
            return "the public key compiled into the app cannot be read (${t.javaClass.simpleName}: ${t.message}); " +
                    "paste the output of tools/hwid-keygen.mjs into LicenseConfig.SIGNING_PUBLIC_KEY_BASE64"
        } ?: return "no public key is configured (LicenseConfig.SIGNING_PUBLIC_KEY_BASE64 is empty)"

        val candidates = signatureCandidates(signatureFileBytes)
        if (candidates.isEmpty()) {
            return "the signature file is neither base64 nor a raw DER signature"
        }

        var lastError = "no signature candidate could be checked"
        for (candidate in candidates) {
            try {
                val verifier = Signature.getInstance(ALGORITHM)
                verifier.initVerify(publicKey)
                verifier.update(payload)
                if (verifier.verify(candidate)) return null
                lastError = "the signature does not match the file contents"
            } catch (t: Throwable) {
                lastError = "the signature could not be checked (${t.javaClass.simpleName}: ${t.message})"
            }
        }
        return lastError
    }

    /**
     * The signature as it appears on disk: base64 of the DER signature (what the signing script
     * writes). A file that is not printable base64 is taken as a raw DER signature, and a base64 file
     * is checked both as-is and after a no-padding decode, so a hand-edited `.sig` does not become a
     * spurious failure.
     */
    @JvmStatic
    fun signatureCandidates(signatureFileBytes: ByteArray): List<ByteArray> {
        val result = ArrayList<ByteArray>(2)
        val asText = String(signatureFileBytes, Charsets.UTF_8)
        val looksLikeBase64 = asText.isNotBlank() && BASE64_LINE.matches(asText.trim())
        if (looksLikeBase64) {
            val compact = asText.filterNot { it.isWhitespace() }
            try {
                result.add(Base64.getDecoder().decode(compact))
            } catch (_: Throwable) {
                // padding was mangled; the MIME decoder below is more forgiving
            }
            try {
                result.add(Base64.getMimeDecoder().decode(compact))
            } catch (_: Throwable) {
                // not base64 after all
            }
        }
        if (result.isEmpty()) {
            result.add(signatureFileBytes)
        }
        return result
    }

    /**
     * Reads the base64 (or PEM) ECDSA P-256 public key that was compiled into the app.
     *
     * @return the key, or null when [publicKeyBase64] is blank.
     */
    @JvmStatic
    fun decodePublicKey(publicKeyBase64: String): PublicKey? {
        val cleaned = publicKeyBase64
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("-----") }
            .joinToString("")
        if (cleaned.isEmpty()) return null

        val encoded = Base64.getMimeDecoder().decode(cleaned)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
    }

    private const val ALGORITHM = "SHA256withECDSA"
    private val BASE64_LINE = Regex("^[A-Za-z0-9+/=\\s]+$")
}
