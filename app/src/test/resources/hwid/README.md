# Test fixtures for the HWID whitelist unit tests

These files exist only for `../../java/dev/sora/protohax/hwid/WhitelistTest.kt` /
`GatePolicyTest.kt` and are not used by the app at all.

* `hwids.json` - a whitelist in the documented format.
* `hwids.json.sig` - its signature, 97 bytes, base64 of the DER ECDSA P-256 / SHA-256 signature.

The keypair that produced the signature was generated with `node tools/hwid-keygen.mjs` while writing
this feature and the **private key is not in this repository** (it is a throwaway: the tests only need a
signature to verify against a public key that is pasted into the test source). Nothing here relates to a
real whitelist; adding these IDs to a real list would not grant anybody anything.

If the payload is ever edited, both artifacts have to be regenerated together:

```bash
node tools/hwid-keygen.mjs --out /tmp/test-signing-key.pem
# paste the printed public key into TEST_PUBLIC_KEY_BASE64 in WhitelistTest.kt
node tools/hwid-sign.mjs --file app/src/test/resources/hwid/hwids.json --key /tmp/test-signing-key.pem
```
