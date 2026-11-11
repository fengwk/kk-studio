package lsp

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

// LSP-T-034, LSP-T-035, LSP-T-037, and LSP-T-110 through LSP-T-115 exercise executable resolution rules.
func TestResolver_AbsoluteHomePATHAndPATHEXT(t *testing.T) {
	dir := t.TempDir()
	home := filepath.Join(dir, "home")
	bin := filepath.Join(home, "bin")
	if err := os.MkdirAll(bin, 0700); err != nil {
		t.Fatal(err)
	}
	tool := filepath.Join(bin, "tool")
	if err := os.WriteFile(tool, []byte("x"), 0700); err != nil {
		t.Fatal(err)
	}
	resolver := NewExecutableResolver(ResolverOptions{Path: " ", PathSet: true, Home: home, HomeSet: true})
	if got, ok := resolver.Resolve(tool); !ok || got != tool {
		t.Fatalf("absolute resolution = %q %v", got, ok)
	}
	if got, ok := resolver.Resolve("~/bin/tool"); !ok || got != tool {
		t.Fatalf("home resolution = %q %v", got, ok)
	}
	if _, ok := resolver.Resolve("relative/tool"); ok {
		t.Fatal("relative command with separator resolved")
	}
	if _, ok := resolver.Resolve("tool"); ok {
		t.Fatal("command resolved from empty PATH")
	}
	cmd := filepath.Join(bin, "tool.cmd")
	if err := os.WriteFile(cmd, []byte("x"), 0600); err != nil {
		t.Fatal(err)
	}
	windows := NewExecutableResolver(ResolverOptions{Windows: true, Path: bin, PathSet: true, PathExt: ".CMD;.EXE", PathExtSet: true, Home: home, HomeSet: true})
	if got, ok := windows.Resolve("tool"); !ok || got != cmd {
		t.Fatalf("PATHEXT resolution = %q %v", got, ok)
	}
	if _, ok := windows.Resolve("tool.cmd"); !ok {
		t.Fatal("recognized PATHEXT suffix was not resolved")
	}
	if _, ok := NewExecutableResolver(ResolverOptions{Path: bin, PathSet: true}).Resolve("tool.cmd"); ok && runtime.GOOS != "windows" {
		t.Fatal("POSIX resolver accepted non-executable file")
	}
}

// LSP-T-030 verifies a .git worktree file bounds root-marker traversal.
func TestDiscovery_GitFileBoundary(t *testing.T) {
	base := t.TempDir()
	outer := filepath.Join(base, "outer")
	worktree := filepath.Join(outer, "worktree")
	sub := filepath.Join(worktree, "module", "src")
	if err := os.MkdirAll(sub, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(worktree, ".git"), []byte("gitdir: elsewhere"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(outer, "pom.xml"), nil, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(worktree, "module", "pom.xml"), nil, 0600); err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(sub, "A.java")
	if err := os.WriteFile(file, nil, 0600); err != nil {
		t.Fatal(err)
	}
	server := ServerConfig{RootMarkers: []string{"pom.xml"}}
	root, err := (&Discovery{}).WorkspaceRoot(server, file)
	if err != nil || root != filepath.Join(worktree, "module") {
		t.Fatalf("root = %q, %v", root, err)
	}
}
