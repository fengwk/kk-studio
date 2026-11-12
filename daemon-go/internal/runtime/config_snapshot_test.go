package runtime

import (
	"context"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// RUN-T-088 freezes the applied configuration snapshot when each invocation is admitted.
func TestRuntimeFreezesConfigurationPerInvocation(t *testing.T) {
	c := newTestConnection()
	queueHandshake(c)
	firstID, secondID := "123e4567-e89b-42d3-a456-426614174000", "223e4567-e89b-42d3-a456-426614174000"
	c.in <- invocationEnvelope()
	firstStarted, releaseFirst := make(chan struct{}), make(chan struct{})
	var releaseOnce sync.Once
	release := func() { releaseOnce.Do(func() { close(releaseFirst) }) }
	defer release()
	secondConfigApplied := make(chan struct{})
	observed := make(chan string, 2)
	r, err := NewRuntime(RuntimeOptions{EnvironmentID: "env", Connections: fixedConnectionFactory{connection: c}, ApplyConfig: func(_ context.Context, v int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
		if v == 2 {
			close(secondConfigApplied)
		}
		return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: v, StudioURL: fmt.Sprintf("https://v%d.example", v)}, nil
	}, Capabilities: map[string]CapabilityExecutor{"echo": func(callCtx context.Context, call CapabilityCall, cc CapabilityContext) (CapabilityResult, error) {
		observed <- cc.Config.StudioURL
		if call.InvocationID == firstID {
			close(firstStarted)
			select {
			case <-releaseFirst:
			case <-callCtx.Done():
				return CapabilityResult{}, callCtx.Err()
			}
		}
		return CapabilityResult{CallID: call.InvocationID}, nil
	}}})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	select {
	case <-firstStarted:
	case <-time.After(time.Second):
		cancel()
		t.Fatal("first invocation did not start")
	}
	c.in <- &protocol.Envelope{MessageType: protocol.MessageType_ENVIRONMENT_CONFIG, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(2), Config: &protocol.DaemonConfiguration{StudioUrl: "https://v2.example", Lsp: &protocol.LspConfiguration{}}}}}
	select {
	case <-secondConfigApplied:
	case <-time.After(time.Second):
		release()
		cancel()
		t.Fatal("updated configuration was not applied")
	}
	c.in <- &protocol.Envelope{MessageType: protocol.MessageType_INVOKE, EnvironmentId: stringPtr("env"), InvocationId: &secondID, Payload: &protocol.Envelope_Invoke{Invoke: &protocol.Invoke{CapabilityId: stringPtr("echo"), ArgumentsJson: stringPtr(`{}`), TimeoutMillis: int64Ptr(0), MaxOutputBytes: int64Ptr(1024), ImageInputTier: imageTierPtr(protocol.ImageInputTier_P720)}}}
	select {
	case got := <-observed:
		if got != "https://v1.example" {
			release()
			cancel()
			t.Fatalf("first invocation snapshot = %q", got)
		}
	case <-time.After(time.Second):
		release()
		cancel()
		t.Fatal("second invocation did not start")
	}
	select {
	case got := <-observed:
		if got != "https://v2.example" {
			release()
			cancel()
			t.Fatalf("second invocation snapshot = %q", got)
		}
	case <-time.After(time.Second):
		release()
		cancel()
		t.Fatal("second invocation did not start")
	}
	release()
	c.waitFor(t, func(frames []*protocol.Envelope) bool {
		n := 0
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_COMPLETED {
				n++
			}
		}
		return n == 2
	})
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
