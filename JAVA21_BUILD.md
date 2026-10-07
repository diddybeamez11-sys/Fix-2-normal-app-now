# Java 21 build

This Android project is configured to build with JDK 21 and Gradle 8.7.

## The ProtoHax library

GitHub Actions (`.github/workflows/android.yml`) does **not** package the jar that is committed in
`app/libs`. It builds ProtoHax from source at a pinned revision - including its `mcpedata` git submodule - and
replaces `app/libs/ProtoHax-1.4.0.jar` before the APK is built:

* the Microsoft OAuth fix (authorization code / refresh token) is patched into ProtoHax;
* `.github/protohax/apply_android_patches.py` targets Minecraft Bedrock 1.21.111 (protocol 844): the dashboard /
  RakNet pong version, and the block, item and block-hardness registry data of that version. ProtoHax's own
  `mcpedata` data stops at protocol 594 (1.20.10), so it is generated from `pmmp/BedrockData` (CC0), pinned in
  `android.yml` to the `bedrock-1.21.111` tag (protocol 844);
* `.github/protohax/relocate_awt_color.py` points the `java.awt.Color` references of the Cloudburst protocol
  classes - Android has no `java.awt` - at a small stand-in (`Color.java`) that is built into the jar;
* `.github/protohax/verify_jar.py` fails the build if the registry assets (of the submodule and of protocol 844)
  are missing, if the v844 codec is not configured, or if a `java.awt.Color` reference is left.

The jar committed in `app/libs` is only a convenience for local builds. It predates these adaptations (it still
advertises protocol 2193 / "1.26.50" and references `java.awt.Color`), so use the CI artifact for testing.

Build locally:

```bash
chmod +x gradlew
./gradlew app:assembleDebug
```

GitHub Actions uses JDK 21 and builds the release APK.
