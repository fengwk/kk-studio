package lsp

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/sourcegraph/jsonrpc2"
)

type closeErrorConn struct {
	net.Conn
	err error
}

func (c closeErrorConn) Close() error {
	_ = c.Conn.Close()
	return c.err
}

// LSP-T-100 and LSP-T-101 ensure disposal failure does not skip termination and both failures aggregate.
func TestClient_StopAggregatesDisposalAndTerminationFailures(t *testing.T) {
	left, right := net.Pipe()
	disposeErr := errors.New("connection close failed")
	terminateErr := errors.New("process termination failed")
	conn := jsonrpc2.NewConn(context.Background(), jsonrpc2.NewBufferedStream(closeErrorConn{Conn: left, err: disposeErr}, newLSPCodec()), rpcHandler(nil))
	process := newFakeProcess("")
	process.terminateErr = terminateErr
	client := &Client{conn: conn, process: process, stopDone: make(chan struct{})}
	client.exitReported.Store(true)
	t.Cleanup(func() { _ = right.Close(); _ = process.Terminate(context.Background(), 0) })

	err := client.Stop(time.Second)
	var aggregate *LspAggregateError
	if !errors.As(err, &aggregate) || aggregate.Error() != "LSP client shutdown failed" || len(aggregate.Errors) != 2 {
		t.Fatalf("stop error = %#v", err)
	}
	var disposal *LspError
	if !errors.As(aggregate.Errors[0], &disposal) || disposal.Error() != "LSP connection disposal failed during shutdown" || !errors.Is(aggregate.Errors[0], disposeErr) {
		t.Fatalf("disposal error = %#v", aggregate.Errors[0])
	}
	var termination *LspError
	if !errors.As(aggregate.Errors[1], &termination) || termination.Error() != "LSP server process termination failed during shutdown" || !errors.Is(aggregate.Errors[1], terminateErr) {
		t.Fatalf("termination error = %#v", aggregate.Errors[1])
	}
	if process.terminates.Load() != 1 {
		t.Fatalf("process termination count = %d", process.terminates.Load())
	}
}
