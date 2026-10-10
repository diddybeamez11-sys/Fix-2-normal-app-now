package dev.sora.protohax.relay.modules

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.cheat.module.EventModuleToggle
import dev.sora.relay.cheat.module.impl.movement.ModuleFly
import dev.sora.relay.game.event.EventDisconnect
import dev.sora.relay.game.event.EventPacketInbound
import dev.sora.relay.game.event.EventPacketOutbound
import dev.sora.relay.session.MinecraftRelaySession
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.RequestAbilityPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Client-side motion fly for the Android relay. This does not alter server-side movement rules. */
class ModuleMotionFly : CheatModule("MotionFly", CheatCategory.MOVEMENT) {

    private var horizontalSpeed by floatValue("Horizontal Speed", 3.5f, 0.5f..10f)
    private var verticalSpeed by floatValue("Vertical Speed", 1.5f, 0.5f..5f)
    private var glideSpeed by floatValue("Glide Speed", 0.1f, -0.01f..1f)
    private var motionInterval by floatValue("Delay", 50f, 10f..100f)
    // ProtoHax provides shortcuts in the overlay UI; there is no module-level Shortcut setting.
    private var antiRubberband by boolValue("Anti Rubber-band", true)
    private var rubberbandMaxStep by floatValue("Max Step", 0.9f, 0.3f..2f)
    private var rubberbandDelayMs by intValue("Step Delay (ms)", 12, 2..40)
    private var bypassMode by boolValue("Lifeboat Bypass", true)
    private var jitterEnabled by boolValue("Jitter", true)
    private var jitterIntensity by floatValue("Jitter Intensity", 0.05f, 0.01f..0.2f)

    private var lastMotionTime = 0L
    private var jitterState = false
    @Volatile private var lastSession: MinecraftRelaySession? = null
    @Volatile private var serverAbilities: UpdateAbilitiesPacket? = null
    @Volatile private var canFly = false
    private var correctionTime = 0L

    override fun onEnable() {
        resetState()
        // Both modules spoof abilities and motion; only one should control the player.
        moduleManager.modules.filterIsInstance<ModuleFly>().firstOrNull()?.let {
            if (it.state) it.state = false
        }
    }

    override fun onDisable() {
        // Restore the server's own abilities when possible rather than leaving fake flight enabled.
        if (canFly) {
            lastSession?.takeIf { it.peer.channel.isActive }?.let { relay ->
                relay.inboundPacket(serverAbilities ?: abilitiesPacket(false, session.player.uniqueEntityId))
            }
        }
        resetState()
    }

    private fun resetState() {
        lastMotionTime = 0L
        correctionTime = 0L
        jitterState = false
        canFly = false
        lastSession = null
        serverAbilities = null
    }

    private val handleDisconnect = handle<EventDisconnect> {
        resetState()
    }

    private val handleFlyToggle = handle<EventModuleToggle> {
        if (module is ModuleFly && targetState) this@ModuleMotionFly.state = false
    }

    private val handleInbound = handle<EventPacketInbound> {
        val relay = session.netSession
        if (lastSession !== relay) {
            resetState()
            lastSession = relay
        }
        when (val inbound = packet) {
            is StartGamePacket -> {
                // A new player/world needs fresh entity ids and server ability state.
                resetState()
                lastSession = relay
            }
            is UpdateAbilitiesPacket -> {
                if (session.player.uniqueEntityId != 0L &&
                    inbound.uniqueEntityId == session.player.uniqueEntityId) {
                    serverAbilities = inbound
                    if (canFly) {
                        // This packet is being forwarded after this handler; don't let it undo flight.
                        cancel()
                        session.netSession.inboundPacket(abilitiesPacket(true, inbound.uniqueEntityId))
                    }
                }
            }
            is CorrectPlayerMovePredictionPacket -> {
                // A server correction is a signal to briefly stop pushing client-side motion.
                correctionTime = System.currentTimeMillis()
            }
        }
    }

    private val handleOutbound = handle<EventPacketOutbound> {
        val abilityRequest = packet as? RequestAbilityPacket
        if (abilityRequest?.ability == Ability.FLYING) {
            // The fake ability belongs only to the local game, not the server.
            cancel()
            return@handle
        }
        val input = packet as? PlayerAuthInputPacket ?: return@handle
        val relay = session.netSession
        val player = session.player
        if (player.uniqueEntityId == 0L || player.runtimeEntityId == 0L) return@handle

        if (lastSession !== relay) {
            resetState()
            lastSession = relay
        }
        if (!canFly) {
            relay.inboundPacket(abilitiesPacket(true, player.uniqueEntityId))
            canFly = true
        }

        val now = System.currentTimeMillis()
        if (now - lastMotionTime < motionInterval.toLong()) return@handle
        if (antiRubberband && correctionTime != 0L && now - correctionTime < rubberbandDelayMs * 10L) return@handle
        if (antiRubberband && now - lastMotionTime < rubberbandDelayMs) return@handle

        val vertical = when {
            PlayerAuthInputData.WANT_UP in input.inputData -> verticalSpeed
            PlayerAuthInputData.WANT_DOWN in input.inputData -> -verticalSpeed
            bypassMode -> -glideSpeed.coerceAtLeast(-0.1f)
            else -> glideSpeed
        }

        val yaw = Math.toRadians(input.rotation.y.toDouble()).toFloat()
        val strafe = input.motion.x * horizontalSpeed
        val forward = input.motion.y * horizontalSpeed
        var x = strafe * cos(yaw) - forward * sin(yaw)
        var z = forward * cos(yaw) + strafe * sin(yaw)
        var y = vertical + if (jitterEnabled) (if (jitterState) jitterIntensity else -jitterIntensity) else 0f

        if (antiRubberband) {
            // Bound each client-side impulse. This cannot guarantee that a server will accept
            // movement, but avoids sending large velocity spikes after every input packet.
            val length = sqrt(x * x + y * y + z * z)
            if (length > rubberbandMaxStep) {
                val scale = rubberbandMaxStep / length
                x *= scale
                y *= scale
                z *= scale
            }
        }

        relay.inboundPacket(SetEntityMotionPacket().apply {
            runtimeEntityId = player.runtimeEntityId
            motion = Vector3f.from(x, y, z)
        })
        jitterState = !jitterState
        lastMotionTime = now
    }

    private fun abilitiesPacket(enabled: Boolean, entityId: Long) = UpdateAbilitiesPacket().apply {
        uniqueEntityId = entityId
        playerPermission = if (enabled) PlayerPermission.OPERATOR else PlayerPermission.VISITOR
        commandPermission = if (enabled) CommandPermission.OWNER else CommandPermission.ANY
        abilityLayers.add(AbilityLayer().apply {
            layerType = AbilityLayer.Type.BASE
            abilitiesSet.addAll(Ability.values())
            abilityValues.addAll(arrayOf(
                Ability.BUILD, Ability.MINE, Ability.DOORS_AND_SWITCHES,
                Ability.OPEN_CONTAINERS, Ability.ATTACK_PLAYERS, Ability.ATTACK_MOBS,
                Ability.OPERATOR_COMMANDS, Ability.FLY_SPEED, Ability.WALK_SPEED
            ))
            if (enabled) {
                abilityValues.add(Ability.MAY_FLY)
                abilityValues.add(Ability.FLYING)
            }
            walkSpeed = 0.1f
            flySpeed = if (enabled) 0.5f else 0.05f
        })
    }
}
