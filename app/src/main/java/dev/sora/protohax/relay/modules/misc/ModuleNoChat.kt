package dev.sora.protohax.relay.modules.misc

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketInbound
import org.cloudburstmc.protocol.bedrock.packet.TextPacket

/**
 * Ported from WClient's `NoChatModule`.
 *
 * Hides what the server says. On 2b2t the chat is a firehose, and most of it is either
 * spam designed to make you miss something or a death message from a fight next to you.
 */
class ModuleNoChat : CheatModule("NoChat", CheatCategory.MISC) {

	private var blockAllChat by boolValue("BlockAll", false)
	private var blockPlayerChat by boolValue("BlockChat", true)
	private var blockSystemChat by boolValue("BlockSystem", false)
	private var blockWhispers by boolValue("BlockWhispers", false)
	private var blockAnnouncements by boolValue("BlockAnnouncements", false)
	private var blockJoinLeave by boolValue("BlockJoinLeave", false)

	private val handlePacketInbound = handle<EventPacketInbound> {
		val pk = packet as? TextPacket ?: return@handle
		if (blockAllChat) {
			cancel()
			return@handle
		}

		val blocked = when (pk.type) {
			TextPacket.Type.CHAT, TextPacket.Type.JSON -> blockPlayerChat
			TextPacket.Type.SYSTEM -> blockSystemChat
			TextPacket.Type.WHISPER, TextPacket.Type.WHISPER_JSON -> blockWhispers
			TextPacket.Type.ANNOUNCEMENT, TextPacket.Type.ANNOUNCEMENT_JSON -> blockAnnouncements
			else -> false
		}
		if (blocked) {
			cancel()
			return@handle
		}

		if (blockJoinLeave) {
			val message = pk.message
			if (message.contains("joined") || message.contains("left")) cancel()
		}
	}
}
