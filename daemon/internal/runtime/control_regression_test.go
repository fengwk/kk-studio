package runtime

import (
	"context"
	"path/filepath"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

func settingsEnvelope() *protocol.Envelope {
	return &protocol.Envelope{MessageType: protocol.MessageType_SETTINGS, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{TemporaryResourceTtlSeconds: int64Ptr(120), TemporaryResourceCleanupIntervalSeconds: int64Ptr(30)}}}}
}

func configEnvelope(version int64) *protocol.Envelope {
	return &protocol.Envelope{MessageType: protocol.MessageType_ENVIRONMENT_CONFIG, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(version), Config: &protocol.DaemonConfiguration{StudioUrl: "https://studio.example", Lsp: &protocol.LspConfiguration{}}}}}
}

// Repeated SETTINGS refreshes resource policy without clearing READY or republishing the handshake.
func TestRuntimeSettingsRefreshKeepsReadyInvocationAvailable(t *testing.T) {
	connection := newTestConnection()
	connection.in <- settingsEnvelope()
	connection.in <- configEnvelope(1)
	connection.in <- settingsEnvelope()
	connection.in <- invocationEnvelope()
	runtime, err := NewRuntime(RuntimeOptions{
		EnvironmentID: "env", Connections: fixedConnectionFactory{connection: connection},
		ApplyConfig: func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: version}, nil
		},
		Capabilities: map[string]CapabilityExecutor{"echo": func(_ context.Context, call CapabilityCall, _ CapabilityContext) (CapabilityResult, error) {
			return CapabilityResult{CallID: call.InvocationID}, nil
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- runtime.Run(ctx) }()
	frames := connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countFrames(frames, protocol.MessageType_COMPLETED) == 1
	})
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if countFrames(frames, protocol.MessageType_READY) != 1 || countFrames(frames, protocol.MessageType_RUNTIME_STATUS) != 0 {
		t.Fatalf("SETTINGS refresh republished handshake/status: READY=%d STATUS=%d", countFrames(frames, protocol.MessageType_READY), countFrames(frames, protocol.MessageType_RUNTIME_STATUS))
	}
	if countFrames(frames, protocol.MessageType_STARTED) != 1 || countFrames(frames, protocol.MessageType_COMPLETED) != 1 {
		t.Fatalf("invocation did not complete after SETTINGS refresh: STARTED=%d COMPLETED=%d", countFrames(frames, protocol.MessageType_STARTED), countFrames(frames, protocol.MessageType_COMPLETED))
	}
}

// READY, SETTINGS refresh, invocation, reconnect, and recovery preserve exactly one execution.
func TestRuntimeSettingsInvokeReconnectRecover(t *testing.T) {
	firstConnection := newTestConnection()
	var executions int
	runtime := newInvocationRuntime(t, firstConnection, func(_ context.Context, call CapabilityCall, _ CapabilityContext) (CapabilityResult, error) {
		executions++
		return CapabilityResult{CallID: call.InvocationID}, nil
	})
	first := runtime.attachConnection(firstConnection)
	if err := runtime.handleSettings(settingsEnvelope()); err != nil {
		t.Fatal(err)
	}
	if err := runtime.handleEnvironmentConfig(context.Background(), configEnvelope(1)); err != nil {
		t.Fatal(err)
	}
	if err := runtime.handleSettings(settingsEnvelope()); err != nil {
		t.Fatal(err)
	}
	drift := configEnvelope(1)
	drift.Payload.(*protocol.Envelope_EnvironmentConfig).EnvironmentConfig.Config.StudioUrl = "https://drift.example"
	if err := runtime.handleEnvironmentConfig(context.Background(), drift); err == nil {
		t.Fatal("same-version configuration drift was accepted after SETTINGS refresh")
	}
	if err := runtime.handleWithIDEContext(context.Background(), context.Background(), nil, invocationEnvelope()); err != nil {
		t.Fatal(err)
	}
	firstFrames := firstConnection.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countFrames(frames, protocol.MessageType_COMPLETED) == 1
	})
	if countFrames(firstFrames, protocol.MessageType_READY) != 1 {
		t.Fatalf("first connection READY count=%d", countFrames(firstFrames, protocol.MessageType_READY))
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
	if err := runtime.handleRecover(&protocol.Envelope{InvocationId: stringPtr(testInvocationID)}); err != nil {
		t.Fatal(err)
	}
	frames := secondConnection.frames()
	if executions != 1 || countFrames(frames, protocol.MessageType_COMPLETED) != 1 || countFrames(frames, protocol.MessageType_RECOVER_NOT_FOUND) != 0 {
		t.Fatalf("recovery executions=%d completed=%d recoverNotFound=%d", executions, countFrames(frames, protocol.MessageType_COMPLETED), countFrames(frames, protocol.MessageType_RECOVER_NOT_FOUND))
	}
}

