package dev.sora.protohax.relay

import dev.sora.relay.session.MinecraftRelayPacketListener
import dev.sora.relay.utils.logInfo
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket

/**
 * Re-attaches the authentication type the relay's rewritten login packet needs on its way to the server.
 *
 * Since protocol 818 (Minecraft 1.21.90) the login serializer requires the auth payload to carry a
 * non-`UNKNOWN` [AuthType] (`LoginSerializer_v818.writeAuthJwt`: "Client requires non-null and
 * non-UNKNOWN AuthType for login"). The protocol 844 codec of Minecraft 1.21.111 inherits that
 * serializer. But both session encryptors of ProtoHax replace the game's auth payload with
 * `CertificateChainPayload(chain)`, whose single-argument constructor leaves the type at `UNKNOWN`:
 *
 *  * `RelayListenerXboxLogin` (online accounts) - the chain fetched from multiplayer.minecraft.net,
 *  * `RelayListenerEncryptedSession` (offline session encryption) - the self-signed chain.
 *
 * `BedrockCodec.tryEncode` wraps the resulting `IllegalArgumentException` as "Error whilst serializing
 * LoginPacket(...)" and fails the write, so the packet is dropped ([MinecraftRelay.watchDroppedPackets]
 * reports it as "packet to the server could not be sent") and the game waits for the answer to its login
 * until the connection times out. Every login on protocol 818 and newer ends that way - the session dies
 * right after "login success", exactly like the reported one.
 *
 * This listener must run after the encryptor ([MinecraftRelay.constructRelay] adds it last): it sees the
 * rewritten payload and repairs the type the new chain actually has - [AuthType.FULL] for the
 * Xbox-authenticated chain, [AuthType.SELF_SIGNED] for the offline self-signed one. A payload that already
 * has a type (the game's own login on protocol 818+, forwarded untouched when no encryptor runs) is left
 * alone, and on older protocols the type is not written to the wire at all, so repairing it there is
 * harmless.
 *
 * The ProtoHax sources carry the same fix (.github/protohax/apply_android_patches.py, step 7); this
 * listener keeps local builds working with a vendored jar that predates it.
 */
class RelayListenerLoginAuthType : MinecraftRelayPacketListener {

    override fun onPacketOutbound(packet: BedrockPacket): Boolean {
        if (packet !is LoginPacket) {
            return true
        }

        // TokenPayload cannot be UNKNOWN (its constructor rejects it), so only a certificate chain
        // without a type needs repairing. A null payload cannot be repaired - there is no chain.
        val payload = packet.authPayload as? CertificateChainPayload ?: return true
        if (payload.authType != null && payload.authType != AuthType.UNKNOWN) {
            return true
        }

        // Which encryptor rewrote the packet follows from the account, exactly like the encryptor
        // selection itself: an account means the Xbox login (FULL), no account means the offline
        // self-signed chain (SELF_SIGNED).
        val fixed = if (AccountManager.currentAccount != null) AuthType.FULL else AuthType.SELF_SIGNED
        packet.authPayload = CertificateChainPayload(payload.chain, fixed)
        logInfo("login auth type repaired: ${payload.authType} -> $fixed (protocol ${packet.protocolVersion})")
        return true
    }
}
