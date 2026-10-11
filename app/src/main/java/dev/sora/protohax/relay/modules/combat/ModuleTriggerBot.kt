package dev.sora.protohax.relay.modules.combat

import dev.sora.protohax.relay.modules.util.ModuleCombat
import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Ported from WClient's `TriggerBotModule`.
 *
 * Attacks whatever the player is already looking at, so the aim is the player's own and
 * only the click is automated - the hardest kind of aura for a server to prove.
 */
class ModuleTriggerBot : CheatModule("TriggerBot", CheatCategory.COMBAT) {

	private var cpsValue by intValue("CPS", 12, 1..20)
	private var rangeValue by floatValue("Range", 4.0f, 2f..7f)
	private var angleValue by intValue("Angle", 20, 5..90)
	private var playersOnly by boolValue("PlayersOnly", true)
	private var mobsOnly by boolValue("MobsOnly", false)
	private var antiBot by boolValue("AntiBot", true)

	private var lastAttackTime = 0L

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		if (packet !is PlayerAuthInputPacket) return@handle

		val now = System.currentTimeMillis()
		val minDelay = 1000L / cpsValue.coerceAtLeast(1)
		if (now - lastAttackTime < minDelay) return@handle

		val target = ModuleCombat.targets(
			session, moduleManager, rangeValue, playersOnly, mobsOnly, antiBot
		).firstOrNull { ModuleCombat.isLookingAt(session, it, angleValue.toDouble()) } ?: return@handle

		ModuleCombat.attack(session, target)
		lastAttackTime = now
	}
}
