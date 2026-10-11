package dev.sora.protohax.relay.modules.misc

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.protocol.bedrock.packet.TextPacket

/**
 * Ported from WClient's `ChatSuffixModule`.
 *
 * Appends a tag to everything the player says. The message is edited on its way out, so
 * the server only ever sees the finished text.
 */
class ModuleChatSuffix : CheatModule("ChatSuffix", CheatCategory.MISC) {

	private var suffixValue by stringValue("Suffix", " | ProtoHax")
	private var randomize by boolValue("Randomize", false)

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		val pk = packet as? TextPacket ?: return@handle
		if (pk.type != TextPacket.Type.CHAT) return@handle

		val message = pk.message
		if (message.isEmpty() || message.startsWith("/")) return@handle

		val suffix = if (randomize) "$suffixValue ${(1000..9999).random()}" else suffixValue
		pk.setMessage(message + suffix)
	}
}
