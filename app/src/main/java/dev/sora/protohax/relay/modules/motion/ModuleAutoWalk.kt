package dev.sora.protohax.relay.modules.motion

import dev.sora.protohax.relay.modules.util.ModuleCombat
import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ported from WClient's `AutoWalkModule`.
 *
 * Walks wherever the player is looking. On 2b2t this is what covers the tens of
 * thousands of blocks between spawn and anywhere worth being, and with [fallSpeed] set
 * to a slow glide it also crosses the gaps the old terrain is full of.
 */
class ModuleAutoWalk : CheatModule("AutoWalk", CheatCategory.MOVEMENT) {

	private var walkSpeed by floatValue("WalkSpeed", 0.7f, 0.1f..2.0f)
	private var fallSpeed by floatValue("FallSpeed", -0.125f, -0.5f..-0.05f)
	private var interval by intValue("Interval", 50, 20..500)

	private var lastWalkTime = 0L

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		val pk = packet as? PlayerAuthInputPacket ?: return@handle

		val now = System.currentTimeMillis()
		if (now - lastWalkTime < interval.toLong()) return@handle
		lastWalkTime = now

		val yaw = Math.toRadians(pk.rotation.y.toDouble())
		ModuleCombat.motion(
			session,
			(-sin(yaw) * walkSpeed).toFloat(),
			fallSpeed,
			(cos(yaw) * walkSpeed).toFloat()
		)
	}
}
