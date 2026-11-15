package runtime

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	goruntime "runtime"
	"slices"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// A server snapshot whose LSP command is home-relative must survive the private cache round trip, so
// restarting from that cache accepts the same version and content instead of rejecting it.
func TestEnvironmentConfigCacheKeepsServerSnapshotIdentity(t *testing.T) {
	const environmentID = "123e4567-e89b-42d3-a456-426614174000"
	home := filepath.Join(t.TempDir(), "home")
	name := "jdtls"
	if goruntime.GOOS == "windows" {
		name += ".exe"
	}
	executable := filepath.Join(home, ".local", "share", "nvim", "mason", "bin", name)
	if err := os.MkdirAll(filepath.Dir(executable), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(executable, []byte("fixture"), 0700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home)
	configured := "~/.local/share/nvim/mason/bin/" + name
	directory := filepath.Join(t.TempDir(), "daemon")
	if err := native.EnsurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	configPath := filepath.Join(directory, "daemon.json")
	document := fmt.Sprintf(`{"environmentId":%q,"version":"1","daemonConfig":{"studioUrl":"https://studio.example","bashExecutable":null,"lsp":{"servers":{"jdtls":{"command":[%q,"--stdio"],"extensions":[".java"],"rootMarkers":["pom.xml"],"firstMatchMarkers":[".git"]}}},"proxyUrl":null}}`, environmentID, configured)
	if err := native.WritePrivateFile(filepath.Join(directory, "daemon.token"), []byte("restart-token\n")); err != nil {
		t.Fatal(err)
	}
	if err := native.WritePrivateFile(configPath, []byte(document)); err != nil {
		t.Fatal(err)
	}
	snapshot := &protocol.DaemonConfiguration{
		StudioUrl: "https://studio.example",
		Lsp: &protocol.LspConfiguration{Servers: []*protocol.LspServer{{
			Id: "jdtls", Command: []string{configured, "--stdio"}, Extensions: []string{".java"},
			RootMarkers: []string{"pom.xml"}, FirstMatchMarkers: []string{".git"},
		}}},
	}
	for attempt := range 2 {
		initial, err := config.LoadDaemonConfig(configPath)
		if err != nil {
			t.Fatalf("attempt %d: reload configuration cache: %v", attempt, err)
		}
		if len(initial.LSP) != 1 || initial.LSP[0].Command[0] != configured {
			t.Fatalf("attempt %d: cached LSP command = %+v, want %q", attempt, initial.LSP, configured)
		}
		manager, err := NewEnvironmentConfigRuntime(EnvironmentConfigOptions{
			Initial: initial,
			Cache:   native.PrivateConfigCacheWriter{},
			Prepare: (&testConfigurationPreparer{}).prepare,
			Status:  NewStatusTracker(),
		})
		if err != nil {
			t.Fatalf("attempt %d: %v", attempt, err)
		}
		result, err := manager.Submit(context.Background(), 1, snapshot)
		if err != nil {
			t.Fatalf("attempt %d: submitting the unchanged server snapshot: %v", attempt, err)
		}
		if result.Kind != ConfigApplied && result.Kind != ConfigConfirmed {
			t.Fatalf("attempt %d: apply result = %+v, want an accepted snapshot", attempt, result)
		}
		manager.Close()
		cached, err := os.ReadFile(configPath)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(string(cached), configured) {
			t.Fatalf("attempt %d: private cache does not keep the configured command: %s", attempt, cached)
		}
	}
}

// A missing language server is still reported as a field validation failure.
func TestEnvironmentConfigSnapshotRejectsMissingLSPExecutable(t *testing.T) {
	initial := initialRuntimeConfig()
	snapshot := &protocol.DaemonConfiguration{
		StudioUrl: "https://studio.example",
		Lsp: &protocol.LspConfiguration{Servers: []*protocol.LspServer{{
			Id: "jdtls", Command: []string{filepath.Join(t.TempDir(), "missing-jdtls")}, Extensions: []string{".java"},
		}}},
	}
	manager := newTestEnvironmentConfigRuntime(t, initial, (&testConfigurationPreparer{}).prepare, &testCacheWriter{})
	defer manager.Close()
	result, err := manager.Submit(context.Background(), 2, snapshot)
	if err != nil {
		t.Fatal(err)
	}
	if result.Kind != ConfigFailed || result.Code != "VALIDATION_FAILED" || result.Field != "lsp.servers.jdtls.command" {
		t.Fatalf("missing LSP executable result = %+v", result)
	}
	current := manager.Current()
	if manager.HasAppliedConfiguration() || current.ConfigVersion != initial.ConfigVersion || !slices.Equal(current.LSP[0].Command, initial.LSP[0].Command) {
		t.Fatalf("a rejected candidate changed the applied configuration: %+v", current)
	}
}
