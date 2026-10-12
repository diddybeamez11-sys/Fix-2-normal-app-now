# This build's HWID whitelist deployment

This file records **this specific deployment** of the device-approval gate: what was generated, what was
configured, how it was verified, and the exact operator workflow. The full operator's manual — the
rationale, every knob, the anti-tamper details and the honest "what this does *not* stop" — is in
**[HWID_WHITELIST.md](HWID_WHITELIST.md)**. This file is the concrete "how it is wired for the
`diddybeamez11-sys/Verify` list" companion to it.

Everything here was produced with the two documented tools in `tools/` — no key or signature was
invented, and none was pasted from anywhere but a real keygen + sign run.

## 1. What is configured

In `app/src/main/java/dev/sora/protohax/hwid/LicenseConfig.kt`:

| constant | value |
|---|---|
| `WHITELIST_URL` | `https://raw.githubusercontent.com/diddybeamez11-sys/Verify/main/devices.json` |
| `SIGNING_PUBLIC_KEY_BASE64` | `MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAERS4HV6bJT+7cmc/U/Aih4m03SEyC9ZuJUtZVhwP0tcViRcAOXFDRy9WBlRqrFLtyGhlxwQqDtXtTZlZcFQ0UpA==` |
| `REQUIRE_SIGNATURE` | `true` (unchanged — signature verification stays **on**) |

The signature file is fetched from `WHITELIST_URL + ".sig"`, i.e.
`https://raw.githubusercontent.com/diddybeamez11-sys/Verify/main/devices.json.sig`.

The public key above is the X.509 `SubjectPublicKeyInfo` of an **ECDSA P-256** key. Its SHA-256
fingerprint (SHA-256 over the SPKI bytes) is:

```
69:18:ec:cb:e7:de:81:59:10:a6:10:af:2f:1c:6d:7a:2b:bd:46:53:45:bb:fb:89:1e:6b:1c:0b:a5:51:a5:5c
```

Reproduce it from the private key at any time (it reveals nothing secret — the key is public):

```bash
openssl ec -in ~/hwid-signing-key.pem -pubout -outform DER | openssl dgst -sha256
```

If that fingerprint does not match the one in `LicenseConfig.kt`, the app is pinned to a different key
and will reject the list.

## 2. The signing key (custody — read this before anything else)

The keypair was generated once with the repo's own tool:

```bash
node tools/hwid-keygen.mjs --out ~/hwid-signing-key.pem
```

* **Private key** → `~/hwid-signing-key.pem` (mode `600`). This is the **only secret** in the whole
  scheme. It is **never committed** to this repo or to `Verify`, **never printed in CI logs**, and
  **never uploaded**. This repo's `.gitignore` now carries `hwid-signing-key.pem`, `*.pem`, `*.key`
  and `devices.json.sig` as a safety net, but the file is deliberately kept **outside** the repo tree
  so it can never be staged by accident. **Back it up** — without it you can never update the list
  again.
* **Public key** → pasted into `LicenseConfig.SIGNING_PUBLIC_KEY_BASE64` (see §1). It is public; it is
  safe to publish and is compiled into the APK.

## 3. What was signed, and how it was verified

`devices.json` in `Verify` (git blob `d9909f2f9f55a13403e42092231340b62fe594e2`, 98 bytes, SHA-256
`e20a60ef24c60e5c9e7f6c79714f8ccdf887b8e25808a493a7fe362c68630a82`):

```json
{
  "version": 1,
  "issued": "2026-10-12T12:00:00Z",
  "devices": [
    "3f2c1e8a5b474d0e"
  ]
}
```

The signature (`devices.json.sig`, base64 of the DER ECDSA P-256 / SHA-256 signature over the **exact
bytes** of the file above) is:

```
MEUCIGjhpuYJU1vpHltmG4X5OgT/MGKO9YQkfo0i4nFERoBkAiEAysD8tFRkOuNxbEt0nWWaHQzvCeN2dfh1ZNclDQ6ZCOE=
```

It was produced and checked three independent ways, all green:

```bash
# produce
node tools/hwid-sign.mjs --file devices.json --key ~/hwid-signing-key.pem

# check #1 - the repo's own tool
node tools/hwid-sign.mjs --file devices.json --key ~/hwid-signing-key.pem --verify-only
#   -> OK: devices.json.sig is a valid signature of devices.json

# check #2 - openssl, a completely separate code path
openssl ec -in ~/hwid-signing-key.pem -pubout -out /tmp/pub.pem
base64 -d devices.json.sig > /tmp/devices.sig.der
openssl dgst -sha256 -verify /tmp/pub.pem -signature /tmp/devices.sig.der devices.json
#   -> Verified OK
```

