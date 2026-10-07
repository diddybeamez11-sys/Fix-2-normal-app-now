#!/usr/bin/env python3
"""
Build guard run by .github/workflows/android.yml against the final ProtoHax jar (the one that is packaged
into the APK). Every check below corresponds to a real failure that happened or was ruled out with evidence:

  * the registry data of the `mcpedata` git submodule must be inside the jar. Without it
    MappingProvider.<init> throws a NullPointerException, ItemMapping$Provider fails to initialise,
    GameSession() throws and the app's MinecraftRelay object dies with "NoClassDefFoundError: <obfuscated>"
    (the "M2.g" error seen at runtime).
  * the Bedrock v844 codec must be present and the relay must be configured for it (not for v2193).
  * no class may still reference java.awt.Color (Android has no java.awt).

usage: verify_jar.py <ProtoHax jar>
"""
import sys
import zipfile

jar = zipfile.ZipFile(sys.argv[1])
names = set(jar.namelist())
problems = []

# 1. registry data from the mcpedata submodule (+ the bundled music file)
for asset in (
    "assets/mcpedata/blocks/index.json",
    "assets/mcpedata/items/index.json",
    "assets/mcpedata/item_tags.json",
    "assets/mcpedata/legacy_block_states.nbt.gz",
    "assets/music.nbs",
):
    if asset not in names:
        problems.append("missing resource %s (is the mcpedata submodule checked out?)" % asset)

# 2. protocol v844
for cls in (
    "org/cloudburstmc/protocol/bedrock/codec/v844/Bedrock_v844.class",
    "org/cloudburstmc/protocol/bedrock/codec/compat/BedrockCompat.class",
    "dev/sora/relay/session/listener/RelayListenerAutoCodec.class",
):
    if cls not in names:
        problems.append("missing class %s" % cls)


def raw(name):
    return jar.read(name) if name in names else b""


relay = raw("dev/sora/relay/MinecraftRelay.class")
if b"Bedrock_v844" not in relay:
    problems.append("dev.sora.relay.MinecraftRelay is not configured for the v844 codec")
if b"Bedrock_v2193" in relay:
    problems.append("dev.sora.relay.MinecraftRelay still advertises the v2193 codec")
session = raw("dev/sora/relay/game/GameSession.class")
if b"1.21.111" not in session or b"1.26.50" in session:
    problems.append("GameSession.RECOMMENDED_VERSION is not 1.21.111")

# 3. java.awt.Color must be gone, its replacement present
if "dev/sora/relay/compat/Color.class" not in names:
    problems.append("dev.sora.relay.compat.Color is missing")
offenders = [
    n for n in names
    if n.endswith(".class") and not n.startswith("META-INF/") and b"java/awt/Color" in jar.read(n)
]
if offenders:
    problems.append("%d classes still reference java.awt.Color, e.g. %s" % (len(offenders), sorted(offenders)[:5]))

if problems:
    print("ProtoHax jar verification FAILED:")
    for p in problems:
        print("  - " + p)
    sys.exit(1)
print("ProtoHax jar verification passed: mcpedata assets present, v844 configured, no java.awt.Color left")
