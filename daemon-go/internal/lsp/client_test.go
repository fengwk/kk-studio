package lsp

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/sourcegraph/jsonrpc2"
)

type fakeSupervisor struct {
	mu           sync.Mutex
	process      *fakeProcess
	starts       int
	blockMethod  string
	errorMethod  string
	emptyMethod  string
	startEntered chan struct{}
	startGate    chan struct{}
	startErr     error
	earlyExit    *native.ProcessExit
	terminateErr error
}

func (s *fakeSupervisor) Start(context.Context, native.ProcessSpec) (native.ManagedProcess, error) {
	s.mu.Lock()
	s.starts++
	startErr, earlyExit := s.startErr, s.earlyExit
	if startErr != nil {
		s.mu.Unlock()
		return nil, startErr
	}
	process := newFakeProcess(s.blockMethod)
	process.errorMethod = s.errorMethod
	process.emptyMethod = s.emptyMethod
	process.terminateErr = s.terminateErr
	s.process = process
	s.mu.Unlock()
	if earlyExit != nil {
		process.exitOnce.Do(func() { process.done <- *earlyExit })
	}
	if s.startEntered != nil {
		s.startEntered <- struct{}{}
	}
	if s.startGate != nil {
		<-s.startGate
	}
	return process, nil
}
func (*fakeSupervisor) Close(context.Context) error { return nil }

type fakeProcess struct {
	blockMethod  string
	errorMethod  string
	emptyMethod  string
	requests     chan string
	cancelled    chan struct{}
	stdinR       *io.PipeReader
	stdinW       *io.PipeWriter
	stdoutR      *io.PipeReader
	stdoutW      *io.PipeWriter
	stderrR      *io.PipeReader
	stderrW      *io.PipeWriter
	done         chan native.ProcessExit
	once         sync.Once
	exitOnce     sync.Once
	terminates   atomic.Int32
	terminateErr error
}

func newFakeProcess(blockMethod string) *fakeProcess {
	inR, inW := io.Pipe()
	outR, outW := io.Pipe()
	errR, errW := io.Pipe()
	p := &fakeProcess{blockMethod: blockMethod, requests: make(chan string, 64), cancelled: make(chan struct{}, 1), stdinR: inR, stdinW: inW, stdoutR: outR, stdoutW: outW, stderrR: errR, stderrW: errW, done: make(chan native.ProcessExit, 1)}
	go p.serve()
	return p
}
func (p *fakeProcess) PID() int              { return 17 }
func (p *fakeProcess) Stdin() io.WriteCloser { return p.stdinW }
func (p *fakeProcess) Stdout() io.ReadCloser { return p.stdoutR }
func (p *fakeProcess) Stderr() io.ReadCloser { return p.stderrR }
func (p *fakeProcess) Wait(ctx context.Context) (native.ProcessExit, error) {
	select {
	case result := <-p.done:
		p.done <- result
		return result, nil
	case <-ctx.Done():
		return native.ProcessExit{}, ctx.Err()
	}
}
func (p *fakeProcess) Terminate(context.Context, time.Duration) error {
	p.terminates.Add(1)
	p.once.Do(func() {
		_ = p.stdinW.Close()
		_ = p.stdoutW.Close()
		_ = p.stderrW.Close()
		p.exitOnce.Do(func() { p.done <- native.ProcessExit{Code: 0} })
	})
	return p.terminateErr
}
func (p *fakeProcess) serve() {
	codec := newLSPCodec()
	reader := bufio.NewReader(p.stdinR)
	for {
		var raw json.RawMessage
		if err := codec.ReadObject(reader, &raw); err != nil {
			return
		}
		var req struct {
			ID     json.RawMessage `json:"id"`
			Method string          `json:"method"`
			Params json.RawMessage `json:"params"`
		}
		if json.Unmarshal(raw, &req) != nil {
			continue
		}
		select {
		case p.requests <- req.Method:
		default:
		}
		if req.Method == "$/cancelRequest" {
			p.cancelled <- struct{}{}
			continue
		}
		if req.Method == p.blockMethod || len(req.ID) == 0 {
			continue
		}
		var result any
		switch req.Method {
		case "initialize":
			result = map[string]any{"capabilities": map[string]any{"definitionProvider": true, "workspaceSymbolProvider": true, "positionEncoding": "utf-8"}}
		case "textDocument/definition":
			result = []map[string]any{{"uri": "file:///tmp/target.go", "range": map[string]any{"start": map[string]int{"line": 2, "character": 4}}}}
		case "workspace/symbol":
			result = []map[string]any{}
		case "java/classFileContents", "workspace/executeCommand":
			result = "class contents"
		case "shutdown":
			result = nil
		default:
			result = nil
		}
		response := map[string]any{"jsonrpc": "2.0", "id": json.RawMessage(req.ID), "result": result}
		if req.Method == p.errorMethod {
			response = map[string]any{"jsonrpc": "2.0", "id": json.RawMessage(req.ID), "error": map[string]any{"code": -1, "message": "secret server details"}}
		}
		if req.Method == p.emptyMethod {
			response = map[string]any{"jsonrpc": "2.0", "id": json.RawMessage(req.ID), "result": ""}
		}
		if err := codec.WriteObject(p.stdoutW, response); err != nil {
			return
		}
	}
}

