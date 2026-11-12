package lsp

import (
	"context"
	"encoding/json"
	"testing"
	"time"
)

// LSP-T-098 verifies shutdown uses null params, sends exit once, and waits for process exit within grace.
func TestClient_StopShutdownParametersAndGraceWait(t *testing.T) {
	shutdownParams := make(chan json.RawMessage, 1)
	exitSeen := make(chan struct{}, 1)
	allowExit := make(chan struct{})
	supervisor := &fakeSupervisor{shutdownParams: shutdownParams, exitBlocks: true, exitSeen: exitSeen, allowProcessExit: allowExit}
	client, err := Launch(context.Background(), ServerConfig{ID: "fake", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err := client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	stopped := make(chan error, 1)
	go func() { stopped <- client.Stop(time.Second) }()
	select {
	case params := <-shutdownParams:
		if len(params) != 0 && string(params) != "null" {
			t.Fatalf("shutdown params = %s, want null or omitted", params)
		}
	case <-time.After(time.Second):
		t.Fatal("shutdown request not observed")
	}
	select {
	case <-exitSeen:
	case <-time.After(time.Second):
		t.Fatal("exit notification not observed")
	}
	if got := supervisor.process.terminates.Load(); got != 0 {
		t.Fatalf("termination began before process exit: %d", got)
	}
	select {
	case err := <-stopped:
		t.Fatalf("Stop returned before exit was released: %v", err)
	case <-time.After(20 * time.Millisecond):
	}
	close(allowExit)
	select {
	case err := <-stopped:
		if err != nil {
			t.Fatalf("stop error = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("Stop did not return after process exit")
	}
	if got := supervisor.process.terminates.Load(); got != 1 {
		t.Fatalf("termination count = %d, want 1", got)
	}
}

// LSP-T-098 verifies zero grace sends exit without waiting indefinitely for shutdown.
func TestClient_StopWithoutGraceSkipsShutdown(t *testing.T) {
	shutdownParams := make(chan json.RawMessage, 1)
	supervisor := &fakeSupervisor{shutdownParams: shutdownParams}
	client, err := Launch(context.Background(), ServerConfig{ID: "fake", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err := client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	if err := client.Stop(0); err != nil {
		t.Fatalf("stop error = %v", err)
	}
	select {
	case params := <-shutdownParams:
		t.Fatalf("unexpected shutdown request with params %s", params)
	default:
	}
	if got := supervisor.process.terminates.Load(); got != 1 {
		t.Fatalf("termination count = %d, want 1", got)
	}
}
