# Device approvals

This repository is the approved-device list of a ProtoHax-Android build. It holds one list and its
signature:

| file | what it is |
|---|---|
| `devices.json` | the approved device IDs, one entry each, plus a `version` that must increase on every edit |
| `devices.json.sig` | the detached ECDSA P-256 / SHA-256 signature of `devices.json`, base64-encoded |

The app reads both files over HTTPS (raw.githubusercontent.com), verifies the signature against a public
key compiled into it, and checks whether this device's own identifier appears in the list. A device that
is listed runs; one that is not is locked out (with a screen that shows its ID and copies it); a list that
cannot be fetched is tolerated for at most seven days after the last successful check, and never for a
device that was removed.

The default identifier is the Android `ANDROID_ID` of the device (16 hex characters, e.g.
`3f2c1e8a5b474d0e`), not a UUID the app generates - the build decides which one it looks up
(`LicenseConfig.APPROVAL_ID_MODE`), and the JSON array may be called `devices`, `hwids` or `hwid`.

**If you are a user of that build, this file is not for you** - it is operated by the person who
distributes the build. To be approved, install the build and open it: it locks and shows the ID it needs
with a **Copy ID** button (it also writes `Android/data/<package>/files/hwid.txt` and logs the ID under
the `ProtoHaxHwid` logcat tag). Send that code to the operator; the screen unlocks by itself once the ID
is on the list.

## Editing it

```bash
# add or remove a line in devices.json, then bump "version", then sign again:
node /path/to/ProtoHax-Android/tools/hwid-sign.mjs --file devices.json --key ~/hwid-signing-key.pem
git add devices.json devices.json.sig
git commit -m "add <name>"
git push
```

Both files must be committed together: the signature covers the exact bytes of `devices.json`, and an
unsigned or stale signature makes the app lock on every device until it is fixed. Never commit the
private key (`hwid-signing-key.pem`) here - whoever has it can add themselves to the list.
