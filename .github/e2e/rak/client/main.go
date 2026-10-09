// Temporary test tool (NOT part of the product): a minimal Bedrock client. It runs inside the emulator under the
// UID of the application that ProtoHax tunnels, so its RakNet connection goes through the real VPN / netstack /
// Java relay.
//
// It reproduces the start of a real join, which is where the relay used to lose sessions:
//
//	phase 1  ->  RequestNetworkSettings(844)   uncompressed   must be answered with NetworkSettings
//	phase 2  ->  RequestNetworkSettings(844)   zlib           must be answered with a zlib NetworkSettings
//
// Phase 2 proves that the relay swapped the compression codec of both connections correctly: the answer of
// phase 1 and the codec swap race each other inside the relay.
package main

import (
	"bytes"
	"compress/flate"
	"encoding/binary"
	"fmt"
	"io"
	"os"
	"time"

	"github.com/sandertv/go-raknet"
)

const (
	idRequestNetworkSettings = 193
	idNetworkSettings        = 143
	compressionZlib          = 0x00
	compressionNone          = 0xff
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
