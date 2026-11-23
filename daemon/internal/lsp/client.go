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
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/sourcegraph/jsonrpc2"
)

// Client owns one supervised language-server process and its JSON-RPC session.
type Client struct {
	server               ServerConfig
	root                 string
	process              native.ManagedProcess
	conn                 *jsonrpc2.Conn
	exited               chan native.ProcessExit
	exitErr              chan error
	closed               atomic.Bool
	exitReported         atomic.Bool
	parkedConfirmations  atomic.Int64
	requestID            atomic.Uint64
	stopOnce             sync.Once
	stopDone             chan struct{}
	stopStarted          chan struct{}
	stopErr              error
	stopping             atomic.Bool
	transportTerminating atomic.Bool
	exitSignal           chan struct{}
	transportDone        chan struct{}
	transportMu          sync.Mutex
	transportErr         error
	shutdownGrace        time.Duration
	sendGateOnce         sync.Once
	sendGate             chan struct{}
	mu                   sync.RWMutex
	capabilities         ServerCapabilities
	positionEncoding     string
	jdtls                bool
	documents            map[string]document
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
func Launch(ctx context.Context, server ServerConfig, root, executable string, processes native.Supervisor, environment []string) (*Client, error) {
	if len(server.Command) == 0 {
		return nil, &LspBootstrapError{Message: fmt.Sprintf("LSP server '%s' cannot be started: %s", server.ID, executable), Converged: true, Cause: errors.New("empty command")}
	}
	process, err := processes.Start(ctx, native.ProcessSpec{Executable: executable, Args: append([]string(nil), server.Command[1:]...), Directory: root, Env: environment})
	if err != nil {
		return nil, &LspBootstrapError{Message: fmt.Sprintf("LSP server '%s' cannot be started: %s", server.ID, executable), Converged: !errors.Is(err, native.ErrScopeNotConverged), Cause: err}
	}
	c := &Client{server: server, root: root, process: process, exited: make(chan native.ProcessExit, 1), exitErr: make(chan error, 1), stopDone: make(chan struct{}), stopStarted: make(chan struct{}), exitSignal: make(chan struct{}), transportDone: make(chan struct{}), shutdownGrace: time.Second, positionEncoding: "utf-16", documents: map[string]document{}, jdtls: isJDTLS(server.Command)}
	go func() {
		defer close(c.exitSignal)
		result, e := process.Wait(context.Background())
		if e != nil {
			c.exitErr <- e
			c.closed.Store(true)
			return
		}
		c.exitReported.Store(true)
		c.exited <- result
		c.closed.Store(true)
	}()
	go func() { _, _ = io.Copy(io.Discard, process.Stderr()) }()
	select {
	case exit := <-c.exited:
		c.exited <- exit
		cleanup := process.Terminate(context.Background(), 0)
		if cleanup != nil {
			return nil, &LspBootstrapError{
				Message:   fmt.Sprintf("LSP server '%s' exited before initialization (exit code %s)", server.ID, processExitCodeText(exit)),
				Converged: false,
				Cause:     &LspAggregateError{Message: "LSP launch failed and cleanup failed", Errors: []error{errors.New("server exited early"), cleanup}},
			}
		}
		return nil, &LspBootstrapError{Message: fmt.Sprintf("LSP server '%s' exited before initialization (exit code %s)", server.ID, processExitCodeText(exit)), Converged: true, Cause: errors.New("server exited early")}
	case e := <-c.exitErr:
		return nil, &LspBootstrapError{Message: fmt.Sprintf("LSP server '%s' failed to start", server.ID), Converged: false, Cause: e}
	case <-time.After(200 * time.Millisecond):
	}
	stream := &stdio{in: process.Stdin(), out: process.Stdout()}
	codec := &recordingCodec{codec: newLSPCodec(), onReadError: c.recordTransportFailure}
	c.conn = jsonrpc2.NewConn(context.Background(), jsonrpc2.NewBufferedStream(stream, codec), rpcHandler(c.handleRequest))
	go c.watchTransport()
	return c, nil
}

func isJDTLS(command []string) bool {
	for _, part := range command {
		base := strings.ToLower(filepath.Base(strings.ReplaceAll(part, `\`, "/")))
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
	var result any
	switch req.Method {
	case "workspace/configuration":
		var params WorkspaceConfigurationParams
		if req.Params != nil {
			_ = json.Unmarshal(*req.Params, &params)
		}
		result = WorkspaceConfigurationResult(make([]WorkspaceConfigurationItem, len(params.Items)))
	case "workspace/workspaceFolders":
		var params WorkspaceFoldersParams
		if req.Params != nil {
			_ = json.Unmarshal(*req.Params, &params)
		}
		result = WorkspaceFoldersResult{{URI: pathURI(c.root), Name: "workspace"}}
	case "workspace/applyEdit":
		result = WorkspaceApplyEditResult{}
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
	uriPath := filepath.ToSlash(abs)
	if runtime.GOOS == "windows" && len(uriPath) >= 2 && uriPath[1] == ':' {
		uriPath = "/" + uriPath
	}
	return (&url.URL{Scheme: "file", Path: uriPath}).String()
}

func (c *Client) recordTransportFailure(err error) {
	c.transportMu.Lock()
	defer c.transportMu.Unlock()
	if c.transportErr == nil {
		c.transportErr = &LspError{Message: err.Error(), Cause: err}
	}
}

func (c *Client) getTransportFailure() error {
	c.transportMu.Lock()
	defer c.transportMu.Unlock()
	return c.transportErr
}

func (c *Client) processExitedWithTransportEOF() bool {
	return c.closed.Load() && errors.Is(c.getTransportFailure(), io.EOF)
}

func (c *Client) watchTransport() {
	<-c.conn.DisconnectNotify()
	defer close(c.transportDone)
	if c.stopping.Load() || c.closed.Load() {
		return
	}
	c.transportTerminating.Store(true)
	recorded := c.getTransportFailure()
	if recorded == nil {
		recorded = &LspError{Message: "LSP transport failure"}
		c.recordTransportFailure(recorded)
	}
	if err := c.process.Terminate(context.Background(), 0); err != nil {
		c.transportMu.Lock()
		c.transportErr = &LspError{
			Message: recorded.Error(),
			Cause:   &LspAggregateError{Message: "LSP transport failure and cleanup failure", Errors: []error{recorded, err}},
		}
		c.transportMu.Unlock()
	}
}

// Initialize performs LSP initialize and initialized.
func (c *Client) Initialize(ctx context.Context, timeout time.Duration) error {
	if timeout <= 0 {
		timeout = 180 * time.Second
	}
	params := InitializeParams{
		Capabilities: ClientCapabilities{
			General:   GeneralClientCapabilities{PositionEncodings: []string{"utf-16", "utf-8"}},
			Workspace: WorkspaceClientCapabilities{WorkspaceFolders: true},
			TextDocument: TextDocumentClientCapabilities{
				Synchronization: TextDocumentSyncClientCapabilities{DidSave: true},
				Definition:      DefinitionClientCapabilities{LinkSupport: true},
			},
		},
		ProcessID:        c.process.PID(),
		RootPath:         c.root,
		RootURI:          pathURI(c.root),
		WorkspaceFolders: []WorkspaceFolder{{Name: "workspace", URI: pathURI(c.root)}},
	}
	if c.jdtls {
		params.InitializationOptions = &InitializationClientCapabilities{
			ExtendedClientCapabilities: ExtendedClientCapabilities{ClassFileContentsSupport: true},
		}
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	var response InitializeResult
	if err := c.Call(ctx, "initialize", params, &response); err != nil {
		return err
	}
	c.mu.Lock()
	c.capabilities = response.Capabilities
	if encoding := response.Capabilities.PositionEncoding; strings.TrimSpace(encoding) != "" {
		c.positionEncoding = encoding
	}
	c.mu.Unlock()
	return c.Notify(context.Background(), "initialized", InitializedParams{})
}

// Call sends one request and redacts server-provided error details.
func (c *Client) Call(ctx context.Context, method string, params, result any) error {
	return c.call(ctx, method, params, result, nil, nil)
}

func (c *Client) call(ctx context.Context, method string, params, result any, onSent, onFinished func()) error {
	return c.callPrepared(ctx, method, params, result, onSent, onFinished, nil)
}

// callPrepared observes the request deadline independently from the synchronous
// JSON-RPC write. The send worker owns the gate through preparation and dispatch,
// then waits for the response without holding it.
func (c *Client) callPrepared(ctx context.Context, method string, params, result any, onSent, onFinished func(), prepare func() (any, error)) error {
	if ctx.Err() != nil {
		return contextFailure(ctx)
	}
	if c.closed.Load() {
		return &LspError{Message: "LSP client stopped"}
	}
	id := c.requestID.Add(1)
	requestCtx, requestCancel := context.WithCancel(ctx)
	defer requestCancel()
	startedAt := time.Now()
	deadline, hasDeadline := ctx.Deadline()
	var stateMu sync.Mutex
	abandoned, attempted := false, false
	var finishOnce sync.Once
	var settled atomic.Bool
	finish := func() {
		settled.Store(true)
		if onFinished != nil {
			finishOnce.Do(onFinished)
		}
	}
	done := make(chan error, 1)
	go func() {
		if err := c.acquireSend(requestCtx); err != nil {
			done <- err
			return
		}
		gateHeld := true
		defer func() {
			if gateHeld {
				c.releaseSend()
			}
		}()
		if requestCtx.Err() != nil {
			done <- contextFailure(requestCtx)
			return
		}
		if c.closed.Load() {
			done <- &LspError{Message: "LSP client stopped"}
			return
		}
		requestParams := params
		if prepare != nil {
			var err error
			requestParams, err = prepare()
			if err != nil {
				done <- err
				return
			}
		}
		stateMu.Lock()
		if abandoned || requestCtx.Err() != nil || c.stopping.Load() {
			stateMu.Unlock()
			if requestCtx.Err() != nil {
				done <- contextFailure(requestCtx)
			} else {
				done <- &LspError{Message: "LSP client stopped"}
			}
			return
		}
		attempted = true
		if onSent != nil {
			onSent()
		}
		stateMu.Unlock()

		waiter, err := c.conn.DispatchCall(requestCtx, method, requestParams, jsonrpc2.PickID(jsonrpc2.ID{Num: id}))
		c.releaseSend()
		gateHeld = false
		if err != nil {
			done <- err
			return
		}
		waitResult := make(chan error, 1)
		go func() { waitResult <- waiter.Wait(requestCtx, result) }()
		select {
		case err = <-waitResult:
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				go c.sendCancel(id)
			}
			if err == nil {
				finish()
			} else {
				var rpcErr *jsonrpc2.Error
				if errors.As(err, &rpcErr) {
					finish()
				}
			}
		case <-c.stopStarted:
			err = &LspError{Message: "LSP client stopped"}
		}
		done <- err
	}()

	defer func() {
		stateMu.Lock()
		abandoned = true
		sent := attempted
		stateMu.Unlock()
		if sent && onFinished != nil && c.exitSignal != nil && !settled.Load() {
			c.parkedConfirmations.Add(1)
			go func() {
				defer c.parkedConfirmations.Add(-1)
				c.confirmWhenProcessStops(finish)
			}()
		}
	}()
	select {
	case err := <-done:
		if err == nil {
			return nil
		}
		if ctx.Err() != nil && (errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded)) {
			return timedOutFailure(ctx, method, startedAt, deadline, hasDeadline)
		}
		return c.responseFailure(err, method, finish)
	case <-ctx.Done():
		return timedOutFailure(ctx, method, startedAt, deadline, hasDeadline)
	case <-c.stopStarted:
		return &LspError{Message: "LSP client stopped"}
	case exit := <-c.exited:
		c.exited <- exit
		return c.exitFailure()
	case exitErr := <-c.exitErr:
		c.exitErr <- exitErr
		return &LspError{Message: "LSP client stopped", Cause: exitErr}
	}
}

func (c *Client) initSendGate() {
	c.sendGateOnce.Do(func() {
		c.sendGate = make(chan struct{}, 1)
		c.sendGate <- struct{}{}
	})
}

func (c *Client) acquireSend(ctx context.Context) error {
	c.initSendGate()
	select {
	case <-c.sendGate:
		if ctx.Err() != nil {
			c.releaseSend()
			return contextFailure(ctx)
		}
		if c.stopping.Load() || c.closed.Load() {
			c.releaseSend()
			return &LspError{Message: "LSP client stopped"}
		}
		return nil
	case <-ctx.Done():
		return contextFailure(ctx)
	case <-c.stopStarted:
		return &LspError{Message: "LSP client stopped"}
	}
}

func (c *Client) acquireSendForStop(ctx context.Context) error {
	c.initSendGate()
	select {
	case <-c.sendGate:
		if ctx.Err() != nil {
			c.releaseSend()
			return ctx.Err()
		}
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (c *Client) releaseSend() {
	c.initSendGate()
	c.sendGate <- struct{}{}
}

func (c *Client) sendCancel(id uint64) {
	if err := c.acquireSend(context.Background()); err != nil {
		return
	}
	defer c.releaseSend()
	_ = c.conn.Notify(context.Background(), "$/cancelRequest", CancelRequestParams{ID: id})
}

// contextFailure maps an already-failed request context before anything is sent.
func contextFailure(ctx context.Context) error {
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return newLspTimeoutError("Operation timed out", ctx.Err())
	}
	return &LspError{Message: "Operation cancelled", Cause: ctx.Err()}
}

// timedOutFailure names the elapsed budget so callers can distinguish a real server hang.
func timedOutFailure(ctx context.Context, method string, started time.Time, deadline time.Time, hasDeadline bool) error {
	if !errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return &LspError{Message: "Operation cancelled", Cause: ctx.Err()}
	}
	message := fmt.Sprintf("LSP request timed out (%s).", method)
	if hasDeadline {
		if timeout := deadline.Sub(started); timeout > 0 {
			message = fmt.Sprintf("LSP request timed out after %dms (%s).", timeout.Milliseconds(), method)
		}
	}
	return newLspTimeoutError(message, ctx.Err())
}

// responseFailure prefers the recorded transport or exit cause over the JSON-RPC error.
func (c *Client) responseFailure(err error, method string, finish func()) error {
	if c.stopping.Load() {
		if c.exitReported.Load() {
			return c.exitError()
		}
		return &LspError{Message: "LSP client stopped"}
	}
	if c.processExitedWithTransportEOF() {
		return c.exitError()
	}
	if c.getTransportFailure() != nil {
		<-c.transportDone
		if c.processExitedWithTransportEOF() {
			return c.exitError()
		}
		if transportErr := c.getTransportFailure(); transportErr != nil {
			return transportErr
		}
	}
	if errors.Is(err, jsonrpc2.ErrClosed) {
		<-c.transportDone
		if transportErr := c.getTransportFailure(); transportErr != nil {
			return transportErr
		}
		if !c.stopping.Load() {
			exitErr := c.exitError()
			var lspErr *LspError
			if errors.As(exitErr, &lspErr) && lspErr.Message != "LSP client stopped" {
				return exitErr
			}
		}
	}
	var rpcErr *jsonrpc2.Error
	if errors.As(err, &rpcErr) {
		finish()
	}
	return &LspError{Message: "LSP request failed (" + method + ")", Cause: err}
}

// exitFailure maps a process exit observed while the request was in flight.
func (c *Client) exitFailure() error {
	if c.stopping.Load() && !c.exitReported.Load() {
		return &LspError{Message: "LSP client stopped"}
	}
	if c.transportTerminating.Load() {
		<-c.transportDone
		if !c.processExitedWithTransportEOF() {
			if transportErr := c.getTransportFailure(); transportErr != nil {
				return transportErr
			}
		}
	}
	return c.exitError()
}
func (c *Client) Notify(ctx context.Context, method string, params any) error {
	if err := c.acquireSend(ctx); err != nil {
		return err
	}
	defer c.releaseSend()
	return c.notifyLocked(ctx, method, params)
}

func (c *Client) notifyLocked(ctx context.Context, method string, params any) error {
	if c.closed.Load() {
		return &LspError{Message: "LSP client stopped"}
	}
	if err := c.conn.Notify(ctx, method, params); err != nil {
		return &LspError{Message: "LSP request failed (" + method + ")", Cause: err}
	}
	return nil
}
func processExitCodeText(exit native.ProcessExit) string {
	if exit.Code == nil {
		return "unknown"
	}
	return strconv.Itoa(*exit.Code)
}

func (c *Client) exitError() error {
	select {
	case exit := <-c.exited:
		c.exited <- exit
		return &LspError{Message: fmt.Sprintf("LSP server '%s' exited (exit code %s)", c.server.ID, processExitCodeText(exit))}
	default:
		return &LspError{Message: "LSP client stopped"}
	}
}
func (c *Client) confirmWhenProcessStops(onFinished func()) {
	<-c.exitSignal
	if c.Stop(c.shutdownGrace) == nil {
		onFinished()
	}
}

func (c *Client) IsAlive() bool {
	if c.closed.Load() {
		return false
	}
	select {
	case exit := <-c.exited:
		c.exited <- exit
		return false
	case err := <-c.exitErr:
		c.exitErr <- err
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
	switch field {
	case "definitionProvider":
		return bool(c.capabilities.DefinitionProvider)
	case "workspaceSymbolProvider":
		return bool(c.capabilities.WorkspaceSymbolProvider)
	default:
		return false
	}
}

// Stop is idempotent and shares the same cleanup result between callers.
func (c *Client) Stop(grace time.Duration) error {
	c.stopOnce.Do(func() {
		c.stopping.Store(true)
		c.closed.Store(true)
		if c.stopStarted != nil {
			close(c.stopStarted)
		}
		go c.finishStop(grace)
	})
	<-c.stopDone
	return c.stopErr
}
func (c *Client) finishStop(grace time.Duration) {
	defer close(c.stopDone)
	ctx := context.Background()
	if grace > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, grace)
		defer cancel()
	}
	var protocolDone chan struct{}
	if !c.exitReported.Load() {
		protocolDone = make(chan struct{})
		go func() {
			defer close(protocolDone)
			if grace > 0 && c.acquireSendForStop(ctx) == nil {
				shutdown, err := c.conn.DispatchCall(ctx, "shutdown", nil)
				c.releaseSend()
				if err == nil {
					_ = shutdown.Wait(ctx, nil)
				}
			}
			if c.acquireSendForStop(context.Background()) == nil {
				_ = c.conn.Notify(context.Background(), "exit", InitializedParams{})
				c.releaseSend()
			}
		}()
		if grace > 0 && c.exitSignal != nil {
			timer := time.NewTimer(grace)
			select {
			case <-c.exitSignal:
			case <-timer.C:
			}
			if !timer.Stop() {
				select {
				case <-timer.C:
				default:
				}
			}
		}
	}
	c.mu.Lock()
	clear(c.documents)
	c.mu.Unlock()
	disposeDone := make(chan error, 1)
	go func() { disposeDone <- c.conn.Close() }()
	terminateErr := c.process.Terminate(context.Background(), grace)
	disposeErr := <-disposeDone
	if protocolDone != nil {
		<-protocolDone
	}
	if errors.Is(disposeErr, jsonrpc2.ErrClosed) || errors.Is(disposeErr, io.ErrClosedPipe) || errors.Is(disposeErr, os.ErrClosed) {
		disposeErr = nil
	}
	if disposeErr != nil && terminateErr != nil {
		c.stopErr = &LspAggregateError{
			Message: "LSP client shutdown failed",
			Errors: []error{
				&LspError{Message: "LSP connection disposal failed during shutdown", Cause: disposeErr},
				&LspError{Message: "LSP server process termination failed during shutdown", Cause: terminateErr},
			},
		}
	} else if disposeErr != nil {
		c.stopErr = &LspError{Message: "LSP connection disposal failed during shutdown", Cause: disposeErr}
	} else if terminateErr != nil {
		c.stopErr = &LspError{Message: "LSP server process termination failed during shutdown", Cause: terminateErr}
	}
}
