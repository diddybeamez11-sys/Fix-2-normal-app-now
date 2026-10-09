package dev.sora.protohax.relay.netty.channel

import io.netty.channel.AbstractServerChannel
import io.netty.channel.DefaultChannelConfig
import io.netty.channel.EventLoop
import libmitm.Libmitm
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import kotlin.concurrent.thread


class NativeRakServerChannel : AbstractServerChannel() {

	private val config = DefaultChannelConfig(this)

	@Volatile
	private var closed = false

	private var acceptor: Thread? = null

	override fun config() = config

	override fun isOpen(): Boolean {
		return true
	}

	override fun isActive(): Boolean {
//		return AppService.isActive
		return true
	}

	override fun isCompatible(p0: EventLoop): Boolean {
		return true
	}

	override fun localAddress0(): SocketAddress {
		return InetSocketAddress(InetAddress.getLocalHost(), 1337)
	}

	override fun doBind(p0: SocketAddress) {
	}

	override fun doClose() {
		closed = true
	}

	/**
	 * [Libmitm.pollConnection] blocks until the netstack hands over the next RakNet connection, so it must never run on
	 * this channel's event loop: the relay's ServerBootstrap uses a single group, which means the accepted child channels
	 * are registered on the very same loops. Every child that landed on the blocked loop stalled - its handshake, its
	 * packets and the replies to the game only moved once yet another connection arrived (verified on a device with
	 * a thread dump: the acceptor loop sat in Libmitm.pollConnection while sessions hung).
	 *
	 * A dedicated thread polls instead and only hands the connections over to the event loop.
	 */
	override fun doBeginRead() {
		if (acceptor != null) return

		acceptor = thread(name = "native-rak-acceptor", isDaemon = true) {
			while (!closed) {
				try {
					val rakConn = Libmitm.pollConnection()
					if (rakConn == null) {
						Thread.sleep(IDLE_BACKOFF_MS)
						continue
					}
					if (closed) {
						rakConn.close()
						break
					}
					eventLoop().execute {
						try {
							pipeline().fireChannelRead(NativeRakChannel(this@NativeRakServerChannel, rakConn))
						} catch (t: Throwable) {
							pipeline().fireExceptionCaught(t)
						}
					}
				} catch (t: Throwable) {
					if (closed) break
					try {
						eventLoop().execute { pipeline().fireExceptionCaught(t) }
						// do not spin if polling keeps failing (e.g. the netstack was shut down)
						Thread.sleep(ERROR_BACKOFF_MS)
					} catch (_: Throwable) {
						break
					}
				}
			}
		}
	}

	companion object {
		private const val IDLE_BACKOFF_MS = 20L
		private const val ERROR_BACKOFF_MS = 500L
	}
}
