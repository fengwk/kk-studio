package main

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/lsp"
)

// Generated JSON must be the lower-cased lsp.servers section accepted by daemon configuration.
func TestLSPConfigCommandEmitsConfigurationSection(t *testing.T) {
	library := requireLSPCLIProfile(t)
	nodeDir := t.TempDir()
	writeLSPFixtureFile(t, filepath.Join(nodeDir, "node"), "#!/bin/sh\nprintf 'v22.0.0\\n'\n")
	if err := os.Chmod(filepath.Join(nodeDir, "node"), 0700); err != nil {
		t.Fatal(err)
	}
	loginEnvironmentProfile(t, nodeDir)
	// The Daemon PATH resolves Bash only, so Node.js can come from the login profile alone.
	t.Setenv("PATH", bashOnlyPath(t))

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
	node, err := filepath.EvalSymlinks(filepath.Join(nodeDir, "node"))
	if err != nil {
		t.Fatal(err)
	}
	if server.Command[0] != node {
		t.Fatalf("Node.js was not resolved from the login environment: %q, want %q", server.Command[0], node)
	}
}

// The login environment is the only source of LSP tools; the Daemon PATH is not a fallback.
func TestLSPConfigCommandRejectsMissingLoginTool(t *testing.T) {
	library := requireLSPCLIProfile(t)
	t.Setenv("KK_TEST_EMPTY_BIN", t.TempDir())
	profile := filepath.Join(t.TempDir(), "profile")
	writeLSPFixtureFile(t, profile, `export PATH="$KK_TEST_EMPTY_BIN"`+"\n")
	t.Setenv("BASH_ENV", profile)
	t.Setenv("PATH", bashOnlyPath(t))

	var out, stderr bytes.Buffer
	args := []string{"--lsp-config", lsp.TypeScriptProfileID, library}
	if code := RunCLI(context.Background(), args, Dependencies{}, &out, &stderr); code != 1 || !strings.Contains(stderr.String(), "LSP configuration unavailable") {
		t.Fatalf("lsp config code=%d stdout=%q stderr=%q", code, out.String(), stderr.String())
	}
	if !strings.Contains(stderr.String(), "Node.js 18 or later") {
		t.Fatalf("missing Node.js diagnostic = %q", stderr.String())
	}
}

// Installation must select npm from the login environment rather than from the Daemon PATH.
func TestInstallLSPCommandUsesLoginEnvironmentNPM(t *testing.T) {
	requireLSPCLIProfile(t)
	loginBin := t.TempDir()
	marker := filepath.Join(t.TempDir(), "npm-invocations")
	t.Setenv("KK_TEST_NPM_MARKER", marker)
	writeLSPFixtureFile(t, filepath.Join(loginBin, "npm"), fakeNPMInstallerScript)
	if err := os.Chmod(filepath.Join(loginBin, "npm"), 0700); err != nil {
		t.Fatal(err)
	}
	loginEnvironmentProfile(t, loginBin)
	// The Daemon PATH keeps Bash plus a decoy npm that must stay unused.
	daemonBin := bashOnlyPath(t)
	writeLSPFixtureFile(t, filepath.Join(daemonBin, "npm"), "#!/bin/sh\nexit 1\n")
	if err := os.Chmod(filepath.Join(daemonBin, "npm"), 0700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", daemonBin)

	library := t.TempDir()
	var out, stderr bytes.Buffer
	args := []string{"--install-lsp", lsp.TypeScriptProfileID, library}
	if code := RunCLI(context.Background(), args, Dependencies{}, &out, &stderr); code != 0 || stderr.Len() != 0 {
		t.Fatalf("lsp install code=%d stdout=%q stderr=%q", code, out.String(), stderr.String())
	}
	if !strings.Contains(out.String(), "Installed "+lsp.TypeScriptProfileID) {
		t.Fatalf("lsp install output = %q", out.String())
	}
	cli := filepath.Join(library, lsp.TypeScriptProfileID, "node_modules", "typescript-language-server", "lib", "cli.mjs")
	if _, err := os.Stat(cli); err != nil {
		t.Fatalf("installed profile is incomplete: %v", err)
	}
	if invocations, err := os.ReadFile(marker); err != nil || len(invocations) == 0 {
		t.Fatalf("login-environment npm was not used: %q, %v", invocations, err)
	}
}

const fakeNPMInstallerScript = `#!/bin/sh
set -eu
printf x >> "$KK_TEST_NPM_MARKER"
mkdir -p node_modules/typescript-language-server/lib node_modules/typescript node_modules/.bin
printf '{"version":"4.3.4"}' > node_modules/typescript-language-server/package.json
printf '{"version":"5.8.3"}' > node_modules/typescript/package.json
printf 'export {};\n' > node_modules/typescript-language-server/lib/cli.mjs
printf '#!/bin/sh\nexit 0\n' > node_modules/.bin/typescript-language-server
chmod 755 node_modules/.bin/typescript-language-server
`

// requireLSPCLIProfile skips where Bash or the verified TypeScript profile is unavailable and
// returns a library directory holding the installed profile material the commands inspect.
func requireLSPCLIProfile(t *testing.T) string {
	t.Helper()
	if runtime.GOOS != "linux" || runtime.GOARCH != "amd64" {
		t.Skip("TypeScript LSP profile is verified only on linux/amd64")
	}
	if _, err := config.ResolveBash(nil); err != nil {
		t.Skip(err)
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
	return library
}

// loginEnvironmentProfile exposes the login-only binary directory through BASH_ENV, which a
// non-interactive login shell sources after the system profile.
func loginEnvironmentProfile(t *testing.T, bin string) {
	t.Helper()
	t.Setenv("KK_TEST_BASE_PATH", os.Getenv("PATH"))
	profile := filepath.Join(t.TempDir(), "profile")
	writeLSPFixtureFile(t, profile, `export PATH="$KK_TEST_LOGIN_BIN:$KK_TEST_BASE_PATH"`+"\n")
	t.Setenv("KK_TEST_LOGIN_BIN", bin)
	t.Setenv("BASH_ENV", profile)
}

// bashOnlyPath returns a PATH that resolves the Daemon's Bash and nothing else.
func bashOnlyPath(t *testing.T) string {
	t.Helper()
	bash, err := config.ResolveBash(nil)
	if err != nil {
		t.Skip(err)
	}
	dir := t.TempDir()
	if err := os.Symlink(bash, filepath.Join(dir, "bash")); err != nil {
		t.Skip(err)
	}
	return dir
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
