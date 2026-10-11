package dev.sora.protohax.relay.modules.misc

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketOutbound
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Ported from WClient's `DesyncModule`.
 *
 * Holds on to the movement the game reports and only releases it when the module is
 * switched off, in small batches. While it is held the server still thinks the player is
 * standing where they were, which is what makes the stored movement usable as a teleport
 * back out of a bad spot - and what every "blink" style module is built on.
 *
 * Keep [maxPackets] low: the longer the hold, the more likely the server is to notice the
 * player stopped answering.
 */
class ModuleDesync : CheatModule("Desync", CheatCategory.MISC) {

	private var maxPackets by intValue("MaxPackets", 400, 20..5000)
	private var initialDelay by intValue("InitialDelay", 1000, 0..5000)
	private var resendDelay by intValue("ResendDelay", 100, 20..1000)

	private val storedPackets = ConcurrentLinkedQueue<PlayerAuthInputPacket>()
	private var flushJob: Job? = null

	override fun onEnable() {
		super.onEnable()
		flushJob?.cancel()
		flushJob = null
		storedPackets.clear()
	}

	override fun onDisable() {
		super.onDisable()
		flush()
	}

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		val pk = packet as? PlayerAuthInputPacket ?: return@handle

		// Overflowing the buffer would only make the eventual replay longer than any
		// server will tolerate, so the oldest packet is released to make room.
		while (storedPackets.size >= maxPackets) {
			val oldest = storedPackets.poll() ?: break
			runCatching { session.sendPacket(oldest) }
		}

		storedPackets.add(pk)
		cancel()
	}

	/** Replays everything that was held, off the packet pipeline. */
	private fun flush() {
		val pending = ArrayList(storedPackets)
		storedPackets.clear()
		if (pending.isEmpty()) return

		flushJob = runCatching {
			session.scope.launch {
				delay(initialDelay.toLong())
				for (packet in pending) {
					runCatching { session.sendPacket(packet) }
					delay(resendDelay.toLong())
				}
			}
		}.getOrNull()
	}
}
