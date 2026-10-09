package dev.sora.protohax.relay

import dev.sora.relay.session.MinecraftRelayPacketListener
import dev.sora.relay.session.MinecraftRelaySession
import dev.sora.relay.utils.logError
import dev.sora.relay.utils.logInfo
import io.netty.channel.Channel
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket

/**
 * Handles the server's `NetworkSettings` packet, which both switches the connection to a
 * compression algorithm and is the answer the game waits for before it sends its login.
 *
 * This replaces `dev.sora.relay.session.listener.RelayListenerNetworkSettings`, which did
 *
 * ```
 * session.client!!.setCompression(algorithm)   // relay <-> server
 * session.sendPacketImmediately(packet)        // relay  -> game
 * session.setCompression(algorithm)            // relay <-> game
 * ```
 *
 * from the event loop of the *server* connection - that is, from a thread that is not the event
 * loop of the game connection. Both of the last two calls act on the game connection:
 *
 *  * `sendPacketImmediately` is `channel.writeAndFlush(...)`, which from a foreign thread only
 *    *schedules* the write on the game connection's event loop, and
 *  * `setCompression` swaps the `CompressionCodec` of the very same pipeline through
 *    `pipeline.replace(...)`. A handler that is added from outside its event loop stays in state
 *    `ADD_PENDING` until that loop has run `callHandlerAdded`, and Netty *skips* a handler in that
 *    state (`AbstractChannelHandlerContext.invokeHandler()`), passing the message straight to the
 *    next one.
 *
 * So whenever the game connection's event loop picked the queued write up after the codec had been
 * swapped - a coin flip, the loop has to be woken from `select()` first - the batch went through
 * the pipeline *without any* compression codec, reached the frame codec with
 * `BedrockBatchWrapper.compressed == null` and was rejected there with
 * "Bedrock batch was not compressed". `sendPacketImmediately` does not look at the write promise,
 * so the packet was dropped without a trace: the game never learned the compression settings, sent
 * no login packet, and the session died with "disconnect.lost" once RakNet timed out, right after
 * the relay had logged "selected compression algorithm". An emulator test of eight consecutive
 * sessions lost three of them that way.
 *
 * Doing the send and the codec swap in one task *on the game connection's event loop* removes both
 * halves of the race: the write is encoded inline (still with the uncompressed codec the game
 * expects for this packet, exactly like the vanilla server) and only then is the codec replaced,
 * in place, without an `ADD_PENDING` window.
 */
class RelayListenerCompression(private val session: MinecraftRelaySession) : MinecraftRelayPacketListener {

    override fun onPacketInbound(packet: BedrockPacket): Boolean {
        if (packet !is NetworkSettingsPacket) {
            return true
        }

        val algorithm = packet.compressionAlgorithm
        logInfo("selected compression algorithm: $algorithm")

        val client = session.client
        if (client == null) {
            // cannot happen: inbound packets are read from the server connection, which only
            // exists once the client session has been created. Forward it rather than drop it.
            logError("network settings arrived without a server session")
            return true
        }

        val serverChannel = client.peer.channel
        val gameChannel = session.peer.channel

        // relay <-> server first: the game answers the packet below with its login packet, which is
        // forwarded to the server and has to be compressed by then.
        onEventLoop(serverChannel) {
            try {
                client.setCompression(algorithm)
                // relay <-> game: send the answer and swap the codec in one task, in this order.
                onEventLoop(gameChannel) {
                    try {
                        session.sendPacketImmediately(packet)
                        session.setCompression(algorithm)
                    } catch (t: Throwable) {
                        logError("apply compression (game)", t)
                    }
                }
            } catch (t: Throwable) {
                logError("apply compression (server)", t)
            }
        }

        // handled here, the packet must not be forwarded a second time
        return false
    }

    private fun onEventLoop(channel: Channel, block: () -> Unit) {
        val eventLoop = channel.eventLoop()
        if (eventLoop.inEventLoop()) {
            block()
        } else {
            eventLoop.execute(Runnable { block() })
        }
    }
}
