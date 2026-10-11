package dev.sora.protohax.relay.modules.combat

import dev.sora.protohax.relay.modules.util.ModuleCombat
import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.entity.Entity
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket

/**
 * Ported from WClient's `HitboxModule`.
 *
 * Rewrites the width/height the *client* renders for entities around the player. The
 * server keeps its own, smaller boxes, so what actually lands the hit is still the
 * server's box - but every other client-side check (aim assist, ESP boxes, reach
 * helpers) sees the enlarged one, which makes the target much easier to click.
 */
class ModuleHitbox : CheatModule("Hitbox", CheatCategory.COMBAT) {

	private var widthValue by floatValue("Width", 1.5f, 0.5f..12f)
	private var heightValue by floatValue("Height", 1.5f, 0.5f..12f)
	private var rangeValue by floatValue("Range", 12f, 2f..40f)
	private var playersOnly by boolValue("PlayersOnly", true)
	private var mobsOnly by boolValue("MobsOnly", false)
	private var antiBot by boolValue("AntiBot", true)
	private var intervalValue by intValue("Interval", 1, 1..20)

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		if (packet !is PlayerAuthInputPacket) return@handle
		if (session.player.tickExists % intervalValue.coerceAtLeast(1).toLong() != 0L) return@handle

		ModuleCombat.targets(session, moduleManager, rangeValue, playersOnly, mobsOnly, antiBot)
			.forEach { resize(it, widthValue, heightValue) }
	}

	override fun onDisable() {
		super.onDisable()
		runCatching {
			ModuleCombat.targets(session, moduleManager, rangeValue, playersOnly, mobsOnly, antiBot)
				.forEach { resize(it, VANILLA_WIDTH, VANILLA_HEIGHT) }
		}
	}

	private fun resize(entity: Entity, width: Float, height: Float) {
		val metadata = EntityDataMap()
		metadata.put(EntityDataTypes.WIDTH, width)
		metadata.put(EntityDataTypes.HEIGHT, height)
		metadata.put(EntityDataTypes.SCALE, 1.0f)

		val packet = SetEntityDataPacket()
		packet.runtimeEntityId = entity.runtimeEntityId
		packet.metadata = metadata
		session.sendPacketToClient(packet)
	}

	companion object {
		private const val VANILLA_WIDTH = 0.6f
		private const val VANILLA_HEIGHT = 1.8f
	}
}
