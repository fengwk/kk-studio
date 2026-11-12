package transport

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	runtimeapi "github.com/fengwk/kk-studio/daemon/internal/runtime"
)

type lifecycleTestConnection struct {
	closed   chan struct{}
	once     sync.Once
	sendErr  error
	closeErr error
}

func newLifecycleTestConnection() *lifecycleTestConnection {
	return &lifecycleTestConnection{closed: make(chan struct{})}
}
func (c *lifecycleTestConnection) Receive(ctx context.Context) (*protocol.Envelope, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-c.closed:
		return nil, errors.New("closed")
	}
}
func (c *lifecycleTestConnection) Send(context.Context, *protocol.Envelope) error { return c.sendErr }
func (c *lifecycleTestConnection) Close() error {
	c.once.Do(func() { close(c.closed) })
	return c.closeErr
}

type lifecycleDialResult struct {
	conn   *lifecycleTestConnection
	goaway func(uint32)
	err    error
}

// TRN-F-007, TRN-T-073, TRN-T-085, TRN-T-160
func TestLifecycle_BackoffReadyGenerationAndGoaway(t *testing.T) {
	results := make(chan lifecycleDialResult, 4)
	var calls atomic.Int32
	var manager *ConnectionLifecycle
	dialer := func(_ context.Context, onGoaway func(uint32)) (runtimeapi.Connection, error) {
		n := calls.Add(1)
		if n == 1 {
			results <- lifecycleDialResult{goaway: onGoaway, err: errors.New("dial failed")}
			return nil, errors.New("dial failed")
		}
		conn := newLifecycleTestConnection()
		results <- lifecycleDialResult{conn: conn, goaway: onGoaway}
		return conn, nil
	}
	disconnected := make(chan runtimeapi.Connection, 4)
	var err error
	manager, err = NewConnectionLifecycle(dialer, 5*time.Millisecond, 20*time.Millisecond, LifecycleCallbacks{OnDisconnected: func(_ uint64, c runtimeapi.Connection) { disconnected <- c }})
	if err != nil {
		t.Fatal(err)
	}
	if !manager.Start(context.Background()) || manager.Start(context.Background()) {
		t.Fatal("Start was not idempotent")
	}
	first := <-results
	if first.err == nil {
		t.Fatal("first connection unexpectedly succeeded")
	}
	if got := <-disconnected; got != nil {
		t.Fatalf("failed dial disconnected %T", got)
	}
	second := <-results
	if second.conn == nil {
		t.Fatal("second connection did not connect")
	}
	gen := manager.Generation()
	waitForLifecycleActive(t, manager, second.conn, gen)
	if gen != 2 || !manager.MarkReady(gen) || manager.MarkReady(gen-1) || manager.State() != StateReady {
		t.Fatalf("generation=%d state=%s", gen, manager.State())
	}
	if !manager.Send(context.Background(), &protocol.Envelope{}) {
		t.Fatal("active connection send failed")
	}
	second.goaway(1)
	if calls.Load() != 2 {
		t.Fatal("non-NO_ERROR GOAWAY reconnected")
	}
	second.goaway(0)
	if got := <-disconnected; got != second.conn {
		t.Fatalf("disconnect callback=%T want active connection", got)
	}
	third := <-results
	if third.conn == nil || manager.Generation() != 3 {
		t.Fatalf("third connection=%v generation=%d", third.conn, manager.Generation())
	}
	waitForLifecycleActive(t, manager, third.conn, 3)
	if !manager.MarkReady(3) {
		t.Fatal("current generation did not become ready")
	}
	manager.Close(StateStopped, "")
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := manager.Wait(ctx); err != nil {
		t.Fatal(err)
	}
	if manager.State() != StateStopped {
		t.Fatalf("state after close=%s", manager.State())
	}
}

// TRN-F-007
func TestLifecycle_StaleGoawayAndSendFailureAreIgnoredAfterClose(t *testing.T) {
	conn := newLifecycleTestConnection()
	callbacks := make(chan runtimeapi.Connection, 2)
	var onGoaway func(uint32)
	manager, err := NewConnectionLifecycle(func(_ context.Context, goaway func(uint32)) (runtimeapi.Connection, error) {
		onGoaway = goaway
		return conn, nil
	}, time.Millisecond, time.Millisecond, LifecycleCallbacks{OnDisconnected: func(_ uint64, c runtimeapi.Connection) { callbacks <- c }})
	if err != nil {
		t.Fatal(err)
	}
	manager.Start(context.Background())
	waitForLifecycleActive(t, manager, conn, 1)
	if !manager.MarkReady(1) {
		t.Fatal("connection not ready")
	}
	manager.Close(StateFailed, "fatal")
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := manager.Wait(ctx); err != nil {
		t.Fatal(err)
	}
	if manager.MarkReady(1) {
		t.Fatal("closed generation became ready")
	}
	if manager.State() != StateFailed || manager.FailureReason() != "fatal" {
		t.Fatalf("failed state=%s reason=%q", manager.State(), manager.FailureReason())
	}
	if onGoaway != nil {
		onGoaway(0)
	}
}

