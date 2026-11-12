package lsp

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/config"
)

// LSP-T-027 covers extension case folding, multi-extension matches, and extensionless files.
func TestDiscovery_ExtensionCases(t *testing.T) {
	discovery, err := NewDiscovery([]config.LSPServerConfig{
		{ID: "ts", Command: []string{"missing-ts"}, Extensions: []string{".ts", ".tsx"}},
		{ID: "java", Command: []string{"missing-java"}, Extensions: []string{".java"}},
	}, ResolverOptions{})
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		path, want string
	}{{"/workspace/App.tsx", "ts"}, {"/workspace/Main.JAVA", "java"}, {"/workspace/Makefile", ""}, {"/workspace/readme.md", ""}, {"/workspace/.ts", ""}} {
		server, ok := discovery.Server(tc.path)
		if (tc.want != "") != ok || ok && server.ID != tc.want {
			t.Errorf("Server(%q) = %#v, %v; want %q", tc.path, server, ok, tc.want)
		}
	}
}

// LSP-T-029 verifies the shallowest marker inside a git worktree wins over nested module markers.
func TestDiscovery_ShallowestRootMarkerInGitWorktree(t *testing.T) {
	base := t.TempDir()
	root := filepath.Join(base, "repo")
	module := filepath.Join(root, "module")
	src := filepath.Join(module, "src")
	if err := os.MkdirAll(filepath.Join(root, ".git"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(src, 0700); err != nil {
		t.Fatal(err)
	}
	for _, marker := range []string{filepath.Join(root, "pom.xml"), filepath.Join(module, "pom.xml")} {
		if err := os.WriteFile(marker, nil, 0600); err != nil {
			t.Fatal(err)
		}
	}
	file := filepath.Join(src, "Main.java")
	if err := os.WriteFile(file, nil, 0600); err != nil {
		t.Fatal(err)
	}
	got, err := (&Discovery{}).WorkspaceRoot(ServerConfig{RootMarkers: []string{"pom.xml"}}, file)
	if err != nil || got != root {
		t.Fatalf("workspace root = %q, %v; want %q", got, err, root)
	}
}
