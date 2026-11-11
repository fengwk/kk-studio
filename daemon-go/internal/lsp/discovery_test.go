package lsp

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/config"
)

// LSP-T-027 through LSP-T-037 and LSP-T-110 through LSP-T-115 cover matching, roots and executable lookup.
func TestDiscovery_MatchesAndResolves(t *testing.T) {
	dir := t.TempDir()
	bin := filepath.Join(dir, "server")
	if err := os.WriteFile(bin, []byte("#!/bin/sh\n"), 0700); err != nil {
		t.Fatal(err)
	}
	root := filepath.Join(dir, "repo")
	if err := os.MkdirAll(filepath.Join(root, "src"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "pom.xml"), nil, 0600); err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(root, "src", "A.JAVA")
	if err := os.WriteFile(file, nil, 0600); err != nil {
		t.Fatal(err)
	}
	d, err := NewDiscovery([]config.LSPServerConfig{{ID: "java", Command: []string{bin}, Extensions: []string{".java"}, RootMarkers: []string{"pom.xml"}}}, ResolverOptions{})
	if err != nil {
		t.Fatal(err)
	}
	support := d.Support(file)
	if support.Language == nil || *support.Language != "java" || !support.Available || !support.Supported {
		t.Fatalf("support = %#v", support)
	}
	server, ok := d.Server(file)
	if !ok || server.ID != "java" {
		t.Fatalf("server = %#v, %v", server, ok)
	}
	got, err := d.WorkspaceRoot(server, file)
	if err != nil || got != root {
		t.Fatalf("root = %q, %v; want %q", got, err, root)
	}
}

// LSP-T-028 verifies duplicate IDs are rejected rather than silently replaced.
func TestDiscovery_DuplicateID(t *testing.T) {
	_, err := NewDiscoveryFromServers([]ServerConfig{{ID: "x"}, {ID: "x"}}, ResolverOptions{})
	if err == nil || err.Error() != "duplicate lsp server id: x" {
		t.Fatalf("error = %v", err)
	}
}
