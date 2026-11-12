package lsp

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
)

// LSP-N-001 verifies oversized transport failures stop the current process and allow a clean rebuild.
func TestLSPTransport_OversizedResponseTerminatesAndRebuilds(t *testing.T) {
	root := t.TempDir()
	executable := filepath.Join(root, "fake-lsp")
	if err := os.WriteFile(executable, []byte("fake"), 0700); err != nil {
		t.Fatal(err)
	}
	source := filepath.Join(root, "main.go")
	if err := os.WriteFile(source, []byte("package main\n"), 0600); err != nil {
		t.Fatal(err)
	}
	discovery, err := NewDiscovery([]config.LSPServerConfig{{ID: "go", Command: []string{executable}, Extensions: []string{".go"}}}, ResolverOptions{})
	if err != nil {
		t.Fatal(err)
	}
	supervisor := &fakeSupervisor{oversizedMethod: "textDocument/definition", oversizedStarts: 1}
	service := NewService(discovery, supervisor, PoolOptions{IdleTimeout: time.Hour})
	lease, err := service.Pool.Lease(context.Background(), source)
	if err != nil {
		t.Fatal(err)
	}
	firstClient := lease.Client
	lease.Release()
	supervisor.mu.Lock()
	firstProcess := supervisor.process
	supervisor.mu.Unlock()

	if _, err := service.GotoDefinition(context.Background(), source, 1, 1); err == nil || err.Error() != "LSP message of 67108865 bytes exceeds the 64 MiB limit" {
		t.Fatalf("oversized definition error = %v", err)
	}
	deadline := time.Now().Add(time.Second)
	for firstClient.IsAlive() && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if firstClient.IsAlive() || firstProcess.terminates.Load() != 1 {
		t.Fatalf("oversized response left client alive=%v, termination calls=%d", firstClient.IsAlive(), firstProcess.terminates.Load())
	}
	result, err := service.GotoDefinition(context.Background(), source, 1, 1)
	if err != nil || result != filepath.FromSlash("/tmp/target.go")+":3:5" {
		t.Fatalf("rebuilt definition = %q, %v", result, err)
	}
	supervisor.mu.Lock()
	starts, currentProcess := supervisor.starts, supervisor.process
	supervisor.mu.Unlock()
	if starts != 2 || currentProcess == firstProcess {
		t.Fatalf("starts=%d, old process reused=%v", starts, currentProcess == firstProcess)
	}
	if err := service.Close(); err != nil {
		t.Fatal(err)
	}
}
