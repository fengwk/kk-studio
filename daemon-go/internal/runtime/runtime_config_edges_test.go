package runtime

import (
	"context"
	"errors"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"google.golang.org/protobuf/proto"
)

// RUN-T-087 closes malformed, stale, duplicated, and failed hot-configuration paths.
func TestRuntimeEnvironmentConfigControlEdges(t *testing.T) {
	base := runtimeOptionsForTest()
	r, connection := newReadyRuntimeForTest(t, base)
	wire := &protocol.DaemonConfiguration{StudioUrl: "https://studio.example", Lsp: &protocol.LspConfiguration{}}
	r.mu.Lock()
	r.config = config.DaemonConfig{EnvironmentID: "env", ConfigVersion: 2}
	r.configProto = proto.Clone(wire).(*protocol.DaemonConfiguration)
	r.mu.Unlock()
	if err := r.handleEnvironmentConfig(context.Background(), &protocol.Envelope{}); err != nil {
		t.Fatal(err)
	}
	if err := r.handleEnvironmentConfig(context.Background(), &protocol.Envelope{Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(-1), Config: wire}}}); err != nil {
		t.Fatal(err)
	}
	if err := r.handleEnvironmentConfig(context.Background(), &protocol.Envelope{Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(1), Config: wire}}}); err != nil {
		t.Fatalf("stale configuration = %v", err)
	}
	if err := r.handleEnvironmentConfig(context.Background(), &protocol.Envelope{Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(2), Config: proto.Clone(wire).(*protocol.DaemonConfiguration)}}}); err != nil {
		t.Fatalf("idempotent configuration = %v", err)
	}
	changed := proto.Clone(wire).(*protocol.DaemonConfiguration)
	changed.StudioUrl = "https://changed.example"
	if err := r.handleEnvironmentConfig(context.Background(), &protocol.Envelope{Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(2), Config: changed}}}); err == nil || err.Error() != "the same environment config version must not change its content" {
		t.Fatalf("same-version drift = %v", err)
	}

	r.options.ApplyConfig = func(context.Context, int64, *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
		return config.DaemonConfig{}, errors.New("validation failed")
	}
	if err := r.handleEnvironmentConfig(context.Background(), &protocol.Envelope{Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(3), Config: changed}}}); err != nil {
		t.Fatalf("apply error should be reported on the connection, got %v", err)
	}
	r.options.ApplyConfig = func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
		return config.DaemonConfig{EnvironmentID: "other", ConfigVersion: version}, nil
	}
	if err := r.handleEnvironmentConfig(context.Background(), &protocol.Envelope{Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(3), Config: changed}}}); err == nil || err.Error() != "applied configuration changed environment scope" {
		t.Fatalf("scope-changing candidate = %v", err)
	}
	r.options.ApplyConfig = func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
		return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: version}, nil
	}
	if err := r.handleEnvironmentConfig(context.Background(), &protocol.Envelope{Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(3), Config: changed}}}); err != nil {
		t.Fatalf("valid replacement = %v", err)
	}
	if r.config.ConfigVersion != 3 || !connection.sent(protocol.MessageType_RUNTIME_STATUS) {
		t.Fatalf("configuration snapshot/status not updated: %+v", r.config)
	}
}