// A deterministic pre-READY rejection has a call-level terminal and exit proof, and duplicate delivery replays it.
func TestRuntimePreReadyInvokeIsJournaledAsFailedAndExited(t *testing.T) {
	connection := newTestConnection()
	runtime, err := NewRuntime(RuntimeOptions{
		EnvironmentID: "env", Connections: fixedConnectionFactory{connection: connection},
		ApplyConfig: func(context.Context, int64, *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{EnvironmentID: "env"}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	active := runtime.attachConnection(connection)
	defer runtime.dropConnection(active)
	if err := runtime.handleSettings(settingsEnvelope()); err != nil {
		t.Fatal(err)
	}
	if err := runtime.handleWithIDEContext(context.Background(), context.Background(), nil, invocationEnvelope()); err != nil {
		t.Fatal(err)
	}
	if err := runtime.handleWithIDEContext(context.Background(), context.Background(), nil, invocationEnvelope()); err != nil {
		t.Fatal(err)
	}
	frames := connection.frames()
	if countFrames(frames, protocol.MessageType_FAILED) != 2 || countFrames(frames, protocol.MessageType_EXECUTION_EXITED) != 2 {
		t.Fatalf("pre-READY rejection replay: FAILED=%d EXECUTION_EXITED=%d", countFrames(frames, protocol.MessageType_FAILED), countFrames(frames, protocol.MessageType_EXECUTION_EXITED))
	}
	entry, ok := runtime.journal.Get(testInvocationID)
	if !ok || entry.State != InvocationFailed || !entry.Exited {
		t.Fatalf("pre-READY call facts were not recorded: %+v, exists=%t", entry, ok)
	}
}

// A recovered in-flight identity blocks a duplicate INVOKE after daemon restart.
func TestRuntimeRecoveredInvocationIsNotExecutedAgain(t *testing.T) {
	path := filepath.Join(t.TempDir(), "journal.json")
	journal, err := NewPersistentInvocationJournal(path, 8)
	if err != nil {
		t.Fatal(err)
	}
	if start := journal.Start(testInvocationID); start.Err != nil || !start.Created {
		t.Fatalf("record received invocation: %+v", start)
	}
	connection := newTestConnection()
	var executions int
	runtime, err := NewRuntime(RuntimeOptions{
		EnvironmentID: "env", Connections: fixedConnectionFactory{connection: connection}, InvocationJournalPath: path,
		ApplyConfig: func(context.Context, int64, *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{EnvironmentID: "env"}, nil
		},
		Capabilities: map[string]CapabilityExecutor{"echo": func(context.Context, CapabilityCall, CapabilityContext) (CapabilityResult, error) {
			executions++
			return CapabilityResult{}, nil
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	active := runtime.attachConnection(connection)
	defer runtime.dropConnection(active)
	if err := runtime.handleSettings(settingsEnvelope()); err != nil {
		t.Fatal(err)
	}
	if err := runtime.handleWithIDEContext(context.Background(), context.Background(), nil, invocationEnvelope()); err != nil {
		t.Fatal(err)
	}
	if executions != 0 || !connection.sent(protocol.MessageType_RECOVER_NOT_FOUND) {
		t.Fatalf("recovered invocation execution=%d RECOVER_NOT_FOUND=%t", executions, connection.sent(protocol.MessageType_RECOVER_NOT_FOUND))
	}
}

// A RECOVER received before READY must not be silently discarded or misreported as unknown.
func TestRuntimePreReadyRecoverReturnsRetryableConnectionFailure(t *testing.T) {
	connection := newTestConnection()
	runtime, err := NewRuntime(RuntimeOptions{
		EnvironmentID: "env", Connections: fixedConnectionFactory{connection: connection},
		ApplyConfig: func(context.Context, int64, *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{EnvironmentID: "env"}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	active := runtime.attachConnection(connection)
	defer runtime.dropConnection(active)
	if err := runtime.handleSettings(settingsEnvelope()); err != nil {
		t.Fatal(err)
	}
	err = runtime.handleWithIDEContext(context.Background(), context.Background(), nil, &protocol.Envelope{MessageType: protocol.MessageType_RECOVER, EnvironmentId: stringPtr("env"), InvocationId: stringPtr("missing")})
	if err == nil {
		t.Fatal("pre-READY RECOVER was silently accepted")
	}
	if !connection.sent(protocol.MessageType_ERROR) || connection.sent(protocol.MessageType_RECOVER_NOT_FOUND) {
		t.Fatal("pre-READY RECOVER did not return a connection-level retry error")
	}
}
