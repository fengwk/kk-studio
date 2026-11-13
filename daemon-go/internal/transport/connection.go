package transport

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/fengwk/kk-studio/daemon/internal/config"
	_ "github.com/fengwk/kk-studio/daemon/internal/h2xconnect"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	runtimeapi "github.com/fengwk/kk-studio/daemon/internal/runtime"
	"google.golang.org/protobuf/proto"
)

// DialConfig contains the applied card snapshot and daemon identity needed for a session.
type DialConfig struct {
	Applied              config.DaemonConfig
	InstanceID, Platform string
	ReadToken            func(string) (string, error)
	OnUpgradeFailed      func(int, *protocol.UpgradeRequired)
	OnGoaway             func(uint32)
	Resolver             ViewResolver
}

// Dial opens the configured WSS channel and serves its multiplexed HTTP/2 routes.
func Dial(ctx context.Context, c DialConfig) (*ControlConnection, error) {
	token, err := readRegistrationToken(c.Applied.TokenPath, c.ReadToken)
	if err != nil {
		return nil, err
	}
	studio, err := url.Parse(c.Applied.StudioURL)
	if err != nil || studio.Host == "" || studio.Opaque != "" {
		return nil, fmt.Errorf("invalid studio URL")
	}
	scheme := "ws"
	switch strings.ToLower(studio.Scheme) {
	case "https":
		scheme = "wss"
	case "http":
	default:
		return nil, fmt.Errorf("invalid studio URL")
	}
	wsURL := scheme + "://" + studio.Host + "/ws/daemon"
	proxyText := ""
	if c.Applied.ProxyURL != nil {
		proxyText = *c.Applied.ProxyURL
	}
	policy, err := NewProxyPolicy(proxyText)
	if err != nil {
		return nil, err
	}
	hdr := make(http.Header)
	hdr.Set("Authorization", "Bearer "+token)
	hdr.Set("KK-Daemon-Protocol", "4")
	hdr.Set("KK-Daemon-Instance", c.InstanceID)
	hdr.Set("KK-Daemon-Platform", c.Platform)
	hdr.Set("KK-Environment-Id", c.Applied.EnvironmentID)
	options := &websocket.DialOptions{HTTPHeader: hdr, CompressionMode: websocket.CompressionDisabled}
	client := policy.HTTPClient()
	options.HTTPClient = client
	ws, resp, err := websocket.Dial(ctx, wsURL, options)
	if err != nil {
		if resp != nil {
			var upgrade *protocol.UpgradeRequired
			if resp.StatusCode == http.StatusUpgradeRequired && resp.Body != nil {
				body, readErr := io.ReadAll(io.LimitReader(resp.Body, (1<<20)+1))
				if readErr == nil && len(body) > 0 && len(body) <= 1<<20 {
					candidate := new(protocol.UpgradeRequired)
					if proto.Unmarshal(body, candidate) == nil && protocol.ValidateUpgrade(candidate) == nil {
						upgrade = candidate
					}
				}
			}
			if c.OnUpgradeFailed != nil {
				c.OnUpgradeFailed(resp.StatusCode, upgrade)
			}
			if resp.StatusCode == http.StatusUnauthorized {
				return nil, runtimeapi.ErrRegistrationRejected
			}
			return nil, fmt.Errorf("daemon websocket handshake failed with status %d", resp.StatusCode)
		}
		return nil, fmt.Errorf("daemon websocket handshake failed: %w", err)
	}
	conn := websocket.NetConn(ctx, ws, websocket.MessageBinary)
	ws.SetReadLimit(1 << 20)
	cc := newControlConnection(conn, c.Resolver, c.OnGoaway)
	return cc, nil
}

func readRegistrationToken(path string, read func(string) (string, error)) (string, error) {
	if read != nil {
		v, err := read(path)
		if err != nil {
			return "", fmt.Errorf("cannot read registration token: %w", err)
		}
		return strings.TrimSpace(v), nil
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("cannot read registration token: %w", err)
	}
	return strings.TrimSpace(string(b)), nil
}

const (
	controlStreamTimeout = 10 * time.Second
	inboundIdleTimeout   = 45 * time.Second
)

var _ runtimeapi.Connection = (*ControlConnection)(nil)

