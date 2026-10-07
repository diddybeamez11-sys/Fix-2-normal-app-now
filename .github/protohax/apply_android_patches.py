#!/usr/bin/env python3
"""
Android / Bedrock v844 adaptations for the ProtoHax checkout, applied by .github/workflows/android.yml
right before ProtoHax is built from source (the ProtoHax revision is pinned, so every patch must match
exactly once - otherwise the build stops and the patch has to be reviewed).

usage: apply_android_patches.py <ProtoHax checkout> <pmmp/BedrockData checkout>

1. Target version
   The pinned ProtoHax revision targets the newest protocol of its Cloudburst snapshot (v2193 / "1.26.50").
   This app targets Minecraft Bedrock 1.21.111 (protocol 844):
     * RECOMMENDED_VERSION (shown in the dashboard and advertised in the RakNet pong) -> "1.21.111"
     * the RakNet pong's protocol number is taken from the v844 codec instead of the v2193 codec
   (RelayListenerAutoCodec keeps its multi-version table: the codec is chosen from the protocol number of
   the connecting game, so a 1.21.111 client gets Bedrock_v844.)

2. java.awt.Color
   Android has no java.awt, yet the Cloudburst protocol classes use java.awt.Color on mandatory paths
   (SerializedSkin.<clinit>, player list / biome definition serializers, ...). Color.java next to this script
   is added to ProtoHax as dev.sora.relay.compat.Color; after the build relocate_awt_color.py points every
   java.awt.Color reference of the shaded jar at it (Shadow's own relocate cannot be used: the pinned Shadow
   8.0.0 bundles an ASM that cannot read the Java 21 class files ProtoHax is compiled to).

3. RakNet client GUID
   The relay connects to the real server with a RakNet client whose GUID is Random.nextLong() - positive half
   of the time. The vanilla Bedrock client's GUID is always negative (go-raknet, which Dragonfly and many
   community servers are built on: "This should always be negative as per the vanilla client implementation"),
   and such servers silently ignore OpenConnectionRequest2 of a positive GUID ("invalid ClientGUID ... expected
   negative"), so about every second connection attempt ends in a connect timeout. The sign bit is set.

4. Inbound frame codec (the relay could not decode a single packet)
   ProtoHax replaces Cloudburst's FrameIdCodec with its own CustomFrameIdCodec (to add RakNet reliability).
   Cloudburst's pipeline is FrameIdCodec -> CompressionCodec -> BedrockBatchDecoder -> BedrockPacketCodec and,
   since the BedrockBatchWrapper refactoring, FrameIdCodec.decode emits a BedrockBatchWrapper and
   CompressionCodec only accepts that. CustomFrameIdCodec.decode still emitted a raw ByteBuf (the encode side
   had been adapted when ProtoHax was moved to the Beta13 snapshot, the decode side had not), so the very first
   packet of every connection - in both directions - failed with
   "ClassCastException: UnpooledSlicedByteBuf cannot be cast to BedrockBatchWrapper" in CompressionCodec.
   decode now wraps the payload exactly like Cloudburst's own FrameIdCodec.

5. Registry data for protocol 844
   ProtoHax resolves block / item definitions with MappingProvider.craftMapping(protocol), which takes the
   newest data set <= the protocol. The data of its `mcpedata` submodule ends at protocol 594 (Minecraft
   1.20.10), so a 1.21.111 session would silently use 1.20.10 block palettes. The same data is published
   per game version by pmmp/BedrockData (CC0). Its canonical_block_states.nbt for 1.20.10 is byte-identical
   to mcpedata's canonical_block_states_594, and its required_item_list.json converts to exactly
   mcpedata's runtime_item_states_594.json, so the files for the tag `bedrock-1.21.111`
   (protocol_version 844) are generated the same way:
     * blocks/canonical_block_states_844.nbt.gz   <- canonical_block_states.nbt (gzip)
     * items/runtime_item_states_844.json         <- required_item_list.json    ([{name, id}])
     * block_hardness.json                        <- block_properties_table.json (hardness per block)
   block_hardness.json is read by MineUtils but does not exist in any revision of the mcpedata submodule
   (without it MineUtils cannot initialise).
"""
import gzip
import json
import shutil
import sys
from pathlib import Path

if len(sys.argv) != 3:
    sys.exit(__doc__)

root = Path(sys.argv[1])
bedrock_data = Path(sys.argv[2])
here = Path(__file__).resolve().parent
TARGET_PROTOCOL = 844
TARGET_VERSION = "1.21.111"


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
      'const val RECOMMENDED_VERSION = "%s"' % TARGET_VERSION)
