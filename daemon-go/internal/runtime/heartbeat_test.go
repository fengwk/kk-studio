package runtime

import (
	"context"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// RUN-T-001 heartbeat publication is suppressed until READY and begins after the first applied snapshot.
func TestRuntimeHeartbeatsOnlyWhenReady(t *testing.T) {
	c := newTestConnection()
	c.in <- &protocol.Envelope{MessageType: protocol.MessageType_SETTINGS, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{MaxResourceBytes: int64Ptr(100)}}}}
	r, err := NewRuntime(RuntimeOptions{EnvironmentID: "env", Connections: fixedConnectionFactory{connection: c}, HeartbeatInterval: 5 * time.Millisecond, ApplyConfig: func(_ context.Context, v int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
		return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: v}, nil
	}})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	time.Sleep(20 * time.Millisecond)
	if c.sent(protocol.MessageType_HEARTBEAT) {
		cancel()
		t.Fatal("heartbeat sent before READY")
	}
	c.in <- &protocol.Envelope{MessageType: protocol.MessageType_ENVIRONMENT_CONFIG, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(1), Config: &protocol.DaemonConfiguration{StudioUrl: "https://studio.example", Lsp: &protocol.LspConfiguration{}}}}}
	c.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_HEARTBEAT {
				return true
			}
		}
		return false
	})
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
