// Temporary test tool (NOT part of the product): a minimal Bedrock client. It runs inside the emulator under the
// UID of the application that ProtoHax tunnels, so its RakNet connection goes through the real VPN / netstack /
// Java relay. It sends RequestNetworkSettings with protocol 844 and prints what comes back.
package main

import (
	"encoding/binary"
	"fmt"
	"os"
	"time"

	"github.com/sandertv/go-raknet"
)

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

	req := []byte{0xFE, 0x06, 0xC1, 0x01, 0, 0, 0, 0}
	binary.BigEndian.PutUint32(req[4:], 844)
	if _, err := c.Write(req); err != nil {
		fmt.Println("CLIENT WRITE ERROR:", err)
		os.Exit(3)
	}
	fmt.Printf("CLIENT SENT RequestNetworkSettings(protocol 844): %x\n", req)

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
			fmt.Println("CLIENT READ ERROR:", r.err)
			os.Exit(4)
		}
		fmt.Printf("CLIENT GOT %d bytes: %x\n", len(r.b), r.b)
		fmt.Println("CLIENT RESULT: reply received through the relay")
	case <-time.After(25 * time.Second):
		fmt.Println("CLIENT RESULT: timeout waiting for a reply")
		os.Exit(5)
	}
}
