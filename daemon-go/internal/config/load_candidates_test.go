package config

import (
	"os"
	"path/filepath"
	"testing"
)

// RUN-T-159 and RUN-T-165 allow startup config without LSP and preserve an unresolved Bash setting.
func TestLoadOptionalLSPAndUnavailableBash(t *testing.T) {
	t.Setenv("PATH", t.TempDir())
	configured := filepath.Join(t.TempDir(), "missing-bash")
	path := writeConfigFixture(t, `{"environmentId":"123e4567-e89b-42d3-a456-426614174000","version":"0","daemonConfig":{"studioUrl":"https://studio.example","bashExecutable":"`+configured+`"}}`)
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

// RUN-T-161 normalizes extension case and surrounding whitespace in file configuration.
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

// RUN-T-170 confirms check-config does not require Bash to resolve at startup.
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
