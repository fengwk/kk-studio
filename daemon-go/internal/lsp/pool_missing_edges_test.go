package lsp

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

// LSP-T-046 coalesces concurrent failed boots and permits retry after the failure converges.
func TestPool_SharedFailedBoot(t *testing.T) {
	pool, supervisor, file := testPool(t, PoolOptions{IdleTimeout: time.Hour})
	spawnErr := errors.New("spawn rejected")
	supervisor.startErr = spawnErr
	supervisor.startEntered = make(chan struct{}, 1)
	supervisor.startGate = make(chan struct{})
	results := make(chan error, 2)
	for range 2 {
		go func() {
			_, err := pool.Lease(context.Background(), file)
			results <- err
		}()
	}
	select {
	case <-supervisor.startEntered:
	case <-time.After(time.Second):
		t.Fatal("failed start did not enter supervisor")
	}
	server, _ := pool.discovery.RequireServer(file)
	root, _ := pool.discovery.WorkspaceRoot(server, file)
	key := poolKey(root, server)
	deadline := time.After(time.Second)
	for {
		pool.mu.Lock()
		boot := pool.boots[key]
		joined := boot != nil && boot.waiters == 2
		pool.mu.Unlock()
		if joined {
			break
		}
		select {
		case <-deadline:
			t.Fatal("both failed leases did not join one boot")
		case <-time.After(time.Millisecond):
		}
	}
	close(supervisor.startGate)
	first, second := <-results, <-results
	var bootstrap *LspBootstrapError
	if first == nil || first != second || !errors.As(first, &bootstrap) || !bootstrap.Converged || !errors.Is(first, spawnErr) || supervisor.starts != 1 {
		t.Fatalf("failed boot errors=%v,%v starts=%d", first, second, supervisor.starts)
	}
	supervisor.startErr = nil
	lease, err := pool.Lease(context.Background(), file)
	if err != nil {
		t.Fatal(err)
	}
	if supervisor.starts != 2 {
		t.Fatalf("retry start count=%d", supervisor.starts)
	}
	lease.Release()
	if err := pool.Close(); err != nil {
		t.Fatal(err)
	}
}

// LSP-T-067 keeps the key closed when last-waiter cancellation cleanup fails.
func TestPool_LastWaiterCleanupFailure(t *testing.T) {
	pool, supervisor, file := testPool(t, PoolOptions{IdleTimeout: time.Hour, ShutdownGrace: 5 * time.Millisecond})
	supervisor.startEntered = make(chan struct{}, 1)
	supervisor.startGate = make(chan struct{})
	cleanupErr := errors.New("last waiter termination failed")
	supervisor.terminateErr = cleanupErr
	ctx, cancel := context.WithCancel(context.Background())
	confirmed := 0
	owner := make(chan error, 1)
	go func() {
		_, err := pool.LeaseWithExit(ctx, file, func() { confirmed++ })
		owner <- err
	}()
	select {
	case <-supervisor.startEntered:
	case <-time.After(time.Second):
		t.Fatal("startup did not reach supervisor")
	}
	server, _ := pool.discovery.RequireServer(file)
	root, _ := pool.discovery.WorkspaceRoot(server, file)
	key := poolKey(root, server)
	cancel()
	if err := <-owner; err == nil || err.Error() != "Operation cancelled" {
		t.Fatalf("owner cancellation error=%v", err)
	}
	close(supervisor.startGate)
	deadline := time.After(time.Second)
	var boot *boot
	for {
		pool.mu.Lock()
		boot = pool.boots[key]
		settled := boot != nil
		if settled {
			select {
			case <-boot.done:
			default:
				settled = false
			}
		}
		pool.mu.Unlock()
		if settled {
			break
		}
		select {
		case <-deadline:
			t.Fatal("cancelled boot cleanup did not settle")
		case <-time.After(time.Millisecond):
		}
	}
	if confirmed != 0 || boot.err == nil || !errors.Is(boot.err, cleanupErr) || supervisor.starts != 1 {
		t.Fatalf("boot error=%v confirmations=%d starts=%d", boot.err, confirmed, supervisor.starts)
	}
	_, firstErr := pool.LeaseWithExit(context.Background(), file, func() { confirmed++ })
	_, secondErr := pool.LeaseWithExit(context.Background(), file, func() { confirmed++ })
	var bootstrap *LspBootstrapError
	if firstErr == nil || firstErr != secondErr || !errors.As(firstErr, &bootstrap) || bootstrap.Converged || !errors.Is(firstErr, cleanupErr) || supervisor.starts != 1 || confirmed != 2 {
		t.Fatalf("isolated failure errors=%v,%v starts=%d confirmations=%d", firstErr, secondErr, supervisor.starts, confirmed)
	}
	if err := pool.Close(); err == nil || !errors.Is(err, cleanupErr) {
		t.Fatalf("close error=%v", err)
	}
}

