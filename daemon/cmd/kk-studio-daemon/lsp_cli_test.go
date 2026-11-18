package main

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/lsp"
)

// Generated JSON must be the lower-cased lsp.servers section accepted by daemon configuration.
func TestLSPConfigCommandEmitsConfigurationSection(t *testing.T) {
	if runtime.GOOS != "linux" || runtime.GOARCH != "amd64" {
		t.Skip("TypeScript LSP profile is verified only on linux/amd64")
	}
	if os.Getenv("PATH") == "" {
		t.Skip("PATH is required to resolve Node.js")
	}
	library := t.TempDir()
	profile := filepath.Join(library, lsp.TypeScriptProfileID)
	writeLSPFixtureFile(t, filepath.Join(profile, "node_modules", "typescript-language-server", "package.json"), `{"version":"4.3.4"}`)
	writeLSPFixtureFile(t, filepath.Join(profile, "node_modules", "typescript", "package.json"), `{"version":"5.8.3"}`)
	writeLSPFixtureFile(t, filepath.Join(profile, "node_modules", "typescript-language-server", "lib", "cli.mjs"), "export {};\n")
	binary := filepath.Join(profile, "node_modules", ".bin", "typescript-language-server")
	writeLSPFixtureFile(t, binary, "#!/bin/sh\nexit 0\n")
	if err := os.Chmod(binary, 0700); err != nil {
		t.Fatal(err)
	}
	nodeDir := t.TempDir()
	writeLSPFixtureFile(t, filepath.Join(nodeDir, "node"), "#!/bin/sh\nprintf 'v22.0.0\\n'\n")
	if err := os.Chmod(filepath.Join(nodeDir, "node"), 0700); err != nil {
		t.Fatal(err)
	}
	oldPath := os.Getenv("PATH")
	if err := os.Setenv("PATH", nodeDir); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Setenv("PATH", oldPath) })

	var out, stderr bytes.Buffer
	args := []string{"--lsp-config", lsp.TypeScriptProfileID, library}
	if code := RunCLI(context.Background(), args, Dependencies{}, &out, &stderr); code != 0 || stderr.Len() != 0 {
		t.Fatalf("lsp config code=%d stdout=%q stderr=%q", code, out.String(), stderr.String())
	}
	var generated struct {
		Servers map[string]struct {
			Command           []string `json:"command"`
			Extensions        []string `json:"extensions"`
			RootMarkers       []string `json:"rootMarkers"`
			FirstMatchMarkers []string `json:"firstMatchMarkers"`
		} `json:"servers"`
	}
	if err := json.Unmarshal(out.Bytes(), &generated); err != nil {
		t.Fatal(err)
	}
	server, ok := generated.Servers[lsp.TypeScriptProfileID]
	if !ok || len(server.Command) != 3 || server.Command[2] != "--stdio" || len(server.Extensions) == 0 || len(server.RootMarkers) == 0 || len(server.FirstMatchMarkers) == 0 {
		t.Fatalf("generated lsp section = %#v", generated)
	}
}

func writeLSPFixtureFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0600); err != nil {
		t.Fatal(err)
	}
}
