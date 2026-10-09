package dev.sora.protohax.relay

import dev.sora.relay.session.MinecraftRelayPacketListener
import dev.sora.relay.session.MinecraftRelaySession
import dev.sora.relay.utils.logInfo
import io.netty.channel.Channel
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Names the packets of the login stage, in both directions, until the session is in the game.
 *
 * A reported session ended like this:
 *
 * ```
 * selected compression algorithm: ZLIB
 * token cache hit
 * login success
 * game connection closed: read raknet ...: use of closed network connection
 * client disconnect: disconnect.lost
 * server disconnect: disconnect.disconnected
 * ```
 *
 * "login success" is logged by `RelayListenerXboxLogin` once it has rewritten the game's login
 * packet - it does not mean the server accepted anything. After it, the relay has to hand that
 * packet to the server and the server has to answer it (PlayStatus LOGIN_SUCCESS, the encryption
 * handshake, ResourcePacksInfo, StartGame). None of that left a trace in the log, and the session
 * died about twenty seconds later when the game gave up waiting for the answer to its login.
 *
 * Two things made that stage unreadable, and this listener is one half of the answer (the other half
 * is [dev.sora.protohax.relay.netty.log.NettyLogger], which used to hide every debug message of the
 * protocol library in a release build - including "Error encoding packet", the only trace a packet
 * leaves that the relay drops instead of sending it):
 *
 *  * the packets themselves were never named, so the log could not say where the flow stopped,
 *  * the connection to the server had no watchdog for dropped writes (see
 *    [MinecraftRelay.watchDroppedPackets]), which this listener installs on its first inbound
 *    packet. That is early enough: the connection to the server is built inside
 *    `MinecraftRelayListener.onSessionCreation`, before the first packet of the game is processed,
 *    so nothing has been written to it when its first packet arrives.
 *
 * `client.disconnect.lost` in that log is not evidence of a timeout, by the way: it is the default
 * `disconnectReason` every `BedrockSession` is constructed with, and the game side of the relay
 * never replaces it because its RakNet session lives in the native netstack, not in netty.
 *
 * Only the login stage is traced. Once [StartGamePacket] has passed - or once [MAX_PACKETS]
 * packets have been named in one direction - the tracing stops, so the gameplay packets
 * (PlayerAuthInput twenty times a second, chunk data) never reach the log.
 */
class RelayListenerLoginTrace(
    private val session: MinecraftRelaySession,
) : MinecraftRelayPacketListener {

    private val inboundCount = AtomicInteger()
    private val outboundCount = AtomicInteger()

    /** Set once the login stage is over; the counters keep running, the naming stops. */
    @Volatile
    private var done = false

    @Volatile
    private var serverWatchdogInstalled = false

    /** server -> relay; the relay forwards what it does not consume to the game */
    override fun onPacketInbound(packet: BedrockPacket): Boolean {
        installServerWatchdogOnce()
        trace("from the server", packet, inboundCount)
        return true
    }

    /** game -> relay; the relay forwards what it does not consume to the server */
    override fun onPacketOutbound(packet: BedrockPacket): Boolean {
        trace("from the game", packet, outboundCount)
        return true
    }

    override fun onDisconnect(fromServer: Boolean, reason: String) {
        logInfo("login trace ends: ${inboundCount.get()} packets came from the server, " +
            "${outboundCount.get()} from the game")
    }

    private fun trace(direction: String, packet: BedrockPacket, counter: AtomicInteger) {
        val index = counter.getAndIncrement()
        if (!done && index < MAX_PACKETS) {
            logInfo("$direction: ${packet.javaClass.simpleName}")
        }
        if (packet is StartGamePacket || packet is DisconnectPacket) {
            // the session reached the game (or is over): the stage this listener is for is done,
            // and what follows (PlayerAuthInput twenty times a second, chunk data) is noise
            done = true
        }
    }

    /**
     * Installs the watchdog of the connection to the server, on that connection's event loop.
     *
     * The first inbound packet is read by that connection, so this runs on its event loop already;
     * the check keeps the handler from being added in `ADD_PENDING` state (netty skips such a
     * handler) should this ever be reached from another thread.
     */
    private fun installServerWatchdogOnce() {
        if (serverWatchdogInstalled) return
        serverWatchdogInstalled = true

        val channel: Channel? = session.client?.peer?.channel
        if (channel == null) {
            // cannot happen: an inbound packet was read from that very connection
            serverWatchdogInstalled = false
            return
        }

        val eventLoop = channel.eventLoop()
        if (eventLoop.inEventLoop()) {
            MinecraftRelay.watchDroppedPackets(channel, "the server")
        } else {
            serverWatchdogInstalled = false
            eventLoop.execute {
                serverWatchdogInstalled = true
                MinecraftRelay.watchDroppedPackets(channel, "the server")
            }
        }
    }

    private companion object {
        /**
         * How many packets are named per direction. The login stage is about a dozen packets; the
         * rest is headroom for a server that sends more of them (resource packs, a second
         * encryption handshake, a kick).
         */
        const val MAX_PACKETS = 60
    }
}
