package dev.sora.protohax.relay.modules.visual

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import org.cloudburstmc.protocol.bedrock.data.camera.CameraEase
import org.cloudburstmc.protocol.bedrock.data.camera.CameraFovInstruction
import org.cloudburstmc.protocol.bedrock.packet.CameraInstructionPacket

/**
 * Ported from WClient's `ZoomModule`, but instead of WClient's abilities trick it uses
 * the camera instruction the protocol actually provides for this: a `CameraInstructionPacket`
 * carrying a field-of-view instruction. Lower [fovValue] means more zoom; the default of
 * 30 is roughly a spyglass.
 *
 * The packet goes to the game only - the server is never told the camera moved.
 */
class ModuleZoom : CheatModule("Zoom", CheatCategory.VISUAL) {

	private var fovValue by floatValue("Fov", 30f, 5f..110f)
	private var easeTime by floatValue("EaseTime", 0.2f, 0f..2f)

	override fun onEnable() {
		super.onEnable()
		push(false)
	}

	override fun onDisable() {
		super.onDisable()
		push(true)
	}

	/**
	 * @param clear true to hand the field of view back to the game
	 */
	private fun push(clear: Boolean) {
		val fov = CameraFovInstruction()
		fov.setFov(fovValue)
		fov.setEaseTime(easeTime)
		fov.setEaseType(CameraEase.LINEAR)
		fov.setClear(clear)

		val packet = CameraInstructionPacket()
		packet.fovInstruction = fov

		runCatching { session.sendPacketToClient(packet) }
	}
}
