package runtime

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// Keeps an in-flight invocation alive across disconnect and replays its RUNNING fact after reconnect.
func TestRuntimeRetainsInFlightExecutionAcrossReconnect(t *testing.T) {
	first, second := newTestConnection(), newTestConnection()
	queueHandshake(first)
	first.in <- invocationEnvelope()
	queueHandshake(second)
	second.in <- &protocol.Envelope{MessageType: protocol.MessageType_RECOVER, EnvironmentId: stringPtr("env"), InvocationId: stringPtr(testInvocationID), Payload: &protocol.Envelope_Recover{Recover: &protocol.Recover{}}}
	started, release := make(chan struct{}), make(chan struct{})
	var executions atomic.Int32
	r, err := NewRuntime(RuntimeOptions{
		EnvironmentID:         "env",
		DaemonVersion:         "1.0.0",
		Connections:           &sequenceConnectionFactory{connections: []Connection{first, second}},
		InitialReconnectDelay: time.Millisecond,
		MaxReconnectDelay:     10 * time.Millisecond,
		ApplyConfig: func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: version}, nil
		},
		Capabilities: map[string]CapabilityExecutor{"echo": func(ctx context.Context, call CapabilityCall, _ CapabilityContext) (CapabilityResult, error) {
			executions.Add(1)
			close(started)
			select {
			case <-release:
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
	case <-started:
	case <-time.After(time.Second):
		cancel()
		t.Fatal("invocation did not start")
	}
	first.fail(errors.New("remote connection lost"))
	frames := second.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_STARTED && frame.GetStarted().GetReplayed() {
				return true
			}
		}
		return false
	})
	if executions.Load() != 1 {
		cancel()
		t.Fatalf("recovery executed capability again: executions=%d", executions.Load())
	}
	var replayed bool
	for _, frame := range frames {
		if frame.MessageType == protocol.MessageType_STARTED {
			replayed = frame.GetStarted().GetReplayed()
		}
	}
	if !replayed {
		cancel()
		t.Fatal("reconnected session did not replay STARTED")
	}
	close(release)
	second.waitFor(t, func(frames []*protocol.Envelope) bool {
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

// Treats an explicit registration rejection as terminal rather than retryable.
func TestRuntimeDoesNotRetryRegistrationRejection(t *testing.T) {
	factory := &rejectingConnectionFactory{}
	r, err := NewRuntime(RuntimeOptions{EnvironmentID: "env", Connections: factory, ApplyConfig: func(_ context.Context, _ int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
		return config.DaemonConfig{}, nil
	}})
	if err != nil {
		t.Fatal(err)
	}
	if err := r.Run(context.Background()); !errors.Is(err, ErrRegistrationRejected) {
		t.Fatalf("registration rejection was not returned: %v", err)
	}
	if factory.calls.Load() != 1 {
		t.Fatalf("registration rejection retried %d times", factory.calls.Load())
	}
}

type rejectingConnectionFactory struct{ calls atomic.Int32 }

func (f *rejectingConnectionFactory) Connect(context.Context, ControlObserver) (Connection, error) {
	f.calls.Add(1)
	return nil, ErrRegistrationRejected
}

// Retries a transient connection attempt and establishes the injected channel on the next attempt.
func TestRuntimeRetriesTransientConnectFailure(t *testing.T) {
	connection := newTestConnection()
	queueHandshake(connection)
	factory := &retryingConnectionFactory{connection: connection}
	r, err := NewRuntime(RuntimeOptions{
		EnvironmentID:         "env",
		Connections:           factory,
		InitialReconnectDelay: time.Millisecond,
		MaxReconnectDelay:     10 * time.Millisecond,
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
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_READY {
				return true
			}
		}
		return false
	})
	if factory.calls.Load() != 2 {
		cancel()
		t.Fatalf("connect attempts=%d, want initial failure plus retry", factory.calls.Load())
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// Fake time distinguishes a reset backoff, an immediate reconnect, and GOAWAY's preserved backoff.
func TestRuntimeReconnectDelayByGenerationOutcome(t *testing.T) {
	const initialDelay = time.Second
	for _, tc := range []struct {
		name string
		want time.Duration
	}{
		{"disconnect after READY", initialDelay},
		{"Studio URL changed", 0},
		{"proxy URL changed", 0},
		{"GOAWAY then dial failure", 2 * initialDelay},
	} {
		t.Run(tc.name, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				initial := initialRuntimeConfig()
				manager, err := NewEnvironmentConfigRuntime(EnvironmentConfigOptions{
					Initial: initial, Cache: &testCacheWriter{}, Prepare: (&testConfigurationPreparer{}).prepare, Status: NewStatusTracker(),
				})
				if err != nil {
					t.Fatal(err)
				}
				conn := newTestConnection()
				conn.in <- &protocol.Envelope{MessageType: protocol.MessageType_SETTINGS, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{}}}}
				environmentFrame := func(version int64, cfg *protocol.DaemonConfiguration) *protocol.Envelope {
					return &protocol.Envelope{MessageType: protocol.MessageType_ENVIRONMENT_CONFIG, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(version), Config: cfg}}}
				}
				conn.in <- environmentFrame(initial.ConfigVersion, configurationOf(initial))
				var observer ControlObserver
				var attempts int
				dials := make(chan time.Time)
				factory := reconnectFactoryFunc(func(ctx context.Context, attempt ControlObserver) (Connection, error) {
					attempts++
					switch attempts {
					case 1:
						return nil, errors.New("initial dial failure")
					case 2:
						observer = attempt
						return conn, nil
					case 3:
						if tc.name == "GOAWAY then dial failure" {
							return nil, errors.New("dial failure after GOAWAY")
						}
					}
					select {
					case dials <- time.Now():
					case <-ctx.Done():
					}
					return nil, ErrRegistrationRejected
				})
				r, err := NewRuntime(RuntimeOptions{EnvironmentID: "env", EnvironmentConfig: manager, Connections: factory, InitialReconnectDelay: initialDelay, MaxReconnectDelay: time.Minute})
				if err != nil {
					t.Fatal(err)
				}
				ctx, cancel := context.WithCancel(context.Background())
				defer cancel()
				done := make(chan error, 1)
				go func() { done <- r.Run(ctx) }()
				time.Sleep(initialDelay)
				synctest.Wait()
				if !conn.sent(protocol.MessageType_READY) {
					t.Fatal("second attempt did not send READY")
				}
				started := time.Now()
				switch tc.name {
				case "disconnect after READY":
					conn.fail(errors.New("disconnected"))
				case "GOAWAY then dial failure":
					observer.NotifyGoaway(0)
				default:
					next := configurationOf(initial)
					if tc.name == "Studio URL changed" {
						next.StudioUrl = "https://new-studio.example"
					} else {
						next.ProxyUrl = stringPtr("http://new-proxy.example:8080")
					}
					conn.in <- environmentFrame(initial.ConfigVersion+1, next)
				}
				select {
				case at := <-dials:
					if got := at.Sub(started); got != tc.want {
						t.Errorf("reconnect after %v, want %v", got, tc.want)
					}
				case <-time.After(time.Minute):
					t.Fatal("did not reconnect")
				}
				if err := <-done; !errors.Is(err, ErrRegistrationRejected) {
					t.Fatalf("Run = %v, want registration rejection from final attempt", err)
				}
			})
		})
	}
}

type reconnectFactoryFunc func(context.Context, ControlObserver) (Connection, error)

func (f reconnectFactoryFunc) Connect(ctx context.Context, observer ControlObserver) (Connection, error) {
	return f(ctx, observer)
}

type retryingConnectionFactory struct {
	calls      atomic.Int32
	connection Connection
}

func (f *retryingConnectionFactory) Connect(context.Context, ControlObserver) (Connection, error) {
	if f.calls.Add(1) == 1 {
		return nil, errors.New("temporary connect failure")
	}
	return f.connection, nil
}
