package dev.sora.protohax.relay.modules.combat

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket

/**
 * Ported from WClient's `HotbarSwitcherModule`.
 *
 * Walks the hotbar on a timer. Useful on its own and as the "always show a full row of
 * totems" trick on servers that read the selected slot.
 */
class ModuleHotbarSwitcher : CheatModule("HotbarSwitcher", CheatCategory.COMBAT) {

	private var startSlot by intValue("StartSlot", 0, 0..8)
	private var endSlot by intValue("EndSlot", 8, 0..8)
	private var switchDelay by intValue("Delay", 100, 10..1000)
	private var loopMode by boolValue("Loop", true)
	private var reverseMode by boolValue("Reverse", false)

	private var currentSlot = 0
	private var lastSwitchTime = 0L
	private var direction = 1

	override fun onEnable() {
		super.onEnable()
		currentSlot = startSlot
		direction = if (reverseMode) -1 else 1
		lastSwitchTime = System.currentTimeMillis()
		switchToSlot(currentSlot)
	}

	override fun onDisable() {
		super.onDisable()
		currentSlot = startSlot
	}

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		if (packet !is PlayerAuthInputPacket) return@handle

		val now = System.currentTimeMillis()
		if (now - lastSwitchTime < switchDelay.toLong()) return@handle
		lastSwitchTime = now

		currentSlot += direction
		if (currentSlot > endSlot) {
			currentSlot = if (loopMode) startSlot else endSlot
			if (!loopMode) direction = -1
		} else if (currentSlot < startSlot) {
			currentSlot = if (loopMode) endSlot else startSlot
			if (!loopMode) direction = 1
		}

		switchToSlot(currentSlot)
	}

	private fun switchToSlot(slot: Int) {
		val packet = PlayerHotbarPacket()
		packet.selectedHotbarSlot = slot.coerceIn(0, 8)
		packet.containerId = 0
		packet.setSelectHotbarSlot(true)
		session.sendPacketToClient(packet)
	}
}
