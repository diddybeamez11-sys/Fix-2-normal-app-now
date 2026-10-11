package dev.sora.protohax.relay.modules.misc

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.cheat.value.NamedChoice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cloudburstmc.protocol.bedrock.packet.TextPacket

/**
 * Ported from WClient's `SpammerModule`.
 *
 * Sends a message on a timer. Renamed because the bundled `Spammer` module already uses
 * that name; this one adds the message modes WClient has.
 */
class ModuleChatSpam : CheatModule("ChatSpam", CheatCategory.MISC) {

	private var messageValue by stringValue("Message", "ProtoHax on top")
	private var delayValue by intValue("Delay", 1000, 50..10000)
	private var modeValue by listValue("Mode", SpamMode.values(), SpamMode.REPEAT)
	private var randomize by boolValue("Randomize", false)

	private var job: Job? = null

	private enum class SpamMode(override val choiceName: String) : NamedChoice {
		REPEAT("Repeat"),
		UPPERCASE("Uppercase"),
		LOWERCASE("Lowercase"),
		ALTERNATING("Alternating"),
		REVERSE("Reverse")
	}

	override fun onEnable() {
		super.onEnable()
		start()
	}

	override fun onDisable() {
		super.onDisable()
		job?.cancel()
		job = null
	}

	private fun start() {
		job?.cancel()
		job = runCatching {
			session.scope.launch {
				while (state) {
					runCatching { sendChat(buildMessage()) }
					delay(delayValue.toLong())
				}
			}
		}.getOrNull()
	}

	private fun sendChat(text: String) {
		val packet = TextPacket()
		packet.type = TextPacket.Type.CHAT
		packet.sourceName = ""
		packet.setMessage(text)
		packet.xuid = ""
		packet.platformChatId = ""
		packet.setNeedsTranslation(false)
		session.sendPacket(packet)
	}

	private fun buildMessage(): String {
		var message = messageValue
		if (randomize) message += SUFFIXES.random()

		return when (modeValue) {
			SpamMode.REPEAT -> message
			SpamMode.UPPERCASE -> message.uppercase()
			SpamMode.LOWERCASE -> message.lowercase()
			SpamMode.ALTERNATING -> message.mapIndexed { index, char ->
				if (index % 2 == 0) char.uppercaseChar() else char.lowercaseChar()
			}.joinToString("")
			SpamMode.REVERSE -> message.reversed()
			else -> message
		}
	}

	companion object {
		private val SUFFIXES = listOf("!", "!!", ".", "..", " :D", " :)", " xD", " lol", " gg", " ez")
	}
}
