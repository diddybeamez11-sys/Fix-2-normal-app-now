package dev.sora.protohax.relay.modules.motion

import dev.sora.protohax.relay.modules.util.Abilities
import dev.sora.protohax.relay.modules.util.ModuleCombat
import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketInbound
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.RequestAbilityPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket

/**
 * Shared plumbing of the two ability modules, ported from WClient's `FlyModule` and
 * `NoClipModule`.
 *
 * Both work the same way, and it is the reason they survive on 2b2t: the server never
 * grants anything and never hears a request. The relay drops the abilities the server
 * sends down and the ability requests the game sends up, and feeds the game its own
 * `UpdateAbilitiesPacket` - operator permissions plus one extra ability. The server only
 * ever sees the resulting movement, which is exactly what an ordinary client that was
 * given fly by a plugin would produce.
 */
abstract class ModuleAbilityBase(
	name: String,
	private val ability: Ability,
	defaultSpeed: Float
) : CheatModule(name, CheatCategory.MOVEMENT) {

	private var speedValue by floatValue("Speed", defaultSpeed, 0.1f..1.5f)
	private var spoofAbilities by boolValue("SpoofAbilities", true)

	/** Whether the extra ability is currently pushed to the game. */
	private var applied = false

	override fun onEnable() {
		super.onEnable()
		applied = false
	}

	override fun onDisable() {
		super.onDisable()
		applied = false
		push(false)
	}

	/**
	 * Keeps the server's own abilities off the client while the module is on: whatever
	 * the server says, the game is told the state this module wants.
	 */
	private val handlePacketInbound = handle<EventPacketInbound> {
		val pk = packet as? UpdateAbilitiesPacket ?: return@handle
		if (!spoofAbilities) return@handle

		cancel()
		push(state)
	}

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		val pk = packet

		// The game asking the server for fly/no-clip would give the whole thing away.
		if (pk is RequestAbilityPacket && pk.ability == ability) {
			cancel()
			return@handle
		}

		if (pk !is PlayerAuthInputPacket) return@handle

		if (state) {
			if (!applied) {
				push(true)
				applied = true
			}
			applyVerticalMotion(pk)
		} else if (applied) {
			push(false)
			applied = false
		}
	}

	/** Up when jump is held, down when sneak is held. */
	private fun applyVerticalMotion(packet: PlayerAuthInputPacket) {
		val inputData = packet.inputData
		val vertical = when {
			inputData.contains(PlayerAuthInputData.JUMPING) -> speedValue
			inputData.contains(PlayerAuthInputData.SNEAKING) -> -speedValue
			else -> 0f
		}
		if (vertical == 0f) return

		ModuleCombat.motion(session, 0f, vertical, 0f)
	}

	/**
	 * Sends the abilities packet to the game only. [enabled] picks between the module's
	 * ability set and a plain survival one, so turning the module off really does take
	 * the ability away again.
	 */
	private fun push(enabled: Boolean) {
		val extra = if (enabled) listOf(ability, Ability.MAY_FLY, Ability.FLY_SPEED, Ability.WALK_SPEED) else emptyList()
		runCatching {
			session.sendPacketToClient(
				Abilities.build(
					session = session,
					extra = extra,
					walkSpeed = 0.1f,
					flySpeed = speedValue
				)
			)
		}
	}
}
