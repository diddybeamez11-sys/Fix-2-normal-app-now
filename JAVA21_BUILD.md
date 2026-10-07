# Java 21 build

This Android project is configured to build with JDK 21 and Gradle 8.7.

## The ProtoHax library

`app/libs/ProtoHax-1.4.0.jar` is the fully adapted ProtoHax library: Minecraft Bedrock 1.21.111
(protocol 844) with the block, item and block-hardness registry data of that version, the Microsoft OAuth fix,
and the `java.awt.Color` stand-in the Cloudburst protocol classes need on Android. Both a local build
(`./gradlew app:assembleRelease`) and GitHub Actions package exactly this jar, so a local build is enough to get
a client that can join 1.21.111 servers.

Everything the library needs is inside this repository (`.github/protohax`), so the build does **not** depend on
any other repository being reachable:

* `.github/protohax/mcpedata` - the registry data of ProtoHax's own `mcpedata` git submodule (pinned copy), so
  the ProtoHax checkout needs no `submodules: true`. Without registry data the shaded jar has no mappings,
  `GameSession()` throws a `NullPointerException` -> `ExceptionInInitializerError` and the app's relay object can
  never be initialised (the runtime `NoClassDefFoundError: M2.g`);
* `.github/protohax/bedrockdata` - `pmmp/BedrockData` (CC0), pinned to the `bedrock-1.21.111` tag
  (protocol 844). ProtoHax's own data stops at protocol 594 (1.20.10), so the 1.21.111 block palette, the item
  runtime ids and the `block_hardness.json` that `MineUtils` reads are generated from it
  (`airId = 12530`, `minecraft:acacia_boat = 405` - the 1.20.10 data said 381);
* `.github/protohax/apply_android_patches.py` targets the version: the dashboard / RakNet pong version and the
  registry data generation. It validates the pinned data (version, protocol, non-beta) before using it;
* `.github/protohax/relocate_awt_color.py` points the `java.awt.Color` references of the Cloudburst protocol
  classes - Android has no `java.awt` - at the stand-in `dev.sora.relay.compat.Color` (`Color.java`), which the
  ProtoHax build compiles into the jar (Shadow's own `relocate` cannot be used: the pinned Shadow 8.0.0 bundles
  an ASM that cannot read Java 21 class files);
* `.github/protohax/verify_jar.py` fails the build if registry assets are missing, if the v844 codec is not
  configured, if a `java.awt.Color` reference is left, or if the OAuth fix is absent.

## Rebuilding the jar

GitHub Actions rebuilds ProtoHax from source at the pinned revision
(`Alextheplayer919/ProtoHax@962b9622`, without submodules - the vendored data is used instead) on every push and
runs the same patch / relocate / verify steps against the result.

To refresh the committed `app/libs/ProtoHax-1.4.0.jar` (for example after changing a patch), dispatch the
`Android CI` workflow with `publish_jar = true`: the verified jar is committed back to the branch, and local
builds pick it up.

Build locally:

```bash
chmod +x gradlew
./gradlew app:assembleDebug
```

GitHub Actions uses JDK 21 and builds the release APK.
