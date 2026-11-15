package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

// Allow startup config without LSP and preserve an unresolved Bash setting.
func TestLoadOptionalLSPAndUnavailableBash(t *testing.T) {
	t.Setenv("PATH", t.TempDir())
	configured := filepath.Join(t.TempDir(), "missing-bash")
	configuredJSON, err := json.Marshal(configured)
	if err != nil {
		t.Fatal(err)
	}
	path := writeConfigFixture(t, `{"environmentId":"123e4567-e89b-42d3-a456-426614174000","version":"0","daemonConfig":{"studioUrl":"https://studio.example","bashExecutable":`+string(configuredJSON)+`}}`)
	got, err := LoadDaemonConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	if got.BashExecutable != nil || got.BashExecutableSetting == nil || *got.BashExecutableSetting != configured {
		t.Fatalf("startup Bash configuration = executable %v setting %v", got.BashExecutable, got.BashExecutableSetting)
	}
	if len(got.LSP) != 0 {
		t.Fatalf("omitted LSP configuration produced entries: %+v", got.LSP)
	}
}

// Normalizes extension case and surrounding whitespace in file configuration.
func TestLoadNormalizesLSPLists(t *testing.T) {
	path := writeConfigFixture(t, `{"environmentId":"123e4567-e89b-42d3-a456-426614174000","version":"0","daemonConfig":{"studioUrl":"https://studio.example","lsp":{"servers":{"go":{"command":["gopls"],"extensions":[" .GO "],"rootMarkers":[" go.mod "],"firstMatchMarkers":[" go.work "]}}}}}`)
	got, err := LoadDaemonConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	server := got.LSP[0]
	if server.Extensions[0] != ".go" || server.RootMarkers[0] != "go.mod" || server.FirstMatchMarkers[0] != "go.work" {
		t.Fatalf("LSP lists were not normalized: %+v", server)
	}
}

// Explicit Studio ports must be usable TCP ports, as at the Java configuration boundary.
func TestStudioURLPortBounds(t *testing.T) {
	for _, tc := range []struct {
		origin string
		valid  bool
	}{
		{"http://studio.example", true},
		{"https://studio.example:1", true},
		{"https://studio.example:65535/", true},
		{"http://[::1]:65535", true},
		{"http://studio.example:0", false},
		{"http://studio.example:65536", false},
		{"http://[::1]:65536", false},
		{"http://studio.example:999999999999999999999999", false},
	} {
		t.Run(tc.origin, func(t *testing.T) {
			_, err := parseStudioURL(tc.origin)
			if (err == nil) != tc.valid {
				t.Fatalf("Studio origin validity = %t, want %t", err == nil, tc.valid)
			}
		})
	}
}

// Confirms check-config does not require Bash to resolve at startup.
func TestCheckConfigAllowsUnresolvedBash(t *testing.T) {
	path := writeConfigFixture(t, `{"environmentId":"123e4567-e89b-42d3-a456-426614174000","version":"0","daemonConfig":{"studioUrl":"https://studio.example","bashExecutable":"/no/such/bash"}}`)
	t.Setenv("PATH", t.TempDir())
	if err := CheckConfig(path); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(filepath.Dir(path), "daemon.lock")); !os.IsNotExist(err) {
		t.Fatal("check-config created daemon.lock")
	}
}
