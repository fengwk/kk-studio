package lsp

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/sourcegraph/jsonrpc2"
)

// Client owns one supervised language-server process and its JSON-RPC session.
type Client struct {
	server           ServerConfig
	root             string
	process          native.ManagedProcess
	conn             *jsonrpc2.Conn
	exited           chan native.ProcessExit
	exitErr          chan error
	closed           atomic.Bool
	requestID        atomic.Uint64
	stopOnce         sync.Once
	stopDone         chan struct{}
	stopErr          error
	mu               sync.RWMutex
	capabilities     map[string]json.RawMessage
	positionEncoding string
	jdtls            bool
	documents        map[string]document
}
type document struct {
	text    string
	version int
}
type stdio struct {
	in   io.WriteCloser
	out  io.ReadCloser
	once sync.Once
}

type rpcHandler func(context.Context, *jsonrpc2.Conn, *jsonrpc2.Request)

func (h rpcHandler) Handle(ctx context.Context, conn *jsonrpc2.Conn, req *jsonrpc2.Request) {
	h(ctx, conn, req)
}

func (s *stdio) Read(p []byte) (int, error)  { return s.out.Read(p) }
func (s *stdio) Write(p []byte) (int, error) { return s.in.Write(p) }
func (s *stdio) Close() error {
	var a, b error
	s.once.Do(func() { a = s.in.Close(); b = s.out.Close() })
	return errors.Join(a, b)
}

// Launch starts a server through native.Supervisor and rejects immediate process exit.
func Launch(ctx context.Context, server ServerConfig, root, executable string, processes native.Supervisor) (*Client, error) {
	if len(server.Command) == 0 {
		return nil, &LspBootstrapError{Message: fmt.Sprintf("LSP server '%s' cannot be started: %s", server.ID, executable), Converged: true, Cause: errors.New("empty command")}
	}
	process, err := processes.Start(ctx, native.ProcessSpec{Executable: executable, Args: append([]string(nil), server.Command[1:]...), Directory: root})
	if err != nil {
		return nil, &LspBootstrapError{Message: fmt.Sprintf("LSP server '%s' cannot be started: %s", server.ID, server.Command[0]), Converged: !errors.Is(err, native.ErrScopeNotConverged), Cause: err}
	}
	c := &Client{server: server, root: root, process: process, exited: make(chan native.ProcessExit, 1), exitErr: make(chan error, 1), stopDone: make(chan struct{}), positionEncoding: "utf-16", documents: map[string]document{}, jdtls: isJDTLS(server.Command)}
	go func() {
		result, e := process.Wait(context.Background())
		if e != nil {
			c.exitErr <- e
			return
		}
		c.exited <- result
		c.closed.Store(true)
	}()
	go func() { _, _ = io.Copy(io.Discard, process.Stderr()) }()
	select {
	case exit := <-c.exited:
		c.exited <- exit
		cleanup := process.Terminate(context.Background(), 0)
		if cleanup != nil {
			return nil, &LspBootstrapError{Message: fmt.Sprintf("LSP server '%s' exited before initialization (exit code %d)", server.ID, exit.Code), Converged: false, Cause: errors.Join(errors.New("server exited early"), cleanup)}
		}
		return nil, &LspBootstrapError{Message: fmt.Sprintf("LSP server '%s' exited before initialization (exit code %d)", server.ID, exit.Code), Converged: true, Cause: errors.New("server exited early")}
	case e := <-c.exitErr:
		return nil, &LspBootstrapError{Message: fmt.Sprintf("LSP server '%s' failed to start", server.ID), Converged: false, Cause: e}
	case <-time.After(200 * time.Millisecond):
	}
	stream := &stdio{in: process.Stdin(), out: process.Stdout()}
	c.conn = jsonrpc2.NewConn(context.Background(), jsonrpc2.NewBufferedStream(stream, newLSPCodec()), rpcHandler(c.handleRequest))
	return c, nil
}

func isJDTLS(command []string) bool {
	for _, part := range command {
		base := strings.ToLower(filepath.Base(part))
		for _, ext := range []string{".exe", ".cmd", ".bat"} {
			base = strings.TrimSuffix(base, ext)
		}
		if base == "jdtls" {
			return true
		}
	}
	return false
}
func (c *Client) handleRequest(ctx context.Context, conn *jsonrpc2.Conn, req *jsonrpc2.Request) {
	var params []map[string]any
	if req.Params != nil {
		_ = json.Unmarshal(*req.Params, &params)
	}
	var result any
	switch req.Method {
	case "workspace/configuration":
		result = make([]map[string]any, len(params))
	case "workspace/workspaceFolders":
		uri := pathURI(c.root)
		result = []map[string]any{{"uri": uri, "name": "workspace"}}
	case "workspace/applyEdit":
		result = map[string]bool{"applied": false}
	case "window/showMessageRequest", "client/registerCapability", "client/unregisterCapability", "window/workDoneProgress/create":
		result = nil
	case "window/showMessage", "window/logMessage", "telemetry/event", "textDocument/publishDiagnostics":
		return
	default:
		result = nil
	}
	if !req.Notif {
		_ = conn.Reply(ctx, req.ID, result)
	}
}
func pathURI(path string) string {
	abs, _ := filepath.Abs(path)
	return (&url.URL{Scheme: "file", Path: filepath.ToSlash(abs)}).String()
}

