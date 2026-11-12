package lsp

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
)

func testPool(t *testing.T, options PoolOptions) (*Pool, *fakeSupervisor, string) {
	t.Helper()
	root := t.TempDir()
	executable := filepath.Join(root, "fake-lsp")
	if err := os.WriteFile(executable, []byte("fake"), 0700); err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(root, "source.go")
	if err := os.WriteFile(file, []byte("package source\n"), 0600); err != nil {
		t.Fatal(err)
	}
	discovery, err := NewDiscovery([]config.LSPServerConfig{{ID: "go", Command: []string{executable}, Extensions: []string{".go"}}}, ResolverOptions{})
	if err != nil {
		t.Fatal(err)
	}
	supervisor := &fakeSupervisor{}
	return NewPool(discovery, supervisor, options), supervisor, file
}

// LSP-T-040 coalesces simultaneous leases while a process launch is held open.
func TestPool_CoalescesBoot(t *testing.T) {
	pool, supervisor, file := testPool(t, PoolOptions{IdleTimeout: time.Hour})
	supervisor.startEntered = make(chan struct{}, 1)
	supervisor.startGate = make(chan struct{})
	const callers = 4
	type result struct {
		lease *Lease
		err   error
	}
	results := make(chan result, callers)
	for i := 0; i < callers; i++ {
		go func() {
			lease, err := pool.Lease(context.Background(), file)
			results <- result{lease: lease, err: err}
		}()
	}
	select {
	case <-supervisor.startEntered:
	case <-time.After(time.Second):
		t.Fatal("launch did not start")
	}
	server, _ := pool.discovery.RequireServer(file)
	root, _ := pool.discovery.WorkspaceRoot(server, file)
	key := poolKey(root, server)
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		pool.mu.Lock()
		boot := pool.boots[key]
		waiters := 0
		if boot != nil {
			waiters = boot.waiters
		}
		pool.mu.Unlock()
		if waiters == callers {
			break
		}
		time.Sleep(time.Millisecond)
	}
	pool.mu.Lock()
	boot := pool.boots[key]
	waiters := 0
	if boot != nil {
		waiters = boot.waiters
	}
	pool.mu.Unlock()
	if waiters != callers {
		close(supervisor.startGate)
		t.Fatal("all leases did not join the same boot")
	}
	close(supervisor.startGate)
	leases := make([]*Lease, 0, callers)
	for i := 0; i < callers; i++ {
		got := <-results
		if got.err != nil {
			t.Fatal(got.err)
		}
		leases = append(leases, got.lease)
	}
	for _, lease := range leases {
		if lease.Client != leases[0].Client {
			t.Fatal("concurrent leases received different clients")
		}
		lease.Release()
	}
	if supervisor.starts != 1 {
		t.Fatalf("started %d clients, want one", supervisor.starts)
	}
	if err := pool.Close(); err != nil {
		t.Fatal(err)
	}
}

// LSP-T-041 evicts only an idle client and terminates its managed process.
func TestPool_IdleEviction(t *testing.T) {
	pool, supervisor, file := testPool(t, PoolOptions{IdleTimeout: 10 * time.Millisecond})
	lease, err := pool.Lease(context.Background(), file)
	if err != nil {
		t.Fatal(err)
	}
	lease.Release()
	deadline := time.Now().Add(time.Second)
	for (pool.ActiveClientCount() != 0 || supervisor.process.terminates.Load() == 0) && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if pool.ActiveClientCount() != 0 || supervisor.process.terminates.Load() != 1 {
		t.Fatalf("active clients=%d terminations=%d", pool.ActiveClientCount(), supervisor.process.terminates.Load())
	}
	if err := pool.Close(); err != nil {
		t.Fatal(err)
	}
}

// LSP-T-044 and LSP-T-058 report cleanup errors with an aggregate cause.
func TestPool_CloseReportsCleanupFailure(t *testing.T) {
	pool, supervisor, file := testPool(t, PoolOptions{IdleTimeout: time.Hour})
	lease, err := pool.Lease(context.Background(), file)
	if err != nil {
		t.Fatal(err)
	}
	lease.Release()
	cleanupErr := errors.New("managed termination failed")
	supervisor.process.terminateErr = cleanupErr
	first := pool.Close()
	var cleanup *LspCleanupError
	var aggregate *LspAggregateError
	if !errors.As(first, &cleanup) || cleanup.Error() != "LSP client pool failed to release all resources (1 cleanup failure(s))" || !errors.As(first, &aggregate) || aggregate.Error() != "LSP client pool cleanup failures" || !errors.Is(first, cleanupErr) {
		t.Fatalf("close error = %#v", first)
	}
	if second := pool.Close(); second != first {
		t.Fatalf("repeated close changed result: first=%p second=%p", first, second)
	}
}

