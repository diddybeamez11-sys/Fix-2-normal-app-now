package dev.sora.protohax.relay

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import club.fdpclient.club.script.ScriptManager
import club.fdpclient.club.script.ScriptManagerFileSystem
import dev.sora.protohax.MyApplication
import dev.sora.protohax.relay.modules.ModuleESP
import dev.sora.protohax.relay.netty.channel.NativeRakConfig
import dev.sora.protohax.relay.netty.channel.NativeRakServerChannel
import dev.sora.protohax.relay.service.AppService
import dev.sora.protohax.ui.components.screen.settings.Settings
import dev.sora.protohax.ui.overlay.ConfigSectionShortcut
import dev.sora.protohax.ui.overlay.hud.HudManager
import dev.sora.protohax.util.ContextUtils.toast
import dev.sora.relay.MinecraftRelayListener
import dev.sora.relay.cheat.command.CommandManager
import dev.sora.relay.cheat.command.impl.CommandDownloadWorld
import dev.sora.relay.cheat.config.ConfigManagerFileSystem
import dev.sora.relay.cheat.config.section.ConfigSectionModule
import dev.sora.relay.cheat.module.ModuleManager
import dev.sora.relay.cheat.module.impl.misc.ModuleResourcePackSpoof
import dev.sora.relay.game.GameSession
import dev.sora.relay.session.MinecraftRelaySession
import dev.sora.relay.session.listener.RelayListenerAutoCodec
import dev.sora.relay.session.listener.RelayListenerEncryptedSession
import dev.sora.relay.session.listener.xbox.RelayListenerXboxLogin
import dev.sora.relay.session.listener.xbox.cache.XboxIdentityTokenCacheFileSystem
import dev.sora.relay.utils.logError
import dev.sora.relay.utils.logInfo
import io.netty.channel.Channel
import io.netty.channel.ChannelFactory
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.ServerChannel
import org.cloudburstmc.netty.channel.raknet.RakReliability
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread


object MinecraftRelay {

	private const val DROPPED_PACKET_WATCHDOG = "protohax-dropped-packet-watchdog"

    private var relay: Relay? = null

    val session = GameSession()
	lateinit var scriptManager: ScriptManager
	lateinit var scriptFileManager: ScriptManagerFileSystem
    val moduleManager: ModuleManager
    val configManager: ConfigManagerFileSystem
	val hudManager: HudManager

	val tokenCacheFile = XboxAuthWarmUp.tokenCacheFile

	var loaderThread: Thread? = null

    init {
        moduleManager = ModuleManager(session)
		hudManager = HudManager(session)

		// load asynchronously
		loaderThread = thread {
			try {
				moduleManager.init()
				registerAdditionalModules(moduleManager)
				MyApplication.instance.getExternalFilesDir("resource_packs")?.also {
					if (!it.exists()) it.mkdirs()
					ModuleResourcePackSpoof.resourcePackProvider = ModuleResourcePackSpoof.FileSystemResourcePackProvider(it)
				}

				if (Settings.enableCommandManager.getValue(MyApplication.instance)) {
					// command manager will register listener itself
					val commandManager = CommandManager(session)
					commandManager.init(moduleManager)
					MyApplication.instance.getExternalFilesDir("downloaded_worlds")?.also {
						commandManager.registerCommand(CommandDownloadWorld(session.eventManager, it))
					}
				}
				scriptManager = ScriptManager()
				scriptFileManager = ScriptManagerFileSystem(
					externalFilesDir("scripts"),
					".lua",
					scriptManager
				)
				Handler(Looper.getMainLooper()).post(Runnable {
					Toast.makeText(
						MyApplication.instance,
						"[Script] loading ${scriptFileManager.listScript().size} scripts",
						Toast.LENGTH_LONG
					).show()
				})
				for (s in scriptFileManager.listScript()) {
					scriptFileManager.loadScript(s)
				}
			} catch (t: Throwable) {
				// Loading modules/scripts must never take the whole app down nor prevent the
				// relay from starting, so failures are only logged.
				logError("load modules", t)
			} finally {
				// clean-up
				loaderThread = null
			}
		}

        configManager = ConfigManagerFileSystem(externalFilesDir("configs"), ".json").also {
			it.addSection(ConfigSectionModule(moduleManager))
			it.addSection(ConfigSectionShortcut(MyApplication.overlayManager))
			it.addSection(hudManager)
		}
    }

	/**
	 * Returns the app specific external directory, falling back to the internal one when the
	 * external storage is not available (getExternalFilesDir returns null in that case).
	 */
	private fun externalFilesDir(name: String): File {
		val dir = MyApplication.instance.getExternalFilesDir(name)
			?: File(MyApplication.instance.filesDir, name)
		if (!dir.exists()) dir.mkdirs()
		return dir
	}

    private fun registerAdditionalModules(moduleManager: ModuleManager) {
		moduleManager.registerModule(ModuleESP())
	}

