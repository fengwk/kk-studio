package config

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

func validEnvironmentConfig(t *testing.T) *protocol.DaemonConfiguration {
	t.Helper()
	bash := writeExecutableFixture(t, "bash")
	proxy := "http://proxy.example:8080"
	return &protocol.DaemonConfiguration{
		StudioUrl:      "https://studio.example/",
		BashExecutable: &bash,
		ProxyUrl:       &proxy,
		Lsp: &protocol.LspConfiguration{Servers: []*protocol.LspServer{
			{Id: "typescript", Command: []string{writeExecutableFixture(t, "tsls"), "--stdio"}, Extensions: []string{" .TS ", ".tsx"}, RootMarkers: []string{" package.json "}},
			{Id: "go", Command: []string{writeExecutableFixture(t, "gopls"), "serve"}, Extensions: []string{".go"}, FirstMatchMarkers: []string{"go.work", "go.mod"}},
		}},
	}
}

func writeExecutableFixture(t *testing.T, name string) string {
	t.Helper()
	if filepath.Ext(name) == "" && runtime.GOOS == "windows" {
		name += ".exe"
	}
	path := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(path, []byte("fixture"), 0700); err != nil {
		t.Fatal(err)
	}
	return path
}

// Applies a complete ordered snapshot without replacing daemon-local paths.
func TestBuildEnvironmentConfigPreservesBaseAndServerOrder(t *testing.T) {
	base := DaemonConfig{EnvironmentID: "env", ConfigPath: "/data/daemon.json", DataDir: "/data", TokenPath: "/data/daemon.token", DistributionDir: "/opt/daemon"}
	input := validEnvironmentConfig(t)
	expectedCommand := input.Lsp.Servers[0].Command[0]
	got, err := BuildEnvironmentConfig(base, 17, input)
	if err != nil {
		t.Fatal(err)
	}
	if got.EnvironmentID != base.EnvironmentID || got.ConfigPath != base.ConfigPath || got.DataDir != base.DataDir || got.TokenPath != base.TokenPath || got.DistributionDir != base.DistributionDir {
		t.Fatalf("local config paths changed: %+v", got)
	}
	if got.ConfigVersion != 17 || got.StudioURL != "https://studio.example" || got.GatewayURL != "wss://studio.example/ws/daemon" || got.ProxyURL == nil || *got.ProxyURL != "http://proxy.example:8080" {
		t.Fatalf("configuration normalization failed: %+v", got)
	}
	if len(got.LSP) != 2 || got.LSP[0].ID != "typescript" || got.LSP[1].ID != "go" {
		t.Fatalf("LSP declaration order lost: %+v", got.LSP)
	}
	if got.LSP[0].Command[0] != expectedCommand || got.LSP[0].Extensions[0] != ".ts" || got.LSP[0].RootMarkers[0] != "package.json" {
		t.Fatalf("LSP executable or normalized rules differ: %+v", got.LSP[0])
	}
	input.Lsp.Servers[0].Command[0] = "mutated"
	if got.LSP[0].Command[0] != expectedCommand {
		t.Fatal("candidate retained mutable input slices")
	}
}

// Identity normalization must retain unknown fields and invalid server entries for validation.
func TestCanonicalConfigurationPreservesRejectedSnapshotFields(t *testing.T) {
	if CanonicalConfiguration(nil) != nil {
		t.Fatal("an absent snapshot must stay absent")
	}
	input := &protocol.DaemonConfiguration{StudioUrl: "https://studio.example/", Lsp: &protocol.LspConfiguration{}}
	unknown := []byte{0x80, 0x01, 0x01}
	input.Lsp.ProtoReflect().SetUnknown(unknown)
	canonical := CanonicalConfiguration(input)
	if canonical.Lsp == nil || !slices.Equal(canonical.Lsp.ProtoReflect().GetUnknown(), unknown) {
		t.Fatal("normalization discarded unknown LSP fields")
	}
	input.Lsp.ProtoReflect().SetUnknown(nil)
	if CanonicalConfiguration(input).Lsp != nil || input.Lsp == nil {
		t.Fatal("empty LSP identity must be absent without mutating the input")
	}
	input.Lsp.Servers = []*protocol.LspServer{nil}
	if len(CanonicalConfiguration(input).GetLsp().GetServers()) != 1 {
		t.Fatal("normalization discarded an invalid server entry")
	}
}

// BuildEnvironmentConfig accepts a complete snapshot without an optional LSP block.
func TestBuildEnvironmentConfigAllowsOptionalLSP(t *testing.T) {
	input := validEnvironmentConfig(t)
	input.Lsp = nil
	got, err := BuildEnvironmentConfig(DaemonConfig{}, 3, input)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.LSP) != 0 {
		t.Fatalf("omitted LSP block produced servers: %+v", got.LSP)
	}
}

