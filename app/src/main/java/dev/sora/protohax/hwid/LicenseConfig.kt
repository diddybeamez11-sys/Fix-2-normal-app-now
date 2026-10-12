package dev.sora.protohax.hwid

/**
 * Which identifier the app asks the operator to approve.
 *
 * The two are not the same thing, and it matters which one the list contains:
 *
 *  * [ANDROID_ID] - `Settings.Secure.ANDROID_ID`, the OS identifier of this device *for this app's
 *    signing key*. Stable across uninstall/reinstall, changes on factory reset, needs no permission,
 *    identical for every install of this APK on the device. Users already know it: it is the value the
 *    game's own "device id" screens show. This is the default.
 *  * [INSTALL_HWID] - the random UUID this app generates and keeps in [HwidStore]. Identifies one
 *    *installation* (a reinstall gets a new one, which has to be re-approved), and survives neither a
 *    factory reset nor a "clear data". Useful if you want to approve exactly one install rather than a
 *    device.
 *
 * With [LicenseConfig.ACCEPT_IDS_EITHER_WAY] the list may contain either, which is the painless
 * migration path: an ID approved yesterday as an install HWID keeps working today.
 */
enum class ApprovalIdMode {
    ANDROID_ID,
    INSTALL_HWID
}

/**
 * Every knob of the device approval lives here, and nothing else in the app hard-codes one of them.
 *
 * The feature, in one paragraph: on start-up the app reads one file over HTTPS from a GitHub raw URL
 * ([WHITELIST_URL] - conventionally `devices.json` in your own repository), verifies its detached
 * signature ([SIGNING_PUBLIC_KEY_BASE64]) and looks up this device's own identifier
 * ([DeviceId.approvalId]). If the ID is in the file the app runs normally. If it is not, the app
 * *locks*: a full-screen activity that shows the ID with a Copy ID button, so the user can send it to
 * you, and that unlocks itself as soon as a later check finds the ID (see
 * `dev.sora.protohax.ui.activities.LicenseLockActivity`). If the file cannot be read at all (offline,
 * GitHub down, a blocked network) the app keeps running on the timestamp of the last successful check
 * for at most [GRACE_PERIOD_MS] and then fails closed. If a device was *removed* from the list, the
 * offline rules do not apply: the removal sticks even without connectivity.
 *
 * There is no server, no API and no dashboard anywhere in this feature: the only network calls are the
 * GET of [WHITELIST_URL] and the GET of the signature next to it. Adding a user is: append their ID to
 * `devices.json`, re-run the signing script, commit both files. Removing a user is: delete their line,
 * re-run the signing script, commit both files. The device picks that up on its next successful check -
 * in the worst case one [RECHECK_INTERVAL_MS] later while it is running, or at the next app start.
 *
 * See `HWID_WHITELIST.md` in the repository root for the operator's manual, and the "what this does not
 * stop" section in it before you rely on it for anything valuable.
 */
object LicenseConfig {

    // ================================================================================================
    // 1. THE URL - the only line that has to be edited to point the app at your list
    // ================================================================================================
    //
    // Paste the raw URL of your file here. It has to be the *raw* URL of the file that holds the list,
    // in the repository you keep it in, e.g.
    //
    //     https://raw.githubusercontent.com/diddybeamez11-sys/protohax-devices/main/devices.json
    //
    // The file name is yours (`devices.json`, `hwids.json`, `approved.txt` - this app never looks at
    // the name). The detached signature is fetched from this same URL with `.sig` appended
    // ([SIGNATURE_URL_SUFFIX]), so in the example above the app reads exactly two files:
    //
    //     devices.json      <- the list
    //     devices.json.sig  <- the signature of the exact bytes of the file above
    //
    // Both are plain files in that repository. Nothing else is fetched, ever. Point this at your
    // repository before shipping: with an unreachable URL and no signature key the build is
    // fail-closed, i.e. every device locks.
    const val WHITELIST_URL: String = "https://raw.githubusercontent.com/<OWNER>/<DEVICES-REPO>/main/devices.json"

    /** The signature file is the list URL plus this. Do not change unless you must. */
    const val SIGNATURE_URL_SUFFIX: String = ".sig"

    // ================================================================================================
    // 2. WHICH ID THE LIST CONTAINS - see [ApprovalIdMode] above
    // ================================================================================================
    val APPROVAL_ID_MODE: ApprovalIdMode = ApprovalIdMode.ANDROID_ID

    /**
     * true  - a device is approved when *either* its [ApprovalIdMode] id or the other id (the install
     *         HWID) is in the list. This is what lets a user who was approved by HWID yesterday keep
     *         working after [APPROVAL_ID_MODE] is switched to [ApprovalIdMode.ANDROID_ID], and it is
     *         what makes the "Copy install HWID" button on the lock screen useful too.
     *
     * false - only the [ApprovalIdMode] id counts. Use this if you want exactly one identifier per
     *         device in the list, e.g. because a copied HWID should not work on a reinstall.
     */
    const val ACCEPT_IDS_EITHER_WAY: Boolean = true

