package transport

import (
	"bytes"
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"google.golang.org/protobuf/proto"
)

const testEnvironment = "123e4567-e89b-12d3-a456-426614174000"

// TRN-T-066, TRN-T-072
func TestTransport_H2ControlRoundTripOverWebSocket(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	accepted := make(chan net.Conn, 1)
	handshake := make(chan http.Header, 1)
	shutdownPlatform := make(chan struct{})
	platform := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		handshake <- r.Header.Clone()
		ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
		if err != nil {
			return
		}
		conn := websocket.NetConn(r.Context(), ws, websocket.MessageBinary)
		accepted <- conn
		select {
		case <-shutdownPlatform:
		case <-r.Context().Done():
		}
	}))
	defer platform.Close()
	defer close(shutdownPlatform)
	cc, err := Dial(ctx, DialConfig{
		Applied:    config.DaemonConfig{TokenPath: "token", StudioURL: platform.URL, EnvironmentID: testEnvironment},
		InstanceID: "daemon-instance", Platform: "linux",
		ReadToken: func(string) (string, error) { return "secret-token", nil },
	})
	if err != nil {
		t.Fatal(err)
	}
	defer cc.Close()
	select {
	case h := <-handshake:
		if h.Get("Authorization") != "Bearer secret-token" || h.Get("KK-Daemon-Protocol") != "4" || h.Get("KK-Daemon-Instance") != "daemon-instance" || h.Get("KK-Daemon-Platform") != "linux" || h.Get("KK-Environment-Id") != testEnvironment {
			t.Fatalf("unexpected handshake headers: %v", h)
		}
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	peer := <-accepted
	protocols := new(http.Protocols)
	protocols.SetUnencryptedHTTP2(true)
	transport := &http.Transport{Protocols: protocols, DialContext: func(context.Context, string, string) (net.Conn, error) { return peer, nil }}
	client := &http.Client{Transport: transport}
	defer client.CloseIdleConnections()
	bodyReader, bodyWriter := io.Pipe()
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, "http://daemon/control", bodyReader)
	if err != nil {
		t.Fatal(err)
	}
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.ProtoMajor != 2 {
		t.Fatalf("control protocol = %s, want HTTP/2", response.Proto)
	}
	duplicateRequest, err := http.NewRequestWithContext(ctx, http.MethodPost, "http://daemon/control", strings.NewReader(""))
	if err != nil {
		t.Fatal(err)
	}
	duplicateResponse, err := client.Do(duplicateRequest)
	if err != nil {
		t.Fatal(err)
	}
	defer duplicateResponse.Body.Close()
	if duplicateResponse.ProtoMajor != 2 || duplicateResponse.StatusCode != http.StatusConflict {
		t.Fatalf("duplicate control response=%s status=%d", duplicateResponse.Proto, duplicateResponse.StatusCode)
	}

	outbound := &protocol.Envelope{MessageType: protocol.MessageType_ERROR, EnvironmentId: proto.String(testEnvironment), Payload: &protocol.Envelope_Error{Error: &protocol.Error{Message: proto.String("ready")}}}
	if err = cc.Send(ctx, outbound); err != nil {
		t.Fatal(err)
	}
	got, err := protocol.ReadFrame(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	decoded, err := protocol.UnmarshalEnvelope(got)
	if err != nil || decoded.GetError().GetMessage() != "ready" {
		t.Fatalf("response envelope=%v err=%v", decoded, err)
	}

	inbound := &protocol.Envelope{MessageType: protocol.MessageType_ERROR, EnvironmentId: proto.String(testEnvironment), Payload: &protocol.Envelope_Error{Error: &protocol.Error{Message: proto.String("ack")}}}
	frame, err := protocol.EncodeEnvelopeFrame(inbound)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = bodyWriter.Write(frame); err != nil {
		t.Fatal(err)
	}
	received, err := cc.Receive(ctx)
	if err != nil || received.GetError().GetMessage() != "ack" {
		t.Fatalf("received envelope=%v err=%v", received, err)
	}
	_ = bodyWriter.Close()
}

