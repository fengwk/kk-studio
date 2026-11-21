package runtime

import (
	"context"
	"path/filepath"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// A lost exit acknowledgement leaves the durable fact for a new READY connection to replay.
func TestRuntimeReplaysUnacknowledgedExitAfterReconnect(t *testing.T) {
	path := filepath.Join(t.TempDir(), "journal.json")
	journal, err := NewPersistentInvocationJournal(path, 4)
	if err != nil {
		t.Fatal(err)
	}
	if start := journal.Start(testInvocationID); start.Err != nil || !start.Created {
		t.Fatalf("record invocation: %+v", start)
	}
	if completed, err := journal.CompleteWithExitDurable(testInvocationID, InvocationFailed, nil, false); err != nil || !completed {
		t.Fatalf("record terminal and exit: completed=%t err=%v", completed, err)
	}

	options := RuntimeOptions{
		EnvironmentID: "env", InvocationJournalPath: path,
		Connections: fixedConnectionFactory{connection: newTestConnection()},
		ApplyConfig: func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: version}, nil
		},
	}
	runtime, err := NewRuntime(options)
	if err != nil {
		t.Fatal(err)
	}
	firstConnection := newTestConnection()
	first := runtime.attachConnection(firstConnection)
	if err := runtime.handleSettings(settingsEnvelope()); err != nil {
		t.Fatal(err)
	}
	if err := runtime.handleEnvironmentConfig(context.Background(), configEnvelope(1)); err != nil {
		t.Fatal(err)
	}
	if countFrames(firstConnection.frames(), protocol.MessageType_EXECUTION_EXITED) != 1 {
		t.Fatal("READY did not replay the pending exit proof")
	}
	if err := runtime.dropConnection(first); err != nil {
		t.Fatal(err)
	}

	secondConnection := newTestConnection()
	second := runtime.attachConnection(secondConnection)
	defer runtime.dropConnection(second)
	if err := runtime.handleSettings(settingsEnvelope()); err != nil {
		t.Fatal(err)
	}
	if err := runtime.handleEnvironmentConfig(context.Background(), configEnvelope(1)); err != nil {
		t.Fatal(err)
	}
	if countFrames(secondConnection.frames(), protocol.MessageType_EXECUTION_EXITED) != 1 {
		t.Fatal("unacknowledged exit proof was not replayed after reconnect")
	}

	ack := &protocol.Envelope{
		MessageType:   protocol.MessageType_EXECUTION_EXIT_ACK,
		EnvironmentId: stringPtr("env"), InvocationId: stringPtr(testInvocationID),
		Payload: &protocol.Envelope_ExecutionExitAck{ExecutionExitAck: &protocol.ExecutionExitAck{}},
	}
	if err := runtime.handleWithIDEContext(context.Background(), context.Background(), nil, ack); err != nil {
		t.Fatal(err)
	}
	if _, exists := runtime.journal.Get(testInvocationID); exists {
		t.Fatal("acknowledged terminal and exit fact was not collected")
	}
	if err := runtime.handleRecover(&protocol.Envelope{InvocationId: stringPtr(testInvocationID)}); err != nil {
		t.Fatal(err)
	}
	frames := secondConnection.frames()
	if countFrames(frames, protocol.MessageType_STARTED) != 0 || countFrames(frames, protocol.MessageType_RECOVER_NOT_FOUND) != 1 {
		t.Fatal("collected invocation was executed instead of remaining unknown to recovery")
	}
}
