package dev.sora.protohax.relay.modules.motion

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Ported from WClient's `SprintModule`.
 *
 * Adds the sprint flags to every `PlayerAuthInputPacket` while it is on, and clears them
 * again on the way out so the server is not left with a permanently sprinting player.
 * Unlike the vanilla key it keeps sprinting through actions that normally cancel it.
 */
class ModuleSprint : CheatModule("Sprint", CheatCategory.MOVEMENT) {

	private var stopOnDisable by boolValue("StopOnDisable", true)

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		val pk = packet as? PlayerAuthInputPacket ?: return@handle

		val inputData = pk.inputData
		if (state) {
			inputData.add(PlayerAuthInputData.SPRINTING)
			inputData.add(PlayerAuthInputData.START_SPRINTING)
			inputData.remove(PlayerAuthInputData.STOP_SPRINTING)
		} else if (stopOnDisable) {
			inputData.remove(PlayerAuthInputData.SPRINTING)
			inputData.remove(PlayerAuthInputData.START_SPRINTING)
			if (!inputData.contains(PlayerAuthInputData.STOP_SPRINTING)) {
				inputData.add(PlayerAuthInputData.STOP_SPRINTING)
			}
		}
	}
}
