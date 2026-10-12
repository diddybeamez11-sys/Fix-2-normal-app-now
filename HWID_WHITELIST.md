# Device approval (the whitelist) - operator's manual

This is how access to this build is restricted to a list of devices you control.

The identifier is the device's **ANDROID_ID** by default: the one Android gives this app for this device,
readable without any permission, and the same value the game's own "device id" screens show. A device
whose ID is not in your list is **locked** out: a full-screen screen shows the ID with a **Copy ID**
button, and it unlocks itself the moment you add the ID and the next check succeeds. Users do not have to
reinstall anything, and you never have to touch their device.

There is **no server, no API and no dashboard** anywhere in this feature. The app makes exactly two
network requests for it - two HTTPS GETs from one GitHub repository - and everything else is a file you
edit and commit:

```
your list repo
  devices.json        <- the list, one ID per entry (any file name works, this one is conventional)
  devices.json.sig    <- the detached signature of devices.json (base64)
```

Three outcomes, deliberately kept apart:

* **ID is in the list** -> the app runs normally.
* **ID is not in the list** -> the app locks (immediately on the next check; no grace period). It also
  cannot bind the relay or register a single module, even if somebody patches the lock out.
* **The list cannot be read at all** (offline, GitHub down, the network blocks it) -> the app keeps
  running on the timestamp of its last successful check for at most `GRACE_PERIOD_MS` (7 days by
  default), then locks. A device that **was removed** while it had no connection stays removed: that fact
  is remembered locally, so pulling the plug does not undo a removal.

If the section you want is "why is there no API and is that a problem", read section 6.1 first - it is the
one design decision worth understanding before you rely on this.

---

## 1. One-time setup

### 1.1 Create the list repository

A separate GitHub repository, **public** (a private one cannot be read without a token, and a token in
the APK is a secret you cannot keep - if you want a private list, see section 6.1).

Add `devices.json`:

```json
{
  "version": 1,
  "issued": "2026-10-12T12:00:00Z",
  "devices": [
    "3f2c1e8a5b474d0e"
  ]
}
```

The array may be called `devices`, `hwids` or `hwid` - all three are read. A bare array (`["id", "id"]`)
works too, and so does a plain one-per-line text file (section 3).

`version` is not decoration: the app remembers the highest version it has accepted and refuses to go
backwards, so an old copy of the list (from before you removed somebody) cannot be replayed at it.
**Bump it on every edit.**

`issued` is for you; the app only logs it.

The IDs themselves (`3f2c1e8a5b474d0e` above is one) are not secrets - see section 6 - but they are also
not personal data: the ANDROID_ID is scoped to your app's signing key and this device, and the alternative
identifier is a random UUID. Either way the file is public, and it is meant to be.

### 1.2 Generate your signing key (once, on your own PC)

```bash
node tools/hwid-keygen.mjs --out ~/hwid-signing-key.pem
```

It writes the **private key** to that file (keep it, do not commit it, back it up - without it you can
never update the list again) and prints the **public key** as one base64 line. Paste it into:

* `app/src/main/java/dev/sora/protohax/hwid/LicenseConfig.kt`
* constant `SIGNING_PUBLIC_KEY_BASE64` (**line 101**), between the quotes.

### 1.3 Point the app at your repository

Same file, **line 69**:

```kotlin
const val WHITELIST_URL: String = "https://raw.githubusercontent.com/<OWNER>/<DEVICES-REPO>/main/devices.json"
```

Replace the placeholder with the raw URL of your file, e.g.
`https://raw.githubusercontent.com/diddybeamez11-sys/protohax-devices/main/devices.json`. The signature is
fetched from this URL with `.sig` appended, so `devices.json.sig` in the same directory of the same branch
is picked up automatically.

That is the only line to change for the URL. Nothing else in the app hard-codes it, and the file name
itself does not matter to the app.