// LSP-T-073, LSP-T-074, LSP-T-079 and LSP-T-098 validate the JSON-RPC handshake, position encoding and shutdown path.
func TestClient_HandshakeDefinitionAndStop(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "sample.go")
	if err := os.WriteFile(file, []byte("a😀b\r\n"), 0600); err != nil {
		t.Fatal(err)
	}
	supervisor := &fakeSupervisor{}
	server := ServerConfig{ID: "go", Command: []string{"fake"}}
	client, err := Launch(context.Background(), server, dir, "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err = client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	if !client.IsAlive() {
		t.Fatal("initialized client is not alive")
	}
	if !client.Supports("textDocument/definition") {
		t.Fatal("definition capability not retained")
	}
	pos, err := client.Position(file, 1, 2)
	if err != nil {
		t.Fatal(err)
	}
	if pos["character"] != 5 {
		t.Fatalf("UTF-8 position = %v", pos)
	}
	locations, err := client.Definition(context.Background(), file, 1, 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(locations) != 1 || locations[0] != filepath.FromSlash("/tmp/target.go")+":3:4" {
		t.Fatalf("locations = %v", locations)
	}
	if err = client.Stop(100 * time.Millisecond); err != nil {
		t.Fatal(err)
	}
	if err = client.Stop(100 * time.Millisecond); err != nil {
		t.Fatal(err)
	}
	if err = client.Notify(context.Background(), "test/notification", nil); err == nil || err.Error() != "LSP client stopped" {
		t.Fatalf("notify after stop = %v", err)
	}
}

// LSP-T-084 verifies timeout returns promptly and sends a cancellation notification.
func TestClient_RequestTimeoutSendsCancel(t *testing.T) {
	supervisor := &fakeSupervisor{blockMethod: "slow/request"}
	client, err := Launch(context.Background(), ServerConfig{ID: "fake", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err := client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Millisecond)
	defer cancel()
	sent, finished := false, false
	err = client.call(ctx, "slow/request", map[string]any{}, nil, func() { sent = true }, func() { finished = true })
	var timeout *LspTimeoutError
	if !errors.As(err, &timeout) || !sent || finished {
		t.Fatalf("call err=%v sent=%v finished=%v", err, sent, finished)
	}
	select {
	case <-supervisor.process.cancelled:
	case <-time.After(time.Second):
		t.Fatal("server did not receive $/cancelRequest")
	}
	if err := client.Stop(100 * time.Millisecond); err != nil {
		t.Fatal(err)
	}
}

var _ native.ManagedProcess = (*fakeProcess)(nil)
var _ jsonrpc2.ObjectCodec = (*lspCodec)(nil)
