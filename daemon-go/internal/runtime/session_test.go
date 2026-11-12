package runtime

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

type testConnection struct {
	in        chan *protocol.Envelope
	failures  chan error
	closed    chan struct{}
	closeOnce sync.Once
	mu        sync.Mutex
	out       []*protocol.Envelope
}

type fixedConnectionFactory struct{ connection Connection }

func (f fixedConnectionFactory) Connect(context.Context) (Connection, error) {
	return f.connection, nil
}

type sequenceConnectionFactory struct {
	mu          sync.Mutex
	connections []Connection
}

func (f *sequenceConnectionFactory) Connect(ctx context.Context) (Connection, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.connections) == 0 {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		return nil, errors.New("no test connection remains")
	}
	connection := f.connections[0]
	f.connections = f.connections[1:]
	return connection, nil
}

func newTestConnection() *testConnection {
	return &testConnection{in: make(chan *protocol.Envelope, 16), failures: make(chan error, 1), closed: make(chan struct{})}
}
func (c *testConnection) Receive(ctx context.Context) (*protocol.Envelope, error) {
	select {
	case e := <-c.in:
		return e, nil
	case err := <-c.failures:
		return nil, err
	case <-c.closed:
		return nil, errors.New("connection closed")
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}
func (c *testConnection) fail(err error) { c.failures <- err }
func (c *testConnection) Send(_ context.Context, e *protocol.Envelope) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.out = append(c.out, e)
	return nil
}
func (c *testConnection) Close() error {
	c.closeOnce.Do(func() { close(c.closed) })
	return nil
}
func (c *testConnection) sent(t protocol.MessageType) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, e := range c.out {
		if e.MessageType == t {
			return true
		}
	}
	return false
}

// RUN-T-003 (ADAPT) rejects SETTINGS missing the current ResourceBudgets contract.
func TestRuntimeSettingsRequireResourceBudgets(t *testing.T) {
	c := newTestConnection()
	r, err := NewRuntime(RuntimeOptions{EnvironmentID: "env", Connections: fixedConnectionFactory{connection: c}, ApplyConfig: func(_ context.Context, _ int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
		return config.DaemonConfig{}, nil
	}})
	if err != nil {
		t.Fatal(err)
	}
	if err := r.handleSettings(&protocol.Envelope{MessageType: protocol.MessageType_SETTINGS, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{}}}); err == nil {
		t.Fatal("expected missing resource budget rejection")
	}
}

// RUN-T-086 does not send READY until the first environment snapshot has finished applying.
func TestRuntimeSendsReadyAfterConfigurationApplication(t *testing.T) {
	c := newTestConnection()
	c.in <- &protocol.Envelope{MessageType: protocol.MessageType_SETTINGS, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{MaxResourceBytes: int64Ptr(100)}}}}
	configMessage := &protocol.Envelope{MessageType: protocol.MessageType_ENVIRONMENT_CONFIG, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(1), Config: &protocol.DaemonConfiguration{StudioUrl: "https://studio.example", Lsp: &protocol.LspConfiguration{}}}}}
	c.in <- configMessage
	c.in <- configMessage
	started, release := make(chan struct{}), make(chan struct{})
	r, err := NewRuntime(RuntimeOptions{EnvironmentID: "env", DaemonVersion: "1.0.0", Connections: fixedConnectionFactory{connection: c}, ApplyConfig: func(_ context.Context, v int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
		close(started)
		<-release
		return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: v}, nil
	}})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	select {
	case <-started:
	case <-time.After(time.Second):
		cancel()
		t.Fatal("configuration application did not begin")
	}
	if c.sent(protocol.MessageType_READY) {
		cancel()
		t.Fatal("READY sent before configuration application completed")
	}
	close(release)
	deadline := time.After(time.Second)
	for !c.sent(protocol.MessageType_READY) {
		select {
		case <-deadline:
			cancel()
			t.Fatal("READY was not sent")
		default:
			time.Sleep(time.Millisecond)
		}
	}
	for _, frame := range c.frames() {
		if frame.MessageType == protocol.MessageType_READY {
			ready := frame.GetReady()
			if ready.GetHostInfo() == nil || ready.GetRuntimeStatus().GetStatus().GetConfiguration().GetAppliedVersion() != 1 {
				cancel()
				t.Fatalf("READY omitted host or applied configuration status: %+v", ready)
			}
		}
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-063 rejects inbound envelopes that declare a different bound environment.
func TestRuntimeRejectsOtherEnvironmentScope(t *testing.T) {
	c := newTestConnection()
	r, err := NewRuntime(RuntimeOptions{EnvironmentID: "env", Connections: fixedConnectionFactory{connection: c}, ApplyConfig: func(_ context.Context, _ int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
		return config.DaemonConfig{}, nil
	}})
	if err != nil {
		t.Fatal(err)
	}
	if err := r.handleSettings(&protocol.Envelope{MessageType: protocol.MessageType_SETTINGS, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{MaxResourceBytes: int64Ptr(1)}}}}); err != nil {
		t.Fatal(err)
	}
	err = r.handle(context.Background(), &protocol.Envelope{MessageType: protocol.MessageType_HEARTBEAT, EnvironmentId: stringPtr("another-env"), Payload: &protocol.Envelope_Heartbeat{Heartbeat: &protocol.Heartbeat{}}})
	if err == nil || err.Error() != "envelope is bound to a different environment" {
		t.Fatalf("unexpected scope mismatch result: %v", err)
	}
}