// Applying a server snapshot keeps the configured LSP command verbatim, so the accepted snapshot,
// the private cache and the server's raw snapshot stay identical across restarts.
func TestBuildEnvironmentConfigKeepsConfiguredLSPCommand(t *testing.T) {
	home := filepath.Join(t.TempDir(), "daemon home")
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home)
	name := "jdtls"
	if runtime.GOOS == "windows" {
		name += ".exe"
	}
	executable := filepath.Join(home, ".local", "share", "nvim", "mason", "bin", name)
	if err := os.MkdirAll(filepath.Dir(executable), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(executable, []byte("fixture"), 0700); err != nil {
		t.Fatal(err)
	}
	for _, prefix := range []string{"~/", "$HOME/", "${HOME}/"} {
		t.Run(prefix, func(t *testing.T) {
			configured := prefix + ".local/share/nvim/mason/bin/" + name
			command := []string{configured, "--stdio", "$HOME/literal-argument"}
			input := validEnvironmentConfig(t)
			input.Lsp.Servers = []*protocol.LspServer{{Id: "jdtls", Command: command, Extensions: []string{".java"}, RootMarkers: []string{"pom.xml", "build.gradle", "settings.gradle"}, FirstMatchMarkers: []string{".git"}}}
			got, err := BuildEnvironmentConfig(DaemonConfig{}, 1, input)
			if err != nil {
				t.Fatal(err)
			}
			if len(got.LSP) != 1 || !slices.Equal(got.LSP[0].Command, command) {
				t.Fatalf("applied LSP configuration = %+v; want the configured command %q", got.LSP, command)
			}
			if input.Lsp.Servers[0].Command[0] != configured {
				t.Fatal("candidate mutated the server snapshot")
			}
		})
	}
	// Validation still requires the configured server to resolve at its home-relative location.
	if err := os.Remove(executable); err != nil {
		t.Fatal(err)
	}
	input := validEnvironmentConfig(t)
	input.Lsp.Servers = []*protocol.LspServer{{Id: "jdtls", Command: []string{"~/.local/share/nvim/mason/bin/" + name}, Extensions: []string{".java"}}}
	_, err := BuildEnvironmentConfig(DaemonConfig{}, 1, input)
	var validation *EnvironmentConfigError
	if !errors.As(err, &validation) || validation.Field != "lsp.servers.jdtls.command" || validation.Code != "VALIDATION_FAILED" {
		t.Fatalf("missing home-relative server validation = %v", err)
	}
}

// ADAPT rejects invalid candidates before returning any publishable configuration.
func TestBuildEnvironmentConfigRejectsInvalidCandidateFields(t *testing.T) {
	cases := []struct {
		name   string
		mutate func(*protocol.DaemonConfiguration)
		field  string
	}{
		{name: "studio URL", mutate: func(c *protocol.DaemonConfiguration) { c.StudioUrl = "https://user:secret@studio.example/path" }, field: "studioUrl"},
		{name: "proxy URL", mutate: func(c *protocol.DaemonConfiguration) { bad := "https://proxy.example:8080"; c.ProxyUrl = &bad }, field: "proxyUrl"},
		{name: "proxy port range", mutate: func(c *protocol.DaemonConfiguration) { bad := "http://proxy.example:65536"; c.ProxyUrl = &bad }, field: "proxyUrl"},
		{name: "empty bash", mutate: func(c *protocol.DaemonConfiguration) { empty := "  "; c.BashExecutable = &empty }, field: "bashExecutable"},
		{name: "unresolvable bash", mutate: func(c *protocol.DaemonConfiguration) {
			missing := filepath.Join(t.TempDir(), "missing-bash")
			c.BashExecutable = &missing
		}, field: "bashExecutable"},
		{name: "unresolvable LSP executable", mutate: func(c *protocol.DaemonConfiguration) {
			c.Lsp.Servers[0].Command[0] = filepath.Join(t.TempDir(), "missing")
		}, field: "lsp.servers.typescript.command"},
		{name: "duplicate LSP", mutate: func(c *protocol.DaemonConfiguration) { c.Lsp.Servers = append(c.Lsp.Servers, c.Lsp.Servers[0]) }, field: "lsp.servers"},
		{name: "unsafe extension", mutate: func(c *protocol.DaemonConfiguration) { c.Lsp.Servers[0].Extensions = []string{"../secret"} }, field: "lsp.servers.typescript.extensions"},
		{name: "unsafe root marker", mutate: func(c *protocol.DaemonConfiguration) { c.Lsp.Servers[0].RootMarkers = []string{"../secret"} }, field: "lsp.servers.typescript.markers"},
		{name: "unknown protobuf field", mutate: func(c *protocol.DaemonConfiguration) { c.ProtoReflect().SetUnknown([]byte{0x18, 0x01}) }, field: "config"},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			base := DaemonConfig{EnvironmentID: "env", ConfigVersion: 2, StudioURL: "https://old.example"}
			input := validEnvironmentConfig(t)
			test.mutate(input)
			_, err := BuildEnvironmentConfig(base, 3, input)
			var validation *EnvironmentConfigError
			if !errors.As(err, &validation) || validation.Field != test.field || validation.Code != "VALIDATION_FAILED" {
				t.Fatalf("unexpected candidate validation error: %#v", err)
			}
			if base.ConfigVersion != 2 || base.StudioURL != "https://old.example" {
				t.Fatal("failed candidate mutated the previously applied snapshot")
			}
		})
	}
}

func TestEnvironmentConfigErrorAndNilClone(t *testing.T) {
	if got := (&EnvironmentConfigError{Field: "studioUrl"}).Error(); got != "studioUrl is invalid" {
		t.Fatalf("validation diagnostic = %q", got)
	}
	if cloneString(nil) != nil {
		t.Fatal("nil optional string changed while cloning")
	}
}

func TestBuildEnvironmentConfigRejectsVersionsOutsideSafeIntegerRange(t *testing.T) {
	for _, version := range []int64{-1, maxEnvironmentConfigVersion + 1} {
		if _, err := BuildEnvironmentConfig(DaemonConfig{}, version, validEnvironmentConfig(t)); err == nil {
			t.Fatalf("version %d was accepted", version)
		}
	}
}
