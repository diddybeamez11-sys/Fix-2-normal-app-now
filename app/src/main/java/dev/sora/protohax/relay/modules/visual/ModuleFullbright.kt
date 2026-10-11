package dev.sora.protohax.relay.modules.visual

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.entity.data.Effect
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Ported from WClient's `FullbrightModule`.
 *
 * Keeps a long, particle-less night vision effect alive on the client. Nothing is sent
 * to the server, so there is no effect for it to see - the caves and the night around a
 * base are simply lit.
 */
class ModuleFullbright : CheatModule("Fullbright", CheatCategory.VISUAL) {

	private var amplifierValue by intValue("Amplifier", 1, 1..5)
	private var intervalValue by intValue("Interval", 20, 5..200)

	private fun effect(event: MobEffectPacket.Event): MobEffectPacket {
		val packet = MobEffectPacket()
		packet.runtimeEntityId = session.player.runtimeEntityId
		packet.event = event
		packet.effectId = Effect.NIGHT_VISION
		packet.amplifier = amplifierValue
		packet.setParticles(false)
		packet.duration = 360000
		return packet
	}

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		if (packet !is PlayerAuthInputPacket) return@handle
		val interval = intervalValue.coerceAtLeast(1).toLong()
		if (session.player.tickExists % interval != 0L) return@handle

		runCatching { session.sendPacketToClient(effect(MobEffectPacket.Event.ADD)) }
	}

	override fun onDisable() {
		super.onDisable()
		runCatching { session.sendPacketToClient(effect(MobEffectPacket.Event.REMOVE)) }
	}
}
