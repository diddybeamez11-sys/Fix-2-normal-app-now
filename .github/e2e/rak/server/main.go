// Temporary test tool (NOT part of the product): a stub Bedrock "backend" that runs on the CI host.
// The relay inside the emulator connects to it (10.0.2.2) after the test client connected through the VPN.
//
// It speaks just enough of the Bedrock wire format for the handshake the relay has to survive:
//
//	client -> RequestNetworkSettings(844)            uncompressed batch  FE <batch>
//	server -> NetworkSettings(zlib)                  uncompressed batch  FE <batch>
//	        ... both sides switch to zlib ...
//	client -> RequestNetworkSettings(844)            compressed batch    FE 00 <raw deflate>
//	server -> NetworkSettings(zlib)                  compressed batch    FE 00 <raw deflate>
//
//	client -> LoginPacket(844)                       compressed batch    FE 00 <raw deflate>
//	server -> NetworkSettings(zlib)                  compressed batch    FE 00 <raw deflate>
//	server -> ItemRegistry(162, 1600 entries)        compressed batch    FE 00 <raw deflate>
//
// Every login it receives is logged with its protocol, AuthenticationType and chain length: the
// relay re-signs the login (offline session encryption is on) and must tag it SELF_SIGNED, so a
// login that arrives answers what the relay did to it.
//
// The item registry that follows every login is the packet a real server sends right after it: since
// protocol 776 the client learns its item runtime ids from packet 162 alone (StartGamePacket no longer
// carries them), and the list of Minecraft 1.21.111 has 1889 entries - more than the 1536 a codec
// helper reads by default (EncodingSettings.DEFAULT.maxListSize). A relay with those limits cannot
// decode the packet and drops it: the game joins, the world renders, and not one item is ever visible.
package main

import (
	"bytes"
	"compress/flate"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"time"

	"github.com/sandertv/go-raknet"
)

const (
	idRequestNetworkSettings = 193
	idNetworkSettings        = 143
	idLogin                  = 1
	idItemRegistry           = 162
	compressionZlib          = 0x00
	compressionNone          = 0xff

	// itemRegistryEntries is above EncodingSettings.DEFAULT.maxListSize (1536) - the limit a codec helper
	// reads with unless the relay lifts it - and stands in for the 1889 entries of the real 1.21.111
	// registry, which this stub does not have to reproduce in full to trip the same check.
	itemRegistryEntries = 1600
)

func main() {
	addr := "0.0.0.0:19132"
	if len(os.Args) > 1 {
		addr = os.Args[1]
	}
	conf := raknet.ListenConfig{
		ErrorLog:      slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelDebug})),
		BlockDuration: -1, // never block an address after an error: we want to see every attempt
	}
	if len(os.Args) > 2 && os.Args[2] == "nocookies" {
		conf.DisableCookies = true
		fmt.Println("SERVER cookies disabled")
	}
	l, err := conf.Listen(addr)
	if err != nil {
		fmt.Println("LISTEN ERROR:", err)
		os.Exit(1)
	}
	l.PongData([]byte("MCPE;E2E backend;844;1.21.111;0;10;1234567890;E2E;Survival;1;19132;19133;"))
	fmt.Println("SERVER listening on", addr)
	for {
		c, err := l.Accept()
		if err != nil {
			fmt.Println("ACCEPT ERROR:", err)
			return
		}
		go handle(c)
	}
}

func handle(c net.Conn) {
	defer c.Close()
	fmt.Println("SERVER client connected from", c.RemoteAddr(), "at", time.Now().Format(time.RFC3339))
	compressed := false
	buf := make([]byte, 1<<16)
	for {
		n, err := c.Read(buf)
		if err != nil {
			fmt.Println("SERVER read ended:", err)
			return
		}
		pk := buf[:n]
		fmt.Printf("SERVER got %d bytes (compression %v): %x\n", n, compressed, trim(pk))
		batch, err := unbatch(pk, compressed)
		if err != nil {
			fmt.Println("SERVER cannot read batch:", err)
			continue
		}
		for _, packet := range batch {
			id, body := packetID(packet)
			if id == idLogin {
				protocol, authType, chainLen := parseLogin(body)
				fmt.Printf("SERVER LoginPacket protocol=%d authType=%d chain=%d\n", protocol, authType, chainLen)
				out, err := batchOf(networkSettings(), compressed)
				if err != nil {
					fmt.Println("SERVER cannot build batch:", err)
					return
				}
				if _, err := c.Write(out); err != nil {
					fmt.Println("SERVER write error:", err)
					return
				}
				fmt.Printf("SERVER sent NetworkSettings for the login (compressed %v): %x\n", compressed, trim(out))
				// what a real server sends right after the login: the registry the client needs to
				// resolve an item runtime id, longer than a codec helper reads by default
				out, err = batchOf(itemRegistry(itemRegistryEntries), true)
				if err != nil {
					fmt.Println("SERVER cannot build the item registry batch:", err)
					return
				}
				if _, err := c.Write(out); err != nil {
					fmt.Println("SERVER write error:", err)
					return
				}
				fmt.Printf("SERVER sent ItemRegistry(%d entries): %d bytes compressed\n", itemRegistryEntries, len(out))
				continue
			}
			if id != idRequestNetworkSettings {
				fmt.Printf("SERVER packet id=%d len=%d\n", id, len(packet))
				continue
			}
			if len(body) < 4 {
				fmt.Println("SERVER short RequestNetworkSettings")
				continue
			}
			protocol := binary.BigEndian.Uint32(body[:4])
			if compressed {
				fmt.Printf("SERVER compressed RequestNetworkSettings protocol=%d\n", protocol)
			} else {
				fmt.Printf("SERVER RequestNetworkSettings protocol=%d\n", protocol)
			}
			out, err := batchOf(networkSettings(), compressed)
			if err != nil {
				fmt.Println("SERVER cannot build batch:", err)
				return
			}
			if _, err := c.Write(out); err != nil {
				fmt.Println("SERVER write error:", err)
				return
			}
			fmt.Printf("SERVER sent NetworkSettings (compressed %v): %x\n", compressed, trim(out))
			// everything after the first NetworkSettings is compressed, in both directions
			compressed = true
		}
	}
}

