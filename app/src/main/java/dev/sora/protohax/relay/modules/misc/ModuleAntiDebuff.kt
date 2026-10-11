package dev.sora.protohax.relay.modules.misc

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketInbound
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket

/**
 * Ported from WClient's `AntiDebuffModule`.
 *
 * Drops the harmful effects the server applies and answers with a matching removal, so
 * the client never renders the blindness / nausea / poison it was given. The server still
 * believes the effect is active, which is exactly the point: it keeps drinking its own
 * potions and the debuff does nothing here.
 */
class ModuleAntiDebuff : CheatModule("AntiDebuff", CheatCategory.MISC) {

	/**
	 * Bedrock effect ids of the effects worth removing - see
	 * `dev.sora.relay.game.entity.data.Effect`.
	 */
	private val badEffects = intArrayOf(
		dev.sora.relay.game.entity.data.Effect.POISON,
		dev.sora.relay.game.entity.data.Effect.WITHER,
		dev.sora.relay.game.entity.data.Effect.FATAL_POISON,
		dev.sora.relay.game.entity.data.Effect.BLINDNESS,
		dev.sora.relay.game.entity.data.Effect.NAUSEA,
		dev.sora.relay.game.entity.data.Effect.WEAKNESS,
		dev.sora.relay.game.entity.data.Effect.MINING_FATIGUE,
		dev.sora.relay.game.entity.data.Effect.HUNGER,
		dev.sora.relay.game.entity.data.Effect.INSTANT_DAMAGE,
		dev.sora.relay.game.entity.data.Effect.SLOWNESS,
		dev.sora.relay.game.entity.data.Effect.DARKNESS,
		dev.sora.relay.game.entity.data.Effect.BAD_OMEN
	)

	private val handlePacketInbound = handle<EventPacketInbound> {
		val pk = packet as? MobEffectPacket ?: return@handle
		if (pk.runtimeEntityId != session.player.runtimeEntityId) return@handle
		if (pk.event != MobEffectPacket.Event.ADD && pk.event != MobEffectPacket.Event.MODIFY) return@handle
		if (pk.effectId !in badEffects) return@handle

		cancel()

		val removal = MobEffectPacket()
		removal.runtimeEntityId = pk.runtimeEntityId
		removal.event = MobEffectPacket.Event.REMOVE
		removal.effectId = pk.effectId
		session.sendPacketToClient(removal)
	}
}
