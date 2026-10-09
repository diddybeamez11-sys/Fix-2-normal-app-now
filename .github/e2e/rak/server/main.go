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
package main

import (
	"bytes"
	"compress/flate"
	"encoding/binary"
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
	compressionZlib          = 0x00
	compressionNone          = 0xff
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
			// NetworkSettings: compression threshold u16 LE, algorithm u16 LE (0 = zlib),
			// client throttle bool, threshold byte, scalar float32 LE
			settings := []byte{0x8F, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
			out, err := batchOf(settings, compressed)
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

// packetID reads the packet header (varint: id | sender << 10 | target << 12).
func packetID(packet []byte) (uint64, []byte) {
	header, read := binary.Uvarint(packet)
	if read <= 0 {
		return 0, nil
	}
	return header & 0x3FF, packet[read:]
}
