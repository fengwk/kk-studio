package transport

import (
	"encoding/binary"
	"io"
	"net"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

const h2SettingsChildEnv = "KK_STUDIO_H2_SETTINGS_CHILD"

// TestServerSettingsEnableConnectProtocol verifies SETTINGS_ENABLE_CONNECT_PROTOCOL in a process started with the required GODEBUG.
func TestServerSettingsEnableConnectProtocol(t *testing.T) {
	if os.Getenv(h2SettingsChildEnv) == "1" {
		assertConnectProtocolSetting(t)
		return
	}
	command := exec.Command(os.Args[0], "-test.run=^TestServerSettingsEnableConnectProtocol$")
	environment := make([]string, 0, len(os.Environ())+1)
	for _, entry := range os.Environ() {
		if !strings.HasPrefix(entry, "GODEBUG=") && !strings.HasPrefix(entry, h2SettingsChildEnv+"=") {
			environment = append(environment, entry)
		}
	}
	environment = append(environment, "GODEBUG=http2xconnect=1", h2SettingsChildEnv+"=1")
	command.Env = environment
	output, err := command.CombinedOutput()
	if err != nil {
		t.Fatalf("child process SETTINGS check failed: %v\n%s", err, output)
	}
}

func assertConnectProtocolSetting(t *testing.T) {
	t.Helper()
	serverConn, clientConn := net.Pipe()
	connection := newControlConnection(serverConn, nil, nil)
	defer connection.Close()
	defer clientConn.Close()
	if err := clientConn.SetDeadline(time.Now().Add(3 * time.Second)); err != nil {
		t.Fatal(err)
	}
	clientPreface := append([]byte(http2ClientPreface), make([]byte, 9)...)
	clientPreface[len(http2ClientPreface)+3] = 4 // Empty client SETTINGS frame.
	writeDone := make(chan error, 1)
	go func() {
		_, err := clientConn.Write(clientPreface)
		writeDone <- err
	}()
	var header [9]byte
	if _, err := io.ReadFull(clientConn, header[:]); err != nil {
		t.Fatalf("read server HTTP/2 frame header: %v", err)
	}
	length := int(header[0])<<16 | int(header[1])<<8 | int(header[2])
	payload := make([]byte, length)
	if _, err := io.ReadFull(clientConn, payload); err != nil {
		t.Fatalf("read server HTTP/2 frame payload: %v", err)
	}
	if err := <-writeDone; err != nil {
		t.Fatalf("write client preface: %v", err)
	}
	if header[3] != 4 || binary.BigEndian.Uint32(header[5:9]) != 0 {
		t.Fatalf("first server frame type=%d stream=%d, want connection SETTINGS", header[3], binary.BigEndian.Uint32(header[5:9]))
	}
	for offset := 0; offset+6 <= len(payload); offset += 6 {
		id := binary.BigEndian.Uint16(payload[offset : offset+2])
		value := binary.BigEndian.Uint32(payload[offset+2 : offset+6])
		if id == 8 {
			if value != 1 {
				t.Fatalf("ENABLE_CONNECT_PROTOCOL=%d, want 1", value)
			}
			return
		}
	}
	t.Fatal("server SETTINGS did not include ENABLE_CONNECT_PROTOCOL")
}
