#!/usr/bin/env python3
"""
Android / Bedrock v844 adaptations for the ProtoHax checkout, applied by .github/workflows/android.yml
right before ProtoHax is built from source (the ProtoHax revision is pinned, so every patch must match
exactly once - otherwise the build stops and the patch has to be reviewed).

usage: apply_android_patches.py <path to the ProtoHax checkout>

1. Target version
   The pinned ProtoHax revision targets the newest protocol of its Cloudburst snapshot (v2193 / "1.26.50").
   This app targets Minecraft Bedrock 1.21.111 (protocol 844):
     * RECOMMENDED_VERSION (shown in the dashboard and advertised in the RakNet pong) -> "1.21.111"
     * the RakNet pong's protocol number is taken from the v844 codec instead of the v2193 codec
   (RelayListenerAutoCodec keeps its multi-version table: the codec is chosen from the protocol number of
   the connecting game, so a 1.21.111 client gets Bedrock_v844.)

2. java.awt.Color
   Android has no java.awt, yet the Cloudburst protocol classes use java.awt.Color on mandatory paths
   (SerializedSkin.<clinit>, player list / biome definition serializers, ...). The shaded jar is built
   with every reference relocated to dev.sora.relay.compat.Color (Color.java next to this script).
"""
import shutil
import sys
from pathlib import Path

if len(sys.argv) != 2:
    sys.exit(__doc__)

root = Path(sys.argv[1])
here = Path(__file__).resolve().parent


def patch(rel, old, new):
    path = root / rel
    text = path.read_text()
    found = text.count(old)
    if found != 1:
        sys.exit("%s: expected exactly one occurrence of %r but found %d - the pinned ProtoHax "
                 "revision changed, review this patch" % (rel, old, found))
    path.write_text(text.replace(old, new))
    print("patched %s\n    - %s\n    + %s" % (rel, old.strip(), new.strip().replace("\n", "\n      ")))


src = "src/main/java/dev/sora/relay/"

# --- 1. target version: Minecraft Bedrock 1.21.111 / protocol 844 -----------------------------------
patch(src + "game/GameSession.kt",
      'const val RECOMMENDED_VERSION = "1.26.50"',
      'const val RECOMMENDED_VERSION = "1.21.111"')
patch(src + "MinecraftRelay.kt",
      "import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193",
      "import org.cloudburstmc.protocol.bedrock.codec.v844.Bedrock_v844")
patch(src + "MinecraftRelay.kt",
      ".protocolVersion(Bedrock_v2193.CODEC.protocolVersion)",
      ".protocolVersion(Bedrock_v844.CODEC.protocolVersion)")

# --- 2. java.awt.Color is not available on Android ---------------------------------------------------
target = root / "src/main/java/dev/sora/relay/compat/Color.java"
target.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(here / "Color.java", target)
print("added %s" % target.relative_to(root))
patch("build.gradle",
      "    duplicatesStrategy DuplicatesStrategy.EXCLUDE\n",
      "    duplicatesStrategy DuplicatesStrategy.EXCLUDE\n"
      "    // Android has no java.awt: point every java.awt.Color reference at the bundled stand-in\n"
      "    relocate 'java.awt.Color', 'dev.sora.relay.compat.Color'\n")
print("ProtoHax Android patches applied")
