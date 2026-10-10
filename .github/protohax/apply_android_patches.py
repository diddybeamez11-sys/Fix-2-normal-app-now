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
   (Found with the emulator relay test of branch arena/482c6c59.)

4. Inbound frame codec (the relay could not decode a single packet)
   ProtoHax replaces Cloudburst's FrameIdCodec with its own CustomFrameIdCodec (to add RakNet reliability).
   Cloudburst's pipeline is FrameIdCodec -> CompressionCodec -> BedrockBatchDecoder -> BedrockPacketCodec and,
   since the BedrockBatchWrapper refactoring, FrameIdCodec.decode emits a BedrockBatchWrapper while
   CompressionCodec only accepts that. CustomFrameIdCodec.decode still emitted a raw ByteBuf (the encode side
   had been adapted when ProtoHax was moved to the Beta13 snapshot, the decode side had not), so the very first
   packet of every connection - in both directions - failed with
   "ClassCastException: UnpooledSlicedByteBuf cannot be cast to BedrockBatchWrapper" in CompressionCodec.
   decode now wraps the payload exactly like Cloudburst's own FrameIdCodec.
   (Found with the emulator relay test of branch arena/482c6c59.)

5. Xbox login robustness (a single failed request used to end the game session)
   The Xbox Live login - Microsoft access token, Xbox user / device / title / XSTS tokens and the
   multiplayer.minecraft.net chain - is fetched *inside* the packet pipeline, when the game sends its
   LoginPacket, and every request had exactly one attempt. Microsoft's login services answer such a request
   with a temporary network / TLS failure every now and then; on Android the app logged e.g.

     login failed: javax.net.ssl.SSLHandshakeException: ... SSLV3_ALERT_HANDSHAKE_FAILURE
       ... HANDSHAKE_FAILURE_ON_CLIENT_HELLO

   followed by "login success" (logged unconditionally, even though the login had failed) and a disconnect of
   the client: the game never got the answer it was waiting for, and the relay had forwarded a login packet it
   could not sign. The two requests are now retried (retryAuth, only for network / TLS failures and the HTTP
   status assertions - a missing Xbox GamerTag still fails immediately), the success is logged only when the
   login really succeeded, the kick message names the reason, and a login that could not be prepared is not
   forwarded to the server.

6. Registry data for protocol 844
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

7. Login authentication type (every login on protocol 818+ was dropped on its way to the server)
   Since protocol 818 (Minecraft 1.21.90) the login serializer requires the auth payload to carry a
   non-UNKNOWN AuthType ("Client requires non-null and non-UNKNOWN AuthType for login"); the protocol 844
   codec inherits that serializer. Both session encryptors replace the game's auth payload with
   CertificateChainPayload(chain), whose single-argument constructor leaves the type at UNKNOWN, so
   BedrockCodec.tryEncode fails every rewritten login with "Error whilst serializing LoginPacket" and the
   packet never reaches the server - the session dies right after "login success". The Xbox-authenticated
   chain is now tagged FULL, the offline self-signed chain SELF_SIGNED (the Android-side
   RelayListenerLoginAuthType restores the same values for a vendored jar that predates this fix).

8. Encoding limits (no item was ever visible: the item registry packet was dropped for being too long)
   Every codec helper starts with EncodingSettings.DEFAULT, whose maxListSize is 1536, and every readArray
   of the helper refuses a longer list ("Tried to read %s bytes but maximum is %s"). The item registry of
   Minecraft 1.21.111 has 1889 entries (required_item_list.json of step 6 is that list), and since protocol
   776 it travels in a packet of its own - id 162, ItemRegistryPacket in Mojang's documentation, still
   ItemComponentPacket here - because StartGameSerializer_v776 reads and writes the item definitions of
   StartGamePacket as a no-op. The relay could not decode that packet and dropped it, so the game never
   learned a single item runtime id: the session joined, the world rendered (block network ids are hashed
   and a chunk palette carries its own), and every item stack - inventory, hand, item entity on the ground -
   resolved to nothing. The server kept the real inventory, so a totem of undying still popped on a fatal
   fall the player could not see. CraftingDataPacket (every recipe of the game) and CreativeContentPacket
   are dropped by the same limit. Both connections now use EncodingSettings.UNLIMITED, the profile the
   library documents for this position ("e.g. Proxy server client <-> downstream server connection"): a
   relay forwards what the server sends, so a limit it enforces is a packet it loses. The settings live in
   the helper and BedrockPacketCodec.setCodec replaces it with a fresh one, so they are re-applied wherever
   a helper is installed - MinecraftRelaySession.setCodec (RelayListenerAutoCodec swaps the codec twice per
   session) and the client setter (the Android-side RelayListenerEncodingSettings does the same for a
   vendored jar that predates this fix, and after any later swap).
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

