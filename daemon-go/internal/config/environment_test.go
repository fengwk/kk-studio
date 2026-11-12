package config

import (
	"errors"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

func validEnvironmentConfig() *protocol.DaemonConfiguration {
	bash := "/usr/bin/bash"
	proxy := "http://proxy.example:8080"
	return &protocol.DaemonConfiguration{
		StudioUrl:      "https://studio.example/",
		BashExecutable: &bash,
		ProxyUrl:       &proxy,
		Lsp: &protocol.LspConfiguration{Servers: []*protocol.LspServer{
			{Id: "typescript", Command: []string{"typescript-language-server", "--stdio"}, Extensions: []string{".ts", ".tsx"}, RootMarkers: []string{"package.json"}},
			{Id: "go", Command: []string{"gopls", "serve"}, Extensions: []string{".go"}, FirstMatchMarkers: []string{"go.work", "go.mod"}},
		}},
	}
}

// RUN-T-189 applies a complete ordered snapshot without replacing daemon-local paths.
func TestBuildEnvironmentConfigPreservesBaseAndServerOrder(t *testing.T) {
	base := DaemonConfig{EnvironmentID: "env", ConfigPath: "/data/daemon.json", DataDir: "/data", TokenPath: "/data/daemon.token", DistributionDir: "/opt/daemon"}
	input := validEnvironmentConfig()
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
	input.Lsp.Servers[0].Command[0] = "mutated"
	if got.LSP[0].Command[0] != "typescript-language-server" {
		t.Fatal("candidate retained mutable input slices")
	}
}

// RUN-T-197 ADAPT rejects invalid candidates before returning any publishable configuration.
func TestBuildEnvironmentConfigRejectsInvalidCandidateFields(t *testing.T) {
	cases := []struct {
		name   string
		mutate func(*protocol.DaemonConfiguration)
		field  string
	}{
		{name: "studio URL", mutate: func(c *protocol.DaemonConfiguration) { c.StudioUrl = "https://user:secret@studio.example/path" }, field: "studioUrl"},
		{name: "proxy URL", mutate: func(c *protocol.DaemonConfiguration) { bad := "https://proxy.example:8080"; c.ProxyUrl = &bad }, field: "proxyUrl"},
		{name: "empty bash", mutate: func(c *protocol.DaemonConfiguration) { empty := "  "; c.BashExecutable = &empty }, field: "bashExecutable"},
		{name: "missing LSP", mutate: func(c *protocol.DaemonConfiguration) { c.Lsp = nil }, field: "lsp"},
		{name: "duplicate LSP", mutate: func(c *protocol.DaemonConfiguration) { c.Lsp.Servers = append(c.Lsp.Servers, c.Lsp.Servers[0]) }, field: "lsp.servers"},
		{name: "unsafe extension", mutate: func(c *protocol.DaemonConfiguration) { c.Lsp.Servers[0].Extensions = []string{"../secret"} }, field: "lsp.servers.typescript.extensions"},
		{name: "unsafe root marker", mutate: func(c *protocol.DaemonConfiguration) { c.Lsp.Servers[0].RootMarkers = []string{"../secret"} }, field: "lsp.servers.typescript.markers"},
		{name: "unknown protobuf field", mutate: func(c *protocol.DaemonConfiguration) { c.ProtoReflect().SetUnknown([]byte{0x18, 0x01}) }, field: "config"},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			base := DaemonConfig{EnvironmentID: "env", ConfigVersion: 2, StudioURL: "https://old.example"}
			input := validEnvironmentConfig()
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
		if _, err := BuildEnvironmentConfig(DaemonConfig{}, version, validEnvironmentConfig()); err == nil {
			t.Fatalf("version %d was accepted", version)
		}
	}
}
