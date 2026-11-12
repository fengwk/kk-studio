package lsp

import (
	"context"
	"errors"
	"io"
	"strings"
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

// LSP-F-027 terminates a process when the JSON-RPC connection disconnects without a codec error.
func TestClient_TransportCloseTerminatesProcess(t *testing.T) {
	supervisor := &fakeSupervisor{}
	client, err := Launch(context.Background(), ServerConfig{ID: "fake", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err = client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	if err = client.conn.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-client.transportDone:
	case <-time.After(time.Second):
		t.Fatal("transport close cleanup did not finish")
	}
	if got := supervisor.process.terminates.Load(); got != 1 {
		t.Fatalf("transport close caused %d terminations, want 1", got)
	}
	if got := client.getTransportFailure(); got == nil || got.Error() == "" {
		t.Fatalf("transport error = %v", got)
	}
	_ = client.Stop(time.Millisecond)
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
	var transportErr *LspError
	var aggregate *LspAggregateError
	if !errors.As(client.transportErr, &transportErr) || transportErr.Error() != io.EOF.Error() || !errors.As(transportErr.Cause, &aggregate) || aggregate.Error() != "LSP transport failure and cleanup failure" || len(aggregate.Errors) != 2 || !errors.Is(client.transportErr, io.EOF) || !errors.Is(client.transportErr, cleanupErr) {
		t.Fatalf("transport error = %#v", client.transportErr)
	}
	_ = client.Stop(time.Millisecond)
}

// LSP-F-011 and LSP-T-082 preserve the exact banner-limit error through the client transport.
func TestClient_BannerLimitErrorIsReported(t *testing.T) {
	supervisor := &fakeSupervisor{blockMethod: "initialize"}
	client, err := Launch(context.Background(), ServerConfig{ID: "fake", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	initializeErr := make(chan error, 1)
	go func() { initializeErr <- client.Initialize(context.Background(), 2*time.Second) }()
	select {
	case method := <-supervisor.process.requests:
		if method != "initialize" {
			t.Fatalf("unexpected request before banner: %q", method)
		}
	case <-time.After(time.Second):
		t.Fatal("initialize request was not sent")
	}
	_, _ = io.WriteString(supervisor.process.stdoutW, strings.Repeat("x", maxBannerBytes+1))
	select {
	case err = <-initializeErr:
	case <-time.After(time.Second):
		t.Fatal("initialize did not fail after the invalid banner")
	}
	want := "LSP server produced more than 8192 bytes of non-protocol output before the first message"
	if err == nil || err.Error() != want {
		t.Fatalf("initialize error = %v, want %q", err, want)
	}
	if err := client.Stop(time.Millisecond); err != nil {
		t.Fatalf("stop after banner failure = %v", err)
	}
}
