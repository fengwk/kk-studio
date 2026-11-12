package lsp

import (
	"context"
	"errors"
	"testing"
	"time"
)

// LSP-T-057 delivers one initialization failure to every concurrent waiter without converting it to cancellation.
func TestPool_InitializeFailureWaiters(t *testing.T) {
	pool, supervisor, file := testPool(t, PoolOptions{InitializeTimeout: 30 * time.Millisecond, ShutdownGrace: 5 * time.Millisecond})
	supervisor.blockMethod = "initialize"
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
		t.Fatal("startup did not enter supervisor")
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
			t.Fatal("concurrent waiters did not join shared initialization")
		case <-time.After(time.Millisecond):
		}
	}
	close(supervisor.startGate)
	first, second := <-results, <-results
	var bootstrap *LspBootstrapError
	if first == nil || first != second || !errors.As(first, &bootstrap) || !bootstrap.Converged || errors.Is(first, context.Canceled) || supervisor.starts != 1 || supervisor.process.terminates.Load() != 1 || pool.ActiveClientCount() != 0 {
		t.Fatalf("initialize failure=%v,%v starts=%d terminations=%d active=%d", first, second, supervisor.starts, supervisor.process.terminates.Load(), pool.ActiveClientCount())
	}
	if err := pool.Close(); err != nil {
		t.Fatal(err)
	}
}
