package lsp

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
)

// LSP-T-039 confirms repeated leases reuse one started process for the same key.
func TestPool_ReusesClient(t *testing.T) {
	base := t.TempDir()
	executable := filepath.Join(base, "fake-lsp")
	if err := os.WriteFile(executable, []byte("fake"), 0700); err != nil {
		t.Fatal(err)
	}
	root := filepath.Join(base, "project")
	if err := os.Mkdir(root, 0700); err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(root, "sample.go")
	if err := os.WriteFile(file, []byte("package main\n"), 0600); err != nil {
		t.Fatal(err)
	}
	discovery, err := NewDiscovery(map[string]config.LSPServerConfig{"go": {Command: []string{executable}, Extensions: []string{".go"}}}, ResolverOptions{})
	if err != nil {
		t.Fatal(err)
	}
	supervisor := &fakeSupervisor{}
	pool := NewPool(discovery, supervisor, PoolOptions{IdleTimeout: time.Hour, ShutdownGrace: time.Second, InitializeTimeout: time.Second})
	first, err := pool.Lease(context.Background(), file)
	if err != nil {
		t.Fatal(err)
	}
	second, err := pool.Lease(context.Background(), file)
	if err != nil {
		t.Fatal(err)
	}
	if first.Client != second.Client || supervisor.starts != 1 {
		t.Fatalf("leases did not share one client (starts=%d)", supervisor.starts)
	}
	first.Release()
	second.Release()
	if err := pool.Close(); err != nil {
		t.Fatal(err)
	}
}
