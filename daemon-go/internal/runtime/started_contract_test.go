package runtime

import (
	"context"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// TestRuntimeStartedFramesFollowReplayContract verifies false is omitted and true is explicitly declared.
func TestRuntimeStartedFramesFollowReplayContract(t *testing.T) {
	const environmentID = "123e4567-e89b-42d3-a456-426614174000"
	connection := newTestConnection()
	runtime, err := NewRuntime(RuntimeOptions{
		EnvironmentID: environmentID,
		Connections:   fixedConnectionFactory{connection: connection},
		ApplyConfig: func(context.Context, int64, *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	active := runtime.attachConnection(connection)
	defer runtime.dropConnection(active)
	settings := &protocol.Envelope{
		MessageType: protocol.MessageType_SETTINGS, EnvironmentId: stringPtr(environmentID),
		Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{}}},
	}
	if err := runtime.handleSettings(settings); err != nil {
		t.Fatal(err)
	}
	if err := runtime.sendStarted("fresh-call", false); err != nil {
		t.Fatal(err)
	}
	if err := runtime.sendStarted("replayed-call", true); err != nil {
		t.Fatal(err)
	}

	connection.mu.Lock()
	frames := append([]*protocol.Envelope(nil), connection.out...)
	connection.mu.Unlock()
	if len(frames) != 2 {
		t.Fatalf("got %d frames, want two STARTED frames", len(frames))
	}
	for i, frame := range frames {
		if _, err := protocol.MarshalEnvelope(frame); err != nil {
			t.Fatalf("STARTED frame %d violates protocol: %v", i, err)
		}
	}
	if frames[0].GetStarted().Replayed != nil {
		t.Fatal("fresh STARTED frame must omit replayed")
	}
	if frames[1].GetStarted().Replayed == nil || !frames[1].GetStarted().GetReplayed() {
		t.Fatal("replayed STARTED frame must declare replayed=true")
	}
}
