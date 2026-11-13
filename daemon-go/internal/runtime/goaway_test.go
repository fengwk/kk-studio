package runtime

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// observingConnectionFactory hands each connection attempt the observer the runtime created for it
// and records that observer so a test can raise transport events for a chosen generation.
type observingConnectionFactory struct {
	mu        sync.Mutex
	counting  []Connection
	observers []ControlObserver
	claims    []func(ControlObserver)
}

func (f *observingConnectionFactory) Connect(ctx context.Context, observer ControlObserver) (Connection, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.observers = append(f.observers, observer)
	if len(f.claims) > 0 {
		claim := f.claims[0]
		f.claims = f.claims[1:]
		// Raise the event while the attempt is still dialing: the runtime has not bound it yet.
		claim(observer)
	}
	if len(f.counting) == 0 {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		return nil, errors.New("no test connection remains")
	}
	connection := f.counting[0]
	f.counting = f.counting[1:]
	return connection, nil
}

func (f *observingConnectionFactory) observer(index int) ControlObserver {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.observers[index]
}

func (f *observingConnectionFactory) attempts() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.observers)
}

// closeCountingConnection records how many times the daemon closed its control connection, so the
// GOAWAY path can prove it closes the retired generation exactly once.
type closeCountingConnection struct {
	*testConnection
	closes atomic.Int32
}

func newCloseCountingConnection() *closeCountingConnection {
	return &closeCountingConnection{testConnection: newTestConnection()}
}

func (c *closeCountingConnection) Close() error {
	c.closes.Add(1)
	return c.testConnection.Close()
}

