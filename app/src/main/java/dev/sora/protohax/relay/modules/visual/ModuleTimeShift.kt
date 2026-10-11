package dev.sora.protohax.relay.modules.visual

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetTimePacket

/**
 * Ported from WClient's `TimeShiftModule`.
 *
 * Pins the client's clock to a fixed time of day (6000 is noon). Purely a rendering
 * change: the server keeps its own time, so mobs still spawn on the real schedule.
 */
class ModuleTimeShift : CheatModule("TimeShift", CheatCategory.VISUAL) {

	private var timeValue by intValue("Time", 6000, 0..24000)
	private var intervalValue by intValue("Interval", 100, 20..2000)

	private var lastTimeUpdate = 0L

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		if (packet !is PlayerAuthInputPacket) return@handle

		val now = System.currentTimeMillis()
		if (now - lastTimeUpdate < intervalValue.toLong()) return@handle
		lastTimeUpdate = now

		val packet = SetTimePacket()
		packet.time = timeValue
		runCatching { session.sendPacketToClient(packet) }
	}

	override fun onDisable() {
		super.onDisable()
		runCatching {
			val packet = SetTimePacket()
			packet.time = 0
			session.sendPacketToClient(packet)
		}
	}
}
