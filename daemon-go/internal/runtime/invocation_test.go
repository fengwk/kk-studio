package runtime

import (
	"context"
	"strings"
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
	c.in <- &protocol.Envelope{MessageType: protocol.MessageType_SETTINGS, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{}}}}
	c.in <- &protocol.Envelope{MessageType: protocol.MessageType_ENVIRONMENT_CONFIG, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(1), Config: &protocol.DaemonConfiguration{StudioUrl: "https://studio.example", Lsp: &protocol.LspConfiguration{}}}}}
}

func invocationEnvelope() *protocol.Envelope {
	return &protocol.Envelope{MessageType: protocol.MessageType_INVOKE, EnvironmentId: stringPtr("env"), InvocationId: stringPtr(testInvocationID), Payload: &protocol.Envelope_Invoke{Invoke: &protocol.Invoke{CapabilityId: stringPtr("echo"), ArgumentsJson: stringPtr(`{}`), TimeoutMillis: int64Ptr(0), MaxOutputBytes: int64Ptr(1024), ImageInputTier: imageTierPtr(protocol.ImageInputTier_P720)}}}
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
	partialSent := make(chan struct{})
	r := newInvocationRuntime(t, c, func(ctx context.Context, call CapabilityCall, capability CapabilityContext) (CapabilityResult, error) {
		close(started)
		if err := capability.Progress(CapabilityResult{CallID: call.InvocationID, Contents: []ResultContent{{Type: "text", Text: "partial match"}}}); err != nil {
			return CapabilityResult{}, err
		}
		close(partialSent)
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
	select {
	case <-partialSent:
	case <-time.After(time.Second):
		cancel()
		t.Fatal("partial result was not sent")
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
	frames := c.frames()
	if got := countFrames(frames, protocol.MessageType_CANCELLED); got != 1 {
		t.Fatalf("CANCELLED terminals=%d, want one", got)
	}
	if got := countFrames(frames, protocol.MessageType_COMPLETED); got != 0 {
		t.Fatalf("completed terminals=%d, want none for cancelled call", got)
	}
	partialCount := 0
	partialPreserved := false
	for _, frame := range frames {
		if frame.MessageType == protocol.MessageType_CANCELLED && frame.GetCancelled().GetReason() != "cancelled" {
			t.Fatalf("cancelled terminal reason=%q", frame.GetCancelled().GetReason())
		}
		if frame.MessageType == protocol.MessageType_PROGRESS {
			partialCount++
			contents := frame.GetProgress().GetResult().GetContents()
			partialPreserved = len(contents) == 1 && contents[0].GetText().GetText() == "partial match"
		}
	}
	if partialCount != 1 || !partialPreserved {
		t.Fatalf("partial progress count=%d preserved=%t, want exactly one delivered match", partialCount, partialPreserved)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-233: an executor that cleans up after CANCEL and returns its partial result is delivered
// as exactly one COMPLETED(error=true) carrying that result, never a CANCELLED terminal, which
// the platform treats as a result-less failure; one exit confirmation follows.
func TestRuntimeCancelledCapabilityResultIsCompletedWithError(t *testing.T) {
	c := newTestConnection()
	queueHandshake(c)
	c.in <- invocationEnvelope()
	started := make(chan struct{})
	r := newInvocationRuntime(t, c, func(ctx context.Context, call CapabilityCall, capability CapabilityContext) (CapabilityResult, error) {
		close(started)
		<-ctx.Done()
		capability.ConfirmExecutionExit()
		return CapabilityResult{
			CallID: call.InvocationID, Error: true, Details: []byte(`{"code":"CANCELLED"}`),
			Contents: []ResultContent{{Type: "text", Text: "[Search cancelled; results are incomplete.]"}},
		}, nil
	})
	runCtx, stopRuntime := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(runCtx) }()
	select {
	case <-started:
	case <-time.After(time.Second):
		stopRuntime()
		t.Fatal("executor did not start")
	}
	c.in <- &protocol.Envelope{MessageType: protocol.MessageType_CANCEL, EnvironmentId: stringPtr("env"), InvocationId: stringPtr(testInvocationID), Payload: &protocol.Envelope_Cancel{Cancel: &protocol.Cancel{}}}
	frames := c.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countFrames(frames, protocol.MessageType_COMPLETED) == 1 && countFrames(frames, protocol.MessageType_EXECUTION_EXITED) == 1
	})
	if got := countFrames(frames, protocol.MessageType_COMPLETED); got != 1 {
		stopRuntime()
		t.Fatalf("COMPLETED terminals=%d, want one", got)
	}
	if got := countFrames(frames, protocol.MessageType_CANCELLED); got != 0 {
		stopRuntime()
		t.Fatalf("CANCELLED terminals=%d, want none when the executor returned a result", got)
	}
	for _, frame := range frames {
		if frame.MessageType == protocol.MessageType_COMPLETED {
			result := frame.GetCompleted().GetResult()
			if !result.GetError() || !strings.Contains(result.GetDetailsJson(), `"code":"CANCELLED"`) || len(result.GetContents()) != 1 {
				stopRuntime()
				t.Fatalf("COMPLETED result = %v, want the executor's cancelled partial result", result)
			}
		}
	}
	if got := countFrames(frames, protocol.MessageType_EXECUTION_EXITED); got != 1 {
		stopRuntime()
		t.Fatalf("EXECUTION_EXITED frames=%d, want one", got)
	}
	stopRuntime()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
