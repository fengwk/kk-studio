package transport

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

type testStream struct {
	reader   io.Reader
	writer   io.Writer
	closeErr error
}

func (s *testStream) Read(p []byte) (int, error)  { return s.reader.Read(p) }
func (s *testStream) Write(p []byte) (int, error) { return s.writer.Write(p) }
func (s *testStream) Close() error                { return s.closeErr }

type deadlineErrorConn struct{ net.Conn }

func (deadlineErrorConn) SetReadDeadline(time.Time) error { return errors.New("deadline failed") }

type failingWriter struct{}

func (failingWriter) Write([]byte) (int, error) { return 0, errors.New("write failed") }

// TRN-T-066, TRN-T-069
func TestDial_ConfigurationAndTokenFailures(t *testing.T) {
	for _, tc := range []struct {
		name   string
		config config.DaemonConfig
		read   func(string) (string, error)
	}{
		{"token read error", config.DaemonConfig{TokenPath: "token", StudioURL: "http://studio.example"}, func(string) (string, error) { return "", errors.New("secret") }},
		{"invalid URL", config.DaemonConfig{TokenPath: "token", StudioURL: "not a URL"}, func(string) (string, error) { return "token", nil }},
		{"unsupported scheme", config.DaemonConfig{TokenPath: "token", StudioURL: "ftp://studio.example"}, func(string) (string, error) { return "token", nil }},
		{"missing host", config.DaemonConfig{TokenPath: "token", StudioURL: "https:"}, func(string) (string, error) { return "token", nil }},
		{"invalid proxy", config.DaemonConfig{TokenPath: "token", StudioURL: "http://studio.example", ProxyURL: protoString("ftp://proxy.example")}, func(string) (string, error) { return "token", nil }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := Dial(context.Background(), DialConfig{Applied: tc.config, ReadToken: tc.read}); err == nil {
				t.Fatal("invalid dial config accepted")
			}
		})
	}
	path := filepath.Join(t.TempDir(), "token")
	if err := os.WriteFile(path, []byte(" token-value \n"), 0600); err != nil {
		t.Fatal(err)
	}
	token, err := readRegistrationToken(path, nil)
	if err != nil || token != "token-value" {
		t.Fatalf("token=%q err=%v", token, err)
	}
	if _, err := readRegistrationToken(filepath.Join(t.TempDir(), "missing"), nil); err == nil {
		t.Fatal("missing token accepted")
	}
}

// TRN-T-011, TRN-T-072
func TestControlConnection_StreamFailuresAndClose(t *testing.T) {
	for _, data := range [][]byte{{0, 0, 0, 1}, {0, 0, 0, 1, 0xff}} {
		left, right := net.Pipe()
		server := &http.Server{}
		cc := &ControlConnection{conn: left, server: server, controlReady: make(chan struct{}), closed: make(chan struct{}), stopControl: make(chan struct{}), controlStream: &testStream{reader: bytes.NewReader(data), writer: io.Discard}}
		close(cc.controlReady)
		if _, err := cc.Receive(context.Background()); err == nil {
			t.Fatal("invalid control stream accepted")
		}
		_ = right.Close()
		if err := cc.Close(); err != nil {
			t.Fatal(err)
		}
	}
	left, right := net.Pipe()
	defer right.Close()
	cc := &ControlConnection{conn: left, server: &http.Server{}, controlReady: make(chan struct{}), closed: make(chan struct{}), stopControl: make(chan struct{}), controlStream: &testStream{reader: bytes.NewReader(nil), writer: failingWriter{}}}
	close(cc.controlReady)
	envelope := &protocol.Envelope{MessageType: protocol.MessageType_ERROR, EnvironmentId: protoString(testEnvironment), Payload: &protocol.Envelope_Error{Error: &protocol.Error{Message: protoString("failure")}}}
	if err := cc.Send(context.Background(), envelope); err == nil {
		t.Fatal("control write failure ignored")
	}
	if err := cc.Close(); err != nil {
		t.Fatal(err)
	}
}

