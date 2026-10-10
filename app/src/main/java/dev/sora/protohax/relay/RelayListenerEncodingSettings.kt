package dev.sora.protohax.relay

import dev.sora.relay.session.MinecraftRelayPacketListener
import dev.sora.relay.session.MinecraftRelaySession
import dev.sora.relay.utils.logInfo
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket

/**
 * Lifts the packet size limits of the protocol library on both sides of the relay, so that the packets a
 * real server sends on join are forwarded instead of being dropped for being "too long".
 *
 * The reported session: the game joined, the world rendered, the player moved and took fall damage - but
 * not a single item was ever visible. The inventory the server had (a totem of undying that popped on the
 * fatal fall) stayed empty on the screen, no item showed in a hand or on the ground.
 *
 * ## Root cause
 *
 * Every codec helper the library hands out starts with `EncodingSettings.DEFAULT`
 * (`BaseBedrockCodecHelper`: `protected EncodingSettings encodingSettings = EncodingSettings.DEFAULT`),
 * whose `maxListSize` is **1536**, and every `readArray` of the helper enforces that limit:
 *
 * ```
 * checkArgument(maxLength <= 0 || length <= maxLength, "Tried to read %s bytes but maximum is %s", ...)
 * ```
 *
 * The item registry of Minecraft 1.21.111 has **1889** entries - this repository carries that very list,
 * `.github/protohax/bedrockdata/required_item_list.json` is the source of `runtime_item_states_844.json`.
 * Since protocol 776 (1.21.60) that registry is no longer part of `StartGamePacket`
 * (`StartGameSerializer_v776` reads and writes the item definitions as a no-op) but a packet of its own,
 * id 162 - `ItemRegistryPacket` in Mojang's protocol documentation, still `ItemComponentPacket` in this
 * library, serialized by `ItemComponentSerializer_v776`, which reads the list with `helper.readArray`.
 *
 * 1889 > 1536, so the relay could not decode that packet: `BedrockCodec.tryDecode` failed with "Tried to
 * read 1889 bytes but maximum is 1536", `BedrockPacketCodec` dropped it and reported "Failed to decode
 * packet" (a line [dev.sora.protohax.relay.netty.log.NettyLogger] keeps in a release build, and
 * [MinecraftRelay.watchDroppedPackets] sees the resulting pipeline error on the server connection). The
 * game therefore never received its item registry - and packet 162 is the *only* source of item runtime
 * ids on protocol 776+. Every item stack that arrived afterwards (inventory, armour, offhand, another
 * player's hand, an item entity on the ground) carried a runtime id the client could not resolve, so
 * nothing was rendered, while the server kept the real inventory: the totem popped, the player just never
 * saw it.
 *
 * Blocks are unaffected, which is why the world looked completely normal: on these protocols the client
 * derives block network ids itself (`StartGamePacket.isBlockNetworkIdsHashed`) and every chunk palette
 * carries the ids of the states it uses, so the terrain renders without a single registry packet. Item ids
 * have no such fallback.
 *
 * The same limit drops the other long lists of a join - `CraftingDataPacket` (every recipe of the game,
 * thousands of entries: nothing could be crafted) and `CreativeContentPacket` (the creative item list: an
 * empty creative inventory). Only the item registry was reported, because it is the one a player sees.
 *
 * ## Fix
 *
 * `EncodingSettings.UNLIMITED` - the profile the library documents for exactly this position: "An
 * EncodingSettings instance for implementations that don't need such limits. (e.g. Proxy server client <->
 * downstream server connection)". A relay is not an endpoint protecting itself from untrusted input, it is
 * a man in the middle that has to forward what the server sends: a limit it enforces is a packet it loses.
 * The limits only guard reads, so the write side is unchanged, and both connections get the profile - the
 * relay reads what the server sends (the long lists) and what the game sends (skins with their geometry
 * data, inventory requests).
 *
 * ## Why a listener, and why it is registered last
 *
 * The settings live in the codec helper, and `BedrockPacketCodec.setCodec` replaces that helper with a
 * fresh one (`this.helper = codec.createHelper()`) - default limits again.
 * [dev.sora.relay.session.listener.RelayListenerAutoCodec] swaps the codec twice per session, on the
 * game's `RequestNetworkSettingsPacket` and on its `LoginPacket`, so a one-off assignment would be wiped
 * before the server sent its first packet. This listener therefore re-applies the profile on every packet
 * of both directions, and it is registered after `RelayListenerAutoCodec` ([MinecraftRelay.constructRelay]
 * adds it last): within one packet the listeners run in order, so it sees the helper the codec swap has
 * just created. The check is a reference comparison and free once the profile is installed.
 *
 * The ProtoHax sources carry the same fix (.github/protohax/apply_android_patches.py, step 8, in
 * `MinecraftRelaySession.setCodec` and its `client` setter - the two places that install a fresh helper);
 * this listener keeps local builds working with a vendored jar that predates it, and re-applies the
 * profile after any later codec swap either way.
 */
class RelayListenerEncodingSettings(
    private val session: MinecraftRelaySession,
) : MinecraftRelayPacketListener {

    /** game -> relay; the codec swaps of a session happen on these packets, so this is the important one */
    override fun onPacketOutbound(packet: BedrockPacket): Boolean {
        install()
        return true
    }

    /** server -> relay; the packet has been decoded already, but the next one has not */
    override fun onPacketInbound(packet: BedrockPacket): Boolean {
        install()
        return true
    }

    /**
     * Installs the unlimited profile on the helper of both connections, unless it is installed already.
     *
     * Public because [MinecraftRelay.constructRelay] calls it once when the session is created: the
     * helpers of that moment are replaced by the codec swaps anyway, but the connection to the game can be
     * written to before the game has sent a packet of its own.
     */
    fun install() {
        installOn(session.peer.codecHelper, "the game")
        // the connection to the server only exists from RelayListener.onSessionCreation's Bootstrap on
        session.client?.peer?.codecHelper?.let { installOn(it, "the server") }
    }

    private fun installOn(helper: BedrockCodecHelper, side: String) {
        val current = helper.encodingSettings
        if (current === EncodingSettings.UNLIMITED) {
            return
        }
        helper.encodingSettings = EncodingSettings.UNLIMITED
        logInfo(
            "encoding limits lifted on the connection to $side: maxListSize was ${current?.maxListSize()}, " +
                "the item registry of Minecraft 1.21.111 has 1889 entries and a longer list is not read"
        )
    }
}