> **The gate is fail-closed.** With the placeholder URL and no public key, every device locks. Do
> 1.1-1.3 before you hand a build to anybody, or the build is dead on arrival. If you want to hand out a
> build *without* the gate while you set this up, see section 8.3.

### 1.4 Build and distribute

```bash
./gradlew app:assembleRelease
```

Release builds are signed with the Android debug keystore unless you add a `keystore.properties` in the
repository root - see section 7.2 for why that matters for the anti-tamper pin and how to set up a real
release key.

---

## 2. Approving and removing users

### Approve a user

They send you their **Device ID** (a 16-character hex string - the ANDROID_ID - unless you switched
`APPROVAL_ID_MODE`, in which case it is the UUID from their lock screen). Then:

```bash
cd <your list repo>
$EDITOR devices.json          # add their id, bump "version"
node /path/to/tools/hwid-sign.mjs --file devices.json --key ~/hwid-signing-key.pem
git add devices.json devices.json.sig
git commit -m "add <name>"
git push
```

What happens on their side, without them doing anything:

* if their app is **open on the lock screen**, it asks again every `LOCK_RETRY_INTERVAL_MS` (60 s) and
  unlocks itself - typically within a minute of your push;
* otherwise it checks at the next start, and at the latest `RECHECK_INTERVAL_MS` (6 hours) later for an
  app that is already running.

### Remove (revoke) a user

```bash
cd <your list repo>
$EDITOR devices.json          # delete their line, bump "version"
node /path/to/tools/hwid-sign.mjs --file devices.json --key ~/hwid-signing-key.pem
git add devices.json devices.json.sig
git commit -m "remove <name>"
git push
```

Their app is refused on its next check: immediately at the next start, while running within 6 hours (the
background re-check). Three details worth knowing:

* **No grace period is involved.** A list that was fetched and does not contain the device is a hard "no",
  even if the same device verified successfully yesterday.
* **It survives being offline.** The removal is written to the app's own storage, and the offline rules
  never override it - so a removed user cannot keep running for a week by turning off Wi-Fi. (This is the
  one place where the app deliberately does *not* fall back to the last good list.)
* **A running relay stops.** If the app had already bound port 1337 and built its tunnel, the process is
  terminated rather than left running with a dead UI
  (`REVOKE_ENDS_THE_PROCESS_WHEN_RUNNING`, line 204). Reopening it shows the lock screen with the ID.

### The signing command, in full

```bash
# with Node (this repo's script; works on Windows, macOS and Linux)
node tools/hwid-sign.mjs --file <repo>/devices.json --key ~/hwid-signing-key.pem

# or with openssl + base64, if you would rather not use Node
openssl dgst -sha256 -sign ~/hwid-signing-key.pem -out /tmp/devices.sig.der devices.json
openssl base64 -A -in /tmp/devices.sig.der -out devices.json.sig

# check what you produced before committing
openssl ec -in ~/hwid-signing-key.pem -pubout -out /tmp/hwid-signing-key.pub.pem
openssl dgst -sha256 -verify /tmp/hwid-signing-key.pub.pem -signature /tmp/devices.sig.der devices.json
# or
node tools/hwid-sign.mjs --file devices.json --key ~/hwid-signing-key.pem --verify-only
```

**Commit both files in the same commit, and never edit `devices.json` without re-signing.** The signature
covers the exact bytes of the file - a changed space, a reordered entry or a different line ending
invalidates it, and every device then locks until you sign again. That is the intended behaviour, not a
bug.

### Alternative: an unsigned plain list

If you set `REQUIRE_SIGNATURE = false` (line 114) the app ignores the `.sig` file completely and the list
may be one ID per line:

```
# 2b2t MCPE testers
3f2c1e8a5b474d0e
9a0b7c342e154f7a  bob
```

Blank lines and lines starting with `#` or `//` are ignored, and only the first word of a line counts, so
you can annotate entries with who they belong to. (The JSON envelope above is accepted in unsigned mode
too; the version then still gives you rollback protection.)

