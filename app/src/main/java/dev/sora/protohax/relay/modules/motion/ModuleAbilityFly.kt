package dev.sora.protohax.relay.modules.motion

import org.cloudburstmc.protocol.bedrock.data.Ability

/**
 * Ported from WClient's `FlyModule`.
 *
 * Creative-style flight without the server ever enabling it - see [ModuleAbilityBase].
 * Hold jump to climb and sneak to descend; the speed setting is how fast.
 */
class ModuleAbilityFly : ModuleAbilityBase("AbilityFly", Ability.FLYING, 0.15f)
