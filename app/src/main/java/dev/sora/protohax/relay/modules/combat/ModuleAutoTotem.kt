package dev.sora.protohax.relay.modules.combat

import dev.sora.protohax.relay.modules.util.ModuleCombat
import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketOutbound
import dev.sora.relay.game.inventory.PlayerInventory
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Ported from WClient's `AutoTotemModule`.
 *
 * Keeps a totem in the offhand by moving one out of the inventory the moment the slot is
 * empty - the single most important habit on an anarchy server, and one that is easy to
 * forget in a fight.
 */
class ModuleAutoTotem : CheatModule("AutoTotem", CheatCategory.COMBAT) {

	private var delayValue by intValue("Delay", 100, 0..1000)
	private var onlyWhenLowHealth by boolValue("OnlyWhenLow", false)
	private var healthThreshold by intValue("HealthThreshold", 10, 1..20)
	private var replaceOffhand by boolValue("ReplaceOffhand", true)

	private var lastTotemTime = 0L

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		if (packet !is PlayerAuthInputPacket) return@handle

		val now = System.currentTimeMillis()
		if (now - lastTotemTime < delayValue.toLong()) return@handle

		val inventory = session.player.inventory

		if (onlyWhenLowHealth && ModuleCombat.health(session) > healthThreshold) return@handle
		if (isTotem(inventory.offhand)) return@handle

		val slot = findTotemSlot(inventory) ?: return@handle
		if (!replaceOffhand && !inventory.offhand.isNull()) return@handle

		runCatching { inventory.moveItem(slot, PlayerInventory.SLOT_OFFHAND, inventory, session) }
		lastTotemTime = now
	}

	private fun isTotem(item: ItemData): Boolean =
		!item.isNull() && item.definition?.identifier == TOTEM

	private fun findTotemSlot(inventory: PlayerInventory): Int? {
		val content = inventory.content
		for (slot in content.indices) {
			if (isTotem(content[slot])) return slot
		}
		return null
	}

	companion object {
		private const val TOTEM = "minecraft:totem_of_undying"
	}
}
