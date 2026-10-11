package dev.sora.protohax.relay.modules.motion

import dev.sora.protohax.relay.modules.util.ModuleCombat
import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ported from WClient's `JetPackModule`.
 *
 * Converts the direction the player is looking into motion, so the camera becomes a
 * throttle: look up and hold nothing, and the player flies that way.
 */
class ModuleJetPack : CheatModule("JetPack", CheatCategory.MOVEMENT) {

	private var speedValue by floatValue("Speed", 2.5f, 1.0f..10.0f)

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		val pk = packet as? PlayerAuthInputPacket ?: return@handle

		val rotation = pk.rotation
		val yaw = Math.toRadians(rotation.y.toDouble())
		val pitch = Math.toRadians(rotation.x.toDouble())

		val cosPitch = cos(pitch)
		ModuleCombat.motion(
			session,
			(-sin(yaw) * cosPitch * speedValue).toFloat(),
			(-sin(pitch) * speedValue).toFloat(),
			(cos(yaw) * cosPitch * speedValue).toFloat()
		)
	}
}
