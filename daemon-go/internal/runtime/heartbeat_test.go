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
// Every heartbeat with active views starts a new round, so the loop is paused before a lease is handled:
// the round of the last heartbeat actually sent is then the runtime's current round.
func TestRuntimePublishesAndConsumesFreshIDELeaseRounds(t *testing.T) {
	ide := &leaseTrackingIDE{applied: make(chan time.Time, 2)}
	options := runtimeOptionsForTest()
	options.IDE = ide
	options.HeartbeatInterval = 5 * time.Millisecond
	r, connection := newReadyRuntimeForTest(t, options)
	r.mu.Lock()
	r.ready = false
	r.mu.Unlock()
	ide.active.Store(true)

	// Not READY: active views do not start a lease round.
	if last := heartbeatsUntil(t, r, connection, 1); last.IdeLeaseRound != nil {
		t.Fatal("heartbeat before READY advertised an IDE lease round")
	}
	r.mu.Lock()
	r.ready = true
	r.mu.Unlock()
	// READY without active views: no lease round either.
	ide.active.Store(false)
	if last := heartbeatsUntil(t, r, connection, 2); last.IdeLeaseRound != nil {
		t.Fatal("heartbeat without active IDE views advertised an IDE lease round")
	}
	ide.active.Store(true)
	firstRound := heartbeatsUntil(t, r, connection, 1).GetIdeLeaseRound()
	if firstRound <= 0 {
		t.Fatalf("READY heartbeat with active IDE views advertised round %d", firstRound)
	}
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

	secondRound := heartbeatsUntil(t, r, connection, 1).GetIdeLeaseRound()
	if secondRound <= firstRound {
		t.Fatalf("next heartbeat round = %d, want greater than %d", secondRound, firstRound)
	}
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

// heartbeatsUntil runs the heartbeat loop until count more heartbeats are sent, stops it, and returns the
// last heartbeat sent. Once the loop has returned, no later heartbeat can start another round.
func heartbeatsUntil(t *testing.T, r *Runtime, connection *testConnection, count int) *protocol.Heartbeat {
	t.Helper()
	before := len(heartbeats(connection.frames()))
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go r.heartbeatLoop(ctx, done)
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		return len(heartbeats(frames)) >= before+count
	})
	cancel()
	<-done
	sent := heartbeats(connection.frames())
	return sent[len(sent)-1]
}

func heartbeats(frames []*protocol.Envelope) []*protocol.Heartbeat {
	var result []*protocol.Heartbeat
	for _, frame := range frames {
		if frame.MessageType == protocol.MessageType_HEARTBEAT {
			result = append(result, frame.GetHeartbeat())
		}
	}
	return result
}