// Initialize performs LSP initialize and initialized.
func (c *Client) Initialize(ctx context.Context, timeout time.Duration) error {
	if timeout <= 0 {
		timeout = 180 * time.Second
	}
	params := map[string]any{"processId": os.Getpid(), "rootUri": pathURI(c.root), "rootPath": c.root, "workspaceFolders": []map[string]string{{"uri": pathURI(c.root), "name": "workspace"}}, "capabilities": map[string]any{"general": map[string]any{"positionEncodings": []string{"utf-16", "utf-8"}}, "workspace": map[string]any{"workspaceFolders": true, "configuration": false, "applyEdit": false}, "textDocument": map[string]any{"synchronization": map[string]any{"willSave": false, "willSaveWaitUntil": false, "didSave": true}, "definition": map[string]any{"linkSupport": true}}}}
	if c.jdtls {
		params["initializationOptions"] = map[string]any{"extendedClientCapabilities": map[string]bool{"classFileContentsSupport": true}}
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	var response struct {
		Capabilities map[string]json.RawMessage `json:"capabilities"`
	}
	if err := c.Call(ctx, "initialize", params, &response); err != nil {
		return err
	}
	c.mu.Lock()
	c.capabilities = response.Capabilities
	if raw := response.Capabilities["positionEncoding"]; len(raw) > 0 {
		var e string
		if json.Unmarshal(raw, &e) == nil && strings.TrimSpace(e) != "" {
			c.positionEncoding = e
		}
	}
	c.mu.Unlock()
	return c.Notify(context.Background(), "initialized", map[string]any{})
}

// Call sends one request and redacts server-provided error details.
func (c *Client) Call(ctx context.Context, method string, params, result any) error {
	return c.call(ctx, method, params, result, nil, nil)
}

func (c *Client) call(ctx context.Context, method string, params, result any, onSent, onFinished func()) error {
	if ctx.Err() != nil {
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return &LspTimeoutError{Message: "Operation timed out", Cause: ctx.Err()}
		}
		return &LspError{Message: "Operation cancelled", Cause: ctx.Err()}
	}
	if c.closed.Load() {
		return &LspError{Message: "LSP client stopped"}
	}
	id := c.requestID.Add(1)
	started := time.Now()
	deadline, hasDeadline := ctx.Deadline()
	if onSent != nil {
		onSent()
	}
	done := make(chan error, 1)
	go func() { done <- c.conn.Call(ctx, method, params, result, jsonrpc2.PickID(jsonrpc2.ID{Num: id})) }()
	select {
	case err := <-done:
		if err != nil {
			var rpcErr *jsonrpc2.Error
			if onFinished != nil && errors.As(err, &rpcErr) {
				onFinished()
			}
			return &LspError{Message: "LSP request failed (" + method + ")", Cause: err}
		}
		if onFinished != nil {
			onFinished()
		}
		return nil
	case <-ctx.Done():
		_ = c.conn.Notify(context.Background(), "$/cancelRequest", map[string]any{"id": id})
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			message := fmt.Sprintf("LSP request timed out (%s).", method)
			if hasDeadline {
				if timeout := deadline.Sub(started); timeout > 0 {
					message = fmt.Sprintf("LSP request timed out after %dms (%s).", timeout.Milliseconds(), method)
				}
			}
			return &LspTimeoutError{Message: message, Cause: ctx.Err()}
		}
		return &LspError{Message: "Operation cancelled", Cause: ctx.Err()}
	case <-c.exited:
		return c.exitError()
	}
}
func (c *Client) Notify(ctx context.Context, method string, params any) error {
	if c.closed.Load() {
		return &LspError{Message: "LSP client stopped"}
	}
	if err := c.conn.Notify(ctx, method, params); err != nil {
		return &LspError{Message: "LSP request failed (" + method + ")", Cause: err}
	}
	return nil
}
func (c *Client) exitError() error {
	select {
	case exit := <-c.exited:
		c.exited <- exit
		return &LspError{Message: fmt.Sprintf("LSP server '%s' exited (exit code %d)", c.server.ID, exit.Code)}
	default:
		return &LspError{Message: "LSP client stopped"}
	}
}
func (c *Client) IsAlive() bool {
	if c.closed.Load() {
		return false
	}
	select {
	case <-c.exited:
		return false
	default:
		return true
	}
}
func (c *Client) Supports(method string) bool {
	c.mu.RLock()
	defer c.mu.RUnlock()
	field := ""
	switch method {
	case "textDocument/definition":
		field = "definitionProvider"
	case "workspace/symbol":
		field = "workspaceSymbolProvider"
	default:
		return c.jdtls
	}
	raw, ok := c.capabilities[field]
	if !ok || string(raw) == "false" || string(raw) == "null" {
		return false
	}
	return true
}

// Stop is idempotent and shares the same cleanup result between callers.
func (c *Client) Stop(grace time.Duration) error {
	c.stopOnce.Do(func() { go c.finishStop(grace) })
	<-c.stopDone
	return c.stopErr
}
func (c *Client) finishStop(grace time.Duration) {
	defer close(c.stopDone)
	c.closed.Store(true)
	ctx := context.Background()
	if grace > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, grace)
		defer cancel()
	}
	_ = c.conn.Call(ctx, "shutdown", map[string]any{}, nil)
	_ = c.conn.Notify(context.Background(), "exit", map[string]any{})
	disposeErr := c.conn.Close()
	terminateErr := c.process.Terminate(context.Background(), grace)
	if disposeErr != nil && terminateErr != nil {
		c.stopErr = errors.Join(&LspError{Message: "LSP connection disposal failed during shutdown", Cause: disposeErr}, &LspError{Message: "LSP server process termination failed during shutdown", Cause: terminateErr})
	} else if disposeErr != nil {
		c.stopErr = &LspError{Message: "LSP connection disposal failed during shutdown", Cause: disposeErr}
	} else if terminateErr != nil {
		c.stopErr = &LspError{Message: "LSP server process termination failed during shutdown", Cause: terminateErr}
	}
}
