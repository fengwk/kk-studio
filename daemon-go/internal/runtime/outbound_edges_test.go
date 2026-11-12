package runtime

import (
	"context"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"github.com/fengwk/kk-studio/daemon/internal/update"
	"google.golang.org/protobuf/proto"
)

// RUN-T-119 verifies invocation and control frames require binding, while UPDATE_RESULT additionally requires READY.
func TestRuntimeOutboundFrameReadinessClasses(t *testing.T) {
	connection := newTestConnection()
	runtime, err := NewRuntime(RuntimeOptions{
		EnvironmentID: "env", Connections: fixedConnectionFactory{connection: connection},
		ApplyConfig: func(context.Context, int64, *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{}, nil
		},
		HeartbeatInterval: 5 * time.Millisecond,
	})
	if err != nil {
		t.Fatal(err)
	}
	active := runtime.attachConnection(connection)
	defer runtime.dropConnection(active)
	if err := runtime.sendStarted("call", false); err == nil {
		t.Fatal("STARTED was sent before the environment was bound")
	}
	settings := &protocol.Envelope{MessageType: protocol.MessageType_SETTINGS, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{}}}}
	if err := runtime.handleSettings(settings); err != nil {
		t.Fatal(err)
	}
	if err := runtime.sendStarted("call", false); err != nil {
		t.Fatalf("bound invocation frame was blocked before READY: %v", err)
	}
	if err := runtime.sendError(nil, "configuration is invalid"); err != nil {
		t.Fatalf("bound error frame was blocked before READY: %v", err)
	}
	if err := runtime.sendStatus(); err != nil {
		t.Fatalf("bound status frame was blocked before READY: %v", err)
	}
	if err := runtime.sendUpdateReceipt(update.Receipt{Command: update.Command{OperationID: "123e4567-e89b-42d3-a456-426614174000", TargetVersion: "1.2.3"}, Phase: update.PhaseAccepted}); err == nil {
		t.Fatal("UPDATE_RESULT was sent before READY")
	}
	if connection.sent(protocol.MessageType_UPDATE_RESULT) {
		t.Fatal("blocked UPDATE_RESULT reached the transport")
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go runtime.heartbeatLoop(ctx, done)
	deadline := time.After(time.Second)
	for !connection.sent(protocol.MessageType_HEARTBEAT) {
		select {
		case <-deadline:
			cancel()
			<-done
			t.Fatal("bound heartbeat was blocked before READY")
		case <-time.After(time.Millisecond):
		}
	}
	cancel()
	<-done
}

// RUN-T-106 limits pre-READY recovery to real in-flight calls while still accepting cancellation of those calls.
func TestRuntimeHandlesInFlightCancelAndRecoverBeforeReady(t *testing.T) {
	connection := newTestConnection()
	runtime, err := NewRuntime(RuntimeOptions{
		EnvironmentID: "env", Connections: fixedConnectionFactory{connection: connection},
		ApplyConfig: func(context.Context, int64, *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	active := runtime.attachConnection(connection)
	defer runtime.dropConnection(active)
	if err := runtime.gate.Bind("env"); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if start := runtime.journal.Start("in-flight"); !start.Created {
		t.Fatal("in-flight journal entry was not created")
	}
	runtime.callsMu.Lock()
	runtime.calls["in-flight"] = &runningCall{cancel: cancel}
	runtime.callsMu.Unlock()

	if err := runtime.handleRecover(&protocol.Envelope{InvocationId: stringPtr("in-flight")}); err != nil {
		t.Fatalf("pre-READY in-flight recovery failed: %v", err)
	}
	if !connection.sent(protocol.MessageType_STARTED) || connection.sent(protocol.MessageType_RECOVER_NOT_FOUND) {
		t.Fatal("pre-READY recovery did not replay only the known in-flight call")
	}
	if err := runtime.handleRecover(&protocol.Envelope{InvocationId: stringPtr("unknown")}); err != nil {
		t.Fatal(err)
	}
	if err := runtime.handleCancel(&protocol.Envelope{InvocationId: stringPtr("in-flight")}); err != nil {
		t.Fatalf("pre-READY in-flight cancellation failed: %v", err)
	}
	select {
	case <-ctx.Done():
	default:
		t.Fatal("pre-READY CANCEL did not stop the real in-flight call")
	}

	terminal := &protocol.Envelope{MessageType: protocol.MessageType_CANCELLED, InvocationId: stringPtr("in-flight"), Payload: &protocol.Envelope_Cancelled{Cancelled: &protocol.Cancelled{Reason: stringPtr("cancelled")}}}
	data, err := proto.Marshal(terminal)
	if err != nil {
		t.Fatal(err)
	}
	if !runtime.journal.Complete("in-flight", InvocationCancelled, data) {
		t.Fatal("terminal state was not stored")
	}
	connection.mu.Lock()
	framesBefore := len(connection.out)
	connection.mu.Unlock()
	if err := runtime.handleCancel(&protocol.Envelope{InvocationId: stringPtr("in-flight")}); err != nil {
		t.Fatal(err)
	}
	connection.mu.Lock()
	framesAfter := len(connection.out)
	connection.mu.Unlock()
	if framesAfter != framesBefore {
		t.Fatal("terminal facts were replayed before READY")
	}
	runtime.mu.Lock()
	runtime.ready = true
	runtime.mu.Unlock()
	runtime.gate.MarkReady()
	if err := runtime.handleRecover(&protocol.Envelope{InvocationId: stringPtr("unknown")}); err != nil {
		t.Fatal(err)
	}
	if !connection.sent(protocol.MessageType_RECOVER_NOT_FOUND) {
		t.Fatal("READY recovery did not report an unknown invocation")
	}
}
