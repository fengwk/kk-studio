package lsp

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

// LSP-T-110 expands supported home prefixes without changing ordinary paths.
func TestResolver_ExpandHomePrefixes(t *testing.T) {
	home := filepath.Join(t.TempDir(), "home")
	resolver := NewExecutableResolver(ResolverOptions{Home: home, HomeSet: true, Path: "", PathSet: true})
	for _, tc := range []struct{ input, want string }{
		{"~", home}, {"~/bin/tool", filepath.Join(home, "bin", "tool")},
		{"~\\bin\\tool", filepath.Join(home, "bin\\tool")},
		{"$HOME/bin/tool", filepath.Join(home, "bin", "tool")},
		{"${HOME}/bin/tool", filepath.Join(home, "bin", "tool")}, {"other/path", "other/path"},
	} {
		if got := resolver.expand(tc.input); got != tc.want {
			t.Errorf("expand(%q) = %q, want %q", tc.input, got, tc.want)
		}
	}
	if runtime.GOOS != "windows" {
		tool := filepath.Join(home, "bin", "tool")
		if err := os.MkdirAll(filepath.Dir(tool), 0700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(tool, []byte("x"), 0700); err != nil {
			t.Fatal(err)
		}
		for _, command := range []string{"~/bin/tool", "$HOME/bin/tool", "${HOME}/bin/tool"} {
			if got, ok := resolver.Resolve(command); !ok || got == "" {
				t.Errorf("Resolve(%q) = %q, %v", command, got, ok)
			}
		}
	}
}

// LSP-T-111 and LSP-T-035 cover PATH empty elements and ordered POSIX search.
func TestResolver_POSIXPathSearch(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("POSIX PATH-list semantics are platform-specific")
	}
	base := t.TempDir()
	bin := filepath.Join(base, "bin")
	if err := os.MkdirAll(bin, 0700); err != nil {
		t.Fatal(err)
	}
	tool := filepath.Join(bin, "my-tool")
	if err := os.WriteFile(tool, []byte("x"), 0700); err != nil {
		t.Fatal(err)
	}
	missing := NewExecutableResolver(ResolverOptions{Path: "", PathSet: true})
	if _, ok := missing.Resolve("my-tool"); ok {
		t.Fatal("empty PATH unexpectedly resolved a command")
	}
	path := filepath.Join(base, "missing") + string(os.PathListSeparator) + bin
	resolver := NewExecutableResolver(ResolverOptions{Path: string(os.PathListSeparator) + path + string(os.PathListSeparator), PathSet: true})
	canonical, err := filepath.EvalSymlinks(tool)
	if err != nil {
		t.Fatal(err)
	}
	if got, ok := resolver.Resolve("my-tool"); !ok || got != canonical {
		t.Fatalf("PATH resolution = %q, %v; want %q", got, ok, canonical)
	}
	if _, ok := resolver.Resolve("not-installed-lsp"); ok {
		t.Fatal("missing command unexpectedly resolved")
	}
}

// LSP-T-112 verifies POSIX executability checks and missing-command behavior.
func TestResolver_POSIXExecutableBits(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("POSIX executable-bit checks are platform-specific")
	}
	bin := t.TempDir()
	plain := filepath.Join(bin, "plain-tool")
	executable := filepath.Join(bin, "executable-tool")
	for _, tc := range []struct {
		path string
		mode os.FileMode
	}{{plain, 0600}, {executable, 0700}} {
		if err := os.WriteFile(tc.path, []byte("x"), tc.mode); err != nil {
			t.Fatal(err)
		}
	}
	resolver := NewExecutableResolver(ResolverOptions{Path: bin, PathSet: true})
	if _, ok := resolver.Resolve("plain-tool"); ok {
		t.Fatal("non-executable PATH file resolved")
	}
	canonical, err := filepath.EvalSymlinks(executable)
	if err != nil {
		t.Fatal(err)
	}
	if got, ok := resolver.Resolve("executable-tool"); !ok || got != canonical {
		t.Fatalf("executable PATH file = %q, %v; want %q", got, ok, canonical)
	}
	if _, ok := resolver.Resolve("missing-tool"); ok {
		t.Fatal("missing PATH file resolved")
	}
}

// LSP-T-113 and LSP-T-114 verify Windows PATHEXT defaults and relative command rejection.
func TestResolver_WindowsPATHEXTDefaultsAndRelativePaths(t *testing.T) {
	bin := t.TempDir()
	bat := filepath.Join(bin, "tool.bat")
	if err := os.WriteFile(bat, []byte("x"), 0600); err != nil {
		t.Fatal(err)
	}
	resolver := NewExecutableResolver(ResolverOptions{Windows: true, Path: bin, PathSet: true})
	if got, ok := resolver.Resolve("tool"); !ok || filepath.Base(got) != filepath.Base(bat) {
		t.Fatalf("default PATHEXT resolution = %q, %v", got, ok)
	}
	if _, ok := resolver.Resolve("missing.exe"); ok {
		t.Fatal("missing recognized extension resolved")
	}
	if _, ok := resolver.Resolve(`.\relative\tool`); ok {
		t.Fatal("relative Windows path resolved")
	}
}
