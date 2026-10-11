package dev.sora.protohax.relay.modules.motion

import dev.sora.protohax.relay.modules.util.ModuleCombat
import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Ported from WClient's `AirJumpModule`.
 *
 * Re-arms the jump the moment the player presses it, whether or not there is ground
 * underneath. Renamed from "AirJump" because the bundled `AirJump` module already uses
 * that name.
 */
class ModuleMultiJump : CheatModule("MultiJump", CheatCategory.MOVEMENT) {

	private var jumpHeight by floatValue("JumpHeight", 0.42f, 0.4f..3.0f)

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		val pk = packet as? PlayerAuthInputPacket ?: return@handle
		if (!pk.inputData.contains(PlayerAuthInputData.JUMP_DOWN)) return@handle

		val player = session.player
		ModuleCombat.motion(session, player.motionX, jumpHeight, player.motionZ)
	}
}
