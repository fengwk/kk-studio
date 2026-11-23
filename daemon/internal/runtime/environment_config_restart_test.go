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
	"google.golang.org/protobuf/proto"
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

// A preparation failure preserves the current configuration and reports the affected field.
func TestEnvironmentConfigReportsPreparationValidationFailure(t *testing.T) {
	initial := initialRuntimeConfig()
	snapshot := &protocol.DaemonConfiguration{
		StudioUrl: "https://studio.example",
		Lsp: &protocol.LspConfiguration{Servers: []*protocol.LspServer{{
			Id: "jdtls", Command: []string{filepath.Join(t.TempDir(), "missing-jdtls")}, Extensions: []string{".java"},
		}}},
	}
	prepare := func(context.Context, config.DaemonConfig, *config.DaemonConfig) (PreparedConfiguration, error) {
		return nil, &config.EnvironmentConfigError{Code: "VALIDATION_FAILED", Field: "lsp.servers.jdtls.command"}
	}
	manager := newTestEnvironmentConfigRuntime(t, initial, prepare, &testCacheWriter{})
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

// Surface differences that Load and Build already collapse must keep the same fingerprint after a cache restart.
func TestEnvironmentConfigRestartAcceptsNormalizedEquivalentSnapshot(t *testing.T) {
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
	document := fmt.Sprintf(`{"environmentId":%q,"version":"1","daemonConfig":{"studioUrl":"https://studio.example","bashExecutable":null,"lsp":{"servers":{"jdtls":{"command":[%q,"--stdio"],"extensions":[".java"],"rootMarkers":["pom.xml"],"firstMatchMarkers":[".git"]}}},"proxyUrl":"http://proxy.example:8080"}}`, environmentID, configured)
	if err := native.WritePrivateFile(filepath.Join(directory, "daemon.token"), []byte("restart-token\n")); err != nil {
		t.Fatal(err)
	}
	if err := native.WritePrivateFile(configPath, []byte(document)); err != nil {
		t.Fatal(err)
	}
	wire := &protocol.DaemonConfiguration{
		StudioUrl: "https://studio.example/",
		ProxyUrl:  stringPtr("HTTP://proxy.example:8080"),
		Lsp: &protocol.LspConfiguration{Servers: []*protocol.LspServer{{
			Id: "jdtls", Command: []string{configured, "--stdio"}, Extensions: []string{" .JAVA "},
			RootMarkers: []string{" pom.xml "}, FirstMatchMarkers: []string{" .git "},
		}}},
	}
	initial, err := config.LoadDaemonConfig(configPath)
	if err != nil {
		t.Fatal(err)
	}
	manager, err := NewEnvironmentConfigRuntime(EnvironmentConfigOptions{
		Initial: initial,
		Cache:   native.PrivateConfigCacheWriter{},
		Prepare: (&testConfigurationPreparer{}).prepare,
		Status:  NewStatusTracker(),
	})
	if err != nil {
		t.Fatal(err)
	}
	result, err := manager.Submit(context.Background(), 1, proto.Clone(wire).(*protocol.DaemonConfiguration))
	if err != nil || result.Kind != ConfigApplied && result.Kind != ConfigConfirmed {
		t.Fatalf("normalized cache snapshot = %+v, %v", result, err)
	}
	manager.Close()

	reloaded, err := config.LoadDaemonConfig(configPath)
	if err != nil {
		t.Fatal(err)
	}
	restarted, err := NewEnvironmentConfigRuntime(EnvironmentConfigOptions{
		Initial: reloaded,
		Cache:   native.PrivateConfigCacheWriter{},
		Prepare: (&testConfigurationPreparer{}).prepare,
		Status:  NewStatusTracker(),
	})
	if err != nil {
		t.Fatal(err)
	}
	defer restarted.Close()
	repeated := proto.Clone(wire).(*protocol.DaemonConfiguration)
	result, err = restarted.Submit(context.Background(), 1, repeated)
	if err != nil || result.Kind != ConfigApplied && result.Kind != ConfigConfirmed {
		t.Fatalf("restart rejected a semantically identical snapshot: %+v, %v", result, err)
	}
	if repeated.StudioUrl != wire.StudioUrl || repeated.Lsp.Servers[0].Extensions[0] != wire.Lsp.Servers[0].Extensions[0] || repeated.Lsp.Servers[0].Command[0] != configured {
		t.Fatal("fingerprint normalization mutated the inbound snapshot")
	}

	options := runtimeOptionsForTest()
	options.EnvironmentID = environmentID
	options.EnvironmentConfig = restarted
	options.Status = restarted.StatusTracker()
	ready, _ := newReadyRuntimeForTest(t, options)
	ready.mu.Lock()
	ready.config = restarted.Current()
	ready.configProto = config.CanonicalConfiguration(wire)
	ready.mu.Unlock()
	if err := ready.handleEnvironmentConfig(context.Background(), &protocol.Envelope{Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(1), Config: proto.Clone(wire).(*protocol.DaemonConfiguration)}}}); err != nil {
		t.Fatalf("READY rejected a normalized equivalent snapshot: %v", err)
	}

	changedCommand := proto.Clone(wire).(*protocol.DaemonConfiguration)
	changedCommand.Lsp.Servers[0].Command = []string{configured, "--changed"}
	if _, err := restarted.Submit(context.Background(), 1, changedCommand); err == nil || err.Error() != "the same environment config version must not change its content" {
		t.Fatalf("changed command = %v", err)
	}
	changedExtension := proto.Clone(wire).(*protocol.DaemonConfiguration)
	changedExtension.Lsp.Servers[0].Extensions = []string{".kt"}
	if _, err := restarted.Submit(context.Background(), 1, changedExtension); err == nil || err.Error() != "the same environment config version must not change its content" {
		t.Fatalf("changed extension = %v", err)
	}
	unknown := proto.Clone(wire).(*protocol.DaemonConfiguration)
	unknown.ProtoReflect().SetUnknown([]byte{0x80, 0x01, 0x01})
	if _, err := restarted.Submit(context.Background(), 1, unknown); err == nil || err.Error() != "the same environment config version must not change its content" {
		t.Fatalf("unknown field = %v", err)
	}
	illegalURL := proto.Clone(wire).(*protocol.DaemonConfiguration)
	illegalURL.StudioUrl = "file:///studio"
	if _, err := restarted.Submit(context.Background(), 1, illegalURL); err == nil || err.Error() != "the same environment config version must not change its content" {
		t.Fatalf("illegal URL = %v", err)
	}
	reordered := proto.Clone(wire).(*protocol.DaemonConfiguration)
	reordered.Lsp.Servers[0].Extensions = []string{".java", ".JAVA"}
	if _, err := restarted.Submit(context.Background(), 1, reordered); err == nil || err.Error() != "the same environment config version must not change its content" {
		t.Fatalf("extension order and duplicates = %v", err)
	}
}
