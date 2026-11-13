package runtime

import (
	"context"
	"sync/atomic"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

type leaseTrackingIDE struct {
	active  atomic.Bool
	applied chan time.Time
}

func (*leaseTrackingIDE) HandleCommand(context.Context, *protocol.IdeCommand) *protocol.IdeResult {
	return &protocol.IdeResult{}
}

func (ide *leaseTrackingIDE) ApplyLease(_ *protocol.IdeLease, sentAt time.Time) {
	ide.applied <- sentAt
}

func (ide *leaseTrackingIDE) HasActiveViews() bool { return ide.active.Load() }

// RUN-T-001 heartbeat publication requires a bound environment but not a completed READY handshake.
func TestRuntimeHeartbeatsAfterEnvironmentBinding(t *testing.T) {
	c := newTestConnection()
	r, err := NewRuntime(RuntimeOptions{EnvironmentID: "env", Connections: fixedConnectionFactory{connection: c}, HeartbeatInterval: 5 * time.Millisecond, ApplyConfig: func(_ context.Context, v int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
		return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: v}, nil
	}})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	time.Sleep(20 * time.Millisecond)
	if c.sent(protocol.MessageType_HEARTBEAT) {
		cancel()
		t.Fatal("heartbeat sent before SETTINGS bound the environment")
	}
	c.in <- &protocol.Envelope{MessageType: protocol.MessageType_SETTINGS, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{}}}}
	c.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_HEARTBEAT {
				if frame.GetHeartbeat().IdeLeaseRound != nil {
					t.Fatal("heartbeat without active IDE views advertised an IDE lease round")
				}
				return true
			}
		}
		return false
	})
	c.in <- &protocol.Envelope{MessageType: protocol.MessageType_ENVIRONMENT_CONFIG, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(1), Config: &protocol.DaemonConfiguration{StudioUrl: "https://studio.example", Lsp: &protocol.LspConfiguration{}}}}}
	c.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_READY {
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

// RUN-T-045/046 covers READY-only lease rounds, active-view gating, expiry, single-use, and heartbeat start time.
func TestRuntimePublishesAndConsumesFreshIDELeaseRounds(t *testing.T) {
	ide := &leaseTrackingIDE{applied: make(chan time.Time, 2)}
	options := runtimeOptionsForTest()
	options.IDE = ide
	options.HeartbeatInterval = 5 * time.Millisecond
	r, connection := newReadyRuntimeForTest(t, options)
	r.mu.Lock()
	r.ready = false
	r.mu.Unlock()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go r.heartbeatLoop(ctx, done)
	defer func() {
		cancel()
		<-done
	}()

	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_HEARTBEAT && frame.GetHeartbeat().IdeLeaseRound == nil {
				return true
			}
		}
		return false
	})
	r.mu.Lock()
	r.ready = true
	r.mu.Unlock()
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		count := 0
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_HEARTBEAT && frame.GetHeartbeat().IdeLeaseRound == nil {
				count++
			}
		}
		return count >= 2
	})
	ide.active.Store(true)
	var firstRound int64
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_HEARTBEAT && frame.GetHeartbeat().GetIdeLeaseRound() > 0 {
				firstRound = frame.GetHeartbeat().GetIdeLeaseRound()
				return true
			}
		}
		return false
	})
	waitForActiveIdeLeaseRound(t, r, firstRound)
	if err := r.handle(context.Background(), ideLeaseEnvelope(firstRound)); err != nil {
		t.Fatalf("fresh lease round rejected: %v", err)
	}
	select {
	case started := <-ide.applied:
		if started.IsZero() || time.Since(started) >= 15*time.Second {
			t.Fatalf("IDE lease used an invalid heartbeat start time: %v", started)
		}
	case <-time.After(time.Second):
		t.Fatal("fresh IDE lease was not applied")
	}

	var secondRound int64
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_HEARTBEAT && frame.GetHeartbeat().GetIdeLeaseRound() > firstRound {
				secondRound = frame.GetHeartbeat().GetIdeLeaseRound()
				return true
			}
		}
		return false
	})
	waitForActiveIdeLeaseRound(t, r, secondRound)
	if err := r.handle(context.Background(), ideLeaseEnvelope(firstRound)); err == nil || err.Error() != "IDE_LEASE round is not the current unexpired round" {
		t.Fatalf("stale IDE lease round error = %v", err)
	}
	if err := r.handle(context.Background(), ideLeaseEnvelope(secondRound)); err != nil {
		t.Fatalf("current IDE lease round rejected: %v", err)
	}
	if err := r.handle(context.Background(), ideLeaseEnvelope(secondRound)); err == nil || err.Error() != "IDE_LEASE round is not the current unexpired round" {
		t.Fatalf("replayed IDE lease round error = %v", err)
	}
	select {
	case <-ide.applied:
	case <-time.After(time.Second):
		t.Fatal("current IDE lease round was not applied")
	}
}

func ideLeaseEnvelope(round int64) *protocol.Envelope {
	return &protocol.Envelope{
		MessageType:   protocol.MessageType_IDE_LEASE,
		EnvironmentId: stringPtr("env"),
		Payload:       &protocol.Envelope_IdeLease{IdeLease: &protocol.IdeLease{Round: int64Ptr(round)}},
	}
}

func waitForActiveIdeLeaseRound(t *testing.T, runtime *Runtime, round int64) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		runtime.ideLeaseMu.Lock()
		ready := runtime.ideLeaseRound == round && !runtime.ideLeaseSentAt.IsZero()
		runtime.ideLeaseMu.Unlock()
		if ready {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("IDE lease round %d was not activated after its heartbeat send", round)
}
