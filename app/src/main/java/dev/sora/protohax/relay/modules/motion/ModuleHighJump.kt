package dev.sora.protohax.relay.modules.motion

import dev.sora.protohax.relay.modules.util.ModuleCombat
import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Ported from WClient's `HighJumpModule`.
 *
 * Multiplies the vertical motion of a real jump - the player still has to press jump on
 * the ground, so the server sees an ordinary jump that simply goes higher.
 */
class ModuleHighJump : CheatModule("HighJump", CheatCategory.MOVEMENT) {

	private var jumpHeight by floatValue("JumpHeight", 0.85f, 0.4f..3.0f)

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		val pk = packet as? PlayerAuthInputPacket ?: return@handle
		val inputData = pk.inputData
		if (!inputData.contains(PlayerAuthInputData.VERTICAL_COLLISION)) return@handle
		if (!inputData.contains(PlayerAuthInputData.JUMP_DOWN)) return@handle
		if (pk.motion.length() <= 0.0) return@handle

		val player = session.player
		ModuleCombat.motion(session, player.motionX, jumpHeight, player.motionZ)
	}
}
