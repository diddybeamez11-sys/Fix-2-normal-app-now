package dev.sora.protohax.relay.modules.motion

import dev.sora.protohax.relay.modules.util.ModuleCombat
import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.random.Random

/**
 * Ported from WClient's `AntiAFKModule`.
 *
 * 2b2t's queue only moves while the player is connected, and an idle player is a free
 * target. This nudges the player by a small random amount at a random-feeling interval,
 * which is enough to look alive to both.
 */
class ModuleAntiAFK : CheatModule("AntiAFK", CheatCategory.MOVEMENT) {

	private var intervalValue by intValue("Interval", 200, 50..1000)
	private var intensity by floatValue("Intensity", 0.5f, 0.1f..2.0f)

	private var lastGlitchTime = 0L

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		if (packet !is PlayerAuthInputPacket) return@handle

		val now = System.currentTimeMillis()
		if (now - lastGlitchTime < intervalValue.toLong()) return@handle
		lastGlitchTime = now

		val offset = intensity / 2f
		ModuleCombat.motion(
			session,
			(Random.nextFloat() - 0.5f) * offset,
			(Random.nextFloat() - 0.5f) * offset,
			(Random.nextFloat() - 0.5f) * offset
		)
	}

	override fun onDisable() {
		super.onDisable()
		runCatching { ModuleCombat.motion(session, 0f, 0f, 0f) }
	}
}
