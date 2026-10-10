// Temporary test tool (NOT part of the product): a minimal Bedrock client. It runs inside the emulator under the
// UID of the application that ProtoHax tunnels, so its RakNet connection goes through the real VPN / netstack /
// Java relay.
//
// It reproduces the start of a real join, which is where the relay used to lose sessions:
//
//	phase 1  ->  RequestNetworkSettings(844)   uncompressed   must be answered with NetworkSettings
//	phase 2  ->  RequestNetworkSettings(844)   zlib           must be answered with a zlib NetworkSettings
//
//	phase 3  ->  LoginPacket(844)              zlib           the relay re-signs it (offline session
//	                                                                     encryption is on) and the backend answers
//	                                                                     every login with a zlib NetworkSettings
//	phase 4  <-  ItemRegistry(162, 1600 entries) zlib         the packet the backend sends after the login
//	                                                                     must arrive with all of its entries
//
// Phase 2 proves that the relay swapped the compression codec of both connections correctly: the answer of
// phase 1 and the codec swap race each other inside the relay. Phase 3 proves that the rewritten login
// reaches the backend: without an authentication type the relay cannot encode it (protocol 818+) and the
// backend never sees it. Phase 4 proves that the relay forwards a packet whose list is longer than the
// 1536 entries a codec helper reads by default (EncodingSettings.DEFAULT.maxListSize): that is the item
// registry, the only source of the client's item runtime ids since protocol 776, and a relay that drops it
// leaves the player without a single visible item.
package main

import (
	"bytes"
	"compress/flate"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
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

	// what the stub backend puts into the item registry it sends after every login: above
	// EncodingSettings.DEFAULT.maxListSize (1536), the limit a codec helper reads with unless the relay
	// lifts it, and standing in for the 1889 entries of the real 1.21.111 registry
	itemRegistryEntries = 1600
)

type conn interface {
	Write([]byte) (int, error)
	ReadPacket() ([]byte, error)
}

func main() {
	addr := "10.0.2.2:19132"
	if len(os.Args) > 1 {
		addr = os.Args[1]
	}
	start := time.Now()
	c, err := raknet.DialTimeout(addr, 25*time.Second)
	if err != nil {
		fmt.Println("CLIENT DIAL ERROR:", err)
		os.Exit(2)
	}
	defer c.Close()
	fmt.Printf("CLIENT CONNECTED to %s in %s\n", addr, time.Since(start))

	// ---- phase 1: the uncompressed request every Bedrock client starts with ------------------
	req := request(false)
	if _, err := c.Write(req); err != nil {
		fmt.Println("CLIENT WRITE ERROR:", err)
		os.Exit(3)
	}
	fmt.Printf("CLIENT SENT RequestNetworkSettings(protocol 844): %x\n", req)

	reply, err := read(c, 25*time.Second)
	if err != nil {
		fmt.Println("CLIENT RESULT:", err)
		os.Exit(4)
	}
	fmt.Printf("CLIENT GOT %d bytes: %x\n", len(reply), reply)
	if !hasNetworkSettings(reply, false) {
		fmt.Println("CLIENT RESULT: the reply is not NetworkSettings")
		os.Exit(5)
	}
	fmt.Println("CLIENT RESULT: reply received through the relay")

	// ---- phase 2: both sides are on zlib now -------------------------------------------------
	time.Sleep(300 * time.Millisecond)
	req = request(true)
	if _, err := c.Write(req); err != nil {
		fmt.Println("CLIENT COMPRESSED RESULT: write error:", err)
		os.Exit(6)
	}
	fmt.Printf("CLIENT SENT compressed RequestNetworkSettings: %x\n", req)
	reply, err = read(c, 20*time.Second)
	if err != nil {
		fmt.Println("CLIENT COMPRESSED RESULT:", err)
		os.Exit(7)
	}
	fmt.Printf("CLIENT GOT %d compressed bytes: %x\n", len(reply), reply)
	if !hasNetworkSettings(reply, true) {
		fmt.Println("CLIENT COMPRESSED RESULT: the reply is not a zlib NetworkSettings")
		os.Exit(8)
	}
	fmt.Println("CLIENT COMPRESSED RESULT: ok")

	// ---- phase 3: a LoginPacket must traverse the relay ----------------------------------
	// The relay re-signs the login on its way to the server (offline session encryption is
	// switched on for this test). Without an authentication type the relay cannot encode it
	// and the backend never sees it, so the backend answers every login it receives and the
	// client waits for that answer here.
	time.Sleep(300 * time.Millisecond)
	login := loginPacket()
	if _, err := c.Write(login); err != nil {
		fmt.Println("CLIENT LOGIN RESULT: write error:", err)
		os.Exit(9)
	}
	fmt.Printf("CLIENT SENT LoginPacket(protocol 844): %d bytes\n", len(login))
	reply, err = read(c, 20*time.Second)
	if err != nil {
		fmt.Println("CLIENT LOGIN RESULT:", err)
		os.Exit(10)
	}
	fmt.Printf("CLIENT GOT %d login-reply bytes: %x\n", len(reply), reply)
	if !hasNetworkSettings(reply, true) {
		fmt.Println("CLIENT LOGIN RESULT: the reply is not a zlib NetworkSettings")
		os.Exit(11)
	}
	fmt.Println("CLIENT LOGIN RESULT: ok")

	// ---- phase 4: the item registry must survive the relay ----------------------------------
	// Since protocol 776 the client learns its item runtime ids from packet 162 alone, and the registry
	// of Minecraft 1.21.111 has 1889 entries - more than the 1536 a codec helper reads by default
	// (EncodingSettings.DEFAULT.maxListSize). A relay that reads with those limits cannot decode the
	// packet and drops it: the game joins, the world renders, and not one item is ever visible while the
	// server keeps the real inventory. The login reply is scanned first: the relay flushes both packets
	// in one datagram whenever it can.
	body, err := waitForPacket(c, idItemRegistry, reply, 20*time.Second)
	if err != nil {
		fmt.Println("CLIENT ITEM REGISTRY RESULT:", err)
		os.Exit(12)
	}
	entries, err := itemRegistryEntriesOf(body)
	if err != nil {
		fmt.Println("CLIENT ITEM REGISTRY RESULT: unreadable packet:", err)
		os.Exit(13)
	}
	if entries != itemRegistryEntries {
		fmt.Printf("CLIENT ITEM REGISTRY RESULT: %d entries arrived, the backend sent %d\n", entries, itemRegistryEntries)
		os.Exit(14)
	}
	fmt.Printf("CLIENT ITEM REGISTRY RESULT: ok (%d entries)\n", entries)
}

