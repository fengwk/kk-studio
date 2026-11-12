package lsp

import (
	"os"
	"path/filepath"
	"testing"
)

// LSP-T-037 rejects directory executables and keeps a filesystem root as its own workspace root.
func TestResolver_DirectoryAndWorkspaceRootInput(t *testing.T) {
	resolver := NewExecutableResolver(ResolverOptions{})
	if got, ok := resolver.Resolve(t.TempDir()); ok {
		t.Fatalf("directory resolved as executable: %q", got)
	}
	root := string(os.PathSeparator)
	server := ServerConfig{}
	got, err := (&Discovery{}).WorkspaceRoot(server, root)
	if err != nil {
		t.Fatal(err)
	}
	absRoot, err := filepath.Abs(root)
	if err != nil {
		t.Fatal(err)
	}
	if got != absRoot {
		t.Fatalf("workspace root for filesystem root = %q, want %q", got, absRoot)
	}
}
