package transport

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	runtimeapi "github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// TRN-F-007
func TestLifecycle_ReconnectAndParentCancellation(t *testing.T) {
	for _, tc := range []struct {
		dial         ConnectionDialer
		initial, max time.Duration
	}{{nil, 0, 0}, {func(context.Context, func(uint32)) (runtimeapi.Connection, error) { return nil, nil }, -time.Millisecond, 0}, {func(context.Context, func(uint32)) (runtimeapi.Connection, error) { return nil, nil }, time.Second, time.Millisecond}} {
		if _, err := NewConnectionLifecycle(tc.dial, tc.initial, tc.max, LifecycleCallbacks{}); err == nil {
			t.Fatal("invalid lifecycle options accepted")
		}
	}
	if !waitLifecycle(context.Background(), 0) {
		t.Fatal("zero-delay wait failed")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if waitLifecycle(ctx, 0) || waitLifecycle(ctx, time.Millisecond) {
		t.Fatal("canceled lifecycle wait succeeded")
	}

	results := make(chan *lifecycleTestConnection, 4)
	var calls atomic.Int32
	disconnected := make(chan runtimeapi.Connection, 4)
	manager, err := NewConnectionLifecycle(func(context.Context, func(uint32)) (runtimeapi.Connection, error) {
		conn := newLifecycleTestConnection()
		calls.Add(1)
		results <- conn
		return conn, nil
	}, time.Millisecond, 2*time.Millisecond, LifecycleCallbacks{OnDisconnected: func(_ uint64, conn runtimeapi.Connection) { disconnected <- conn }})
	if err != nil {
		t.Fatal(err)
	}
	parentContext, parentCancel := context.WithCancel(context.Background())
	if !manager.Start(parentContext) {
		t.Fatal("lifecycle failed to start")
	}
	first := receiveLifecycleConnection(t, results)
	firstGeneration := manager.Generation()
	waitForLifecycleActive(t, manager, first, firstGeneration)
	if !manager.IsCurrent(firstGeneration) || manager.IsCurrent(firstGeneration+1) {
		t.Fatal("current generation identity is wrong")
	}
	first.sendErr = errors.New("send failed")
	if manager.Send(context.Background(), &protocol.Envelope{}) {
		t.Fatal("send failure reported success")
	}
	if got := <-disconnected; got != first {
		t.Fatalf("send-failure connection callback=%T", got)
	}
	second := receiveLifecycleConnection(t, results)
	secondGeneration := manager.Generation()
	waitForLifecycleActive(t, manager, second, secondGeneration)
	if secondGeneration <= firstGeneration || !manager.IsCurrent(secondGeneration) {
		t.Fatalf("generation did not advance: first=%d second=%d", firstGeneration, secondGeneration)
	}
	if !manager.Reconnect() {
		t.Fatal("active reconnect rejected")
	}
	if got := <-disconnected; got != second {
		t.Fatalf("manual reconnect callback=%T", got)
	}
	third := receiveLifecycleConnection(t, results)
	if third == nil {
		t.Fatal("third connection is nil")
	}
	thirdGeneration := manager.Generation()
	waitForLifecycleActive(t, manager, third, thirdGeneration)
	if !manager.MarkReady(thirdGeneration) {
		t.Fatal("third generation did not become ready")
	}
	parentCancel()
	waitCtx, waitCancel := context.WithTimeout(context.Background(), time.Second)
	defer waitCancel()
	if err := manager.Wait(waitCtx); err != nil {
		t.Fatal(err)
	}
	if manager.State() != StateStopped || manager.IsCurrent(thirdGeneration) {
		t.Fatalf("parent cancellation left state=%s current=%t", manager.State(), manager.IsCurrent(thirdGeneration))
	}
	if calls.Load() != 3 {
		t.Fatalf("dial calls=%d", calls.Load())
	}
}

// TRN-F-007
func TestLifecycle_CloseBeforeStartAndWaitCancellation(t *testing.T) {
	manager, err := NewConnectionLifecycle(func(context.Context, func(uint32)) (runtimeapi.Connection, error) { return nil, nil }, 0, 0, LifecycleCallbacks{})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := manager.Wait(ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("Wait error=%v", err)
	}
	manager.Close(StateFailed, "closed")
	if manager.Start(context.Background()) {
		t.Fatal("closed lifecycle started")
	}
	if err := manager.Wait(context.Background()); err != nil {
		t.Fatal(err)
	}
	manager.Close(StateStopped, "ignored")
	if manager.State() != StateFailed || manager.FailureReason() != "closed" {
		t.Fatalf("state=%s reason=%q", manager.State(), manager.FailureReason())
	}
}

func receiveLifecycleConnection(t *testing.T, results <-chan *lifecycleTestConnection) *lifecycleTestConnection {
	t.Helper()
	select {
	case conn := <-results:
		return conn
	case <-time.After(time.Second):
		t.Fatal("connection was not dialed")
		return nil
	}
}