func trim(b []byte) []byte {
	if len(b) > 256 {
		return b[:256]
	}
	return b
}

// unbatch splits a game packet (FE [prefix] <batch>) into the single packets of its batch.
func unbatch(pk []byte, compressed bool) ([][]byte, error) {
	if len(pk) < 2 || pk[0] != 0xFE {
		return nil, fmt.Errorf("not a game packet: % x", trim(pk))
	}
	body := pk[1:]
	if compressed {
		switch body[0] {
		case compressionZlib:
			raw, err := io.ReadAll(flate.NewReader(bytes.NewReader(body[1:])))
			if err != nil {
				return nil, fmt.Errorf("inflate: %w", err)
			}
			body = raw
		case compressionNone:
			body = body[1:]
		default:
			return nil, fmt.Errorf("unknown compression prefix 0x%02x", body[0])
		}
	}
	var out [][]byte
	for len(body) > 0 {
		size, read := binary.Uvarint(body)
		if read <= 0 || uint64(len(body)-read) < size {
			return nil, fmt.Errorf("broken batch at % x", trim(body))
		}
		out = append(out, body[read:read+int(size)])
		body = body[read+int(size):]
	}
	return out, nil
}

// batchOf wraps one packet into a game packet, compressing it the way the relay expects.
func batchOf(packet []byte, compressed bool) ([]byte, error) {
	batch := binary.AppendUvarint(nil, uint64(len(packet)))
	batch = append(batch, packet...)
	if !compressed {
		return append([]byte{0xFE}, batch...), nil
	}
	var deflated bytes.Buffer
	w, err := flate.NewWriter(&deflated, 7)
	if err != nil {
		return nil, err
	}
	if _, err := w.Write(batch); err != nil {
		return nil, err
	}
	if err := w.Close(); err != nil {
		return nil, err
	}
	return append([]byte{0xFE, compressionZlib}, deflated.Bytes()...), nil
}

// networkSettings is the body of the NetworkSettings reply: compression threshold u16 LE,
// algorithm u16 LE (0 = zlib), client throttle bool, threshold byte, scalar float32 LE.
func networkSettings() []byte {
	return []byte{0x8F, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
}

// itemRegistry builds the packet that carries the item registry: id 162, ItemComponentPacket in the
// protocol library, ItemRegistryPacket in Mojang's documentation, laid out the way
// ItemComponentSerializer_v776 reads it:
//
//	varint count
//	count x { string identifier | uint16 LE runtimeId | bool componentBased | zigzag varint version | network NBT }
//
// The entries are placeholders - what the relay has to survive is the length of the list, not its
// content. The component data is an empty unnamed compound (tag type, name length 0, TAG_End), which is
// what the library writes for an item without components.
func itemRegistry(entries int) []byte {
	packet := binary.AppendUvarint(nil, idItemRegistry)
	packet = binary.AppendUvarint(packet, uint64(entries))
	var runtimeID [2]byte
	for i := 0; i < entries; i++ {
		name := fmt.Sprintf("minecraft:e2e_item_%04d", i)
		packet = binary.AppendUvarint(packet, uint64(len(name)))
		packet = append(packet, name...)
		binary.LittleEndian.PutUint16(runtimeID[:], uint16(i+1))
		packet = append(packet, runtimeID[:]...)
		packet = append(packet, 0x00)             // componentBased = false
		packet = append(packet, 0x00)             // ItemVersion ordinal 0 (LEGACY), zigzag varint
		packet = append(packet, 0x0a, 0x00, 0x00) // empty unnamed compound
	}
	return packet
}

// parseLogin reads the protocol, the AuthenticationType and the chain length from a LoginPacket
// body. Anything it cannot read stays -1, so a malformed login fails the test loudly instead of
// passing silently.
func parseLogin(body []byte) (protocol uint32, authType int, chainLen int) {
	protocol, authType, chainLen = 0, -1, -1
	if len(body) < 4 {
		return
	}
	protocol = binary.BigEndian.Uint32(body[:4])
	rest := body[4:]
	total, read := binary.Uvarint(rest)
	if read <= 0 || uint64(len(rest)-read) < total {
		return
	}
	rest = rest[read : read+int(total)]
	if len(rest) < 4 {
		return
	}
	authLen := int(binary.LittleEndian.Uint32(rest[:4]))
	if len(rest) < 4+authLen {
		return
	}
	var auth struct {
		AuthenticationType int    `json:"AuthenticationType"`
		Certificate        string `json:"Certificate"`
	}
	if err := json.Unmarshal(rest[4:4+authLen], &auth); err != nil {
		return
	}
	authType = auth.AuthenticationType
	var cert struct {
		Chain []string `json:"chain"`
	}
	if err := json.Unmarshal([]byte(auth.Certificate), &cert); err != nil {
		return
	}
	chainLen = len(cert.Chain)
	return
}

// packetID reads the packet header (varint: id | sender << 10 | target << 12).
func packetID(packet []byte) (uint64, []byte) {
	header, read := binary.Uvarint(packet)
	if read <= 0 {
		return 0, nil
	}
	return header & 0x3FF, packet[read:]
}
