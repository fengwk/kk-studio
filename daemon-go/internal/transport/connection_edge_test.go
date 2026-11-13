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
	"sync"
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

type blockedControlWriter struct {
	writeStarted       chan struct{}
	unblockWrite       chan struct{}
	closed             <-chan struct{}
	handlerReturned    <-chan struct{}
	writeStartedOnce   sync.Once
	unblockWriteOnce   sync.Once
	mu                 sync.Mutex
	writes             int
	writeAfterReturned bool
}

func (w *blockedControlWriter) Header() http.Header { return make(http.Header) }
func (w *blockedControlWriter) WriteHeader(int)     {}
func (w *blockedControlWriter) releaseWrite() {
	w.unblockWriteOnce.Do(func() { close(w.unblockWrite) })
}
func (w *blockedControlWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	w.writes++
	select {
	case <-w.handlerReturned:
		w.writeAfterReturned = true
	default:
	}
	w.mu.Unlock()
	w.writeStartedOnce.Do(func() { close(w.writeStarted) })
	<-w.unblockWrite
	select {
	case <-w.closed:
		return 0, net.ErrClosed
	default:
		return len(p), nil
	}
}

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

// TRN-N-004, TRN-T-011, TRN-T-086
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

// TRN-T-086 verifies the control handler waits for in-flight sends before returning.
func TestControlConnection_HandlerWaitsForActiveSendBeforeClose(t *testing.T) {
	left, right := net.Pipe()
	defer right.Close()
	cc := &ControlConnection{conn: left, server: &http.Server{}, controlReady: make(chan struct{}), closed: make(chan struct{}), stopControl: make(chan struct{})}
	cc.sendStateCond = sync.NewCond(&cc.sendStateMu)
	handlerReturned := make(chan struct{})
	writer := &blockedControlWriter{
		writeStarted:    make(chan struct{}),
		unblockWrite:    make(chan struct{}),
		closed:          cc.closed,
		handlerReturned: handlerReturned,
	}
	defer writer.releaseWrite()
	requestCtx, cancel := context.WithCancel(context.Background())
	defer cancel()
	handlerDone := make(chan struct{})
	go func() {
		cc.handleControl(writer, httptest.NewRequest(http.MethodPost, "/control", nil).WithContext(requestCtx))
		close(handlerReturned)
		close(handlerDone)
	}()
	select {
	case <-cc.controlReady:
	case <-time.After(5 * time.Second):
		t.Fatal("phase=control-ready: handler did not publish the control stream")
	}

	envelope := &protocol.Envelope{MessageType: protocol.MessageType_ERROR, EnvironmentId: protoString(testEnvironment), Payload: &protocol.Envelope_Error{Error: &protocol.Error{Message: protoString("failure")}}}
	sendDone := make(chan error, 1)
	go func() { sendDone <- cc.Send(context.Background(), envelope) }()
	select {
	case <-writer.writeStarted:
	case <-time.After(5 * time.Second):
		t.Fatal("phase=send-blocked: send did not enter the control writer")
	}

	cancel()
	if err := cc.Close(); err != nil {
		t.Fatalf("phase=close: %v", err)
	}
	sendClosureObserved := make(chan struct{})
	go func() {
		cc.sendStateMu.Lock()
		for !cc.sendClosed {
			cc.sendStateCond.Wait()
		}
		cc.sendStateMu.Unlock()
		close(sendClosureObserved)
	}()
	select {
	case <-sendClosureObserved:
	case <-time.After(5 * time.Second):
		t.Fatal("phase=handler-closing: handler did not reject new sends")
	}
	select {
	case <-handlerDone:
		t.Fatal("handler returned while a send was still blocked")
	default:
	}
	writer.releaseWrite()
	select {
	case err := <-sendDone:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatalf("in-flight send error=%v, want net.ErrClosed", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("phase=send-return: closed control write did not return")
	}
	select {
	case <-handlerDone:
	case <-time.After(5 * time.Second):
		t.Fatal("phase=handler-return: handler did not finish after send returned")
	}

	if err := cc.Send(context.Background(), envelope); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("send after handler return error=%v, want net.ErrClosed", err)
	}
	writer.mu.Lock()
	defer writer.mu.Unlock()
	if writer.writes != 1 {
		t.Fatalf("control writer writes=%d, want only the blocked in-flight write", writer.writes)
	}
	if writer.writeAfterReturned {
		t.Fatal("control writer was called after the handler returned")
	}
}

// TRN-T-086 verifies closing the WebSocket interrupts an active HTTP/2 handler.
func TestControlConnection_CloseInterruptsActiveHTTP2Handler(t *testing.T) {
	clientConn, serverConn := net.Pipe()
	defer clientConn.Close()
	handlerStarted := make(chan struct{})
	handlerDone := make(chan struct{})
	server := &http.Server{Handler: http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		close(handlerStarted)
		<-r.Context().Done()
		close(handlerDone)
	})}
	protocols := new(http.Protocols)
	protocols.SetUnencryptedHTTP2(true)
	server.Protocols = protocols
	listener := &singleConnListener{conn: serverConn, closed: make(chan struct{})}
	cc := &ControlConnection{conn: serverConn, server: server, listener: listener, closed: make(chan struct{}), stopControl: make(chan struct{})}
	defer serverConn.Close()
	defer server.Close()
	go func() { _ = server.Serve(listener) }()

	transport := &http.Transport{Protocols: protocols, DialContext: func(context.Context, string, string) (net.Conn, error) { return clientConn, nil }}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport}
	request, err := http.NewRequest(http.MethodPost, "http://daemon/control", nil)
	if err != nil {
		t.Fatal(err)
	}
	requestDone := make(chan struct{})
	go func() {
		defer close(requestDone)
		_, _ = client.Do(request)
	}()
	select {
	case <-handlerStarted:
	case <-time.After(5 * time.Second):
		t.Fatal("HTTP/2 handler did not start")
	}

	closeDone := make(chan error, 1)
	go func() { closeDone <- cc.Close() }()
	select {
	case err := <-closeDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		_ = server.Close()
		<-closeDone
		t.Fatal("Close waited for an active HTTP/2 handler instead of closing the transport")
	}
	select {
	case <-handlerDone:
	case <-time.After(time.Second):
		t.Fatal("closing the transport did not cancel the active handler")
	}
	<-requestDone
	_ = clientConn.Close()
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
	goaway := &ControlConnection{closed: make(chan struct{}), goaway: make(chan uint32, 1), observer: recordingObserver{goaway: func(code uint32) { callback <- code }}}
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
	close(cc.controlReady)
	close(cc.closed)
	if err := cc.awaitControl(context.Background()); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("closed wait error=%v", err)
	}
	cc.controlStream = &testStream{reader: bytes.NewReader(nil), writer: failingWriter{}}
	envelope := &protocol.Envelope{MessageType: protocol.MessageType_ERROR, EnvironmentId: protoString(testEnvironment), Payload: &protocol.Envelope_Error{Error: &protocol.Error{Message: protoString("failure")}}}
	if err := cc.Send(context.Background(), envelope); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("send after closed connection error=%v, want net.ErrClosed", err)
	}
}