func read(c conn, timeout time.Duration) ([]byte, error) {
	type result struct {
		b   []byte
		err error
	}
	ch := make(chan result, 1)
	go func() {
		b, err := c.ReadPacket()
		ch <- result{b, err}
	}()
	select {
	case r := <-ch:
		if r.err != nil {
			return nil, fmt.Errorf("read error: %w", r.err)
		}
		return r.b, nil
	case <-time.After(timeout):
		return nil, fmt.Errorf("timeout waiting for a reply")
	}
}

// request builds a RequestNetworkSettings(844) game packet.
func request(compressed bool) []byte {
	packet := binary.AppendUvarint(nil, idRequestNetworkSettings)
	packet = binary.BigEndian.AppendUint32(packet, 844)
	batch := binary.AppendUvarint(nil, uint64(len(packet)))
	batch = append(batch, packet...)
	if !compressed {
		return append([]byte{0xFE}, batch...)
	}
	var deflated bytes.Buffer
	w, _ := flate.NewWriter(&deflated, 7)
	w.Write(batch)
	w.Close()
	return append([]byte{0xFE, compressionZlib}, deflated.Bytes()...)
}

// loginPacket builds a LoginPacket(844) game packet, compressed the way the relay expects after
// the NetworkSettings handshake. The chain carries an extraData JWT like a real client login; the
// relay re-signs it (offline session encryption) and must tag it with an authentication type,
// otherwise it cannot encode the packet and the backend never sees it. The client claims FULL
// authentication on purpose: whatever the backend receives must be SELF_SIGNED, which proves the
// relay rewrote it. The JWT segments use padded base64: the relay decodes them with Java's
// standard decoder, which rejects the unpadded form.
func loginPacket() []byte {
	chainJwt := "e30." + base64.URLEncoding.EncodeToString([]byte(
		`{"extraData":{"displayName":"E2E","identity":"00000000-0000-0000-0000-000000000000"},`+
			`"identityPublicKey":"ZTNK"}`)) + ".ZTJl"
	chain, _ := json.Marshal(map[string][]string{"chain": {chainJwt}})
	auth, _ := json.Marshal(map[string]any{
		"AuthenticationType": 0,
		"Certificate":        string(chain),
		"Token":              "",
	})
	clientJwt := "e30." + base64.URLEncoding.EncodeToString([]byte(
		`{"DeviceOS":1,"DeviceModel":"E2E","GameVersion":"1.21.111","ClientRandomId":1}`)) + ".ZTJl"

	packet := binary.AppendUvarint(nil, idLogin)
	packet = binary.BigEndian.AppendUint32(packet, 844)
	packet = binary.AppendUvarint(packet, uint64(len(auth)+len(clientJwt)+8))
	packet = binary.LittleEndian.AppendUint32(packet, uint32(len(auth)))
	packet = append(packet, auth...)
	packet = binary.LittleEndian.AppendUint32(packet, uint32(len(clientJwt)))
	packet = append(packet, clientJwt...)

	batch := binary.AppendUvarint(nil, uint64(len(packet)))
	batch = append(batch, packet...)
	var deflated bytes.Buffer
	w, _ := flate.NewWriter(&deflated, 7)
	w.Write(batch)
	w.Close()
	return append([]byte{0xFE, compressionZlib}, deflated.Bytes()...)
}

