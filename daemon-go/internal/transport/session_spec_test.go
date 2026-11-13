package transport

import (
	"net"
	"net/http"
	"testing"
	"time"
)

// TRN-T-023 verifies graceful connection close terminates the peer after HTTP server shutdown.
func TestControlConnection_CloseTerminatesPeer(t *testing.T) {
	local, peer := net.Pipe()
	defer peer.Close()
	cc := &ControlConnection{
		conn:         local,
		server:       &http.Server{},
		closed:       make(chan struct{}),
		stopControl:  make(chan struct{}),
		controlReady: make(chan struct{}),
	}
	if err := peer.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	if err := cc.Close(); err != nil {
		t.Fatalf("close control connection: %v", err)
	}
	if _, err := peer.Read(make([]byte, 1)); err == nil {
		t.Fatal("peer connection remained open after graceful close")
	}
	if err := cc.Close(); err != nil {
		t.Fatalf("repeated close: %v", err)
	}
}
