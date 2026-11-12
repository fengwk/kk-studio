package lsp

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
)

// LSP-T-015 verifies service-level client accounting and file refresh routing.
func TestService_ActiveClientCount(t *testing.T) {
	root := t.TempDir()
	executable := filepath.Join(root, "fake-lsp")
	if err := os.WriteFile(executable, []byte("fake"), 0700); err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(root, "main.go")
	if err := os.WriteFile(file, []byte("package main\n"), 0600); err != nil {
		t.Fatal(err)
	}
	discovery, err := NewDiscovery([]config.LSPServerConfig{{ID: "go", Command: []string{executable}, Extensions: []string{".go"}}}, ResolverOptions{})
	if err != nil {
		t.Fatal(err)
	}
	supervisor := &fakeSupervisor{}
	service := NewService(discovery, supervisor, PoolOptions{IdleTimeout: time.Hour})
	if service.ActiveClientCount() != 0 {
		t.Fatal("new service has an active client")
	}
	var confirmed int
	result, err := service.GotoDefinition(context.Background(), file, 1, 0, func() { confirmed++ })
	if err != nil || result != filepath.FromSlash("/tmp/target.go")+":3:4" {
		t.Fatalf("definition = %q, %v", result, err)
	}
	if service.ActiveClientCount() != 1 || supervisor.starts != 1 || confirmed != 1 {
		t.Fatalf("clients=%d starts=%d confirmations=%d", service.ActiveClientCount(), supervisor.starts, confirmed)
	}
	service.FileChanged(file)
	if err := service.Close(); err != nil {
		t.Fatal(err)
	}
	if service.ActiveClientCount() != 0 {
		t.Fatal("closed service still reports active clients")
	}
}

// LSP-F-016 and LSP-F-024 retain stable path validation error text.
func TestValidatePathErrors(t *testing.T) {
	missing := filepath.Join(t.TempDir(), "missing")
	cases := []struct {
		path string
		want string
	}{{"", "path is required"}, {"relative", "path must be an absolute path (a relative or non-absolute value was given)"}, {missing, "path does not exist: " + missing}}
	for _, tc := range cases {
		_, err := validatePath(tc.path)
		if err == nil || err.Error() != tc.want {
			t.Errorf("validatePath(%q) error = %v, want %q", tc.path, err, tc.want)
		}
	}
}