// hasNetworkSettings reports whether a game packet contains a NetworkSettings packet.
func hasNetworkSettings(pk []byte, compressed bool) bool {
	if len(pk) < 3 || pk[0] != 0xFE {
		return false
	}
	body := pk[1:]
	if compressed {
		switch body[0] {
		case compressionZlib:
			raw, err := io.ReadAll(flate.NewReader(bytes.NewReader(body[1:])))
			if err != nil {
				fmt.Println("CLIENT inflate error:", err)
				return false
			}
			fmt.Printf("CLIENT inflated %d bytes: %x\n", len(raw), raw)
			body = raw
		case compressionNone:
			body = body[1:]
		default:
			fmt.Printf("CLIENT unknown compression prefix 0x%02x\n", body[0])
			return false
		}
	}
	for len(body) > 0 {
		size, read := binary.Uvarint(body)
		if read <= 0 || uint64(len(body)-read) < size {
			return false
		}
		packet := body[read : read+int(size)]
		if id, _ := binary.Uvarint(packet); id&0x3FF == idNetworkSettings {
			return true
		}
		body = body[read+int(size):]
	}
	return false
}

// waitForPacket reads batches until one of them carries the game packet with the given id and returns the
// body of that packet (its header stripped). alreadyRead is a batch the caller has read before: the relay
// puts several packets into one datagram whenever it flushes them together, so the packet looked for here
// may have arrived with the reply of the previous phase.
func waitForPacket(c conn, id uint64, alreadyRead []byte, timeout time.Duration) ([]byte, error) {
	if body := findPacket(alreadyRead, id); body != nil {
		return body, nil
	}
	deadline := time.Now().Add(timeout)
	for {
		remaining := time.Until(deadline)
		if remaining <= 0 {
			return nil, fmt.Errorf("timeout waiting for packet %d", id)
		}
		pk, err := read(c, remaining)
		if err != nil {
			return nil, err
		}
		if body := findPacket(pk, id); body != nil {
			return body, nil
		}
		fmt.Printf("CLIENT read %d bytes that did not carry packet %d\n", len(pk), id)
	}
}

// findPacket inflates a game packet (FE [compression prefix] <batch>) when it has to and returns the body
// of the packet with the given id, or nil when the batch does not carry it. Both sides are on zlib from
// the NetworkSettings handshake on, so a batch without a prefix is read as it is.
func findPacket(pk []byte, id uint64) []byte {
	if len(pk) < 2 || pk[0] != 0xFE {
		return nil
	}
	body := pk[1:]
	switch body[0] {
	case compressionZlib:
		raw, err := io.ReadAll(flate.NewReader(bytes.NewReader(body[1:])))
		if err != nil {
			fmt.Println("CLIENT inflate error:", err)
			return nil
		}
		return findPacketInBatch(raw, id)
	case compressionNone:
		return findPacketInBatch(body[1:], id)
	default:
		return findPacketInBatch(body, id)
	}
}

// findPacketInBatch splits a batch (varint length + packet, repeated) and returns the body of the packet
// with the given id.
func findPacketInBatch(batch []byte, id uint64) []byte {
	for len(batch) > 0 {
		size, n := binary.Uvarint(batch)
		if n <= 0 || uint64(len(batch)-n) < size {
			return nil
		}
		packet := batch[n : n+int(size)]
		packetID, header := binary.Uvarint(packet)
		if packetID == id {
			return packet[header:]
		}
		batch = batch[n+int(size):]
	}
	return nil
}

// itemRegistryEntriesOf reads the entry count of an item registry packet and checks that the first and the
// last identifier survived, so a packet that arrived truncated or wrongly re-encoded fails the phase
// instead of passing on its length prefix alone.
func itemRegistryEntriesOf(body []byte) (uint64, error) {
	entries, n := binary.Uvarint(body)
	if n <= 0 || entries == 0 {
		return 0, fmt.Errorf("no entry count in the %d bytes that arrived", len(body))
	}
	first := []byte("minecraft:e2e_item_0000")
	last := []byte(fmt.Sprintf("minecraft:e2e_item_%04d", entries-1))
	if !bytes.Contains(body, first) || !bytes.Contains(body, last) {
		return 0, fmt.Errorf("%q or %q is missing from the %d bytes that arrived", first, last, len(body))
	}
	return entries, nil
}
