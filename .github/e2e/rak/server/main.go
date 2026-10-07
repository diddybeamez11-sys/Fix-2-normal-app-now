// Temporary test tool (NOT part of the product): a stub Bedrock "backend" that runs on the CI host.
// The relay inside the emulator connects to it (10.0.2.2) after the test client connected through the VPN.
package main

import (
	"encoding/binary"
	"fmt"
	"net"
	"os"
	"time"

	"github.com/sandertv/go-raknet"
)

func main() {
	addr := "0.0.0.0:19132"
	if len(os.Args) > 1 {
		addr = os.Args[1]
	}
	l, err := raknet.Listen(addr)
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
	buf := make([]byte, 1<<16)
	for {
		n, err := c.Read(buf)
		if err != nil {
			fmt.Println("SERVER read ended:", err)
			return
		}
		pk := buf[:n]
		fmt.Printf("SERVER got %d bytes: %x\n", n, pk)
		// game packet batch: FE <len> <varint packet id> ...; RequestNetworkSettings = id 193 (varint C1 01) + int32 BE protocol
		if n >= 8 && pk[0] == 0xFE && pk[2] == 0xC1 && pk[3] == 0x01 {
			fmt.Printf("SERVER RequestNetworkSettings protocol=%d\n", binary.BigEndian.Uint32(pk[4:8]))
			// NetworkSettings = id 143 (varint 8F 01): compression threshold u16 LE, algorithm u16 LE (0 = zlib),
			// client throttle bool, threshold byte, scalar float32 LE
			body := []byte{0x8F, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
			out := append([]byte{0xFE, byte(len(body))}, body...)
			if _, err := c.Write(out); err != nil {
				fmt.Println("SERVER write error:", err)
				return
			}
			fmt.Printf("SERVER sent NetworkSettings: %x\n", out)
		}
	}
}