    private fun constructRelay(): Relay {
        return Relay(object : MinecraftRelayListener {
            override fun onSessionCreation(session: MinecraftRelaySession): InetSocketAddress {
                // add listeners
                watchDroppedPackets(session.peer.channel, "the game")
                closeServerConnectionGroup(session)
                // This must be first: NetworkSettings is consumed by RelayListenerCompression, which
                // stops listener dispatch. Tracing first ensures that the server's initial packet is
                // recorded and installs the server write watchdog before the game's Login is sent.
                session.listeners.add(RelayListenerLoginTrace(session))
                session.listeners.add(RelayListenerCompression(session))
                session.listeners.add(RelayListenerAutoCodec(session))
                this@MinecraftRelay.session.netSession = session
                session.listeners.add(this@MinecraftRelay.session)

                val sessionEncryptor = if (Settings.offlineSessionEncryption.getValue(MyApplication.instance) && AccountManager.currentAccount == null) {
					RelayListenerEncryptedSession()
				} else {
					AccountManager.currentAccount?.let { account ->
						logInfo("logged in as ${account.remark}")
						RelayListenerXboxLogin({
							account.refresh()
						}, account.platform).also {
							it.tokenCache = XboxIdentityTokenCacheFileSystem(tokenCacheFile, account.remark)
						}
					}
				}
                sessionEncryptor?.let {
                    it.session = session
                    session.listeners.add(it)
                }

                // Must run after the encryptor above: the encryptor drops the authentication type when it
                // rewrites the game's login packet, and this listener re-attaches it before the packet is
                // forwarded to the server (protocol 818+ cannot encode a login without one).
                session.listeners.add(RelayListenerLoginAuthType())

                // resolve original ip and pass to relay client
                val address = session.peer.channel.config().getOption(NativeRakConfig.RAK_NATIVE_TARGET_ADDRESS)
                logInfo("SessionCreation $address")
				return address
            }
        })
    }

	/**
	 * Shuts the event loop group of the connection to the server down once the session is over.
	 *
	 * ProtoHax creates one NioEventLoopGroup per session for that connection (MinecraftRelay
	 * .BedrockRelayInitializer.createSession0) and never shuts it down, so every join leaked about
	 * three threads - they stayed parked for as long as the app was running. The group belongs to
	 * this session alone; the group of the game connection is the one of the relay server and must
	 * stay up, so only the former is terminated here.
	 */
	private fun closeServerConnectionGroup(session: MinecraftRelaySession) {
		session.peer.channel.closeFuture().addListener(ChannelFutureListener {
			val group = session.client?.peer?.channel?.eventLoop()?.parent()
			if (group != null && !group.isShuttingDown) {
				group.shutdownGracefully(200L, 3000L, TimeUnit.MILLISECONDS)
			}
		})
	}

	/**
	 * Logs packets the netty pipeline of a connection fails to encode or to send.
	 *
	 * `BedrockPeer.sendPacketImmediately` ignores the write promise and `BedrockPeer.flushPacketQueue`
	 * drops the promise of every queued packet altogether (`channel.write(wrapper)` without looking at
	 * the future), so an exception thrown by one of the encoders does not reach anybody: the packet is
	 * silently dropped and the other side waits for an answer that never arrives until the connection
	 * times out. The protocol library does report such a failure, but only through
	 * `log.debug("Error encoding packet {}", ...)`, which a release build of this app threw away - and
	 * the connection to the server had no watchdog at all. Those are exactly the failures that are worth
	 * a bug report, hence this handler. It is added last, which for outbound messages means first:
	 * writes travel from the tail of the pipeline to its head, so the handler sees the result of every
	 * encoder below it.
	 *
	 * It also reports what the pipeline throws: netty passes an inbound decode failure to
	 * `exceptionCaught` of the handler that failed and from there towards the tail, so a handler at the
	 * tail sees it. (An outbound encoder failure does not travel that way - `MessageToMessageEncoder`
	 * fails the write promise instead - which is why both are needed.)
	 *
	 * @param side how to name the connection in the log ("the game" / "the server")
	 */
	fun watchDroppedPackets(channel: Channel, side: String) {
		val pipeline = channel.pipeline()
		if (pipeline.get(DROPPED_PACKET_WATCHDOG) != null) return
		pipeline.addLast(DROPPED_PACKET_WATCHDOG, object : ChannelOutboundHandlerAdapter() {
			override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
				if (!promise.isVoid) {
					promise.addListener(ChannelFutureListener { future ->
						if (future.isSuccess) {
							// nothing to report
						} else if (future.channel().isActive) {
							logError("packet to $side could not be sent", future.cause())
						} else {
							// the session ended before the packet left: expected, not a defect
							logInfo("packet to $side dropped, the connection is closed: ${future.cause()}")
						}
					})
				}
				ctx.write(msg, promise)
			}

			override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
				logError("the connection to $side hit a pipeline error (a packet it could not encode or decode)", cause)
				ctx.fireExceptionCaught(cause)
			}
		})
	}

	fun updateReliability() {
		relay?.optionReliability = if (Settings.enableRakReliability.getValue(MyApplication.instance))
			RakReliability.RELIABLE_ORDERED else RakReliability.RELIABLE
	}

	fun announceRelayUp() {
		if (relay == null) {
			relay = constructRelay()
			updateReliability()
		}
		loaderThread?.join()
		if (!relay!!.isRunning) {
			relay!!.bind(InetSocketAddress("0.0.0.0", 1337))
			logInfo("relay started")
		}

		// ProtoHax runs the whole Xbox Live login inside the packet pipeline, on the login packet of the
		// game. Doing it here as well - while the user is still starting the game - keeps that login packet
		// fast (the identity token is cached, the TLS connections are warm) and reports a failure instead of
		// leaving the user in the game's "connection lost" screen.
		XboxAuthWarmUp.warmUp("relay started", notifyUser = true)
	}

	class Relay(listener: MinecraftRelayListener) : dev.sora.relay.MinecraftRelay(listener) {

		override fun channelFactory(): ChannelFactory<out ServerChannel> {
			return ChannelFactory {
				NativeRakServerChannel()
			}
		}
	}
}
