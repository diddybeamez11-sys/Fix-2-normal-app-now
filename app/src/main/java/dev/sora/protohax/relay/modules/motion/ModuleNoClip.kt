package dev.sora.protohax.relay.modules.motion

import org.cloudburstmc.protocol.bedrock.data.Ability

/**
 * Ported from WClient's `NoClipModule`.
 *
 * Phase: the client is told it may walk through blocks, so it stops colliding with them
 * and reports positions inside walls. Combined with the motion modules this is how the
 * walled-off stashes on an anarchy server get opened without breaking a single block.
 */
class ModuleNoClip : ModuleAbilityBase("NoClip", Ability.NO_CLIP, 0.15f)
