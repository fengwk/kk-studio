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
	"google.golang.org/protobuf/proto"
)

// DialConfig contains the applied card snapshot and daemon identity needed for a session.
type DialConfig struct {
	Applied              config.DaemonConfig
	InstanceID, Platform string
	ReadToken            func(string) (string, error)
	Resolver             ViewResolver
}

// Dial opens the configured WSS channel and serves its multiplexed HTTP/2 routes.
func Dial(ctx context.Context, c DialConfig) (*ControlConnection, error) {
	token, err := readRegistrationToken(c.Applied.TokenPath, c.ReadToken)
	if err != nil {
		return nil, err
	}
	studio, err := url.Parse(c.Applied.StudioURL)
	if err != nil {
		return nil, fmt.Errorf("invalid studio URL")
	}
	scheme := "ws"
	if studio.Scheme == "https" {
		scheme = "wss"
	} else if studio.Scheme != "http" {
		return nil, fmt.Errorf("invalid studio URL")
	}
	wsURL := scheme + "://" + studio.Host + "/ws/daemon"
	proxyText := ""
	if c.Applied.ProxyURL != nil {
		proxyText = *c.Applied.ProxyURL
	}
	policy, err := NewProxyPolicy(proxyText, "")
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
			return nil, fmt.Errorf("daemon websocket handshake failed with status %d", resp.StatusCode)
		}
		return nil, fmt.Errorf("daemon websocket handshake failed: %w", err)
	}
	ws.SetReadLimit(1 << 20)
	conn := websocket.NetConn(ctx, ws, websocket.MessageBinary)
	cc := newControlConnection(conn, c.Resolver)
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

type ControlConnection struct {
	conn          net.Conn
	server        *http.Server
	listener      *singleConnListener
	mu            sync.Mutex
	readMu        sync.Mutex
	writeMu       sync.Mutex
	closed        chan struct{}
	closeOnce     sync.Once
	controlReady  chan struct{}
	controlStream io.ReadWriteCloser
	resolver      ViewResolver
}

func newControlConnection(conn net.Conn, resolver ViewResolver) *ControlConnection {
	cc := &ControlConnection{conn: conn, closed: make(chan struct{}), controlReady: make(chan struct{}), resolver: resolver}
	mux := http.NewServeMux()
	mux.HandleFunc("/control", cc.handleControl)
	mux.HandleFunc("/ide/", cc.handleIDE)
	mux.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) { http.Error(w, "not found", http.StatusNotFound) })
	cc.server = &http.Server{Handler: mux, HTTP2: &http.HTTP2Config{MaxConcurrentStreams: 512, MaxReceiveBufferPerStream: 1 << 20}}
	protocols := new(http.Protocols)
	protocols.SetUnencryptedHTTP2(true)
	cc.server.Protocols = protocols
	cc.listener = &singleConnListener{conn: conn}
	go func() { _ = cc.server.Serve(cc.listener) }()
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
		http.Error(w, "control stream already open", http.StatusTooManyRequests)
		return
	}
	c.controlStream = pipe
	close(c.controlReady)
	c.mu.Unlock()
	pipe.flush()
	<-r.Context().Done()
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
		return nil, err
	}
	return protocol.UnmarshalEnvelope(b)
}

// Send validates and writes one control envelope.
func (c *ControlConnection) Send(ctx context.Context, e *protocol.Envelope) error {
	b, err := protocol.MarshalEnvelope(e)
	if err != nil {
		return err
	}
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

func (c *ControlConnection) awaitControl(ctx context.Context) error {
	select {
	case <-c.controlReady:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	case <-c.closed:
		return net.ErrClosed
	}
}

// Close shuts down HTTP/2 before terminating the WebSocket transport.
func (c *ControlConnection) Close() error {
	var err error
	c.closeOnce.Do(func() {
		close(c.closed)
		err = c.server.Close()
		if e := c.conn.Close(); err == nil {
			err = e
		}
	})
	return err
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
	conn   net.Conn
	once   sync.Once
	closed chan struct{}
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
	if l.closed == nil {
		l.closed = make(chan struct{})
	}
	select {
	case <-l.closed:
	default:
		close(l.closed)
	}
	return nil
}
func (*singleConnListener) Addr() net.Addr { return dummyAddr("websocket") }

type dummyAddr string

func (a dummyAddr) Network() string { return string(a) }
func (a dummyAddr) String() string  { return string(a) }

var _ = proto.Size
