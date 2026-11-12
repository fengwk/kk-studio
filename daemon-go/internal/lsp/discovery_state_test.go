package lsp

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/config"
)

// LSP-T-033 verifies support states distinguish unsupported, unavailable, and installed servers.
func TestDiscovery_SupportStates(t *testing.T) {
	dir := t.TempDir()
	binary := filepath.Join(dir, "installed")
	if err := os.WriteFile(binary, []byte("x"), 0700); err != nil {
		t.Fatal(err)
	}
	discovery, err := NewDiscovery([]config.LSPServerConfig{{ID: "ready", Command: []string{binary}, Extensions: []string{".go"}}, {ID: "missing", Command: []string{"not-installed-lsp"}, Extensions: []string{".java"}}}, ResolverOptions{Path: "", PathSet: true})
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		path, language       string
		supported, available bool
	}{{"/tmp/main.go", "ready", true, true}, {"/tmp/Main.JAVA", "missing", true, false}, {"/tmp/readme.md", "", false, false}} {
		got := discovery.Support(tc.path)
		if got.Supported != tc.supported || got.Available != tc.available {
			t.Errorf("Support(%q) = %#v", tc.path, got)
		}
		if tc.language == "" && got.Language != nil {
			t.Errorf("unsupported language = %q, want nil", *got.Language)
		}
		if tc.language != "" && (got.Language == nil || *got.Language != tc.language) {
			t.Errorf("Support(%q) language = %v, want %q", tc.path, got.Language, tc.language)
		}
	}
	if _, err := discovery.RequireServer("/tmp/readme.md"); err == nil || err.Error() != "No LSP server configured for /tmp/readme.md. Add the server to lsp.servers in the daemon configuration." {
		t.Fatalf("RequireServer error = %v", err)
	}
	if _, err := discovery.RequireExecutable(ServerConfig{ID: "missing", Command: []string{"not-installed-lsp"}}); err == nil || err.Error() != "LSP server 'missing' is not installed. Command 'not-installed-lsp' must be available on PATH or be an absolute executable path." {
		t.Fatalf("RequireExecutable error = %v", err)
	}
}

// LSP-T-036 verifies empty configuration and empty commands remain unavailable.
func TestDiscovery_EmptyConfig(t *testing.T) {
	discovery, err := NewDiscovery(nil, ResolverOptions{})
	if err != nil || len(discovery.Servers()) != 0 {
		t.Fatalf("empty discovery = %#v, %v", discovery, err)
	}
	if got := discovery.Support("/tmp/main.go"); got.Supported || got.Available || got.Language != nil {
		t.Fatalf("empty support = %#v", got)
	}
	if _, ok := discovery.Executable(ServerConfig{}); ok {
		t.Fatal("empty command unexpectedly resolved")
	}
}

// LSP-T-031 verifies first-match markers are a fallback behind root markers.
func TestDiscovery_FirstMatchAndRootMarkerPrecedence(t *testing.T) {
	base := t.TempDir()
	root := filepath.Join(base, "repo")
	module := filepath.Join(root, "module")
	src := filepath.Join(module, "src")
	if err := os.MkdirAll(src, 0700); err != nil {
		t.Fatal(err)
	}
	for _, marker := range []string{filepath.Join(root, "root.marker"), filepath.Join(module, "first.marker")} {
		if err := os.WriteFile(marker, nil, 0600); err != nil {
			t.Fatal(err)
		}
	}
	file := filepath.Join(src, "main.go")
	if err := os.WriteFile(file, nil, 0600); err != nil {
		t.Fatal(err)
	}
	server := ServerConfig{RootMarkers: []string{"root.marker"}, FirstMatchMarkers: []string{"first.marker"}}
	got, err := (&Discovery{}).WorkspaceRoot(server, file)
	if err != nil || got != root {
		t.Fatalf("root marker precedence = %q, %v", got, err)
	}
	server.RootMarkers = nil
	got, err = (&Discovery{}).WorkspaceRoot(server, file)
	if err != nil || got != module {
		t.Fatalf("first match fallback = %q, %v", got, err)
	}
}
