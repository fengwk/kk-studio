package lsp

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/sourcegraph/jsonrpc2"
)

// LSP-T-099 verifies concurrent Stop calls share one delayed termination result.
func TestClient_ConcurrentStopSharesTermination(t *testing.T) {
	left, right := net.Pipe()
	process := newFakeProcess("")
	gate := make(chan struct{})
	process.terminateGate = gate
	process.terminateErr = errors.New("deferred termination failure")
	conn := jsonrpc2.NewConn(context.Background(), jsonrpc2.NewBufferedStream(closeErrorConn{Conn: left}, newLSPCodec()), rpcHandler(nil))
	client := &Client{conn: conn, process: process, stopDone: make(chan struct{})}
	client.exitReported.Store(true)
	t.Cleanup(func() { _ = right.Close(); _ = process.Terminate(context.Background(), 0) })

	results := make(chan error, 2)
	started := make(chan struct{}, 2)
	for i := 0; i < 2; i++ {
		go func() {
			started <- struct{}{}
			results <- client.Stop(time.Second)
		}()
	}
	<-started
	<-started
	deadline := time.After(time.Second)
	for process.terminates.Load() == 0 {
		select {
		case <-deadline:
			t.Fatal("termination did not start")
		default:
			time.Sleep(time.Millisecond)
		}
	}
	select {
	case err := <-results:
		t.Fatalf("Stop returned before termination settled: %v", err)
	case <-time.After(20 * time.Millisecond):
	}
	if process.terminates.Load() != 1 {
		t.Fatalf("termination calls before release = %d", process.terminates.Load())
	}
	close(gate)
	first, second := <-results, <-results
	if first == nil || first != second || first.Error() != "LSP server process termination failed during shutdown" {
		t.Fatalf("concurrent stop errors = %v, %v", first, second)
	}
	if process.terminates.Load() != 1 {
		t.Fatalf("termination calls after release = %d", process.terminates.Load())
	}
}

// LSP-T-100 verifies connection disposal failure still terminates the process and remains the returned error.
func TestClient_StopDisposalFailureStillTerminates(t *testing.T) {
	left, right := net.Pipe()
	disposeErr := errors.New("connection close failed")
	process := newFakeProcess("")
	conn := jsonrpc2.NewConn(context.Background(), jsonrpc2.NewBufferedStream(closeErrorConn{Conn: left, err: disposeErr}, newLSPCodec()), rpcHandler(nil))
	client := &Client{conn: conn, process: process, stopDone: make(chan struct{})}
	client.exitReported.Store(true)
	t.Cleanup(func() { _ = right.Close(); _ = process.Terminate(context.Background(), 0) })

	err := client.Stop(time.Second)
	var lspErr *LspError
	if !errors.As(err, &lspErr) || lspErr.Error() != "LSP connection disposal failed during shutdown" || !errors.Is(err, disposeErr) {
		t.Fatalf("stop error = %#v", err)
	}
	if process.terminates.Load() != 1 {
		t.Fatalf("process termination count = %d", process.terminates.Load())
	}
}