# --- 3. RakNet client GUID: negative like the vanilla client's -------------------------------------------
patch(src + "MinecraftRelay.kt",
      "RakChannelOption.RAK_PROTOCOL_VERSION))\n\t\t\t\t.option(RakChannelOption.RAK_GUID, Random.nextLong())",
      "RakChannelOption.RAK_PROTOCOL_VERSION))\n"
      "\t\t\t\t// the vanilla client's RakNet GUID is always negative (servers built on go-raknet reject positive ones)\n"
      "\t\t\t\t.option(RakChannelOption.RAK_GUID, Random.nextLong() or Long.MIN_VALUE)")

# --- 4. inbound frame codec: emit the BedrockBatchWrapper the next pipeline stage requires ----------------
patch(src + "session/CustomFrameIdCodec.kt",
      "out.add(content.readRetainedSlice(content.readableBytes()))",
      "out.add(BedrockBatchWrapper.newInstance(content.readRetainedSlice(content.readableBytes()), null))")

# --- 5. Xbox login: retry the login requests, log the truth and never forward a login that cannot work ----
patch(src + "session/listener/xbox/RelayListenerXboxLogin.kt",
      "                } ?: fetchIdentityToken(accessToken(), deviceInfo).also {",
      "                } ?: retryAuth(\"fetch the Xbox identity token (login.live.com, *.xboxlive.com)\") {\n"
      "                    fetchIdentityToken(accessToken(), deviceInfo)\n"
      "                }.also {")

patch(src + "session/listener/xbox/RelayListenerXboxLogin.kt",
      "    private val chain: List<String>\n        get() = fetchChain(identityToken.token, keyPair)",
      "    private val chain: List<String>\n"
      "        get() = retryAuth(\"fetch the login chain (multiplayer.minecraft.net)\") {\n"
      "            fetchChain(identityToken.token, keyPair)\n"
      "        }")

patch(src + "session/listener/xbox/RelayListenerXboxLogin.kt",
      """        if (packet is LoginPacket) {
\t\t\tsession.keyPair = keyPair
            try {
                packet.authPayload = CertificateChainPayload(chain)
\t\t\t\tpacket.clientJwt = signJWT(packet.clientJwt.split('.')[1], keyPair, base64Encoded = true)
            } catch (e: Throwable) {
                session.inboundPacket(DisconnectPacket().apply {
                    setKickMessage(e.toString())
                })
                logError("login failed", e)
            }
            logInfo("login success")
        }

        return true""",
      """        if (packet is LoginPacket) {
\t\t\tsession.keyPair = keyPair
            try {
                packet.authPayload = CertificateChainPayload(chain)
\t\t\t\tpacket.clientJwt = signJWT(packet.clientJwt.split('.')[1], keyPair, base64Encoded = true)
                logInfo("login success")
            } catch (e: Throwable) {
                logError("login failed", e)
                session.inboundPacket(DisconnectPacket().apply {
                    setKickMessage("Xbox login failed: " + (e.message ?: e.toString()))
                })
                // the login of this session cannot be signed, so the game's own (unsigned) login packet
                // must not be forwarded: the server could only kick the player for it
                return false
            }
        }

        return true""")

patch(src + "session/listener/xbox/RelayListenerXboxLogin.kt",
      "        fun fetchIdentityToken(accessToken: String, deviceInfo: XboxDeviceInfo): XboxIdentityToken {",
      '''        /**
         * The Xbox Live login services answer a request with a temporary network / TLS failure every now
         * and then. On Android such a failure shows up as
         * "SSLHandshakeException ... SSLV3_ALERT_HANDSHAKE_FAILURE ... HANDSHAKE_FAILURE_ON_CLIENT_HELLO":
         * the TLS handshake of one request is refused, the next one usually succeeds.
         *
         * Every request of the login used to have exactly one attempt, and because the login packet is
         * answered inside the packet pipeline such a hiccup ended the whole game session (the "login failed"
         * of such a session was followed by a disconnect while the game waited for its login answer).
         * Retrying lets the login succeed instead.
         */
        fun <T> retryAuth(what: String, attempts: Int = 3, block: () -> T): T {
            for (attempt in 1..attempts) {
                try {
                    return block()
                } catch (t: Throwable) {
                    if (attempt == attempts || !isTransientAuthFailure(t)) {
                        throw t
                    }
                    logWarn("$what failed (attempt $attempt/$attempts), retrying: $t")
                    try {
                        Thread.sleep(500L * attempt)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw t
                    }
                }
            }

            error("$what failed")
        }

        private fun isTransientAuthFailure(t: Throwable): Boolean {
            // "Have you registered a Xbox GamerTag?" - retrying cannot help, the player has to sign in
            if (t is XboxGamerTagException) {
                return false
            }
            // network and TLS failures (SocketException, SSLException, timeouts, ...) and the
            // "Http code <n>" assertions of the login requests
            return t is java.io.IOException || t is AssertionError
        }

        fun fetchIdentityToken(accessToken: String, deviceInfo: XboxDeviceInfo): XboxIdentityToken {''')

# --- 6. registry data for protocol 844 ------------------------------------------------------------------
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

# --- 7. login authentication type: protocol 818+ refuses a login without one ---------------------------
# (This runs after step 5 on purpose: it matches the login block step 5 rewrote.)
patch(src + "session/listener/xbox/RelayListenerXboxLogin.kt",
      "import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload",
      "import org.cloudburstmc.protocol.bedrock.data.auth.AuthType\n"
      "import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload")