// LSP-T-053 preserves other waiters when one caller cancels during a shared boot.
func TestPool_IndependentWaiters(t *testing.T) {
	pool, supervisor, file := testPool(t, PoolOptions{IdleTimeout: time.Hour})
	supervisor.startEntered = make(chan struct{}, 1)
	supervisor.startGate = make(chan struct{})
	firstCtx, cancelFirst := context.WithCancel(context.Background())
	defer cancelFirst()
	var confirmed atomic.Int32
	firstResult := make(chan error, 1)
	secondResult := make(chan *Lease, 1)
	go func() { _, err := pool.LeaseWithExit(firstCtx, file, func() { confirmed.Add(1) }); firstResult <- err }()
	select {
	case <-supervisor.startEntered:
	case <-time.After(time.Second):
		t.Fatal("startup did not reach supervisor")
	}
	go func() { lease, _ := pool.Lease(context.Background(), file); secondResult <- lease }()
	server, _ := pool.discovery.RequireServer(file)
	root, _ := pool.discovery.WorkspaceRoot(server, file)
	key := poolKey(root, server)
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		pool.mu.Lock()
		waiters := pool.boots[key].waiters
		pool.mu.Unlock()
		if waiters == 2 {
			break
		}
		time.Sleep(time.Millisecond)
	}
	cancelFirst()
	select {
	case err := <-firstResult:
		if err == nil || err.Error() != "Operation cancelled" {
			t.Fatalf("cancelled waiter error = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("cancelled waiter waited for startup")
	}
	if confirmed.Load() != 0 {
		t.Fatal("cancelled waiter was confirmed before startup converged")
	}
	close(supervisor.startGate)
	var lease *Lease
	select {
	case lease = <-secondResult:
	case <-time.After(2 * time.Second):
		t.Fatal("remaining waiter did not receive its client")
	}
	if lease == nil {
		t.Fatal("remaining waiter received no lease")
	}
	lease.Release()
	deadline = time.Now().Add(time.Second)
	for confirmed.Load() != 1 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if confirmed.Load() != 1 || supervisor.starts != 1 {
		t.Fatalf("confirmations=%d starts=%d", confirmed.Load(), supervisor.starts)
	}
	if err := pool.Close(); err != nil {
		t.Fatal(err)
	}
}

// LSP-T-054 cancels a shared boot after its last waiter exits and confirms only after cleanup.
func TestPool_LastWaiterCancelsBoot(t *testing.T) {
	pool, supervisor, file := testPool(t, PoolOptions{InitializeTimeout: time.Second, ShutdownGrace: time.Second})
	supervisor.startEntered = make(chan struct{}, 1)
	supervisor.startGate = make(chan struct{})
	ctx, cancel := context.WithCancel(context.Background())
	var confirmed atomic.Int32
	result := make(chan error, 1)
	go func() { _, err := pool.LeaseWithExit(ctx, file, func() { confirmed.Add(1) }); result <- err }()
	select {
	case <-supervisor.startEntered:
	case <-time.After(time.Second):
		t.Fatal("startup did not reach supervisor")
	}
	cancel()
	select {
	case err := <-result:
		if err == nil || err.Error() != "Operation cancelled" {
			t.Fatalf("cancelled lease error = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("last waiter did not return promptly")
	}
	if confirmed.Load() != 0 {
		t.Fatal("last waiter confirmed before boot cleanup")
	}
	close(supervisor.startGate)
	deadline := time.Now().Add(2 * time.Second)
	for confirmed.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if confirmed.Load() != 1 || supervisor.process.terminates.Load() != 1 {
		t.Fatalf("confirmations=%d terminations=%d", confirmed.Load(), supervisor.process.terminates.Load())
	}
	if err := pool.Close(); err != nil {
		t.Fatal(err)
	}
}

// LSP-T-042, LSP-T-043, and LSP-T-045 close a pool while startup is blocked and await cleanup.
func TestPool_CloseDuringBoot(t *testing.T) {
	pool, supervisor, file := testPool(t, PoolOptions{InitializeTimeout: 20 * time.Millisecond, ShutdownGrace: time.Second})
	supervisor.startEntered = make(chan struct{}, 1)
	supervisor.startGate = make(chan struct{})
	confirmed := 0
	leaseResult := make(chan error, 1)
	go func() {
		_, err := pool.LeaseWithExit(context.Background(), file, func() { confirmed++ })
		leaseResult <- err
	}()
	select {
	case <-supervisor.startEntered:
	case <-time.After(time.Second):
		t.Fatal("startup did not reach supervisor")
	}
	closed := make(chan error, 1)
	go func() { closed <- pool.Close() }()
	select {
	case err := <-closed:
		t.Fatalf("close returned before held launch: %v", err)
	case <-time.After(10 * time.Millisecond):
	}
	close(supervisor.startGate)
	select {
	case err := <-leaseResult:
		if err == nil {
			t.Fatal("lease unexpectedly succeeded after close")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("lease did not settle after startup cleanup")
	}
	select {
	case err := <-closed:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("pool close did not wait for startup cleanup")
	}
	if confirmed != 1 || supervisor.process.terminates.Load() != 1 {
		t.Fatalf("confirmations=%d terminations=%d", confirmed, supervisor.process.terminates.Load())
	}
}

// LSP-T-052 and LSP-T-057 clean up an initialization failure and return it to the lease.
func TestPool_InitializeFailureCleanup(t *testing.T) {
	pool, supervisor, file := testPool(t, PoolOptions{InitializeTimeout: 20 * time.Millisecond, ShutdownGrace: time.Second})
	supervisor.blockMethod = "initialize"
	confirmed := 0
	_, err := pool.LeaseWithExit(context.Background(), file, func() { confirmed++ })
	var bootstrap *LspBootstrapError
	if !errors.As(err, &bootstrap) || !bootstrap.Converged || confirmed != 1 || supervisor.process.terminates.Load() != 1 {
		t.Fatalf("lease error=%v confirmed=%d terminations=%d", err, confirmed, supervisor.process.terminates.Load())
	}
	if err := pool.Close(); err != nil {
		t.Fatal(err)
	}
}

// LSP-T-047 verifies a converged process-start failure can be retried.
func TestPool_ConvergedRetry(t *testing.T) {
	pool, supervisor, file := testPool(t, PoolOptions{IdleTimeout: time.Hour})
	spawnErr := errors.New("spawn rejected")
	supervisor.startErr = spawnErr
	confirmed := 0
	_, firstErr := pool.LeaseWithExit(context.Background(), file, func() { confirmed++ })
	var bootstrap *LspBootstrapError
	if !errors.As(firstErr, &bootstrap) || !bootstrap.Converged || !errors.Is(firstErr, spawnErr) || confirmed != 1 {
		t.Fatalf("first lease error=%v confirmed=%d", firstErr, confirmed)
	}
	supervisor.startErr = nil
	lease, err := pool.Lease(context.Background(), file)
	if err != nil {
		t.Fatal(err)
	}
	lease.Release()
	if supervisor.starts != 2 {
		t.Fatalf("start attempts = %d, want 2", supervisor.starts)
	}
	if err := pool.Close(); err != nil {
		t.Fatal(err)
	}
}

// LSP-T-048 retains an unconverged failure and does not confirm its owner.
func TestPool_UnconvergedIsolation(t *testing.T) {
	pool, supervisor, file := testPool(t, PoolOptions{IdleTimeout: time.Hour})
	supervisor.startErr = native.ErrScopeNotConverged
	confirmed := 0
	_, firstErr := pool.LeaseWithExit(context.Background(), file, func() { confirmed++ })
	var bootstrap *LspBootstrapError
	if !errors.As(firstErr, &bootstrap) || bootstrap.Converged || confirmed != 0 {
		t.Fatalf("first lease error=%v confirmed=%d", firstErr, confirmed)
	}
	_, secondErr := pool.LeaseWithExit(context.Background(), file, func() { confirmed++ })
	if secondErr != firstErr || supervisor.starts != 1 || confirmed != 0 {
		t.Fatalf("second error=%v starts=%d confirmed=%d", secondErr, supervisor.starts, confirmed)
	}
	if err := pool.Close(); err == nil {
		t.Fatal("close hid the unresolved start failure")
	}
}

// LSP-T-055 rejects an already-cancelled lease without increasing its in-flight count.
func TestPool_PreAbortedWarmLease(t *testing.T) {
	pool, _, file := testPool(t, PoolOptions{IdleTimeout: time.Hour})
	lease, err := pool.Lease(context.Background(), file)
	if err != nil {
		t.Fatal(err)
	}
	lease.Release()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	confirmed := 0
	if _, err = pool.LeaseWithExit(ctx, file, func() { confirmed++ }); err == nil || err.Error() != "Operation cancelled" {
		t.Fatalf("cancelled lease error = %v", err)
	}
	if confirmed != 1 {
		t.Fatalf("confirmed %d exits, want 1", confirmed)
	}
	pool.mu.Lock()
	for _, entry := range pool.entries {
		if entry.inFlight != 0 {
			pool.mu.Unlock()
			t.Fatalf("cancelled lease increased in-flight count to %d", entry.inFlight)
		}
	}
	pool.mu.Unlock()
	if err := pool.Close(); err != nil {
		t.Fatal(err)
	}
}

// LSP-T-059 and LSP-T-061 check client lookup and rejection after pool closure.
func TestPool_ClientLookupAndClosedLease(t *testing.T) {
	pool, _, file := testPool(t, PoolOptions{IdleTimeout: time.Hour})
	if pool.Client(filepath.Join(filepath.Dir(file), "unsupported.txt")) != nil {
		t.Fatal("unsupported file returned a client")
	}
	if pool.Client(file) != nil {
		t.Fatal("unstarted file returned a client")
	}
	lease, err := pool.Lease(context.Background(), file)
	if err != nil {
		t.Fatal(err)
	}
	lease.Release()
	if pool.Client(file) == nil {
		t.Fatal("active file did not return its client")
	}
	if err := pool.Close(); err != nil {
		t.Fatal(err)
	}
	confirmed := 0
	if _, err := pool.LeaseWithExit(context.Background(), file, func() { confirmed++ }); err == nil || err.Error() != "LSP service is closed" || confirmed != 1 {
		t.Fatalf("closed lease error=%v confirmations=%d", err, confirmed)
	}
}
