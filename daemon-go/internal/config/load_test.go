package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeConfigFixture(t *testing.T, document string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "daemon.json")
	if err := os.WriteFile(path, []byte(document), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(filepath.Dir(path), "daemon.token"), []byte("secret\n"), 0600); err != nil {
		t.Fatal(err)
	}
	return path
}

// RUN-T-159 validates the canonical envelope and derives data/token/gateway locations.
func TestLoadCanonicalConfiguration(t *testing.T) {
	path := writeConfigFixture(t, `{"environmentId":"123e4567-e89b-42d3-a456-426614174000","version":"4","daemonConfig":{"studioUrl":"https://studio.example/","bashExecutable":null,"lsp":{"servers":{"ts":{"command":["tsls"],"extensions":[".ts"],"rootMarkers":["package.json"],"firstMatchMarkers":[]},"go":{"command":["gopls","serve"],"extensions":[".go"],"rootMarkers":[],"firstMatchMarkers":[]}}},"proxyUrl":null}}`)
	got, err := LoadDaemonConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	if got.DataDir != filepath.Dir(path) || got.TokenPath != filepath.Join(filepath.Dir(path), "daemon.token") || got.GatewayURL != "wss://studio.example/ws/daemon" {
		t.Fatalf("unexpected paths/URL: %#v", got)
	}
	if len(got.LSP) != 2 || got.LSP[0].ID != "ts" || got.LSP[1].ID != "go" {
		t.Fatalf("LSP JSON declaration order was not preserved: %+v", got.LSP)
	}
}

func TestLoadNormalizesBackslashMarkers(t *testing.T) {
	path := writeConfigFixture(t, `{"environmentId":"123e4567-e89b-42d3-a456-426614174000","version":"4","daemonConfig":{"studioUrl":"https://studio.example","lsp":{"servers":{"ts":{"command":["tsls"],"extensions":[".ts"],"rootMarkers":["folder\\README.md"],"firstMatchMarkers":[]}}}}}`)
	got, err := LoadDaemonConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	if marker := got.LSP[0].RootMarkers[0]; marker != "folder/README.md" {
		t.Fatalf("normalized root marker = %q", marker)
	}
}

// RUN-T-164 rejects duplicate keys rather than silently accepting a normalized JSON object.
func TestLoadRejectsDuplicateKeys(t *testing.T) {
	path := writeConfigFixture(t, `{"environmentId":"123e4567-e89b-42d3-a456-426614174000","environmentId":"123e4567-e89b-42d3-a456-426614174000","version":"0","daemonConfig":{}}`)
	if _, err := LoadDaemonConfig(path); err == nil {
		t.Fatal("expected duplicate key rejection")
	}
}

// RUN-T-170 confirms check-config validates token without creating resources.
func TestCheckConfigIsReadOnly(t *testing.T) {
	path := writeConfigFixture(t, `{"environmentId":"123e4567-e89b-42d3-a456-426614174000","version":"0","daemonConfig":{"studioUrl":"http://localhost","lsp":{"servers":{"x":{"command":["x"],"extensions":[".x"]}}}}}`)
	if err := CheckConfig(path); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(filepath.Dir(path), "daemon.lock")); !os.IsNotExist(err) {
		t.Fatal("check-config created daemon.lock")
	}
}

// RUN-T-178 confirms invalid configuration diagnostics do not echo configuration contents.
func TestLoadDiagnosticDoesNotEchoSecret(t *testing.T) {
	path := writeConfigFixture(t, `{"environmentId":"secret-url-token","version":"0","daemonConfig":{}}`)
	_, err := LoadDaemonConfig(path)
	if err == nil || strings.Contains(err.Error(), "secret-url-token") {
		t.Fatalf("unsafe diagnostic: %v", err)
	}
}
