package runtime

import (
	"context"
	"sync/atomic"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

const testInvocationID = "123e4567-e89b-42d3-a456-426614174000"

func (c *testConnection) frames() []*protocol.Envelope {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]*protocol.Envelope(nil), c.out...)
}

func (c *testConnection) waitFor(t *testing.T, predicate func([]*protocol.Envelope) bool) []*protocol.Envelope {
	t.Helper()
	deadline := time.NewTimer(time.Second)
	defer deadline.Stop()
	ticker := time.NewTicker(time.Millisecond)
	defer ticker.Stop()
	for {
		frames := c.frames()
		if predicate(frames) {
			return frames
		}
		select {
		case <-deadline.C:
			t.Fatal("timed out waiting for outbound frame")
		case <-ticker.C:
		}
	}
}

func queueHandshake(c *testConnection) {
	c.in <- &protocol.Envelope{MessageType: protocol.MessageType_SETTINGS, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{MaxResourceBytes: int64Ptr(1024)}}}}
	c.in <- &protocol.Envelope{MessageType: protocol.MessageType_ENVIRONMENT_CONFIG, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(1), Config: &protocol.DaemonConfiguration{StudioUrl: "https://studio.example", Lsp: &protocol.LspConfiguration{}}}}}
}

func invocationEnvelope() *protocol.Envelope {
	return &protocol.Envelope{MessageType: protocol.MessageType_INVOKE, EnvironmentId: stringPtr("env"), InvocationId: stringPtr(testInvocationID), Payload: &protocol.Envelope_Invoke{Invoke: &protocol.Invoke{CapabilityId: stringPtr("echo"), ArgumentsJson: stringPtr(`{}`), TimeoutMillis: int64Ptr(0)}}}
}

func newInvocationRuntime(t *testing.T, c *testConnection, executor CapabilityExecutor) *Runtime {
	t.Helper()
	r, err := NewRuntime(RuntimeOptions{
		EnvironmentID: "env",
		DaemonVersion: "1.0.0",
		Connections:   fixedConnectionFactory{connection: c},
		ApplyConfig: func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: version}, nil
		},
		Capabilities: map[string]CapabilityExecutor{"echo": executor},
	})
	if err != nil {
		t.Fatal(err)
	}
	return r
}

// RUN-T-006 ignores an in-flight duplicate, emits replayed STARTED, and runs the executor once.
func TestRuntimeDeduplicatesRunningInvocation(t *testing.T) {
	c := newTestConnection()
	queueHandshake(c)
	c.in <- invocationEnvelope()
	c.in <- invocationEnvelope()
	started, release := make(chan struct{}), make(chan struct{})
	var executions atomic.Int32
	r := newInvocationRuntime(t, c, func(_ context.Context, call CapabilityCall, _ CapabilityContext) (CapabilityResult, error) {
		executions.Add(1)
		close(started)
		<-release
		return CapabilityResult{CallID: call.InvocationID}, nil
	})
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	select {
	case <-started:
	case <-time.After(time.Second):
		cancel()
		t.Fatal("executor did not start")
	}
	frames := c.waitFor(t, func(frames []*protocol.Envelope) bool {
		count := 0
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_STARTED {
				count++
			}
		}
		return count == 2
	})
	startedCount, replayed := 0, 0
	for _, frame := range frames {
		if frame.MessageType == protocol.MessageType_STARTED {
			startedCount++
			if frame.GetStarted().GetReplayed() {
				replayed++
			}
		}
	}
	if startedCount != 2 || replayed != 1 || executions.Load() != 1 {
		close(release)
		cancel()
		t.Fatalf("duplicate handling: started=%d replayed=%d executions=%d", startedCount, replayed, executions.Load())
	}
	close(release)
	c.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_COMPLETED {
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

// RUN-T-007 replays a completed terminal frame without re-executing the capability.
func TestRuntimeReplaysCompletedInvocation(t *testing.T) {
	c := newTestConnection()
	queueHandshake(c)
	c.in <- invocationEnvelope()
	var executions atomic.Int32
	r := newInvocationRuntime(t, c, func(_ context.Context, call CapabilityCall, _ CapabilityContext) (CapabilityResult, error) {
		executions.Add(1)
		return CapabilityResult{CallID: call.InvocationID}, nil
	})
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	c.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_COMPLETED {
				return true
			}
		}
		return false
	})
	c.in <- invocationEnvelope()
	frames := c.waitFor(t, func(frames []*protocol.Envelope) bool {
		count := 0
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_COMPLETED {
				count++
			}
		}
		return count == 2
	})
	if executions.Load() != 1 {
		cancel()
		t.Fatalf("completed duplicate executed %d times", executions.Load())
	}
	startedCount := 0
	for _, frame := range frames {
		if frame.MessageType == protocol.MessageType_STARTED {
			startedCount++
		}
	}
	if startedCount != 1 {
		cancel()
		t.Fatalf("terminal replay sent %d STARTED frames", startedCount)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-008 sends cancellation to an active invocation context and preserves its cancellation terminal.
func TestRuntimeCancelsRunningInvocation(t *testing.T) {
	c := newTestConnection()
	queueHandshake(c)
	c.in <- invocationEnvelope()
	cancelMessage := &protocol.Envelope{MessageType: protocol.MessageType_CANCEL, EnvironmentId: stringPtr("env"), InvocationId: stringPtr(testInvocationID), Payload: &protocol.Envelope_Cancel{Cancel: &protocol.Cancel{}}}
	started := make(chan struct{})
	r := newInvocationRuntime(t, c, func(ctx context.Context, call CapabilityCall, _ CapabilityContext) (CapabilityResult, error) {
		close(started)
		<-ctx.Done()
		return CapabilityResult{}, ctx.Err()
	})
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	select {
	case <-started:
	case <-time.After(time.Second):
		cancel()
		t.Fatal("executor did not start")
	}
	c.in <- cancelMessage
	c.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_CANCELLED {
				return true
			}
		}
		return false
	})
	cancelled := false
	for _, frame := range c.frames() {
		if frame.MessageType == protocol.MessageType_CANCELLED {
			cancelled = frame.GetCancelled().GetReason() == "cancelled"
		}
	}
	if !cancelled {
		cancel()
		t.Fatal("cancelled terminal did not preserve the cancellation reason")
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
