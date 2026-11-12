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