    // ================================================================================================
    // 3. THE PUBLIC KEY - paste the output of `node tools/hwid-keygen.mjs` here
    // ================================================================================================
    //
    // Base64 of the X.509 SubjectPublicKeyInfo of an ECDSA P-256 public key, all on one line, exactly
    // as `tools/hwid-keygen.mjs` (or `openssl ec -pubout` + `base64 -w0`) prints it. A full PEM block
    // with the BEGIN/END lines is accepted too, they are stripped.
    //
    // Empty means "no key configured": with REQUIRE_SIGNATURE = true that makes every list fail
    // verification, i.e. every device locks. That is deliberate - the gate is fail-closed - and it is
    // why the setup order in HWID_WHITELIST.md sets this before anything is shipped.
    const val SIGNING_PUBLIC_KEY_BASE64: String = ""

    /**
     * true  - a valid signature is required. The list is only trusted if it was signed with the private
     *         key belonging to [SIGNING_PUBLIC_KEY_BASE64]. This is what stops somebody from pointing
     *         the request at their own copy of the list that happens to contain their own ID: without a
     *         signature, anyone who can redirect the request (a DNS or hosts change on the device, a
     *         proxy, a router, a public Wi-Fi captive portal) can serve a list that approves them.
     *
     * false - the list is trusted as-is, which makes the whole check bypassable exactly that way. The
     *         signature files are then ignored entirely. Only use this for a quick trial, never for a
     *         binary you hand out.
     */
    const val REQUIRE_SIGNATURE: Boolean = true

    /**
     * What to do when the list was fetched but did not verify (bad signature, a list that is older than
     * one the device has already seen, a truncated download that still returned HTTP 200, a captive
     * portal answering with its own page).
     *
     * true  - refuse immediately. The verification result is the *only* trustworthy input, so a list
     *         that fails it is treated as an attack, not as "offline". A captive portal therefore costs
     *         the user a start until they leave that network.
     * false - treat it like "could not fetch", i.e. the grace period below applies. That hands an
     *         attacker who can answer the request with garbage up to [GRACE_PERIOD_MS] of runtime on
     *         the last verification, which also delays every removal by that long for such a user.
     */
    const val TAMPER_FAILS_CLOSED: Boolean = true

    // ================================================================================================
    // 4. THE OFFLINE GRACE PERIOD - set it to 0 for strict online-only
    // ================================================================================================
    //
    // Used only when the list could not be fetched at all (no connectivity, a timeout, GitHub being
    // down, a network that blocks raw.githubusercontent.com). The device may then keep running for this
    // long after its last *successful* check, and not one millisecond longer. A device that has never
    // completed a check cannot use the grace period at all - there is no timestamp to measure from - so
    // the very first start requires the list to be reachable.
    //
    // This applies to devices that are still approved. A device that was *removed* from the list stays
    // removed: the app remembers the removal (see HwidGate) and the offline rules never override it,
    // otherwise unplugging the network would undo a revocation.
    //
    // 0 = strict online-only: any start that cannot read the list locks the app.
    // The trade-off is spelled out in HWID_WHITELIST.md ("What the grace period costs").
    const val GRACE_PERIOD_MS: Long = 7L * 24L * 60L * 60L * 1000L

    // ================================================================================================
    // 5. NETWORK TIMEOUTS
    // ================================================================================================
    const val CONNECT_TIMEOUT_MS: Long = 5_000L
    const val READ_TIMEOUT_MS: Long = 8_000L

    /** Hard ceiling for one GET, so a slow server cannot hold the verdict forever. */
    const val CALL_TIMEOUT_MS: Long = 12_000L

    /** Refuse to buffer more than this from either file; a list of IDs is a few kilobytes. */
    const val MAX_DOWNLOAD_BYTES: Int = 1 shl 20 // 1 MiB

    /**
     * How long the relay gate waits for a verdict before it applies the offline rules itself. Only
     * reached if the verifying thread is somehow wedged; a healthy fetch finishes in seconds.
     */
    const val VERDICT_WAIT_TIMEOUT_MS: Long = 35_000L

    // ================================================================================================
    // 6. RE-CHECKING (how long a removal takes to arrive)
    // ================================================================================================
    //
    // The app re-reads the list in the background at this interval while it is running, so deleting
    // somebody's line takes effect within one interval instead of "the next time they restart".
    const val RECHECK_INTERVAL_MS: Long = 6L * 60L * 60L * 1000L

    /**
     * How often the *lock screen* asks again while it is open and the device is not approved. Shorter
     * than [RECHECK_INTERVAL_MS] because this is the one moment where the answer is expected to change:
     * the user has just sent their ID and is waiting to be added. One small GET per minute, only while
     * a locked device has the app open in the foreground.
     */
    const val LOCK_RETRY_INTERVAL_MS: Long = 60_000L