**Why the signature is the default:** without it, anyone who can change where the request goes - a DNS
setting, a hosts file, a proxy, a root certificate on a rooted device, a router - can serve the app their
own copy of the list containing their own ID, and the check passes for everybody. With it, they need your
private key. Keep `REQUIRE_SIGNATURE = true` for anything you distribute.

---

## 3. The exact file format

`devices.json` - what `tools/hwid-sign.mjs` signs and the app reads:

| field | required | meaning |
|---|---|---|
| `version` | recommended | integer, strictly increasing on every edit. The app remembers the highest version it accepted and treats a lower one as a replay (refuses it). |
| `issued` | optional | free-form string; logged only. |
| `devices` (or `hwids` / `hwid`) | yes | array of strings, one ID each. An entry may be `"<id>  <note>"`; only the first whitespace-separated word is used, and matching is case-insensitive. |

`devices.json.sig` - base64 of the DER-encoded ECDSA P-256 / SHA-256 signature over the exact bytes of
`devices.json`. One line, but line breaks inside it are tolerated.

The app verifies with `SHA256withECDSA` against the public key compiled into it, and additionally refuses
a `version` lower than the last one it accepted.

### 3.1 Which identifier goes in the list

`LicenseConfig.APPROVAL_ID_MODE` (line 77) decides what the app looks itself up by:

| mode | the ID in your list | where the user reads it | stability |
|---|---|---|---|
| `ANDROID_ID` (**default**) | the 16-hex-character `Settings.Secure.ANDROID_ID` | game's own device-id screens, or the app's lock screen / dashboard | survives uninstall+reinstall; **changes on factory reset** |
| `INSTALL_HWID` | the random UUID the app generates on first start | only this app (lock screen / dashboard) | survives until the app's data is cleared; a reinstall gets a new one |

With `ACCEPT_IDS_EITHER_WAY = true` (line 88, the default) **either** identifier approves the device, which
is what makes switching modes painless: users you approved by HWID yesterday keep working after you
switch to ANDROID_ID, and vice versa. The app shows both IDs on the lock screen, so a user can always send
you the one you ask for.

---

## 4. What each outcome looks like

