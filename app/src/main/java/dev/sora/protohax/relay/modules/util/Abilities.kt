package dev.sora.protohax.relay.modules.util

import dev.sora.relay.game.GameSession
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket

/**
 * Builds the client-side `UpdateAbilitiesPacket` that the ability based modules need.
 *
 * The packet never reaches the server: it is injected into the stream towards the *game*,
 * which is why the server - 2b2t included - keeps seeing a perfectly normal player while
 * the local client happily walks through walls or hovers. Every ability layer it builds
 * claims to be a plain survival layer plus [extra] abilities.
 */
object Abilities {

	/** The abilities a vanilla survival layer always carries. */
	private val BASE_ABILITIES = arrayOf(
		Ability.BUILD,
		Ability.MINE,
		Ability.DOORS_AND_SWITCHES,
		Ability.OPEN_CONTAINERS,
		Ability.ATTACK_PLAYERS,
		Ability.ATTACK_MOBS,
		Ability.OPERATOR_COMMANDS
	)

	fun build(
		session: GameSession,
		extra: Collection<Ability> = emptyList(),
		walkSpeed: Float = 0.1f,
		flySpeed: Float = 0.15f,
		verticalFlySpeed: Float = 0.15f
	): UpdateAbilitiesPacket {
		val layer = AbilityLayer()
		layer.layerType = AbilityLayer.Type.BASE
		layer.abilitiesSet.addAll(Ability.values())
		layer.abilityValues.addAll(BASE_ABILITIES)
		layer.abilityValues.addAll(extra)
		layer.walkSpeed = walkSpeed
		layer.flySpeed = flySpeed
		layer.verticalFlySpeed = verticalFlySpeed

		val packet = UpdateAbilitiesPacket()
		packet.playerPermission = PlayerPermission.OPERATOR
		packet.commandPermission = CommandPermission.OWNER
		packet.uniqueEntityId = session.player.uniqueEntityId
		packet.abilityLayers.add(layer)
		return packet
	}
}
