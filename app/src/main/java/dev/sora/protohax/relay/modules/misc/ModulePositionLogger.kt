package dev.sora.protohax.relay.modules.misc

import dev.sora.protohax.MyApplication
import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketOutbound
import dev.sora.relay.utils.logInfo
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ported from WClient's `PositionLoggerModule`.
 *
 * Writes the player's coordinates to `logs/positions.log` in the app's external files.
 * The classic use is finding a base you have been dragged to, or retracing a route after
 * a death; the file is plain text so anything can read it.
 */
class ModulePositionLogger : CheatModule("PositionLogger", CheatCategory.MISC) {

	private var intervalValue by intValue("Interval", 5000, 500..60000)
	private var logToApp by boolValue("LogToApp", false)

	private var lastLogTime = 0L

	private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		if (packet !is PlayerAuthInputPacket) return@handle

		val now = System.currentTimeMillis()
		if (now - lastLogTime < intervalValue.toLong()) return@handle
		lastLogTime = now

		val player = session.player
		val line = "${stampFormat.format(Date(now))}  " +
			"x=${"%.2f".format(Locale.US, player.posX)} " +
			"y=${"%.2f".format(Locale.US, player.posY)} " +
			"z=${"%.2f".format(Locale.US, player.posZ)}"

		if (logToApp) logInfo("[PositionLogger] $line")

		runCatching { logFile()?.appendText("$line\n") }
	}

	private fun logFile(): File? {
		val context = MyApplication.instance
		val dir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
		if (!dir.exists() && !dir.mkdirs()) return null
		return File(dir, "positions.log")
	}
}