A **negative test** confirmed the signature is bound to the exact bytes: flipping a single byte of
`devices.json` makes OpenSSL report `Verification failure`, while the original still reports
`Verified OK`. This is the same `SHA256withECDSA` check the app runs against the compiled-in public
key, so a matching signature on the real bytes is what the app will accept.

## 4. One manual step left (could not be automated from this environment)

The sandbox this was prepared in can **read** `diddybeamez11-sys/Verify` but its token is **not allowed
to write** to it — both the REST API and `git push` return `403` for that repository, so `devices.json.sig`
could not be pushed from here. The signature file itself is already produced and verified; it only needs
to be committed to `Verify` by someone with write access (you, from your own machine or CI):

```bash
cd <your Verify checkout>
# the verified signature is saved next to this deployment as devices.json.sig
cp /path/to/devices.json.sig ./devices.json.sig
git add devices.json.sig
git commit -m "Sign devices.json for HWID whitelist verification"
git push
```

Only `devices.json.sig` is added — `devices.json` is left byte-for-byte unchanged (same blob), which is
exactly the file the signature was computed over. Until this file is present in `Verify`, the app fetches
a `devices.json.sig` that 404s and therefore **fails closed** (locks) — by design.

If you would rather regenerate everything yourself on a machine that can write to `Verify`, the whole
thing is three commands (they overwrite nothing you cannot reproduce, but re-running §2 with a fresh key
means pasting the new public key into `LicenseConfig.kt` and rebuilding):

```bash
node tools/hwid-keygen.mjs --out ~/hwid-signing-key.pem      # -> paste public key into LicenseConfig.kt
node tools/hwid-sign.mjs --file devices.json --key ~/hwid-signing-key.pem
git -C <Verify> add devices.json devices.json.sig && git -C <Verify> commit -m "sign list" && git -C <Verify> push
```

## 5. The operator approval workflow (day to day)

There is no server, API or dashboard. Adding or removing a device is a git commit in `Verify`. Both
`devices.json` **and** `devices.json.sig` must be committed together, and `version` must increase on
every edit (the app refuses a list whose `version` is lower than the highest it has already accepted).

**Approve a user**

1. They install and open the build; it locks and shows their **Device ID** (the `ANDROID_ID`, a
   16-hex-character string) with a **Copy ID** button. They send you that ID.
2. In your `Verify` checkout:

   ```bash
   $EDITOR devices.json          # add the id to "devices", bump "version"
   node /path/to/tools/hwid-sign.mjs --file devices.json --key ~/hwid-signing-key.pem
   git add devices.json devices.json.sig
   git commit -m "approve <name>"
   git push
   ```

3. Their screen unlocks by itself within a minute (the lock screen re-checks every 60 s), or on the next
   app start. Nothing to reinstall.

**Remove (revoke) a user**

```bash
$EDITOR devices.json          # delete their line, bump "version"
node /path/to/tools/hwid-sign.mjs --file devices.json --key ~/hwid-signing-key.pem
git add devices.json devices.json.sig
git commit -m "remove <name>"
git push
```

The device is refused on its next check (immediately at start, within ~6 h for a running app). A removed
device stays removed even offline — the removal is remembered locally. Full detail, including the grace
period and the lock-vs-terminate behaviour, is in **[HWID_WHITELIST.md §2 and §4](HWID_WHITELIST.md)**.

## 6. Building and testing

```bash
./gradlew :app:assembleRelease      # release APK (see HWID_WHITELIST.md §7.2 for a real release key)
./gradlew :app:testDebugUnitTest    # pure-JVM tests of the signature/parse/policy logic
```

> **This deployment could not be built inside the preparation sandbox**: it has no JDK, no Android SDK,
> and no access to the Gradle/Maven/Google download hosts, so `./gradlew` stops at
> `JAVA_HOME is not set`. That is an environment limitation, not a code error — the only app changes are
> the two string constants in §1. Run the commands above on a machine with JDK 21 + the Android SDK (or
> let `android.yml` in CI build it) to confirm.

The unit tests cover the exact verification path (`SignatureVerifier`), both file formats, rollback
rejection and every policy branch, using a committed signed fixture with a throwaway key unrelated to the
deployment key above.
