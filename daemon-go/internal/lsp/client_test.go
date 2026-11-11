package lsp

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/sourcegraph/jsonrpc2"
)

type fakeSupervisor struct {
	process *fakeProcess
	starts  int
}

func (s *fakeSupervisor) Start(context.Context, native.ProcessSpec) (native.ManagedProcess, error) {
	s.starts++
	s.process = newFakeProcess()
	return s.process, nil
}
func (*fakeSupervisor) Close(context.Context) error { return nil }

type fakeProcess struct {
	stdinR  *io.PipeReader
	stdinW  *io.PipeWriter
	stdoutR *io.PipeReader
	stdoutW *io.PipeWriter
	stderrR *io.PipeReader
	stderrW *io.PipeWriter
	done    chan native.ProcessExit
	once    sync.Once
}

func newFakeProcess() *fakeProcess {
	inR, inW := io.Pipe()
	outR, outW := io.Pipe()
	errR, errW := io.Pipe()
	p := &fakeProcess{stdinR: inR, stdinW: inW, stdoutR: outR, stdoutW: outW, stderrR: errR, stderrW: errW, done: make(chan native.ProcessExit, 1)}
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
	p.once.Do(func() {
		_ = p.stdinW.Close()
		_ = p.stdoutW.Close()
		_ = p.stderrW.Close()
		p.done <- native.ProcessExit{Code: 0}
	})
	return nil
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
		if json.Unmarshal(raw, &req) != nil || len(req.ID) == 0 {
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
		case "shutdown":
			result = nil
		default:
			result = nil
		}
		encodedID := json.RawMessage(req.ID)
		response := map[string]any{"jsonrpc": "2.0", "id": encodedID, "result": result}
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
	if len(locations) != 1 || locations[0] != "/tmp/target.go:3:4" {
		t.Fatalf("locations = %v", locations)
	}
	if err = client.Stop(100 * time.Millisecond); err != nil {
		t.Fatal(err)
	}
	if err = client.Stop(100 * time.Millisecond); err != nil {
		t.Fatal(err)
	}
}

var _ native.ManagedProcess = (*fakeProcess)(nil)
var _ jsonrpc2.ObjectCodec = (*lspCodec)(nil)