| situation | what the app does |
|---|---|
| ID is in the list, signature good | runs normally |
| ID is not in the list | **locked**: lock screen with the ID + Copy ID; no grace period |
| ID was removed from the list after being approved | **locked**, immediately at start, and the removal is remembered - it applies even while offline |
| list fetched but signature missing/wrong/unparseable | **locked** (a trusted list is the only basis for a decision). `TAMPER_FAILS_CLOSED = false` (line 128) downgrades this to the offline rules |
| list fetched, `version` lower than the last accepted | **locked** (replay) |
| list could not be fetched, last successful check < `GRACE_PERIOD_MS` ago | runs, and the dashboard says it is on the cached check and when that expires |
| list could not be fetched, grace expired or the device never verified | **locked** |
| device clock moved back more than 6 hours | **locked** (it is how the grace period would be extended) |
| repackaged APK (signing certificate does not match the build's pin) | **terminated** on purpose - see below |
| debuggable APK with a debugger attached | **terminated** on purpose |
| rooted device (`su`, test-keys ROM) | runs, **warns** - `ROOT_DETECTION_FATAL = true` (line 243) refuses it instead |

### 4.1 Locked vs terminated

**Locked** is the normal refusal, and it exists because the user has to be able to read their ID: a
full-screen activity (`LicenseLockActivity`) with the ID, a **Copy ID** button, a **Check again** button,
and a self-retrying check every 60 seconds. It replaces the task, so Back cannot return to the dashboard;
Back only sends the app to the background. The moment your list contains the ID, the screen closes and the
dashboard opens by itself. Nothing is written to the device, nothing has to be reinstalled, and the user
does not have to do anything except keep the app open (or start it again later).

**Terminated** happens in exactly two cases, both deliberate, both with a log line rather than an
exception:

* a **repackaged or debugged APK** (`INTEGRITY_FAILURE_IS_FATAL`, line 258) - there is no legitimate
  reason for one to exist, and a lock screen in a binary the user compiled themselves is a joke;
* a refusal that arrives **while the relay is already running** (`REVOKE_ENDS_THE_PROCESS_WHEN_RUNNING`,
  line 204) - there is a bound socket and a live tunnel at that moment, so the app stops instead of
  leaving a working relay behind an idle UI.

The termination order is: a logged error line (`logcat -s ProtoHaxHwid`), the ID copied to the clipboard,
the ID written to `Android/data/dev.sora.protohax/files/hwid.txt`, a toast explaining it, the VPN service
stopped, and then the process killed - about 2.5 seconds later, so the toast is actually visible.

### 4.2 Where the refusal is enforced

Four places, deliberately, so that patching one of them out does not produce a working app:

1. `MyApplication.onCreate` starts the check before any activity or service exists
   (`HwidGate.startAsync`).
2. `MinecraftRelay` - both the module loader (`init` -> `HwidGate.awaitAllowed`) and the bind path
   (`announceRelayUp()` -> `HwidGate.awaitAllowed`, before `relay.bind(...)`). No module registers and
   port 1337 is never opened without a verdict that says yes.
3. `AppService.onStartCommand` refuses to build a VPN tunnel when the verdict is already "no".
4. `MainActivity` (and the other activities, on resume) put the lock screen back on top whenever the
   verdict is "no", including when it changes *while* the app is open - the background re-check that
   notices a removal.

Removing the lock from 4 does not make the app useful: 2 is what makes the relay work, and it is the same
check, not a second implementation of it.

---

## 5. How a user gets their Device ID to you

**A new user (the chicken-and-egg case, and the reason the lock screen exists):**

1. They install the APK and open it.
2. The app checks the list, does not find their ID, and locks. The screen shows the ID and the sentence
   "send this to the admin", with a **Copy ID** button.
3. They tap it and send you the copied code (one line, nothing else needed).
4. You add it to `devices.json`, bump `version`, sign, commit, push.
5. Their screen unlocks by itself within a minute - or they tap **Check again**.

The lock screen also shows a small "Alternative ID" (the other identifier, section 3.1) with its own copy
button, so if you prefer to keep the list by install HWID you can just ask for that one instead.

**An approved user** sees the same ID, with the same Copy ID button, in the dashboard's "License" card -
and additionally, at the bottom of the screen's scroll: `Android/data/dev.sora.protohax/files/hwid.txt`
holds both IDs in a small report (written only on a termination, not on a lock), and
`adb logcat -s ProtoHaxHwid` logs the ID with the reason for every refusal.

The ANDROID_ID is also readable by the user from the game's own "device id" screen - which is convenient,
but note that the *only* ID that is guaranteed to match your list is the one the app shows: the
ANDROID_ID scopes to the app's signing key, so a different build of this app on the same device sees a
different value.

---

## 6. What this does NOT stop

Read this before trusting it. This feature raises the cost of the naive paths; it is not DRM.

* **A determined user on a rooted device.** They can patch the check out of the APK (the app is GPLv3, the
  source is public), or block the request and sit inside their grace period. The certificate pin, the
  lock screen and the module/relay gates make each of those take real work; none of them makes it
  impossible. **A rooted device is the assumed adversary, not a solved problem.**
* **An impersonated ID.** The ANDROID_ID is **not a secret**: every app on the device can read it, it can
  be read from the game's own screens, and a rooted user can make a patched app report any value they
  like. The same is true for a copy of an install HWID. So "approving an ID" approves *a value*, not a
  piece of hardware - if a user sends you somebody else's ID, that somebody gets a second seat. Nothing in
  this design ties an entry to a real person or a specific chip; no Android app can do that for you.
* **Anyone sharing their approved device**, or using a copied ID on a second device you did not intend to
  approve.
* **Removal latency**: up to `RECHECK_INTERVAL_MS` (6 hours) for a running app that is online; immediate at
  the next start. A removed device that is offline is refused too (the removal is remembered), so the
  only gap is the 6-hour re-check window while it is online.
* **The public list.** Your repository must be public (no token in the APK), so anybody can see the
  approved IDs. That is what makes "copy an approved ID from the file" an option - see above.
* **Keeping your private signing key secret.** If that file leaks, whoever has it can add themselves and
  every device will accept the list as authentic. It is the only real secret in the scheme.
* **Anti-tamper against a patcher.** The certificate pin catches a *re-signed* APK; it does not catch a
  user who patches the decompiled code and re-signs with... exactly the point: re-signing changes the
  certificate, which the pin notices. What it does not catch is any of it being bypassed at runtime by
  code that never had to be repackaged, e.g. an Xposed module on a rooted device.
* **Server-side detection of the cheats themselves.** This controls who may run the client, not whether
  the client plays fair, or whether a server ban follows.

What it *does* stop, reliably: sharing a build with a non-approved user (they get a locked app, not a
warning), a repackaged APK signed with somebody else's key, a device you removed (even offline, within the
first bullet's caveat), and a redirected list request (the redirect serves a list they cannot sign).

### 6.1 Why there is no backend API, and what that would add

You asked for a "secure backend API" to check approval. What is implemented instead is the same trust
model with fewer moving parts - and the differences are worth stating plainly, because they are a real
trade-off rather than a shortcut:

| | this design (signed file in a repo) | a real backend API |
|---|---|---|
| what runs anywhere | nothing - GitHub serves two static files | a server you deploy, patch, monitor and pay for, plus a database |
| who can approve | you, with git | you, or an admin UI you build |
| can a device be approved while its app is open | **yes** - the lock screen polls every 60 s | yes |
| revocation latency | up to 6 h while running and online, 60 s if the lock screen is open | seconds, if you push/keep a socket |
| secrets in the APK | a **public** key (safe to publish) | a client credential or token that must be protected forever |
| if it goes down | GitHub does not; if it did, devices inside the grace period keep working | every device with `GRACE_PERIOD_MS` > 0 keeps working, everyone else is locked out until your server is back |
| what an attacker gains by reading the client | the list format and your public key | the API's contract, and a target to attack |
| adding a user | edit one line, sign, commit | database row + your API must be up |
| privacy | a public list of device IDs | a database of device IDs *plus* the traffic log of every check |

Everything the client sends is exactly the ID it displays, and everything it accepts is a file your
private key signed - so the "API" here is the repository. **The honest answer to "is that as secure as a
backend": it is secure for the same thing a backend would be secure for** - an unapproved device cannot
approve itself without the signing key, whether the list arrives over your API or over GitHub's. What you
do not get is the operational side: instant revocation everywhere, per-device telemetry, and the ability
to change the rules without shipping an APK.

If you do want a backend later, the seam is one file: `WhitelistClient.fetch()` returns a
`FetchResult.Ok(payload, signature)` and everything downstream - signature check, parse, policy, lock -
works unchanged. Point it at your API instead, keep the signature, and nothing else in this feature has to
change. Just remember the rule this feature was built under: the private key stays on your PC, and nothing
you add may require a secret in the APK.

---

## 7. Anti-tamper details

### 7.1 What is checked

* **APK signing certificate pin.** The build reads the certificate of the keystore it signs the release APK
  with, hashes it (SHA-256) and compiles the hash in as `BuildConfig.HWID_SIGNING_CERT_SHA256`.
  `Integrity` compares it with the certificate of the running APK. Repackaging (`apktool` +
  `uber-apk-signer` and friends) changes the certificate and is refused.
* **Debuggable APK with a debugger attached** - refused. (A debuggable build without a debugger is only
  reported: that is a developer build, not an attack.)
* **Root heuristics** - `su` in the usual paths or on `PATH`, `test-keys` in `Build.TAGS`. **Reported, not
  fatal by default** (`ROOT_DETECTION_FATAL = false`, line 243), and that is a deliberate change from the
  first version of this feature:
  * it is not what protects the list - the list is signed, and a rooted user can patch any check in this
    app including this one, so a refusal is mostly theatre;
  * it breaks legitimate users: LineageOS and other common ROMs are test-keys builds, and so is the
    emulator this repository's own `e2e.yml` runs the release APK on;
  * the checks that do the real work are the certificate pin and the debugger check, which catch a
    *modified* APK rather than a specific device setup.
  Set it to `true` if your users are not expected to run rooted devices and you would rather be strict;
  the finding is logged and shown on the dashboard either way.

The pin is only as good as the build that produced the APK:

* **No pin in the APK** (`BuildConfig.HWID_CERT_PIN_SOURCE == "UNPINNED"`, e.g. a CI runner whose debug
  keystore does not exist yet): the check is skipped at runtime and reported in the dashboard. Build with
  `-Phwid.requireCertPin=true` to make that a build failure instead.
* **Google Play App Signing**: the APK Play ships is re-signed with your *app signing* certificate, not
  with the upload key this build knows. Pass the certificate from the Play Console explicitly:
  `-Phwid.certSha256=<sha256>` (or `hwid.certSha256=` in `gradle.properties`). Without it, every Play user
  is refused - which is the point of the check, but painful when it is your own build.

### 7.2 Signing with a real release keystore (recommended)

```bash
keytool -genkeypair -v -keystore ~/protohax-release.jks -alias protohax \
        -keyalg RSA -keysize 4096 -validity 10000
```

then, in the repository root, `keystore.properties` (not committed - it is in `.gitignore`):

```properties
storeFile=/home/you/protohax-release.jks
storePassword=...
keyAlias=protohax
keyPassword=...
```

`./gradlew app:assembleRelease` now signs with that key and pins its certificate automatically.
`./gradlew printHwidCertPin` shows which certificate the current configuration would pin.

### 7.3 What the anti-tamper layer is not

No third-party anti-tamper SDK is involved, on purpose. Everything here is defeated by a user who is
willing to patch the APK or run an Xposed module - the honest bar is "a repackaged or debugged copy does
not work without effort", not "nobody can run this". See section 6.

---

## 8. The knobs

All of them are in `app/src/main/java/dev/sora/protohax/hwid/LicenseConfig.kt`; nothing else hard-codes
any of these values. Line numbers are from the current file and may drift - search by name.

| constant | default | what it does |
|---|---|---|
| `WHITELIST_URL` | placeholder | **the one line to edit** (line 69) |
| `SIGNATURE_URL_SUFFIX` | `.sig` | signature file = URL + this |
| `APPROVAL_ID_MODE` | `ANDROID_ID` | which identifier the list holds (line 77); see 3.1 |
| `ACCEPT_IDS_EITHER_WAY` | `true` | approve a device by either identifier (line 88) |
| `SIGNING_PUBLIC_KEY_BASE64` | empty | your public key (line 101); empty = lock everything |
| `REQUIRE_SIGNATURE` | `true` | require the signature (`false` = unsigned plain list, see 2.4) |
| `TAMPER_FAILS_CLOSED` | `true` | a fetched-but-unverifiable list locks (it is not "offline") |
| `GRACE_PERIOD_MS` | 7 days | offline grace period; **`0` = strict online-only** (line 146) |
| `CONNECT_TIMEOUT_MS` / `READ_TIMEOUT_MS` / `CALL_TIMEOUT_MS` | 5 s / 8 s / 12 s | per-request limits |
| `MAX_DOWNLOAD_BYTES` | 1 MiB | refuse larger bodies |
| `VERDICT_WAIT_TIMEOUT_MS` | 35 s | how long the relay gate waits for a verdict |
| `RECHECK_INTERVAL_MS` | 6 h | re-check interval while running (approval/removal latency) |
| `LOCK_RETRY_INTERVAL_MS` | 60 s | how often the lock screen asks again |
| `LOCK_POLL_INTERVAL_MS` | 1 s | how often the lock screen re-reads the local verdict |
| `LOCK_RELAUNCH_DEBOUNCE_MS` | 10 s | anti-flicker guard for the lock screen |
| `REVOKE_ENDS_THE_PROCESS_WHEN_RUNNING` | `true` | a refusal while the relay is up terminates the app (line 204) |
| `ENFORCE_CERT_PIN` | `true` | the APK signing certificate pin |
| `DEBUGGER_DETECTION_FATAL` | `true` | debuggable APK + attached debugger is fatal |
| `ROOT_DETECTION_FATAL` | **`false`** | rooted device is refused; the default only reports it (line 243) |
| `CLOCK_ROLLBACK_TOLERANCE_MS` | 6 h | how far the clock may jump back before it counts as tampering |
| `INTEGRITY_FAILURE_IS_FATAL` | `true` | a repackaged APK is terminated, not locked (line 258) |
| `ABORT_KILL_DELAY_MS` | 2.5 s | between the refusal toast and the process kill |
| `PREFS_FILE_NAME` / `HWID_FILE_NAME` / `LOG_TAG` | - | storage and logcat names |

### 8.1 "Is a grace period wrong?" - the trade-off

No, but be clear about what it costs. Without a grace period, a user on a plane, in a tunnel, on a captive
portal or on a network that blocks `raw.githubusercontent.com` gets a locked app, and their only recourse
is to wait - that is a support burden that will dwarf the abuse it prevents, because the abuse it prevents
is *already* covered: a device that is still approved cannot be added back by a network, and a device that
was removed is refused offline anyway (section 4).

What the grace period *does* cost:

* a device you have not yet decided about can run for up to `GRACE_PERIOD_MS` after its last good check -
  notably, a device whose ID you *removed* is not affected (the removal is remembered), but one whose
  removal the device never managed to download is;
* an attacker who can only *block* GitHub (not forge a signature) can hold the last good list in place for
  the same period - which is why a *forged/incorrect* list is not treated as "offline" at all
  (`TAMPER_FAILS_CLOSED`).

If your users have reliable connectivity, set `GRACE_PERIOD_MS = 0` and the whole question disappears:
any start that cannot read the list locks the app.

### 8.2 Clock manipulation

The grace period is measured in wall-clock time, so it is only as good as the device clock. The gate
stores the highest wall-clock value it has ever seen and refuses to run when the clock goes back more than
`CLOCK_ROLLBACK_TOLERANCE_MS` (6 hours, well above DST and NTP corrections). A user who wants more than 7
days offline has to set the clock back - and gets a locked app for it.

### 8.3 Turning the gate off for a local test build

Three options, in order of preference:

1. Set `GRACE_PERIOD_MS = 0` and point `WHITELIST_URL` at a repository you control that contains your own
   device's ID. The gate is then doing exactly what it will do in production, which is what you want to
   test.
2. `REQUIRE_SIGNATURE = false` with a plain-list URL, while you have not set up the key yet.
3. Do not ship it. `REQUIRE_SIGNATURE = false` in a distributed build means anyone who can redirect the
   request is in.

---

## 9. Troubleshooting

| symptom | look at |
|---|---|
| every device is locked | is the public key pasted in (line 101)? is `devices.json.sig` committed and up to date with `devices.json`? did you bump `version`? |
| the lock screen appears but the user is definitely on the list | are you comparing the same identifier? The list must contain the value shown on the lock screen (ANDROID_ID vs install HWID - section 3.1) |
| "the list did not pass its signature check" | the ID and the reason are in `logcat -s ProtoHaxHwid`; Crashlytics gets a non-fatal with the same reason. Re-sign and commit both files |
| "not on the list" | the ID in the log is not in `devices.json` (or the wrong identifier kind, see above; matching is case-insensitive, so a typo is the usual cause) |
| "could not be fetched" | check the raw URL in a browser on the phone; a 404 means a wrong path/branch, a private repo, or a file that was never committed |
| "the device clock jumped backwards" | the device's date/time is in the past; set it to automatic |
| "was removed from the list by the operator" but you just re-added them | the app is on an older list (`version`): sign, commit, and let it re-check - or tap **Check again** on the lock screen |
| "APK is not signed with the expected certificate" | the APK was re-signed (or built with a different keystore than the pin) - see 7.1, and `./gradlew printHwidCertPin` |
| the app closes a moment after start with no explanation | that is a *termination*, not a lock: a repackaged/debugged APK, or a refusal while the relay was running. `adb logcat -s ProtoHaxHwid` has the reason, and the ID is on the clipboard and in `Android/data/dev.sora.protohax/files/hwid.txt` |
| the user's ID changed | factory reset (ANDROID_ID) or "clear data" (install HWID). Approve the new one; the old entry can be deleted |

### 9.1 The end-to-end workflow and the gate

`.github/workflows/e2e.yml` (manual dispatch only - it does not run on pushes) builds the release APK,
installs it on an emulator and drives the real UI: pick the app, Connect, open the overlay menu, and then
it runs 16 RakNet sessions through the relay. **With the gate in place that emulator is not an approved
device, so the app locks and the checks about the dashboard/relay fail.** That is the feature working as
designed, not a broken test.

To run it, approve the emulator first - the lock screen makes this easy:

1. run the e2e workflow (or install the APK on an emulator by hand) once; the app locks and shows its ID;
2. read that ID: it is on the clipboard, in the screen itself, or in `adb logcat -s ProtoHaxHwid`;
3. add it to `devices.json`, bump `version`, sign, commit, push;
4. run the workflow again.

The ANDROID_ID survives reinstalls of the same APK on the same emulator image, but a fresh emulator image
means a new one; with `APPROVAL_ID_MODE = INSTALL_HWID` every reinstall needs a new entry. If you would
rather not depend on your real list in CI, point `WHITELIST_URL` at a test repository containing the
emulator's ID (with `REQUIRE_SIGNATURE = false` while you iterate) - the gate then behaves exactly as it
does in production, which is what the test is for.

### 9.2 The unit tests

`./gradlew :app:testDebugUnitTest` runs the pure-JVM tests of this logic
(`app/src/test/java/dev/sora/protohax/hwid/`): signature verification against a committed signed sample
(the same code path the app uses), both file formats (including the `devices` key), rollback rejection,
and every branch of the policy - approved, not approved, removed (online and offline), the secondary
identifier, offline inside/outside the grace period, clock rollback, no key configured. This repository's
`android.yml` does not run them (the workflow was left untouched by this change); add
`app:testDebugUnitTest` next to `app:assembleRelease` if you want them on every push.

---

## 10. Licence note (GPLv3)

This repository (and the WClient code merged in PR #12) is GPLv3. Adding this feature is compatible with
that: everything new here is original code in the same repository, under the same licence, and nothing in
it restricts the four freedoms the GPL grants. What the GPL *does* mean for a whitelist is worth being
explicit about:

* **The source stays free.** Anyone who receives your binary is entitled to the source, including this
  gate - so the gate is patchable by design rather than by accident. That is consistent with section 6:
  the gate is a barrier for casual sharing, not a technical lock on a determined user.
* **The private key is not covered by the licence.** It is your secret and it stays out of the repository;
  that is what keeps the list authentic even though everyone can read the code that checks it.
* **You may charge for your build or restrict who receives it**, but the licence does not let you restrict
  what a recipient may do with the source once they have it (GPLv3 §10 / §11).
