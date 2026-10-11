package dev.sora.protohax.relay.modules.misc

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket

/**
 * Ported from WClient's `FakeDeathModule`.
 *
 * Plays the death animation and the respawn screen for the local client only. The server
 * sees a player standing perfectly still, which is enough to end most fights - people
 * stop hitting a corpse and start looking for the loot.
 */
class ModuleFakeDeath : CheatModule("FakeDeath", CheatCategory.MISC) {

	private var intervalValue by intValue("Interval", 2000, 500..10000)

	private var lastDeathEffect = 0L
	private var isDead = false

	override fun onEnable() {
		super.onEnable()
		isDead = false
		lastDeathEffect = 0L
	}

	override fun onDisable() {
		super.onDisable()
		if (isDead) {
			respawn(RespawnPacket.State.SERVER_READY)
			respawn(RespawnPacket.State.CLIENT_READY)
			isDead = false
		}
	}

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		if (packet !is PlayerAuthInputPacket) return@handle

		val now = System.currentTimeMillis()
		if (isDead || now - lastDeathEffect < intervalValue.toLong()) return@handle
		lastDeathEffect = now
		isDead = true

		val player = session.player
		val death = EntityEventPacket()
		death.runtimeEntityId = player.runtimeEntityId
		death.type = EntityEventType.DEATH
		death.data = 0
		runCatching { session.sendPacketToClient(death) }

		respawn(RespawnPacket.State.SERVER_SEARCHING)
	}

	private fun respawn(state: RespawnPacket.State) {
		val player = session.player
		val packet = RespawnPacket()
		packet.position = Vector3f.from(player.posX, player.posY, player.posZ)
		packet.state = state
		packet.runtimeEntityId = player.runtimeEntityId
		runCatching { session.sendPacketToClient(packet) }
	}
}
