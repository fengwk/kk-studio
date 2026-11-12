package lsp

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

// LSP-T-049 confirms cancelled and timed-out waiters only after the shared boot cleanup succeeds.
func TestPool_AbortWaitersConverge(t *testing.T) {
	pool, supervisor, file := testPool(t, PoolOptions{IdleTimeout: time.Hour, ShutdownGrace: 5 * time.Millisecond})
	supervisor.startEntered = make(chan struct{}, 1)
	supervisor.startGate = make(chan struct{})
	cancelCtx, cancel := context.WithCancel(context.Background())
	timeoutCtx, timeoutCancel := context.WithTimeout(context.Background(), time.Second)
	defer timeoutCancel()
	var confirmed atomic.Int32
	results := make(chan error, 2)
	go func() {
		_, err := pool.LeaseWithExit(cancelCtx, file, func() { confirmed.Add(1) })
		results <- err
	}()
	select {
	case <-supervisor.startEntered:
	case <-time.After(time.Second):
		t.Fatal("startup did not enter supervisor")
	}
	go func() {
		_, err := pool.LeaseWithExit(timeoutCtx, file, func() { confirmed.Add(1) })
		results <- err
	}()
	server, _ := pool.discovery.RequireServer(file)
	root, _ := pool.discovery.WorkspaceRoot(server, file)
	key := poolKey(root, server)
	waitDeadline := time.After(time.Second)
	for {
		pool.mu.Lock()
		boot := pool.boots[key]
		joined := boot != nil && boot.waiters == 2
		pool.mu.Unlock()
		if joined {
			break
		}
		select {
		case <-waitDeadline:
			t.Fatal("both waiters did not join the shared boot")
		case <-time.After(time.Millisecond):
		}
	}
	cancel()
	if err := <-results; err == nil || err.Error() != "Operation cancelled" {
		t.Fatalf("cancelled waiter error=%v", err)
	}
	select {
	case err := <-results:
		var timeout *LspTimeoutError
		if !errors.As(err, &timeout) || err.Error() != "Operation timed out" {
			t.Fatalf("timed out waiter error=%v", err)
		}
	case <-timeoutCtx.Done():
		err := <-results
		var timeout *LspTimeoutError
		if !errors.As(err, &timeout) || err.Error() != "Operation timed out" {
			t.Fatalf("timed out waiter error=%v", err)
		}
	}
	if confirmed.Load() != 0 {
		t.Fatalf("waiters confirmed before cleanup: %d", confirmed.Load())
	}
	close(supervisor.startGate)
	deadline := time.After(2 * time.Second)
	for confirmed.Load() != 2 {
		select {
		case <-deadline:
			t.Fatalf("confirmations=%d, want 2", confirmed.Load())
		case <-time.After(time.Millisecond):
		}
	}
	if supervisor.starts != 1 || supervisor.process.terminates.Load() != 1 {
		t.Fatalf("starts=%d terminations=%d", supervisor.starts, supervisor.process.terminates.Load())
	}
	if err := pool.Close(); err != nil {
		t.Fatal(err)
	}
}
