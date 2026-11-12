package runtime

import (
	"context"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// RUN-T-087 keeps the session CONNECTING after initial validation failure and becomes READY after a repaired snapshot.
func TestRuntimeSignalsConfigurationFailureAndAllowsRepair(t *testing.T) {
	initial := initialRuntimeConfig()
	status := NewStatusTracker()
	manager, err := NewEnvironmentConfigRuntime(EnvironmentConfigOptions{
		Initial: initial,
		Cache:   &testCacheWriter{},
		Prepare: (&testConfigurationPreparer{}).prepare,
		Status:  status,
	})
	if err != nil {
		t.Fatal(err)
	}
	connection, reconnected := newTestConnection(), newTestConnection()
	connection.in <- &protocol.Envelope{MessageType: protocol.MessageType_SETTINGS, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{}}}}
	first := configurationOf(initial)
	connection.in <- &protocol.Envelope{MessageType: protocol.MessageType_ENVIRONMENT_CONFIG, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(1), Config: first}}}
	invalid := configurationOf(initial)
	invalid.StudioUrl = "file:///invalid"
	connection.in <- &protocol.Envelope{MessageType: protocol.MessageType_ENVIRONMENT_CONFIG, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(2), Config: invalid}}}
	repaired := configurationOf(initial)
	repaired.StudioUrl = "https://repaired.example"
	connection.in <- &protocol.Envelope{MessageType: protocol.MessageType_ENVIRONMENT_CONFIG, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(3), Config: repaired}}}
	reconnected.in <- &protocol.Envelope{MessageType: protocol.MessageType_SETTINGS, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{}}}}
	reconnected.in <- &protocol.Envelope{MessageType: protocol.MessageType_ENVIRONMENT_CONFIG, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(3), Config: repaired}}}
	factory := &sequenceConnectionFactory{connections: []Connection{connection, reconnected}}
	r, err := NewRuntime(RuntimeOptions{EnvironmentID: "env", Connections: factory, InitialReconnectDelay: time.Millisecond, MaxReconnectDelay: 10 * time.Millisecond, EnvironmentConfig: manager, Status: status})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		var ready, failure bool
		for _, frame := range frames {
			switch frame.MessageType {
			case protocol.MessageType_READY:
				ready = true
			case protocol.MessageType_RUNTIME_STATUS:
				errorStatus := frame.GetRuntimeStatus().GetRuntimeStatus().GetStatus().GetConfiguration().GetError()
				failure = failure || errorStatus != nil && errorStatus.GetField() == "studioUrl"
			}
		}
		return ready && failure
	})
	deadline := time.After(time.Second)
	for {
		frames := reconnected.frames()
		ready := false
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_READY && frame.GetReady().GetRuntimeStatus().GetStatus().GetConfiguration().GetAppliedVersion() == 3 {
				ready = true
			}
		}
		if ready {
			break
		}
		select {
		case err := <-done:
			t.Fatalf("runtime stopped before READY after reconnect: %v; first=%v second=%v", err, connection.frames(), frames)
		case <-deadline:
			t.Fatalf("reconnected session did not become READY; first=%v second=%v", connection.frames(), frames)
		case <-time.After(time.Millisecond):
		}
	}
	if manager.Current().ConfigVersion != 3 || manager.Current().StudioURL != "https://repaired.example" {
		t.Fatalf("repaired configuration was not published: %+v", manager.Current())
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
