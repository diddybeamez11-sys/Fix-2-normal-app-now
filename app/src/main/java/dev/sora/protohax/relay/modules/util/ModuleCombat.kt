package dev.sora.protohax.relay.modules.util

import dev.sora.relay.cheat.module.ModuleManager
import dev.sora.relay.cheat.module.impl.combat.ModuleTargets
import dev.sora.relay.game.GameSession
import dev.sora.relay.game.entity.Entity
import dev.sora.relay.game.entity.EntityLocalPlayer
import dev.sora.relay.game.entity.EntityPlayer
import dev.sora.relay.game.entity.EntityUnknown
import dev.sora.relay.game.utils.Rotation
import dev.sora.relay.game.utils.constants.Attribute
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.SoundEvent
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelSoundEventPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Helpers shared by the modules ported from WClient.
 *
 * They are the ProtoHax counterparts of WClient's `LocalPlayer.attack()/swing()` and
 * `RotationUtils`: the same packet flows, written against `dev.sora.relay` instead of
 * WClient's own session model. Every helper takes the [GameSession] explicitly so a
 * CheatModule can use them from inside its event handlers.
 */
object ModuleCombat {

	// ---------------------------------------------------------------- targeting

	fun isMob(entity: Entity): Boolean =
		entity is EntityUnknown && entity.identifier in MobList.mobTypes

	/**
	 * Delegates the bot check to ProtoHax's own `Targets` module, so every combat module
	 * here shares one definition of "bot" - and one anti-bot setting - with the rest of
	 * the client.
	 */
	fun isBot(moduleManager: ModuleManager?, entity: EntityPlayer): Boolean {
		if (entity is EntityLocalPlayer) return false
		val targets = moduleManager?.getModule(ModuleTargets::class.java) ?: return false
		return with(targets) { entity.isBot() }
	}

	/**
	 * Collects attackable entities inside [range], nearest first.
	 *
	 * @param playersOnly target other players
	 * @param mobsOnly    target mobs
	 * @param antiBot     drop players the `Targets` module considers a bot
	 */
	fun targets(
		session: GameSession,
		moduleManager: ModuleManager?,
		range: Float,
		playersOnly: Boolean,
		mobsOnly: Boolean,
		antiBot: Boolean = true
	): List<Entity> {
		val player = session.player
		return session.level.entityMap.values
			.asSequence()
			.filter { it !== player }
			.filter { it.distance(player) <= range }
			.filter { entity ->
				when (entity) {
					is EntityLocalPlayer -> false
					is EntityPlayer -> playersOnly && (!antiBot || !isBot(moduleManager, entity))
					is EntityUnknown -> mobsOnly && isMob(entity)
					else -> false
				}
			}
			.sortedBy { it.distance(player) }
			.toList()
	}

	// ------------------------------------------------------------------ combat

	/** Swings the arm: the animation the server expects, plus the swish sound. */
	fun swing(session: GameSession) {
		val player = session.player

		val animate = AnimatePacket()
		animate.action = AnimatePacket.Action.SWING_ARM
		animate.runtimeEntityId = player.runtimeEntityId
		session.sendPacket(animate)
		session.sendPacketToClient(animate)

		val sound = LevelSoundEventPacket()
		sound.sound = SoundEvent.ATTACK_NODAMAGE
		sound.position = player.vec3Position
		sound.extraData = -1
		sound.identifier = "minecraft:player"
		sound.setBabySound(false)
		sound.setRelativeVolumeDisabled(false)
		session.sendPacket(sound)
		session.sendPacketToClient(sound)
	}