type ControlConnection struct {
	conn          net.Conn
	server        *http.Server
	listener      *singleConnListener
	mu            sync.Mutex
	readMu        sync.Mutex
	writeMu       sync.Mutex
	sendStateMu   sync.Mutex
	sendStateCond *sync.Cond
	sendClosed    bool
	activeSends   int
	closed        chan struct{}
	stopControl   chan struct{}
	closeOnce     sync.Once
	closeErr      error
	controlReady  chan struct{}
	controlStream io.ReadWriteCloser
	resolver      ViewResolver
	goaway        chan uint32
	onGoaway      func(uint32)
}

func newControlConnection(conn net.Conn, resolver ViewResolver, onGoaway func(uint32)) *ControlConnection {
	cc := &ControlConnection{closed: make(chan struct{}), stopControl: make(chan struct{}), controlReady: make(chan struct{}), resolver: resolver, onGoaway: onGoaway}
	cc.conn = &idleConn{Conn: conn, timeout: inboundIdleTimeout, observer: &h2FrameObserver{}, onGoaway: cc.signalGoaway}
	if onGoaway != nil {
		cc.goaway = make(chan uint32, 1)
		go cc.forwardGoaway()
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/control", cc.handleControl)
	mux.HandleFunc("/ide/", cc.handleIDE)
	mux.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) { http.Error(w, "not found", http.StatusNotFound) })
	cc.server = &http.Server{Handler: mux, HTTP2: &http.HTTP2Config{MaxConcurrentStreams: 512, MaxReceiveBufferPerStream: 1 << 20, MaxReadFrameSize: 16 << 10}}
	protocols := new(http.Protocols)
	protocols.SetUnencryptedHTTP2(true)
	cc.server.Protocols = protocols
	cc.listener = &singleConnListener{conn: cc.conn, closed: make(chan struct{})}
	go func() { _ = cc.server.Serve(cc.listener) }()
	go cc.awaitInitialControlStream(controlStreamTimeout)
	return cc
}

func (c *ControlConnection) handleControl(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	pipe := &controlPipe{reader: r.Body, writer: w, flush: func() {
		if f, ok := w.(http.Flusher); ok {
			f.Flush()
		}
	}}
	c.mu.Lock()
	if c.controlStream != nil {
		c.mu.Unlock()
		http.Error(w, "control stream already open", http.StatusConflict)
		return
	}
	c.controlStream = pipe
	close(c.controlReady)
	c.mu.Unlock()
	defer c.finishControlHandler()
	pipe.flush()
	select {
	case <-r.Context().Done():
		go c.Close()
	case <-c.stopControl:
	case <-c.closed:
	}
}

func (c *ControlConnection) signalGoaway(code uint32) {
	if c.goaway == nil {
		return
	}
	select {
	case c.goaway <- code:
	default:
	}
}

func (c *ControlConnection) forwardGoaway() {
	for {
		select {
		case code := <-c.goaway:
			c.onGoaway(code)
		case <-c.closed:
			return
		}
	}
}

func (c *ControlConnection) awaitInitialControlStream(timeout time.Duration) {
	if !waitForControlStream(timeout, c.controlReady, c.closed) {
		_ = c.Close()
	}
}

func waitForControlStream(timeout time.Duration, ready, closed <-chan struct{}) bool {
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case <-ready:
		return true
	case <-closed:
		return false
	case <-timer.C:
		return false
	}
}

// Receive reads and validates the next control envelope.
func (c *ControlConnection) Receive(ctx context.Context) (*protocol.Envelope, error) {
	c.readMu.Lock()
	defer c.readMu.Unlock()
	if err := c.awaitControl(ctx); err != nil {
		return nil, err
	}
	c.mu.Lock()
	stream := c.controlStream
	c.mu.Unlock()
	if deadline, ok := ctx.Deadline(); ok {
		_ = c.conn.SetReadDeadline(deadline)
		defer c.conn.SetReadDeadline(time.Time{})
	}
	b, err := protocol.ReadFrame(stream)
	if err != nil {
		_ = c.Close()
		return nil, err
	}
	e, err := protocol.UnmarshalEnvelope(b)
	if err != nil {
		_ = c.Close()
		return nil, err
	}
	return e, nil
}