// TRN-T-069, TRN-T-070, TRN-T-080, TRN-T-081
func TestTransport_HandshakeRejection(t *testing.T) {
	upgradeBytes, err := proto.Marshal(&protocol.UpgradeRequired{TargetVersion: proto.String("1.2.3")})
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name        string
		status      int
		body        []byte
		wantVersion string
	}{
		{"unauthorized", http.StatusUnauthorized, nil, ""},
		{"upgrade", http.StatusUpgradeRequired, upgradeBytes, "1.2.3"},
		{"empty upgrade", http.StatusUpgradeRequired, nil, ""},
		{"unavailable", http.StatusServiceUnavailable, nil, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(tc.status); _, _ = w.Write(tc.body) }))
			defer server.Close()
			called := make(chan struct {
				status  int
				upgrade *protocol.UpgradeRequired
			}, 1)
			_, err := Dial(context.Background(), DialConfig{Applied: config.DaemonConfig{TokenPath: "token", StudioURL: server.URL, EnvironmentID: testEnvironment}, ReadToken: func(string) (string, error) { return "token", nil }, OnUpgradeFailed: func(status int, u *protocol.UpgradeRequired) {
				called <- struct {
					status  int
					upgrade *protocol.UpgradeRequired
				}{status, u}
			}})
			if err == nil {
				t.Fatal("handshake rejection unexpectedly succeeded")
			}
			result := <-called
			if result.status != tc.status {
				t.Fatalf("callback status=%d", result.status)
			}
			if tc.wantVersion == "" {
				if result.upgrade != nil {
					t.Fatalf("unexpected upgrade: %v", result.upgrade)
				}
			} else if result.upgrade == nil || result.upgrade.GetTargetVersion() != tc.wantVersion {
				t.Fatalf("upgrade=%v", result.upgrade)
			}
		})
	}
}

// TRN-T-083, TRN-T-156
func TestTransport_WebSocketReadLimitAfterNetConn(t *testing.T) {
	wsServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer conn.Close(websocket.StatusNormalClosure, "")
		_ = conn.Write(r.Context(), websocket.MessageBinary, bytes.Repeat([]byte{'x'}, (1<<20)+1))
	}))
	defer wsServer.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	wsURL := "ws" + strings.TrimPrefix(wsServer.URL, "http")
	conn, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{CompressionMode: websocket.CompressionDisabled})
	if err != nil {
		t.Fatal(err)
	}
	byteConn := websocket.NetConn(ctx, conn, websocket.MessageBinary)
	conn.SetReadLimit(1 << 20)
	_, err = io.Copy(io.Discard, byteConn)
	if err == nil {
		t.Fatal("oversized WebSocket message was accepted")
	}
	_ = byteConn.Close()
}

// TRN-T-082
func TestTransport_ControlStreamWaitTimeout(t *testing.T) {
	ready, closed := make(chan struct{}), make(chan struct{})
	if waitForControlStream(time.Millisecond, ready, closed) {
		t.Fatal("missing control stream accepted")
	}
	close(ready)
	if !waitForControlStream(time.Second, ready, closed) {
		t.Fatal("opened control stream timed out")
	}
	close(closed)
	if waitForControlStream(time.Second, make(chan struct{}), closed) {
		t.Fatal("closed connection accepted control stream")
	}
}

// TRN-T-074
func TestTransport_InactivityDeadline(t *testing.T) {
	left, right := net.Pipe()
	defer left.Close()
	defer right.Close()
	conn := &idleConn{Conn: left, timeout: 10 * time.Millisecond}
	_, err := conn.Read(make([]byte, 1))
	if timeout, ok := err.(net.Error); !ok || !timeout.Timeout() {
		t.Fatalf("idle read error = %v, want timeout", err)
	}
}

// TRN-T-011
func TestTransport_ControlWaitHonorsContext(t *testing.T) {
	cc := &ControlConnection{controlReady: make(chan struct{}), closed: make(chan struct{})}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
	defer cancel()
	if err := cc.awaitControl(ctx); err == nil {
		t.Fatal("awaitControl ignored cancellation")
	}
}