patch(src + "session/listener/xbox/RelayListenerXboxLogin.kt",
      "packet.authPayload = CertificateChainPayload(chain)",
      "packet.authPayload = CertificateChainPayload(chain, AuthType.FULL)")
patch(src + "session/listener/RelayListenerEncryptedSession.kt",
      "import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload",
      "import org.cloudburstmc.protocol.bedrock.data.auth.AuthType\n"
      "import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload")
patch(src + "session/listener/RelayListenerEncryptedSession.kt",
      "packet.authPayload = CertificateChainPayload(listOfNotNull(newChain))",
      "packet.authPayload = CertificateChainPayload(listOfNotNull(newChain), AuthType.SELF_SIGNED)")

# --- 8. encoding limits: the item registry of 1.21.111 is longer than a codec helper is allowed to read --
# A relay has to forward what the server sends; EncodingSettings is the library's own knob for that, and its
# documentation names this exact position ("e.g. Proxy server client <-> downstream server connection").
session_file = src + "session/MinecraftRelaySession.kt"
patch(session_file,
      "import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec\n",
      "import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec\n"
      "import org.cloudburstmc.protocol.bedrock.data.EncodingSettings\n")
# the server-facing session is created after the game-facing one and gets a fresh helper from `it.codec = codec`;
# inside the setter `client` still holds the old value, so the new helper has to be reached through `it`.
patch(session_file,
      "\t\t\t\tit.peer.codecHelper.itemDefinitions = peer.codecHelper.itemDefinitions\n",
      "\t\t\t\tit.peer.codecHelper.itemDefinitions = peer.codecHelper.itemDefinitions\n"
      "\t\t\t\t// `it.codec = codec` above installed a fresh helper, and a fresh helper carries the library's\n"
      "\t\t\t\t// default read limits again (see unlimitedEncodingSettings)\n"
      "\t\t\t\tit.peer.codecHelper.encodingSettings = EncodingSettings.UNLIMITED\n")
# every codec swap (RelayListenerAutoCodec: on the game's RequestNetworkSettingsPacket and on its LoginPacket)
# replaces the helper of both peers, so the profile has to be re-applied here.
patch(session_file,
      "    override fun setCodec(codec: BedrockCodec) {\n"
      "        client?.codec = codec\n"
      "        super.setCodec(codec)\n"
      "    }\n",
      "    override fun setCodec(codec: BedrockCodec) {\n"
      "        client?.codec = codec\n"
      "        super.setCodec(codec)\n"
      "        // both peers just got a fresh codec helper, and a fresh helper carries the library's default\n"
      "        // read limits again\n"
      "        unlimitedEncodingSettings()\n"
      "    }\n"
      "\n"
      "    /**\n"
      "     * Removes the protocol library's read limits from the codec helper of both connections.\n"
      "     *\n"
      "     * Every helper starts with `EncodingSettings.DEFAULT`, whose `maxListSize` is 1536, and every\n"
      "     * `readArray` of the helper refuses a longer list (\"Tried to read %s bytes but maximum is %s\").\n"
      "     * The item registry of Minecraft 1.21.111 has 1889 entries and since protocol 776 it travels in a\n"
      "     * packet of its own (id 162, `ItemComponentPacket` here, `ItemRegistryPacket` in Mojang's protocol\n"
      "     * documentation) instead of in `StartGamePacket`, whose item definitions `StartGameSerializer_v776`\n"
      "     * reads and writes as a no-op. That packet therefore could not be decoded and was dropped, and a\n"
      "     * client that never receives it cannot resolve a single item runtime id: the session joined, the\n"
      "     * world rendered (block network ids are hashed and a chunk palette carries the ids it uses), and no\n"
      "     * item was ever visible - not in the inventory the server had, not in a hand, not on the ground.\n"
      "     * `CraftingDataPacket` (every recipe of the game) and `CreativeContentPacket` are dropped by the\n"
      "     * same limit.\n"
      "     *\n"
      "     * `EncodingSettings.UNLIMITED` is the profile the library documents for this position (\"e.g. Proxy\n"
      "     * server client <-> downstream server connection\"): a relay forwards what the server sends, so a\n"
      "     * limit it enforces is a packet it loses. The limits only guard reads, the write side is unchanged.\n"
      "     *\n"
      "     * Called from here and from the `client` setter because `BedrockPacketCodec.setCodec` replaces the\n"
      "     * helper (`this.helper = codec.createHelper()`), and `RelayListenerAutoCodec` swaps the codec twice\n"
      "     * per session - on the game's `RequestNetworkSettingsPacket` and on its `LoginPacket`.\n"
      "     */\n"
      "    private fun unlimitedEncodingSettings() {\n"
      "        peer.codecHelper.encodingSettings = EncodingSettings.UNLIMITED\n"
      "        client?.peer?.codecHelper?.encodingSettings = EncodingSettings.UNLIMITED\n"
      "    }\n")

print("ProtoHax Android patches applied")