// TRN-T-161
func TestLifecycle_GoawayCloseErrorStillNotifiesAndReconnects(t *testing.T) {
	results := make(chan lifecycleDialResult, 2)
	first := newLifecycleTestConnection()
	first.closeErr = errors.New("close failed")
	var calls atomic.Int32
	disconnected := make(chan runtimeapi.Connection, 1)
	manager, err := NewConnectionLifecycle(func(_ context.Context, onGoaway func(uint32)) (runtimeapi.Connection, error) {
		if calls.Add(1) == 1 {
			results <- lifecycleDialResult{conn: first, goaway: onGoaway}
			return first, nil
		}
		conn := newLifecycleTestConnection()
		results <- lifecycleDialResult{conn: conn}
		return conn, nil
	}, 10*time.Millisecond, 20*time.Millisecond, LifecycleCallbacks{OnDisconnected: func(_ uint64, conn runtimeapi.Connection) {
		if conn != nil {
			disconnected <- conn
		}
	}})
	if err != nil {
		t.Fatal(err)
	}
	manager.Start(context.Background())
	connected := <-results
	waitForLifecycleActive(t, manager, connected.conn, 1)
	connected.goaway(0)
	select {
	case got := <-disconnected:
		if got != first {
			t.Fatalf("disconnect callback got %T, want active connection", got)
		}
	case <-time.After(time.Second):
		t.Fatal("disconnect callback was not called after Close error")
	}
	select {
	case reconnected := <-results:
		if reconnected.conn == nil || manager.Generation() != 2 {
			t.Fatalf("reconnected=%v generation=%d", reconnected.conn, manager.Generation())
		}
	case <-time.After(time.Second):
		t.Fatal("lifecycle did not reconnect after Close error")
	}
	manager.Close(StateStopped, "")
}

// TRN-T-162
func TestLifecycle_GoawayReconnectFailureKeepsBackoff(t *testing.T) {
	const initialDelay = 150 * time.Millisecond
	type attempt struct {
		n  int32
		at time.Time
	}
	attempts := make(chan attempt, 3)
	first := newLifecycleTestConnection()
	var calls atomic.Int32
	var goaway func(uint32)
	manager, err := NewConnectionLifecycle(func(_ context.Context, onGoaway func(uint32)) (runtimeapi.Connection, error) {
		n := calls.Add(1)
		attempts <- attempt{n: n, at: time.Now()}
		if n == 1 {
			goaway = onGoaway
			return first, nil
		}
		if n == 2 {
			return nil, errors.New("immediate reconnect failed")
		}
		return newLifecycleTestConnection(), nil
	}, initialDelay, 4*initialDelay, LifecycleCallbacks{})
	if err != nil {
		t.Fatal(err)
	}
	manager.Start(context.Background())
	firstAttempt := <-attempts
	if firstAttempt.n != 1 {
		t.Fatalf("first dial attempt=%d", firstAttempt.n)
	}
	waitForLifecycleActive(t, manager, first, 1)
	goaway(0)
	secondAttempt := <-attempts
	if secondAttempt.n != 2 {
		t.Fatalf("immediate dial attempt=%d", secondAttempt.n)
	}
	thirdAttempt := <-attempts
	if thirdAttempt.n != 3 {
		t.Fatalf("retry dial attempt=%d", thirdAttempt.n)
	}
	if elapsed := thirdAttempt.at.Sub(secondAttempt.at); elapsed < initialDelay+initialDelay/2 {
		t.Fatalf("retry delay=%s, want at least %s after immediate dial failed", elapsed, initialDelay+initialDelay/2)
	}
	manager.Close(StateStopped, "")
}

func waitForLifecycleActive(t *testing.T, lifecycle *ConnectionLifecycle, connection runtimeapi.Connection, generation uint64) {
	t.Helper()
	waitUntil(t, func() bool {
		lifecycle.mu.Lock()
		defer lifecycle.mu.Unlock()
		return lifecycle.active == connection && lifecycle.activeGeneration == generation
	})
}

func waitUntil(t *testing.T, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("condition not reached")
}
