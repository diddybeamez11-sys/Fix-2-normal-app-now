# ProtoHax-Android

<img align="right" width="159px" src="https://raw.githubusercontent.com/SkidderMC/ProtoHax/stable/icon.png">

ProtoHax-Android is the Android implementation of [ProtoHax](https://github.com/SkidderMC/ProtoHax), an open-source cheat for Minecraft: Bedrock Edition that works through the network layer. This repository contains the Android-specific code for the cheat.

**This fork additionally carries the module set of [WClient](https://github.com/Alextheplayer919/WClient), ported onto ProtoHax's API - see [WClient modules](#wclient-modules) below.**

## Features
1. No modifications to Minecraft client
2. Seamless switching/adapting multiple versions
3. Full control of the packet layer

## WClient modules

`app/src/main/java/dev/sora/protohax/relay/modules` holds 29 modules ported from
[WClient](https://github.com/Alextheplayer919/WClient) (GPLv3), which ships a much larger set of
anarchy-server modules than ProtoHax does on its own. WClient has its own session and entity model,
so each module was rewritten against `dev.sora.relay`: WClient's `beforePacketBound()` became
`handle<EventPacketInbound>` / `handle<EventPacketOutbound>`, `intercept()` became `cancel()`,
`session.clientBound()` became `session.sendPacketToClient()`.

| Category | Modules |
|---|---|
| Combat | AntiKnockback, AutoTotem, Hitbox, HotbarSwitcher, InfiniteAura, TriggerBot |
| Movement | AbilityFly, AntiAFK, AutoWalk, Bhop, Boost, HighJump, JetPack, MultiJump, NoClip, Spider, Sprint |
| Visual | Fullbright, TimeShift, Zoom |
| Misc | AntiDebuff, AutoDisconnect, ChatSpam, ChatSuffix, Desync, FakeDeath, NoChat, PositionLogger |

Four names differ from WClient's because the ProtoHax library already uses them: `AbilityFly`
(WClient `fly`), `Boost` (`speed`), `MultiJump` (`air_jump`) and `ChatSpam` (`Spammer`).

Two ports deliberately differ from the original:

* **AbilityFly / NoClip** keep WClient's approach - the server never grants anything, the relay
  drops the abilities the server sends down and the ability requests the game sends up, and feeds
  the game its own `UpdateAbilitiesPacket`. The shared plumbing is in
  `modules/util/Abilities.kt` and `modules/motion/ModuleAbilityBase.kt`.
* **Zoom** uses the protocol's own `CameraInstructionPacket` field-of-view instruction instead of
  WClient's walk-speed trick, which this protocol version has no equivalent for.

Shared helpers live next to them in `modules/util`: `MobList` (ported verbatim), `Abilities`, and
`ModuleCombat` (attack, swing, silent aim, targeting, motion). All of them are registered in
`MinecraftRelay.registerAdditionalModules`, and they show up in the overlay menu's existing
category tabs - no UI change was needed.

WClient is licensed under the GNU GPLv3 and so is this repository; see
[License](#license).

## Device approval (the whitelist)

This fork can restrict itself to a list of approved devices. The list is a file in a **separate GitHub
repository** - there is no server, no API and no dashboard: on start-up the app fetches `devices.json`
(any file name works) and its detached `devices.json.sig` over HTTPS, verifies the signature against a
public key compiled into the APK, and looks up this device's own identifier.

* **in the list** -> the app runs;
* **not in the list** -> the app **locks**: a screen shows the Device ID with a **Copy ID** button so the
  user can send it to the operator, and it unlocks by itself as soon as a later check finds the ID - no
  reinstall, no broken state. The relay cannot bind and no module registers either, even if that lock is
  patched out;
* **removed from the list** -> locked at the next check, and the removal is remembered locally, so it
  applies even while the app is offline;
* **list unreachable** -> it runs on the timestamp of the last successful check for at most 7 days, then
  locks;
* **repackaged or debugged APK** -> terminated, not locked.

The identifier is the device's **ANDROID_ID** by default - readable with no permission, stable across
reinstalls, shown on the lock screen and in the game's own device-id screens - and the app can be
configured to use a random per-installation UUID instead (or to accept either). Both are shown with copy
buttons on the lock screen and on the dashboard's License card, so a user always has something to send.

Everything an operator needs - key generation, the signing command, the file format, approving and
removing users, the grace-period trade-off, why this needs no backend, and an honest list of what this
does *not* stop - is in **[HWID_WHITELIST.md](HWID_WHITELIST.md)**. The two constants to edit are in
`app/src/main/java/dev/sora/protohax/hwid/LicenseConfig.kt`: `WHITELIST_URL` (line 69) and
`SIGNING_PUBLIC_KEY_BASE64` (line 101).

## Issues
- If you notice any bugs or missing features in the Android-specific code, you can let us know by opening an issue [here](https://github.com/SkidderMC/ProtoHax-Android/issues).
- If you're having issues with the cheat features themselves, please send the issue to the main repository [here](https://github.com/SkidderMC/ProtoHax/issues).

Please notice that this is an **English-only** repository, so all issues and pull requests must be in English, if you can't speak English, please use a [translator](https://translate.google.com/).

## License
This project is subject to the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.en.html). This does only apply for source code located directly in this clean repository. During the development and compilation process, additional source code may be used to which we have obtained no rights. Such code is not covered by the GPL license.

For those who are unfamiliar with the license, here is a summary of its main points. This is by no means legal advice nor legally binding.

*Actions that you are allowed to do:*

- Use
- Share
- Modify

*If you do decide to use ANY code from the source:*

- **You must disclose the source code of your modified work and the source code you took from this project. This means you are not allowed to use code from this project (even partially) in a closed-source (or even obfuscated) application.**
- **Your modified application must also be licensed under the GPL**

## Installation
We have used the [local maven repository](https://docs.gradle.org/current/userguide/declaring_repositories.html#sec:case-for-maven-local), you need to build and publish [ProtoHax](https://github.com/SkidderMC/ProtoHax) to the repository before you can build, sorry for inconvenience.

### With Android Studio
1. Clone the repository: `git clone https://github.com/SkidderMC/ProtoHax-Android.git`
2. Import the project into Android Studio
3. Connect your Android device to your computer
4. Build and run the project on your device

### With Gradle
1. Clone the repository: `git clone https://github.com/SkidderMC/ProtoHax-Android.git`
2. CD into the local repository.
3. Connect your Android device to your computer
3. Run `gradlew app:assembleDebug`

## Contributing
We welcome contributions to ProtoHax! If you would like to contribute, please fork the repository and make changes as you'd like. Pull requests are welcome.

## Disclaimer
Please use ProtoHax at your own risk. We **DO NOT** take responsibility for any bans or punishments that may occur as a result of using this cheat.
