package lsp

import (
	"context"
	"errors"
	"testing"
	"time"
)

// LSP-F-027 verifies an unexpected JSON-RPC disconnect terminates its supervised process.
func TestClient_TransportFailureTerminatesProcess(t *testing.T) {
	supervisor := &fakeSupervisor{}
	client, err := Launch(context.Background(), ServerConfig{ID: "fake", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err = client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	if err = supervisor.process.stdoutW.Close(); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(time.Second)
	for supervisor.process.terminates.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if got := supervisor.process.terminates.Load(); got != 1 {
		t.Fatalf("transport disconnect caused %d terminations, want 1", got)
	}
	if err := client.Stop(time.Millisecond); err != nil {
		t.Fatalf("stop after transport termination = %v", err)
	}
}

// LSP-F-027 preserves both transport and process-cleanup failures.
func TestClient_TransportCleanupFailureIsAggregated(t *testing.T) {
	supervisor := &fakeSupervisor{}
	client, err := Launch(context.Background(), ServerConfig{ID: "fake", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err = client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	cleanupErr := errors.New("terminate failed")
	supervisor.process.terminateErr = cleanupErr
	if err = supervisor.process.stdoutW.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-client.transportDone:
	case <-time.After(time.Second):
		t.Fatal("transport cleanup did not finish")
	}
	var aggregate *LspAggregateError
	if !errors.As(client.transportErr, &aggregate) || aggregate.Error() != "LSP transport failure and cleanup failure" || len(aggregate.Errors) != 2 || !errors.Is(client.transportErr, cleanupErr) {
		t.Fatalf("transport error = %#v", client.transportErr)
	}
	_ = client.Stop(time.Millisecond)
}
