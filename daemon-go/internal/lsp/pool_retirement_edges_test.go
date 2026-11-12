package lsp

import (
	"context"
	"errors"
	"testing"
	"time"
)

// LSP-T-062, LSP-T-063, and LSP-T-065 verify failed idle retirement isolates the key and is reported by close.
func TestPool_RetirementFailureIsIsolated(t *testing.T) {
	cleanupErr := errors.New("retirement termination failed")
	pool, supervisor, file := testPool(t, PoolOptions{IdleTimeout: 10 * time.Millisecond, ShutdownGrace: 10 * time.Millisecond})
	supervisor.terminateErr = cleanupErr
	lease, err := pool.Lease(context.Background(), file)
	if err != nil {
		t.Fatal(err)
	}
	original := lease.Client
	lease.Release()
	key := poolKey(original.root, original.server)
	deadline := time.After(2 * time.Second)
	for {
		pool.mu.Lock()
		retired := pool.retiring[key]
		ready := retired != nil
		if ready {
			select {
			case <-retired.done:
				ready = true
			default:
				ready = false
			}
		}
		pool.mu.Unlock()
		if ready {
			break
		}
		select {
		case <-deadline:
			t.Fatal("idle retirement did not settle")
		case <-time.After(time.Millisecond):
		}
	}
	if pool.ActiveClientCount() != 0 || supervisor.process.terminates.Load() != 1 {
		t.Fatalf("active clients=%d terminations=%d", pool.ActiveClientCount(), supervisor.process.terminates.Load())
	}
	var confirmations int
	_, firstErr := pool.LeaseWithExit(context.Background(), file, func() { confirmations++ })
	_, secondErr := pool.LeaseWithExit(context.Background(), file, func() { confirmations++ })
	if firstErr == nil || firstErr != secondErr || !errors.Is(firstErr, cleanupErr) || confirmations != 2 || supervisor.starts != 1 {
		t.Fatalf("retirement errors first=%v second=%v confirmations=%d starts=%d", firstErr, secondErr, confirmations, supervisor.starts)
	}
	closeErr := pool.Close()
	var cleanup *LspCleanupError
	if !errors.As(closeErr, &cleanup) || !errors.Is(closeErr, cleanupErr) || closeErr.Error() != "LSP client pool failed to release all resources (1 cleanup failure(s))" {
		t.Fatalf("close error = %#v", closeErr)
	}
	if supervisor.process.terminates.Load() != 1 {
		t.Fatalf("failed retirement was retried %d times", supervisor.process.terminates.Load())
	}
}

// LSP-T-060 starts a new client after a previously leased client dies.
func TestPool_RestartDeadClient(t *testing.T) {
	pool, supervisor, file := testPool(t, PoolOptions{IdleTimeout: time.Hour, ShutdownGrace: 5 * time.Millisecond})
	first, err := pool.Lease(context.Background(), file)
	if err != nil {
		t.Fatal(err)
	}
	oldClient := first.Client
	if err := oldClient.Stop(5 * time.Millisecond); err != nil {
		t.Fatal(err)
	}
	if oldClient.IsAlive() {
		t.Fatal("stopped client still reports alive")
	}
	second, err := pool.Lease(context.Background(), file)
	if err != nil {
		t.Fatal(err)
	}
	if second.Client == oldClient || supervisor.starts != 2 {
		t.Fatalf("reused dead client=%v starts=%d", second.Client == oldClient, supervisor.starts)
	}
	second.Release()
	if err := pool.Close(); err != nil {
		t.Fatal(err)
	}
}

// LSP-T-064 cancellation and timeout reject waiters independently while retirement is held.
func TestPool_RetirementWaiterAbort(t *testing.T) {
	for _, tc := range []struct {
		name      string
		timeout   bool
		wantError string
	}{
		{name: "cancel", wantError: "Operation cancelled"},
		{name: "timeout", timeout: true, wantError: "Operation timed out"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			gate := make(chan struct{})
			pool, supervisor, file := testPool(t, PoolOptions{IdleTimeout: 10 * time.Millisecond, ShutdownGrace: 5 * time.Millisecond})
			supervisor.terminateGate = gate
			lease, err := pool.Lease(context.Background(), file)
			if err != nil {
				t.Fatal(err)
			}
			lease.Release()
			key := poolKey(lease.Client.root, lease.Client.server)
			deadline := time.After(2 * time.Second)
			for {
				pool.mu.Lock()
				retiring := pool.retiring[key] != nil
				pool.mu.Unlock()
				if retiring {
					break
				}
				select {
				case <-deadline:
					t.Fatal("retirement did not begin")
				case <-time.After(time.Millisecond):
				}
			}
			confirmed := 0
			ctx, cancel := context.WithCancel(context.Background())
			if tc.timeout {
				ctx, cancel = context.WithTimeout(context.Background(), time.Millisecond)
			}
			type leaseResult struct {
				err     error
				elapsed time.Duration
			}
			result := make(chan leaseResult, 1)
			go func() {
				start := time.Now()
				_, leaseErr := pool.LeaseWithExit(ctx, file, func() { confirmed++ })
				result <- leaseResult{err: leaseErr, elapsed: time.Since(start)}
			}()
			if tc.timeout {
				select {
				case <-ctx.Done():
				case <-time.After(time.Second):
					t.Fatal("waiter deadline did not expire")
				}
			} else {
				cancel()
			}
			outcome := <-result
			cancel()
			if outcome.err == nil || outcome.err.Error() != tc.wantError || outcome.elapsed > 500*time.Millisecond || confirmed != 1 || supervisor.starts != 1 {
				t.Fatalf("lease error=%v elapsed=%s confirms=%d starts=%d", outcome.err, outcome.elapsed, confirmed, supervisor.starts)
			}
			close(gate)
			if err := pool.Close(); err != nil {
				t.Fatalf("close after retirement settled: %v", err)
			}
		})
	}
}
