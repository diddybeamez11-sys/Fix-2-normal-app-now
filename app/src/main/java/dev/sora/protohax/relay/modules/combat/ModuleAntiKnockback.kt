package dev.sora.protohax.relay.modules.combat

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.cheat.value.NamedChoice
import dev.sora.relay.game.event.EventPacketInbound
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket

/**
 * Ported from WClient's `AntiKnockbackModule`.
 *
 * Knockback reaches the client as a `SetEntityMotionPacket`; dropping it - or scaling it
 * down - is all it takes to stand still in a fight, and because the server never learns
 * that the packet was dropped there is nothing for an anti-cheat to correlate.
 */
class ModuleAntiKnockback : CheatModule("AntiKnockback", CheatCategory.COMBAT) {

	private var modeValue by listValue("Mode", Mode.values(), Mode.CANCEL)
	private var horizontalValue by intValue("Horizontal", 0, 0..100)
	private var verticalValue by intValue("Vertical", 0, 0..100)

	private enum class Mode(override val choiceName: String) : NamedChoice {
		CANCEL("Cancel"),
		CUSTOM("Custom")
	}

	private val handlePacketInbound = handle<EventPacketInbound> {
		val pk = packet
		if (pk !is SetEntityMotionPacket) return@handle
		if (pk.runtimeEntityId != session.player.runtimeEntityId) return@handle

		if (modeValue == Mode.CANCEL) {
			cancel()
			return@handle
		}

		val horizontal = horizontalValue / 100f
		val vertical = verticalValue / 100f
		if (horizontal <= 0f && vertical <= 0f) {
			cancel()
			return@handle
		}

		val motion = pk.motion
		pk.motion = Vector3f.from(motion.x * horizontal, motion.y * vertical, motion.z * horizontal)
	}
}
