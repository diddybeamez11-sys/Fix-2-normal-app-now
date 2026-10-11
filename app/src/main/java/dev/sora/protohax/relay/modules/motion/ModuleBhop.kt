package dev.sora.protohax.relay.modules.motion

import dev.sora.protohax.relay.modules.util.ModuleCombat
import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Ported from WClient's `BhopModule`.
 *
 * Pushes the player up every time it lands, which turns a sprint into a bunny hop. The
 * interval is deliberately coarse: a hop every tick is what makes a movement check
 * light up, one every few ticks looks like a player who simply holds jump.
 */
class ModuleBhop : CheatModule("Bhop", CheatCategory.MOVEMENT) {

	private var jumpHeight by floatValue("JumpHeight", 0.42f, 0.4f..3.0f)
	private var motionInterval by intValue("Interval", 120, 50..2000)
	private var times by intValue("Times", 1, 1..20)

	private var lastMotionTime = 0L

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		val pk = packet as? PlayerAuthInputPacket ?: return@handle
		if (!pk.inputData.contains(PlayerAuthInputData.VERTICAL_COLLISION)) return@handle

		val now = System.currentTimeMillis()
		if (now - lastMotionTime < motionInterval.toLong()) return@handle
		lastMotionTime = now

		val step = motionInterval / times.coerceAtLeast(1)
		val player = session.player
		val vertical = if ((now / step) % 2 == 0L) jumpHeight else -jumpHeight

		ModuleCombat.motion(session, player.motionX, vertical, player.motionZ)
	}
}
