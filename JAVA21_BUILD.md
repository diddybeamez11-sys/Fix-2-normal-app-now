# Java 21 build

This Android project is configured to build with JDK 21 and Gradle 8.7.

## The ProtoHax library

`app/libs/ProtoHax-1.4.0.jar` is the fully adapted ProtoHax library: Minecraft Bedrock 1.21.111
(protocol 844) with the block, item and block-hardness registry data of that version, the Microsoft OAuth fix,
the `java.awt.Color` stand-in the Cloudburst protocol classes need on Android, the two relay fixes that make a
session work at all (a negative RakNet GUID and the `BedrockBatchWrapper` of the inbound frame codec), the
Xbox login robustness patch (the login requests are retried, a failure is logged as a failure), the login
authentication type fix (protocol 818+ refuses a login without one) and the unlimited encoding settings a relay
needs (the library's default limit of 1536 list entries is shorter than the 1889-entry item registry of
1.21.111, so packet 162 was dropped and no item was ever visible in a joined session). Both a local
build (`./gradlew app:assembleRelease`) and GitHub Actions package exactly this jar, so a local build is enough
to get a client that can join 1.21.111 servers.

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
* `.github/protohax/apply_android_patches.py` targets the version (dashboard / RakNet pong version and the
  registry data generation, validating the pinned data), and patches the relay bugs found on the way - the first
  two by the emulator relay test of branch `arena/482c6c59`, the rest by the sessions that followed:
  * the RakNet client towards the real server uses a GUID whose sign bit is set. The vanilla client's GUID is
    always negative, and servers built on go-raknet (Dragonfly, many community servers) silently ignore
    OpenConnectionRequest2 of a positive GUID, so about every second connection attempt timed out;
  * `CustomFrameIdCodec.decode` now emits the `BedrockBatchWrapper` Cloudburst's `CompressionCodec` requires.
    It used to emit a raw `ByteBuf`, so the very first packet of every connection failed with
    `ClassCastException: UnpooledSlicedByteBuf cannot be cast to BedrockBatchWrapper` - the relay could not
    decode a single packet;
  * the Xbox login of `RelayListenerXboxLogin` retries the identity token and chain requests (a single refused
    TLS handshake used to end the game session), logs `login success` only for a login that really succeeded,
    and does not forward a login packet it could not sign (the kick message names the reason);
  * the rewritten login carries an authentication type: `RelayListenerXboxLogin` tags its Xbox-authenticated
    chain `FULL`, `RelayListenerEncryptedSession` its offline self-signed chain `SELF_SIGNED`. Protocol 818+
    cannot encode a login without one, so every rewritten login was dropped with `Error whilst serializing
    LoginPacket` and never reached the server (the app-side `RelayListenerLoginAuthType` restores the same
    values for a jar that predates the fix);
  * both codec helpers read without the protocol library's default size limits. Every helper starts with
    `EncodingSettings.DEFAULT`, whose `maxListSize` is 1536, and the item registry of 1.21.111 has 1889
    entries; since protocol 776 that registry travels in packet 162 instead of in `StartGamePacket`, so the
    relay could not decode it and dropped it - the client's only source of item runtime ids, which left a
    session that joined and rendered the world without a single visible item (`CraftingDataPacket` and
    `CreativeContentPacket` went with it). `MinecraftRelaySession.setCodec` and its `client` setter now install
    `EncodingSettings.UNLIMITED`, the profile the library documents for a proxy connection, because a codec
    swap replaces the helper (the app-side `RelayListenerEncodingSettings` does the same for a jar that
    predates the fix and after any later swap);
* `.github/protohax/relocate_awt_color.py` points the `java.awt.Color` references of the Cloudburst protocol
  classes - Android has no `java.awt` - at the stand-in `dev.sora.relay.compat.Color` (`Color.java`), which the
  ProtoHax build compiles into the jar (Shadow's own `relocate` cannot be used: the pinned Shadow 8.0.0 bundles
  an ASM that cannot read Java 21 class files);
* `.github/protohax/verify_jar.py` fails the build if registry assets are missing, if the v844 codec is not
  configured, if a `java.awt.Color` reference is left, if the OAuth fix is absent, or if the relay / login fixes
  above are not in the jar.

## Xbox login HTTP client (app side)

The jar's own HTTP client (`HttpUtils.client`, OkHttp with its default `MODERN_TLS` spec) is replaced at app start by
`AuthHttpClient` (`app/src/main/java/dev/sora/protohax/relay/AuthHttpClient.kt`, through reflection - the field's
name is kept in `proguard-rules.pro`). When a TLS handshake is refused
(`SSLV3_ALERT_HANDSHAKE_FAILURE ... HANDSHAKE_FAILURE_ON_CLIENT_HELLO`), it retries the request with a broader cipher
list, then TLS 1.2 only, then without the system proxy, and finally with the host's address resolved by
DNS-over-HTTPS (`DohDns`: 1.1.1.1, 8.8.8.8, 223.5.5.5) instead of the phone's DNS. If everything is refused, the
log names the addresses the phone's DNS and DNS-over-HTTPS returned.

## Rebuilding the jar

GitHub Actions rebuilds ProtoHax from source at the pinned revision
(`Alextheplayer919/ProtoHax@962b9622`, without submodules - the vendored data is used instead) on every push and
runs the same patch / relocate / verify steps against the result.

To refresh the committed `app/libs/ProtoHax-1.4.0.jar` (for example after changing a patch), either dispatch the
`Android CI` workflow with `publish_jar = true`, or push a commit whose message contains `[publish-jar]`. The
verified jar is then committed back to the branch and local builds pick it up.

That publish step is the last step of the job, so a publish problem never keeps the APK from being built, and the
built jar is always available as the `ProtoHax-JAR-<commit>` workflow artifact. The step tries `git push` with the
checkout credentials, then `git push` with an explicit token URL, then a commit through the Git data REST API. If
all three fail, it writes the whole command trace into a check run named `publish-jar-diagnostic-<run id>` and into
error annotations on the commit, both readable through the API.

If you replace the jar by hand, note that `app/.gitignore` ignores `/libs`: git then refuses a plain `git add` with
"paths are ignored", even though the jar is tracked. Use:

```bash
git add -f app/libs/ProtoHax-1.4.0.jar
git commit -m "Update the vendored ProtoHax jar"
```

Build locally:

```bash
chmod +x gradlew
./gradlew app:assembleDebug
```

GitHub Actions uses JDK 21 and builds the release APK.