// LSP-T-068 waits for cancelled boot cleanup before starting a non-overlapping replacement.
func TestPool_NonOverlappingRestart(t *testing.T) {
	pool, supervisor, file := testPool(t, PoolOptions{IdleTimeout: time.Hour, ShutdownGrace: 5 * time.Millisecond})
	supervisor.startEntered = make(chan struct{}, 1)
	supervisor.startGate = make(chan struct{})
	cleanupGate := make(chan struct{})
	supervisor.terminateGate = cleanupGate
	ctx, cancel := context.WithCancel(context.Background())
	ownerDone := make(chan error, 1)
	var confirmed int32
	go func() {
		_, err := pool.LeaseWithExit(ctx, file, func() { atomic.AddInt32(&confirmed, 1) })
		ownerDone <- err
	}()
	select {
	case <-supervisor.startEntered:
	case <-time.After(time.Second):
		t.Fatal("startup did not reach supervisor")
	}
	server, _ := pool.discovery.RequireServer(file)
	root, _ := pool.discovery.WorkspaceRoot(server, file)
	key := poolKey(root, server)
	cancel()
	if err := <-ownerDone; err == nil || err.Error() != "Operation cancelled" {
		t.Fatalf("cancelled boot owner error=%v", err)
	}
	close(supervisor.startGate)
	deadline := time.After(time.Second)
	var oldProcess *fakeProcess
	for {
		oldProcess = supervisor.process
		if oldProcess != nil && oldProcess.terminates.Load() > 0 {
			break
		}
		select {
		case <-deadline:
			t.Fatal("cancelled boot cleanup did not begin")
		case <-time.After(time.Millisecond):
		}
	}
	waitCtx, stopWait := context.WithTimeout(context.Background(), 20*time.Millisecond)
	_, waitErr := pool.Lease(waitCtx, file)
	stopWait()
	var timeout *LspTimeoutError
	if !errors.As(waitErr, &timeout) || supervisor.starts != 1 {
		t.Fatalf("lease during cleanup error=%v starts=%d", waitErr, supervisor.starts)
	}
	close(cleanupGate)
	settledDeadline := time.After(time.Second)
	for {
		pool.mu.Lock()
		oldBoot := pool.boots[key]
		settled := oldBoot == nil
		pool.mu.Unlock()
		if settled {
			break
		}
		select {
		case <-settledDeadline:
			t.Fatal("old boot did not converge")
		case <-time.After(time.Millisecond):
		}
	}
	if atomic.LoadInt32(&confirmed) != 1 {
		t.Fatalf("cancelled owner confirmations=%d", confirmed)
	}
	second, err := pool.Lease(context.Background(), file)
	if err != nil {
		t.Fatal(err)
	}
	if second.Client == nil || second.Client.process == oldProcess || supervisor.starts != 2 {
		t.Fatalf("replacement overlapped old process: starts=%d", supervisor.starts)
	}
	second.Release()
	if err := pool.Close(); err != nil {
		t.Fatal(err)
	}
}
