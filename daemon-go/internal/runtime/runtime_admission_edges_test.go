package runtime

import (
	"context"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// RUN-T-020 verifies the concurrent invocation ceiling rejects excess work with the contracted failure.
func TestRuntimeRejectsInvocationBeyondConcurrentLimit(t *testing.T) {
	started := make(chan string, 1)
	release := make(chan struct{})
	options := runtimeOptionsForTest()
	options.MaxConcurrentInvocations = 1
	options.Capabilities = map[string]CapabilityExecutor{
		"echo": func(_ context.Context, call CapabilityCall, _ CapabilityContext) (CapabilityResult, error) {
			started <- call.InvocationID
			<-release
			return CapabilityResult{CallID: call.InvocationID, Details: []byte(`{}`)}, nil
		},
	}
	runtime, connection := newReadyRuntimeForTest(t, options)
	if err := runtime.handleInvoke(context.Background(), invokeWithArgs("first", `{}`)); err != nil {
		t.Fatal(err)
	}
	select {
	case id := <-started:
		if id != "first" {
			t.Fatalf("executor started invocation %q", id)
		}
	case <-time.After(time.Second):
		t.Fatal("first invocation did not enter the executor")
	}
	if err := runtime.handleInvoke(context.Background(), invokeWithArgs("second", `{}`)); err != nil {
		t.Fatal(err)
	}
	frames := connection.frames()
	found := false
	for _, frame := range frames {
		if frame.MessageType == protocol.MessageType_FAILED && frame.GetFailed().GetMessage() == "too many concurrent capability invocations" {
			found = true
		}
	}
	if !found {
		t.Fatalf("concurrency limit rejection missing from frames: %+v", frames)
	}
	close(release)
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_COMPLETED && frame.GetCompleted().GetResult().GetCallId() == "first" {
				return true
			}
		}
		return false
	})
}

// RUN-T-018 fails and cancels an invocation when its progress payload cannot be encoded safely.
func TestRuntimeProgressEncodingFailureCancelsCallAndFails(t *testing.T) {
	progressReturned := make(chan error, 1)
	callCancelled := make(chan bool, 1)
	options := runtimeOptionsForTest()
	options.Capabilities = map[string]CapabilityExecutor{
		"echo": func(ctx context.Context, call CapabilityCall, capabilityContext CapabilityContext) (CapabilityResult, error) {
			progressReturned <- capabilityContext.Progress(CapabilityResult{
				CallID:   call.InvocationID,
				Contents: []ResultContent{{Type: "binary", Bytes: []byte("not allowed in progress")}},
			})
			callCancelled <- ctx.Err() != nil
			return CapabilityResult{CallID: call.InvocationID, Details: []byte(`{}`)}, nil
		},
	}
	runtime, connection := newReadyRuntimeForTest(t, options)
	if err := runtime.handleInvoke(context.Background(), invokeWithArgs("progress-failure", `{}`)); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-progressReturned:
		if err == nil || err.Error() != "progress does not allow binary content" {
			t.Fatalf("progress error = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("progress callback did not return")
	}
	select {
	case cancelled := <-callCancelled:
		if !cancelled {
			t.Fatal("progress encoding failure did not cancel the active invocation")
		}
	case <-time.After(time.Second):
		t.Fatal("capability executor did not report cancellation state")
	}
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_FAILED && frame.GetFailed().GetMessage() == "progress does not allow binary content" {
				return true
			}
		}
		return false
	})
	if connection.sent(protocol.MessageType_CANCELLED) {
		t.Fatal("progress encoding failure was reported as user cancellation")
	}
}