patch(src + "MinecraftRelay.kt",
      "import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193",
      "import org.cloudburstmc.protocol.bedrock.codec.v%d.Bedrock_v%d" % (TARGET_PROTOCOL, TARGET_PROTOCOL))
patch(src + "MinecraftRelay.kt",
      ".protocolVersion(Bedrock_v2193.CODEC.protocolVersion)",
      ".protocolVersion(Bedrock_v%d.CODEC.protocolVersion)" % TARGET_PROTOCOL)

# --- 2. java.awt.Color is not available on Android (the references are relocated after the build) -----
target = root / "src/main/java/dev/sora/relay/compat/Color.java"
target.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(here / "Color.java", target)
print("added %s" % target.relative_to(root))

# --- 3. RakNet client GUID: negative like the vanilla client ---------------------------------------------
patch(src + "MinecraftRelay.kt",
      "RakChannelOption.RAK_PROTOCOL_VERSION))\n\t\t\t\t.option(RakChannelOption.RAK_GUID, Random.nextLong())",
      "RakChannelOption.RAK_PROTOCOL_VERSION))\n"
      "\t\t\t\t// the vanilla client's RakNet GUID is always negative (servers built on go-raknet reject positive ones)\n"
      "\t\t\t\t.option(RakChannelOption.RAK_GUID, Random.nextLong() or Long.MIN_VALUE)")

# --- 4. inbound frame codec: emit the BedrockBatchWrapper the next pipeline stage requires -------------------
patch(src + "session/CustomFrameIdCodec.kt",
      "out.add(content.readRetainedSlice(content.readableBytes()))",
      "out.add(BedrockBatchWrapper.newInstance(content.readRetainedSlice(content.readableBytes()), null))")

# --- 5. registry data for protocol 844 ------------------------------------------------------------------
info = json.loads((bedrock_data / "protocol_info.json").read_text())["version"]
version = "%d.%d.%d" % (info["major"], info["minor"], info["patch"])
if info["protocol_version"] != TARGET_PROTOCOL or version != TARGET_VERSION or info["beta"]:
    sys.exit("pmmp/BedrockData is %s (protocol %s, beta=%s), expected %s (protocol %d)"
             % (version, info["protocol_version"], info["beta"], TARGET_VERSION, TARGET_PROTOCOL))

assets = root / "src/main/resources/assets/mcpedata"
if not (assets / "blocks" / "index.json").is_file():
    sys.exit("%s is empty - is the mcpedata submodule checked out?" % assets)


def register(index_file):
    versions = json.loads(index_file.read_text())
    if TARGET_PROTOCOL in versions:
        sys.exit("%s already lists protocol %d" % (index_file, TARGET_PROTOCOL))
    index_file.write_text(json.dumps(sorted(versions + [TARGET_PROTOCOL]), separators=(",", ":")) + "\n")


# block palette (network NBT, a sequence of {name, states, version} compounds)
palette = (bedrock_data / "canonical_block_states.nbt").read_bytes()
if len(palette) < 1_000_000:
    sys.exit("canonical_block_states.nbt is suspiciously small (%d bytes)" % len(palette))
(assets / "blocks" / ("canonical_block_states_%d.nbt.gz" % TARGET_PROTOCOL)).write_bytes(
    gzip.compress(palette, compresslevel=9, mtime=0))
register(assets / "blocks" / "index.json")

# item runtime ids
items = json.loads((bedrock_data / "required_item_list.json").read_text())
item_states = [{"name": name, "id": entry["runtime_id"]} for name, entry in items.items()]
if len(item_states) < 1000 or not any(i["name"] == "minecraft:acacia_boat" for i in item_states):
    sys.exit("required_item_list.json looks wrong (%d items)" % len(item_states))
(assets / "items" / ("runtime_item_states_%d.json" % TARGET_PROTOCOL)).write_text(
    json.dumps(item_states, separators=(",", ":")))
register(assets / "items" / "index.json")

# block hardness (read by MineUtils: identifier -> hardness)
properties = json.loads((bedrock_data / "block_properties_table.json").read_text())
hardness = {name: props["hardness"] for name, props in properties.items()}
if len(hardness) < 1000 or hardness.get("minecraft:stone") is None:
    sys.exit("block_properties_table.json looks wrong (%d blocks)" % len(hardness))
(assets / "block_hardness.json").write_text(json.dumps(hardness, separators=(",", ":")))

print("registry data for protocol %d (Minecraft %s) generated: %d block-state bytes, %d items, %d block hardness values"
      % (TARGET_PROTOCOL, version, len(palette), len(item_states), len(hardness)))
print("ProtoHax Android patches applied")