// RUN-T-055 retires the old generation on a graceful GOAWAY exactly once: the generation is closed,
// its IDE work is cancelled with CONTROL_DISCONNECTED while the in-flight Agent execution survives,
// and the runtime reconnects immediately instead of waiting for the disconnect backoff.
func TestRuntimeClosesOldGenerationOnceOnGoawayAndCancelsItsIDEWork(t *testing.T) {
	first, second := newCloseCountingConnection(), newTestConnection()
	queueHandshake(first.testConnection)
	first.in <- invocationEnvelope()
	queueHandshake(second)
	factory := &observingConnectionFactory{counting: []Connection{first, second}}
	ide := &blockingIDEAdmission{entered: make(chan struct{}, 1), release: make(chan struct{}), canUpdate: true}
	agentStarted, releaseAgent := make(chan struct{}), make(chan struct{})
	r, err := NewRuntime(RuntimeOptions{
		EnvironmentID:         "env",
		DaemonVersion:         "1.0.0",
		Connections:           factory,
		InitialReconnectDelay: time.Hour,
		MaxReconnectDelay:     time.Hour,
		IDE:                   ide,
		ApplyConfig: func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: version}, nil
		},
		Capabilities: map[string]CapabilityExecutor{"echo": func(ctx context.Context, call CapabilityCall, _ CapabilityContext) (CapabilityResult, error) {
			close(agentStarted)
			select {
			case <-releaseAgent:
				return CapabilityResult{CallID: call.InvocationID}, nil
			case <-ctx.Done():
				return CapabilityResult{}, ctx.Err()
			}
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	select {
	case <-agentStarted:
	case <-time.After(time.Second):
		cancel()
		t.Fatal("in-flight invocation did not start")
	}
	first.in <- &protocol.Envelope{
		MessageType: protocol.MessageType_IDE_COMMAND, EnvironmentId: stringPtr("env"),
		InvocationId: stringPtr("123e4567-e89b-42d3-a456-426614174055"),
		Payload:      &protocol.Envelope_IdeCommand{IdeCommand: &protocol.IdeCommand{Action: protocol.IdeAction_CLOSE_VIEW, Discard: boolPtr(false)}},
	}
	select {
	case <-ide.entered:
	case <-time.After(time.Second):
		cancel()
		t.Fatal("IDE command did not start")
	}

	factory.observer(0).NotifyGoaway(0)

	ideWG := make(chan struct{})
	go func() { defer close(ideWG); r.ideWG.Wait() }()
	select {
	case <-ideWG:
	case <-time.After(time.Second):
		cancel()
		t.Fatal("GOAWAY did not cancel the retired generation's IDE work")
	}
	if got := first.closes.Load(); got != 1 {
		cancel()
		t.Fatalf("GOAWAY closed the retired generation %d times, want exactly once", got)
	}
	// The retired generation stops being the active connection, so its scope can no longer serve work.
	r.mu.RLock()
	current := r.connection
	r.mu.RUnlock()
	if current != nil && current.connection == Connection(first) {
		cancel()
		t.Fatal("GOAWAY left the retired generation as the active connection")
	}
	// A GOAWAY never cancels an accepted Agent execution: the journal keeps the RUNNING fact.
	if entry, ok := r.journal.Get(testInvocationID); !ok || entry.State != InvocationRunning {
		cancel()
		t.Fatalf("journal entry after GOAWAY = %+v, want RUNNING", entry)
	}
	// The reconnect is immediate, so it cannot depend on the one-hour backoff expiring.
	deadline := time.After(2 * time.Second)
	for factory.attempts() < 2 {
		select {
		case <-deadline:
			cancel()
			t.Fatalf("GOAWAY did not reconnect immediately: attempts=%d", factory.attempts())
		default:
			time.Sleep(time.Millisecond)
		}
	}
	second.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countFrames(frames, protocol.MessageType_READY) == 1
	})
	if got := first.closes.Load(); got != 1 {
		cancel()
		t.Fatalf("reconnect closed the retired generation again: closes=%d", got)
	}
	// A late GOAWAY from the retired generation must not disturb the new binding: the surviving
	// invocation still answers on the new generation instead of the runtime being torn down again.
	factory.observer(0).NotifyGoaway(0)
	second.in <- invocationEnvelope()
	second.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_STARTED && frame.GetStarted().GetReplayed() {
				return true
			}
		}
		return false
	})
	if got := first.closes.Load(); got != 1 {
		cancel()
		t.Fatalf("late GOAWAY closed the retired generation again: closes=%d", got)
	}
	close(releaseAgent)
	second.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countFrames(frames, protocol.MessageType_COMPLETED) == 1
	})
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-055 a GOAWAY observed before the attempt's generation was bound still retires that
// generation once it is attached, instead of leaving it serving traffic.
func TestRuntimeRetiresGenerationForGoawayBeforeBind(t *testing.T) {
	first, second := newCloseCountingConnection(), newTestConnection()
	queueHandshake(first.testConnection)
	queueHandshake(second)
	factory := &observingConnectionFactory{counting: []Connection{first, second}}
	r, err := NewRuntime(RuntimeOptions{
		EnvironmentID:         "env",
		DaemonVersion:         "1.0.0",
		Connections:           factory,
		InitialReconnectDelay: time.Hour,
		MaxReconnectDelay:     time.Hour,
		ApplyConfig: func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: version}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	// Raise the GOAWAY while the first attempt is still dialing, before it can be bound.
	factory.mu.Lock()
	factory.claims = append(factory.claims, func(observer ControlObserver) { observer.NotifyGoaway(0) })
	factory.mu.Unlock()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	second.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countFrames(frames, protocol.MessageType_READY) == 1
	})
	if got := first.closes.Load(); got != 1 {
		cancel()
		t.Fatalf("early GOAWAY closed the retired generation %d times, want exactly once", got)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-055 a GOAWAY carrying an error code is not a graceful drain; it stays on the ordinary
// disconnect/backoff path so the daemon does not treat a failed session as a clean handover.
func TestRuntimeIgnoresNonZeroGoawayCode(t *testing.T) {
	connection := newCloseCountingConnection()
	queueHandshake(connection.testConnection)
	factory := &observingConnectionFactory{counting: []Connection{connection}}
	r, err := NewRuntime(RuntimeOptions{
		EnvironmentID:         "env",
		DaemonVersion:         "1.0.0",
		Connections:           factory,
		InitialReconnectDelay: time.Hour,
		MaxReconnectDelay:     time.Hour,
		ApplyConfig: func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: version}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countFrames(frames, protocol.MessageType_READY) == 1
	})
	factory.observer(0).NotifyGoaway(2)
	time.Sleep(50 * time.Millisecond)
	if got := connection.closes.Load(); got != 0 {
		cancel()
		t.Fatalf("non-NO_ERROR GOAWAY closed the live generation %d times", got)
	}
	if attempts := factory.attempts(); attempts != 1 {
		cancel()
		t.Fatalf("non-NO_ERROR GOAWAY reconnected: attempts=%d", attempts)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
