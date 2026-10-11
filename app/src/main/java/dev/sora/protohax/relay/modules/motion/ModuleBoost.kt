package dev.sora.protohax.relay.modules.motion

import dev.sora.protohax.relay.modules.util.ModuleCombat
import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Ported from WClient's `SpeedModule`.
 *
 * Scales the horizontal motion the client reports the moment it touches the ground. The
 * server is never told about the boost - it only ever sees the position the client
 * arrives at - so there is no "speed" flag for an anti-cheat to read.
 */
class ModuleBoost : CheatModule("Boost", CheatCategory.MOVEMENT) {

	private var speedValue by floatValue("Speed", 1.3f, 0.1f..5f)
	private var onlyOnGround by boolValue("OnlyOnGround", true)

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		val pk = packet as? PlayerAuthInputPacket ?: return@handle
		if (onlyOnGround && !pk.inputData.contains(PlayerAuthInputData.VERTICAL_COLLISION)) return@handle
		if (pk.motion.length() <= 0.0) return@handle

		val player = session.player
		ModuleCombat.motion(
			session,
			player.motionX * speedValue,
			player.motionY,
			player.motionZ * speedValue
		)
	}
}