// RUN-T-005 dispatches a valid INVOKE and sends STARTED before its terminal result.
func TestRuntimeExecutesInvocation(t *testing.T) {
	c := newTestConnection()
	c.in <- &protocol.Envelope{MessageType: protocol.MessageType_SETTINGS, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{MaxResourceBytes: int64Ptr(100)}}}}
	c.in <- &protocol.Envelope{MessageType: protocol.MessageType_ENVIRONMENT_CONFIG, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(1), Config: &protocol.DaemonConfiguration{StudioUrl: "https://studio.example", Lsp: &protocol.LspConfiguration{}}}}}
	c.in <- &protocol.Envelope{MessageType: protocol.MessageType_INVOKE, EnvironmentId: stringPtr("env"), InvocationId: stringPtr("123e4567-e89b-42d3-a456-426614174000"), Payload: &protocol.Envelope_Invoke{Invoke: &protocol.Invoke{CapabilityId: stringPtr("echo"), ArgumentsJson: stringPtr(`{"ok":true}`), TimeoutMillis: int64Ptr(0)}}}
	r, err := NewRuntime(RuntimeOptions{EnvironmentID: "env", DaemonVersion: "1.0.0", Connections: fixedConnectionFactory{connection: c}, ApplyConfig: func(_ context.Context, v int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
		return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: v}, nil
	}, Capabilities: map[string]CapabilityExecutor{"echo": func(_ context.Context, call CapabilityCall, _ CapabilityContext) (CapabilityResult, error) {
		if !json.Valid(call.Arguments) {
			t.Fatal("arguments were not JSON")
		}
		return CapabilityResult{CallID: call.InvocationID, Details: json.RawMessage(`{"ok":true}`)}, nil
	}}})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	deadline := time.After(time.Second)
	for !c.sent(protocol.MessageType_COMPLETED) {
		select {
		case <-deadline:
			cancel()
			t.Fatal("COMPLETED was not sent")
		default:
			time.Sleep(time.Millisecond)
		}
	}
	frames := c.frames()
	startedIndex, completedIndex := -1, -1
	for index, frame := range frames {
		if frame.MessageType == protocol.MessageType_STARTED && startedIndex < 0 {
			startedIndex = index
		}
		if frame.MessageType == protocol.MessageType_COMPLETED && completedIndex < 0 {
			completedIndex = index
		}
	}
	if startedIndex < 0 || completedIndex < 0 || startedIndex >= completedIndex {
		t.Fatalf("STARTED must precede COMPLETED: started=%d completed=%d", startedIndex, completedIndex)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