    /** How often the lock screen re-reads the local verdict (no network) to notice an unlock. */
    const val LOCK_POLL_INTERVAL_MS: Long = 1_000L

    /**
     * Stops the lock screen from being started over and over (each poll of the dashboard would
     * otherwise relaunch it). Long enough to cover a start-up transition, short enough that a real
     * denial after a re-check still gets through.
     */
    const val LOCK_RELAUNCH_DEBOUNCE_MS: Long = 10_000L

    // ================================================================================================
    // 7. ENFORCEMENT - how hard a refusal hits
    // ================================================================================================

    /**
     * A device that is running the relay and is *removed from the list* while doing so is terminated,
     * not just locked: it has an active tunnel and a bound relay socket at that moment, and the polite
     * thing to do is stop, not to leave a zombie UI behind. The user restarts the app and gets the lock
     * screen, which is where the Copy ID button lives.
     *
     * false = a removal only locks the UI and refuses the relay; the process is left alone.
     */
    const val REVOKE_ENDS_THE_PROCESS_WHEN_RUNNING: Boolean = true

    // ================================================================================================
    // 8. ANTI-TAMPER (proportionate, see HWID_WHITELIST.md for what each of these does NOT stop)
    // ================================================================================================

    /**
     * Enforce the APK signing certificate pin that the build injects as
     * `BuildConfig.HWID_SIGNING_CERT_SHA256` (derived from the keystore the release build signs with,
     * see app/build.gradle). A repackaged APK is signed with a different key and is refused.
     *
     * When the build could not determine the pin (`BuildConfig.HWID_CERT_PIN_SOURCE == "UNPINNED"`,
     * e.g. a fresh CI runner whose debug keystore does not exist yet) the check is skipped and a
     * warning is logged - refusing to run every such APK would be worse than the missing check.
     * `-Phwid.requireCertPin=true` turns that warning into a build failure.
     */
    const val ENFORCE_CERT_PIN: Boolean = true

    /** A repackaged APK with android:debuggable="true", or one with a debugger attached, is refused. */
    const val DEBUGGER_DETECTION_FATAL: Boolean = true

    /**
     * Root heuristics (`su` in the usual places, `test-keys` ROMs) are *reported* always and refused only
     * when this is true.
     *
     * The default is false on purpose, and it is a considered trade-off rather than an oversight:
     *
     *  * it is not what protects the list. The list is signed, so a rooted user cannot approve themselves
     *    by editing files; what they can do is patch this app, and a rooted user can patch any check in it
     *    including this one. Refusing to run is therefore mostly theatre.
     *  * it breaks legitimate users. LineageOS and other well-known ROMs are signed with test-keys, and
     *    this repository's own end-to-end workflow runs the release APK on an emulator, which is one too
     *    (see `.github/workflows/e2e.yml`). A build that refuses those is a build that refuses its own CI.
     *  * the honest anti-tamper is [ENFORCE_CERT_PIN] and [DEBUGGER_DETECTION_FATAL], which catch a
     *    *modified* APK rather than a specific device setup.
     *
     * Set it to true if your users are not expected to run rooted devices and you would rather be strict -
     * the finding is logged and shown on the dashboard either way.
     */
    const val ROOT_DETECTION_FATAL: Boolean = false

    /**
     * How far the wall clock may jump backwards between two checks before the gate calls it tampering.
     * Without this, setting the clock back is all it takes to extend [GRACE_PERIOD_MS] indefinitely.
     * Time zones, DST and NTP corrections are far below this.
     */
    const val CLOCK_ROLLBACK_TOLERANCE_MS: Long = 6L * 60L * 60L * 1000L

    /**
     * A repackaged or debugged APK is not locked, it is terminated: there is no legitimate reason for
     * one to exist, and a lock screen in a binary the user compiled themselves is a joke. Everything
     * else (not on the list, never approved, removed, offline past the grace period) locks instead,
     * because the affected user needs to read their ID off the screen.
     */
    const val INTEGRITY_FAILURE_IS_FATAL: Boolean = true

    // ================================================================================================
    // 9. THE ABORT ITSELF
    // ================================================================================================

    /**
     * Delay between "the app decided to terminate itself" and the process being killed, so the toast
     * that explains why is actually visible. The ID is copied to the clipboard and written to a file
     * before this delay starts, so nothing is lost if it is skipped.
     */
    const val ABORT_KILL_DELAY_MS: Long = 2_500L

    /** Shared-preferences file (encrypted) holding the identity and the verification state. */
    const val PREFS_FILE_NAME: String = "protohax_hwid_identity"

    /** The name of the ID file written next to the app's other files, for support. */
    const val HWID_FILE_NAME: String = "hwid.txt"

    /** Log tag used by every line this feature prints, so it can be filtered with `logcat -s`. */
    const val LOG_TAG: String = "ProtoHaxHwid"

    /** The URL the signature is fetched from, derived from [WHITELIST_URL]. */
    val SIGNATURE_URL: String
        get() = WHITELIST_URL + SIGNATURE_URL_SUFFIX
}