// TRN-T-082
func TestControlConnection_ControlRouteAndGoaway(t *testing.T) {
	cc := &ControlConnection{controlReady: make(chan struct{}), closed: make(chan struct{}), stopControl: make(chan struct{})}
	close(cc.stopControl)
	wrong := httptest.NewRecorder()
	cc.handleControl(wrong, httptest.NewRequest(http.MethodGet, "/control", nil))
	if wrong.Code != http.StatusMethodNotAllowed {
		t.Fatalf("GET status=%d", wrong.Code)
	}
	first := httptest.NewRecorder()
	cc.handleControl(first, httptest.NewRequest(http.MethodPost, "/control", nil))
	if first.Code != http.StatusOK {
		t.Fatalf("POST status=%d", first.Code)
	}
	second := httptest.NewRecorder()
	cc.handleControl(second, httptest.NewRequest(http.MethodPost, "/control", nil))
	if second.Code != http.StatusConflict {
		t.Fatalf("duplicate status=%d", second.Code)
	}
	cc.signalGoaway(0)
	callback := make(chan uint32, 1)
	goaway := &ControlConnection{closed: make(chan struct{}), goaway: make(chan uint32, 1), onGoaway: func(code uint32) { callback <- code }}
	done := make(chan struct{})
	go func() { defer close(done); goaway.forwardGoaway() }()
	goaway.signalGoaway(2)
	select {
	case code := <-callback:
		if code != 2 {
			t.Fatalf("GOAWAY code=%d", code)
		}
	case <-time.After(time.Second):
		t.Fatal("GOAWAY callback not delivered")
	}
	close(goaway.closed)
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("GOAWAY forwarder leaked")
	}
	addr := dummyAddr("websocket")
	if addr.Network() != "websocket" || addr.String() != "websocket" {
		t.Fatalf("dummy addr=%v", addr)
	}
}

// TRN-T-074
func TestIdleConn_SetDeadlineFailure(t *testing.T) {
	conn := &idleConn{Conn: deadlineErrorConn{}, timeout: time.Second}
	if _, err := conn.Read(make([]byte, 1)); err == nil {
		t.Fatal("deadline failure ignored")
	}
	if err := conn.SetReadDeadline(time.Time{}); err == nil {
		t.Fatal("explicit deadline failure ignored")
	}
}

// TRN-T-082 verifies the single-connection listener returns its one connection and terminates waiters.
func TestSingleConnListener_AcceptAndClose(t *testing.T) {
	left, right := net.Pipe()
	defer left.Close()
	defer right.Close()
	listener := &singleConnListener{conn: left, closed: make(chan struct{})}
	accepted, err := listener.Accept()
	if err != nil || accepted != left {
		t.Fatalf("accepted=%v err=%v", accepted, err)
	}
	if listener.Addr().Network() != "websocket" {
		t.Fatalf("listener address=%v", listener.Addr())
	}
	waiter := make(chan error, 1)
	go func() { _, acceptErr := listener.Accept(); waiter <- acceptErr }()
	if err := listener.Close(); err != nil {
		t.Fatal(err)
	}
	if err := <-waiter; !errors.Is(err, net.ErrClosed) {
		t.Fatalf("second accept error=%v", err)
	}
	if err := listener.Close(); err != nil {
		t.Fatal(err)
	}
	if err := (&controlPipe{}).Close(); err != nil {
		t.Fatal(err)
	}
	if err := (&ControlConnection{}).Send(context.Background(), nil); err == nil {
		t.Fatal("invalid outbound envelope accepted")
	}
}

// TRN-T-011
func TestControlConnection_CloseBeforeControlWait(t *testing.T) {
	cc := &ControlConnection{controlReady: make(chan struct{}), closed: make(chan struct{})}
	close(cc.closed)
	if err := cc.awaitControl(context.Background()); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("closed wait error=%v", err)
	}
}