	/**
	 * Attacks [entity] with an `ITEM_USE_ON_ENTITY` transaction, as a vanilla client would.
	 *
	 * @param from the position to claim the swing came from. Modules that desync the
	 *             reported position ([ModuleInfiniteAura]) must pass the same position
	 *             they put in the input packet, or the server judges the hit against a
	 *             position it was never told about.
	 */
	fun attack(session: GameSession, entity: Entity, from: Vector3f = session.player.vec3Position) {
		val player = session.player
		val inventory = player.inventory

		swing(session)

		val transaction = InventoryTransactionPacket()
		transaction.transactionType = InventoryTransactionType.ITEM_USE_ON_ENTITY
		transaction.actionType = 1
		transaction.runtimeEntityId = entity.runtimeEntityId
		transaction.hotbarSlot = inventory.heldItemSlot
		transaction.itemInHand = inventory.hand
		transaction.playerPosition = from
		transaction.clickPosition = Vector3f.ZERO
		session.sendPacket(transaction)
	}

	// ----------------------------------------------------------------- aiming

	/** The rotation that looks from [from] at [to]. */
	fun aimRotation(from: Vector3f, to: Vector3f): Rotation {
		val dx = (to.x - from.x).toDouble()
		val dy = (to.y - from.y).toDouble()
		val dz = (to.z - from.z).toDouble()
		val horizontal = sqrt(dx * dx + dz * dz)
		val yaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
		val pitch = (-Math.toDegrees(atan2(dy, horizontal))).toFloat()
		return Rotation(yaw, pitch)
	}

	/**
	 * Aims the server-side rotation at [point] without moving the camera: ProtoHax swaps
	 * [EntityLocalPlayer.silentRotation] into the outgoing `PlayerAuthInputPacket`, so the
	 * server is told we look at the target while the screen stays where the player put it.
	 */
	fun silentAim(session: GameSession, point: Vector3f) {
		val player = session.player
		player.silentRotation = aimRotation(player.vec3Position, point)
	}

	/** True when the player's camera is within [maxAngle] degrees of [entity]'s body. */
	fun isLookingAt(session: GameSession, entity: Entity, maxAngle: Double = 20.0): Boolean {
		val player = session.player
		val bodyPoints = arrayOf(
			Vector3f.from(entity.posX, entity.posY, entity.posZ),
			Vector3f.from(entity.posX, entity.posY + 0.9f, entity.posZ),
			Vector3f.from(entity.posX, entity.posY + 1.8f, entity.posZ)
		)

		val yawRad = Math.toRadians(player.rotationYaw.toDouble() + 90.0)
		val pitchRad = Math.toRadians(-player.rotationPitch.toDouble())
		val lookX = cos(pitchRad) * cos(yawRad)
		val lookY = sin(pitchRad)
		val lookZ = cos(pitchRad) * sin(yawRad)

		for (target in bodyPoints) {
			val dx = (target.x - player.posX).toDouble()
			val dy = (target.y - player.posY).toDouble()
			val dz = (target.z - player.posZ).toDouble()
			val distance = sqrt(dx * dx + dy * dy + dz * dz)
			if (distance < 0.0001) return true

			val dot = (dx * lookX + dy * lookY + dz * lookZ) / distance
			val angle = Math.toDegrees(Math.acos(dot.coerceIn(-1.0, 1.0)))
			if (angle <= maxAngle) return true
		}
		return false
	}

	// ---------------------------------------------------------------- movement

	/**
	 * Pushes the local player by sending a `SetEntityMotionPacket` to the game only.
	 *
	 * Motion is one of the few things a Bedrock proxy can still set on the client without
	 * the server's permission: the client applies it locally and reports the resulting
	 * position with its next `PlayerAuthInputPacket`, which is what makes the motion
	 * modules below work on servers that never grant fly or speed.
	 */
	fun motion(session: GameSession, x: Float, y: Float, z: Float) {
		val packet = SetEntityMotionPacket()
		packet.runtimeEntityId = session.player.runtimeEntityId
		packet.motion = Vector3f.from(x, y, z)
		session.sendPacketToClient(packet)
	}

	// ------------------------------------------------------------------ stats

	/** Current health, assuming full health when the server never sent the attribute. */
	fun health(session: GameSession): Float =
		session.player.attributes[Attribute.HEALTH]?.value ?: 20f
}
