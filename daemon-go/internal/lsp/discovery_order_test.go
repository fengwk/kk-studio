package lsp

import (
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/config"
)

// LSP-F-002 verifies configured declaration order selects the first extension match.
func TestDiscovery_ConfigOrderWins(t *testing.T) {
	discovery, err := NewDiscovery([]config.LSPServerConfig{{ID: "first", Extensions: []string{".x"}}, {ID: "second", Extensions: []string{".x"}}}, ResolverOptions{})
	if err != nil {
		t.Fatal(err)
	}
	server, ok := discovery.Server("/tmp/source.X")
	if !ok || server.ID != "first" {
		t.Fatalf("server = %#v, %v; declaration order must win", server, ok)
	}
}
