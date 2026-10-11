package dev.sora.protohax.relay.modules.misc

import dev.sora.protohax.relay.modules.util.ModuleCombat
import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Ported from WClient's `AutoDisconnectModule`.
 *
 * Drops the connection the moment health falls to [healthThreshold]. On an anarchy server
 * that is worth more than a totem: a disconnect saves the inventory, whereas the second
 * death very often does not - by the time you run back, someone else is wearing it.
 */
class ModuleAutoDisconnect : CheatModule("AutoDisconnect", CheatCategory.MISC) {

	private var healthThreshold by intValue("HealthThreshold", 4, 1..20)
	private var checkDelay by intValue("Delay", 100, 0..1000)

	private var lastCheckTime = 0L
	private var disconnected = false

	override fun onEnable() {
		super.onEnable()
		disconnected = false
		lastCheckTime = 0L
	}

	override fun onDisable() {
		super.onDisable()
		disconnected = false
		lastCheckTime = 0L
	}

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		if (disconnected) return@handle
		if (packet !is PlayerAuthInputPacket) return@handle

		val now = System.currentTimeMillis()
		if (now - lastCheckTime < checkDelay.toLong()) return@handle
		lastCheckTime = now

		val health = ModuleCombat.health(session)
		if (health > healthThreshold) return@handle

		disconnected = true

		val packet = DisconnectPacket()
		packet.setKickMessage("AutoDisconnected at ${health.toInt()} HP")
		packet.setMessageSkipped(false)
		runCatching {
			session.sendPacketToClient(packet)
			session.sendPacket(packet)
		}

		state = false
	}
}
