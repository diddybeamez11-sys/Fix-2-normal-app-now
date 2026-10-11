package dev.sora.protohax.relay.modules.combat

import dev.sora.protohax.relay.modules.util.ModuleCombat
import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.entity.Entity
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ported from WClient's `InfiniteAuraModule`.
 *
 * Rewrites the position the outgoing `PlayerAuthInputPacket` reports so the server sees
 * us standing right behind the target while the client keeps rendering us where we
 * actually are - a full position desync, which is why it reaches past normal range.
 *
 * A server that runs movement prediction will eventually rubber-band the player back;
 * [silentLagbacks] absorbs those corrections, [maxDistance] bounds how far the module is
 * willing to reach in the first place.
 */
class ModuleInfiniteAura : CheatModule("InfiniteAura", CheatCategory.COMBAT) {

	private var cpsValue by intValue("CPS", 12, 1..20)
	private var maxDistanceValue by floatValue("MaxDistance", 30f, 4f..80f)
	private var behindOffset by floatValue("BehindOffset", 2.0f, 0.5f..5.0f)
	private var playersOnly by boolValue("PlayersOnly", true)
	private var mobsOnly by boolValue("MobsOnly", false)
	private var antiBot by boolValue("AntiBot", true)
	private var silentLagbacks by boolValue("SilentLagbacks", true)

	private var lastAttackTime = 0L
	private var lastLagbackTime = 0L
	private var serverSidePos: Vector3f? = null

	override fun onEnable() {
		super.onEnable()
		serverSidePos = session.player.vec3Position
	}

	override fun onDisable() {
		super.onDisable()
		serverSidePos = null
	}

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		val pk = packet as? PlayerAuthInputPacket ?: return@handle

		val player = session.player
		val currentPos = player.vec3Position
		val now = System.currentTimeMillis()

		// A jump this large is the server pulling us back, not our own movement.
		val last = serverSidePos
		if (last != null && last.distance(currentPos) > 50f) {
			if (!silentLagbacks) {
				state = false
				return@handle
			}
			lastLagbackTime = now
		}
		if (now - lastLagbackTime < 100L) {
			serverSidePos = currentPos
			return@handle
		}

		val target = ModuleCombat.targets(
			session, moduleManager, maxDistanceValue, playersOnly, mobsOnly, antiBot
		).firstOrNull()

		if (target == null) {
			serverSidePos = currentPos
			return@handle
		}

		val behindPos = positionBehind(target)

		val aim = ModuleCombat.aimRotation(behindPos, target.vec3Position)

		serverSidePos = behindPos
		pk.position = behindPos
		pk.rotation = Vector3f.from(player.rotationPitch, aim.yaw, aim.yaw)

		// Reported from where the server now thinks we are, not from where we really are.
		if (now - lastAttackTime >= 1000L / cpsValue.coerceAtLeast(1)) {
			ModuleCombat.attack(session, target, behindPos)
			lastAttackTime = now
		}
	}

	/** A spot [behindOffset] blocks behind [entity], at the entity's feet. */
	private fun positionBehind(entity: Entity): Vector3f {
		val yawRad = Math.toRadians(entity.rotationYaw.toDouble() + 90.0)
		val feetY = entity.posY - 1.62f
		return Vector3f.from(
			entity.posX - cos(yawRad).toFloat() * behindOffset,
			feetY,
			entity.posZ - sin(yawRad).toFloat() * behindOffset
		)
	}
}