// Send validates and writes one control envelope.
func (c *ControlConnection) Send(ctx context.Context, e *protocol.Envelope) error {
	b, err := protocol.MarshalEnvelope(e)
	if err != nil {
		return err
	}
	if err := c.beginSend(); err != nil {
		return err
	}
	defer c.endSend()
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	if err := c.awaitControl(ctx); err != nil {
		return err
	}
	c.mu.Lock()
	stream := c.controlStream
	c.mu.Unlock()
	if deadline, ok := ctx.Deadline(); ok {
		_ = c.conn.SetWriteDeadline(deadline)
		defer c.conn.SetWriteDeadline(time.Time{})
	}
	return protocol.WriteFrame(stream, b)
}

func (c *ControlConnection) beginSend() error {
	c.sendStateMu.Lock()
	defer c.sendStateMu.Unlock()
	if c.sendClosed {
		return net.ErrClosed
	}
	select {
	case <-c.closed:
		return net.ErrClosed
	default:
	}
	c.activeSends++
	return nil
}

func (c *ControlConnection) endSend() {
	c.sendStateMu.Lock()
	c.activeSends--
	if c.sendClosed && c.activeSends == 0 && c.sendStateCond != nil {
		c.sendStateCond.Broadcast()
	}
	c.sendStateMu.Unlock()
}

func (c *ControlConnection) finishControlHandler() {
	c.sendStateMu.Lock()
	if c.sendStateCond == nil {
		c.sendStateCond = sync.NewCond(&c.sendStateMu)
	}
	c.sendClosed = true
	c.sendStateCond.Broadcast()
	for c.activeSends > 0 {
		c.sendStateCond.Wait()
	}
	c.sendStateMu.Unlock()
}

func (c *ControlConnection) awaitControl(ctx context.Context) error {
	select {
	case <-c.closed:
		return net.ErrClosed
	default:
	}
	select {
	case <-c.controlReady:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	case <-c.closed:
		return net.ErrClosed
	}
}

// Close terminates the WebSocket transport and its HTTP/2 server.
func (c *ControlConnection) Close() error {
	c.closeOnce.Do(func() {
		close(c.stopControl)
		close(c.closed)
		connErr := c.conn.Close()
		c.closeErr = c.server.Close()
		if c.closeErr == nil {
			c.closeErr = connErr
		}
	})
	return c.closeErr
}

type controlPipe struct {
	reader io.Reader
	writer io.Writer
	flush  func()
}

func (p *controlPipe) Read(b []byte) (int, error)  { return p.reader.Read(b) }
func (p *controlPipe) Write(b []byte) (int, error) { n, e := p.writer.Write(b); p.flush(); return n, e }
func (*controlPipe) Close() error                  { return nil }

type singleConnListener struct {
	conn      net.Conn
	once      sync.Once
	closeOnce sync.Once
	closed    chan struct{}
}

func (l *singleConnListener) Accept() (net.Conn, error) {
	var c net.Conn
	l.once.Do(func() { c = l.conn })
	if c != nil {
		return c, nil
	}
	<-l.closed
	return nil, net.ErrClosed
}
func (l *singleConnListener) Close() error {
	l.closeOnce.Do(func() { close(l.closed) })
	return nil
}
func (*singleConnListener) Addr() net.Addr { return dummyAddr("websocket") }

type dummyAddr string

func (a dummyAddr) Network() string { return string(a) }
func (a dummyAddr) String() string  { return string(a) }

type idleConn struct {
	net.Conn
	timeout      time.Duration
	mu           sync.Mutex
	readDeadline time.Time
	observer     *h2FrameObserver
	onGoaway     func(uint32)
}

func (c *idleConn) SetReadDeadline(deadline time.Time) error {
	c.mu.Lock()
	c.readDeadline = deadline
	defer c.mu.Unlock()
	return c.updateReadDeadline()
}

func (c *idleConn) Read(p []byte) (int, error) {
	c.mu.Lock()
	err := c.updateReadDeadline()
	c.mu.Unlock()
	if err != nil {
		return 0, err
	}
	n, err := c.Conn.Read(p)
	if n > 0 && c.observer != nil {
		c.observer.feed(p[:n], c.onGoaway)
	}
	if n > 0 {
		c.mu.Lock()
		_ = c.updateReadDeadline()
		c.mu.Unlock()
	}
	return n, err
}

func (c *idleConn) updateReadDeadline() error {
	deadline := time.Now().Add(c.timeout)
	if !c.readDeadline.IsZero() && c.readDeadline.Before(deadline) {
		deadline = c.readDeadline
	}
	return c.Conn.SetReadDeadline(deadline)
}
