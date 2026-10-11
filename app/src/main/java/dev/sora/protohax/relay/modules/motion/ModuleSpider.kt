package dev.sora.protohax.relay.modules.motion

import dev.sora.protohax.relay.modules.util.ModuleCombat
import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Ported from WClient's `SpiderModule`.
 *
 * Walks up walls: every horizontal collision gets an upward push, so climbing a face is
 * just walking into it.
 */
class ModuleSpider : CheatModule("Spider", CheatCategory.MOVEMENT) {

	private var climbSpeed by floatValue("ClimbSpeed", 0.5f, 0.1f..2.0f)

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		val pk = packet as? PlayerAuthInputPacket ?: return@handle
		if (!pk.inputData.contains(PlayerAuthInputData.HORIZONTAL_COLLISION)) return@handle

		val player = session.player
		ModuleCombat.motion(session, player.motionX, climbSpeed, player.motionZ)
	}
}
