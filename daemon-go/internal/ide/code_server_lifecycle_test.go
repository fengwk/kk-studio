package ide

import (
	"context"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/native"
)

// IDE-T-028 verifies inherited environment is preserved and bridge credentials are injected on each launch.
func TestLaunchEnvironment(t *testing.T) {
	root := t.TempDir()
	installFakeDistribution(t, root)
	t.Setenv("http_proxy", "http://host.invalid:1")
	supervisor := &fakeSupervisor{}
	manager := NewCodeServerManager(fakeIDEConfig(root), supervisor, BridgeCredentials{URL: "http://127.0.0.1:9000/bridge", Token: "bridge-secret"})
	if _, err := manager.EnsureStarted(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := manager.Stop(context.Background()); err != nil {
		t.Fatal(err)
	}
	if _, err := manager.EnsureStarted(context.Background()); err != nil {
		t.Fatal(err)
	}
	supervisor.mu.Lock()
	specs := append([]native.ProcessSpec(nil), supervisor.specs...)
	supervisor.mu.Unlock()
	if len(specs) != 2 {
		t.Fatalf("launches=%d", len(specs))
	}
	for index, spec := range specs {
		environment := make(map[string]string)
		for _, entry := range spec.Env {
			key, value, ok := strings.Cut(entry, "=")
			if ok {
				environment[key] = value
			}
		}
		if environment["http_proxy"] != "http://host.invalid:1" || environment["KK_STUDIO_IDE_BRIDGE_URL"] != "http://127.0.0.1:9000/bridge" || environment["KK_STUDIO_IDE_BRIDGE_TOKEN"] != "bridge-secret" || environment["KK_STUDIO_IDE_MANAGEMENT_TOKEN"] == "" {
			t.Fatalf("launch %d environment missing inherited or bridge values: %#v", index, environment)
		}
		if environment["KK_STUDIO_IDE_MANAGEMENT_TOKEN"] == environment["KK_STUDIO_IDE_BRIDGE_TOKEN"] {
			t.Fatalf("launch %d reused bridge token as management token", index)
		}
	}
	if err := manager.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
}

// IDE-T-032 verifies startup and close preserve the termination failure when the login step fails.
func TestStartupTerminationFailure(t *testing.T) {
	root := t.TempDir()
	installFakeDistribution(t, root)
	supervisor := &fakeSupervisor{loginStatus: http.StatusUnauthorized, terminateErr: errors.New("cannot terminate")}
	manager := NewCodeServerManager(fakeIDEConfig(root), supervisor, BridgeCredentials{})
	if _, err := manager.EnsureStarted(context.Background()); err == nil || !strings.Contains(SafeMessage(err), "could not be terminated") {
		t.Fatalf("startup error=%v", err)
	}
	if err := manager.Close(context.Background()); err == nil || err.Error() != "cannot terminate" {
		t.Fatalf("close termination error=%v", err)
	}
	supervisor.mu.Lock()
	process := supervisor.proc
	supervisor.mu.Unlock()
	process.terminateErr = nil
	if err := manager.Close(context.Background()); err != nil {
		t.Fatalf("convergent close retry: %v", err)
	}
}

// IDE-T-033 verifies a reset login connection fails safely and terminates the startup process.
func TestLoginReset(t *testing.T) {
	root := t.TempDir()
	installFakeDistribution(t, root)
	supervisor := &fakeSupervisor{resetLogin: true}
	manager := NewCodeServerManager(fakeIDEConfig(root), supervisor, BridgeCredentials{})
	if _, err := manager.EnsureStarted(context.Background()); SafeMessage(err) != "code-server login failed" {
		t.Fatalf("login reset error=%v", err)
	}
	if err := manager.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
}

// IDE-T-034 verifies an already-cancelled caller never starts a supervised process.
func TestAlreadyCancelledStartup(t *testing.T) {
	root := t.TempDir()
	installFakeDistribution(t, root)
	supervisor := &fakeSupervisor{}
	manager := NewCodeServerManager(fakeIDEConfig(root), supervisor, BridgeCredentials{})
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := manager.EnsureStarted(ctx); !errors.Is(err, ErrCancelled) {
		t.Fatalf("cancelled startup error=%v", err)
	}
	if supervisor.count() != 0 {
		t.Fatalf("spawned %d processes", supervisor.count())
	}
	if err := manager.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
}

// IDE-T-035 verifies a cancelled startup waiter leaves the shared launch running for another caller.
func TestSharedStartupCancellation(t *testing.T) {
	root := t.TempDir()
	installFakeDistribution(t, root)
	startGate := make(chan struct{})
	startEntered := make(chan struct{})
	supervisor := &fakeSupervisor{startHook: func(context.Context) { close(startEntered); <-startGate }}
	manager := NewCodeServerManager(fakeIDEConfig(root), supervisor, BridgeCredentials{})
	firstResult := make(chan error, 1)
	go func() { _, err := manager.EnsureStarted(context.Background()); firstResult <- err }()
	select {
	case <-startEntered:
	case <-time.After(time.Second):
		t.Fatal("shared startup did not begin")
	}
	waiter := newSignaledCancelContext()
	secondResult := make(chan error, 1)
	go func() { _, err := manager.EnsureStarted(waiter); secondResult <- err }()
	select {
	case <-waiter.entered:
	case <-time.After(time.Second):
		t.Fatal("second caller did not join startup")
	}
	waiter.cancel()
	if err := <-secondResult; !errors.Is(err, ErrCancelled) {
		t.Fatalf("cancelled waiter error=%v", err)
	}
	close(startGate)
	if err := <-firstResult; err != nil {
		t.Fatalf("shared startup error=%v", err)
	}
	if supervisor.count() != 1 {
		t.Fatalf("launches=%d", supervisor.count())
	}
	if err := manager.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
}

// IDE-T-036 verifies close cancels and joins startup before making the manager permanently closed.
func TestCloseDuringStartup(t *testing.T) {
	root := t.TempDir()
	installFakeDistribution(t, root)
	startEntered := make(chan struct{})
	supervisor := &fakeSupervisor{startHook: func(ctx context.Context) { close(startEntered); <-ctx.Done() }}
	manager := NewCodeServerManager(fakeIDEConfig(root), supervisor, BridgeCredentials{})
	startResult := make(chan error, 1)
	go func() { _, err := manager.EnsureStarted(context.Background()); startResult <- err }()
	select {
	case <-startEntered:
	case <-time.After(time.Second):
		t.Fatal("startup did not enter supervisor")
	}
	closeResult := make(chan error, 1)
	go func() { closeResult <- manager.Close(context.Background()) }()
	select {
	case err := <-closeResult:
		if err != nil {
			t.Fatalf("close during startup=%v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("close did not join startup")
	}
	if err := <-startResult; err == nil {
		t.Fatal("in-flight startup unexpectedly succeeded")
	}
	if _, err := manager.EnsureStarted(context.Background()); SafeMessage(err) != "ide service is closed" {
		t.Fatalf("start after close=%v", err)
	}
}

// IDE-T-037 verifies an exited process scope is terminated before a replacement process starts.
func TestConvergeExitedScope(t *testing.T) {
	root := t.TempDir()
	installFakeDistribution(t, root)
	supervisor := &fakeSupervisor{}
	manager := NewCodeServerManager(fakeIDEConfig(root), supervisor, BridgeCredentials{}).(*codeServerManager)
	first, err := manager.EnsureStarted(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	supervisor.mu.Lock()
	oldProcess := supervisor.proc
	supervisor.mu.Unlock()
	oldProcess.exit <- native.ProcessExit{Code: intPointer(7)}
	deadline := time.Now().Add(time.Second)
	for manager.Session() != nil && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	second, err := manager.EnsureStarted(context.Background())
	if err != nil || second.PID == first.PID {
		t.Fatalf("replacement session=%+v err=%v", second, err)
	}
	if oldProcess.terminateCalls == 0 {
		t.Fatal("exited process scope was not converged before restart")
	}
	if err := manager.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
}

// IDE-T-038 verifies intentional stops stay quiet while unexpected exits notify exactly once.
func TestUnexpectedExitNotification(t *testing.T) {
	root := t.TempDir()
	installFakeDistribution(t, root)
	supervisor := &fakeSupervisor{}
	manager := NewCodeServerManager(fakeIDEConfig(root), supervisor, BridgeCredentials{}).(*codeServerManager)
	exits := make(chan uint64, 4)
	manager.OnUnexpectedExit(func(generation uint64) { exits <- generation })
	first, err := manager.EnsureStarted(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if err := manager.Stop(context.Background()); err != nil {
		t.Fatal(err)
	}
	select {
	case generation := <-exits:
		t.Fatalf("intentional stop notified generation %d", generation)
	case <-time.After(10 * time.Millisecond):
	}
	second, err := manager.EnsureStarted(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	supervisor.mu.Lock()
	process := supervisor.proc
	supervisor.mu.Unlock()
	process.exit <- native.ProcessExit{Code: intPointer(9)}
	select {
	case generation := <-exits:
		if generation != second.Generation {
			t.Fatalf("notified generation=%d want %d (first=%d)", generation, second.Generation, first.Generation)
		}
	case <-time.After(time.Second):
		t.Fatal("unexpected exit not notified")
	}
	select {
	case generation := <-exits:
		t.Fatalf("duplicate exit notification for %d", generation)
	case <-time.After(10 * time.Millisecond):
	}
	if err := manager.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
}

// IDE-T-036 verifies concurrent close callers share termination and cancellation only detaches the waiter.
func TestConcurrentStopWaiterCancellation(t *testing.T) {
	root := t.TempDir()
	installFakeDistribution(t, root)
	gate := make(chan struct{})
	entered := make(chan struct{})
	supervisor := &fakeSupervisor{terminateGate: gate, terminateEntered: entered}
	manager := NewCodeServerManager(fakeIDEConfig(root), supervisor, BridgeCredentials{})
	if _, err := manager.EnsureStarted(context.Background()); err != nil {
		t.Fatal(err)
	}
	firstResult := make(chan error, 1)
	go func() { firstResult <- manager.Stop(context.Background()) }()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("first stop did not enter termination")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := manager.Close(ctx); !errors.Is(err, ErrCancelled) {
		t.Fatalf("cancelled close waiter=%v", err)
	}
	close(gate)
	if err := <-firstResult; err != nil {
		t.Fatalf("shared stop=%v", err)
	}
	if err := manager.Close(context.Background()); err != nil {
		t.Fatalf("final close=%v", err)
	}
}

// IDE-T-039 verifies close surfaces termination failures and preserves private IPC diagnostics for retry.
func TestTerminationFailureOnClose(t *testing.T) {
	root := t.TempDir()
	installFakeDistribution(t, root)
	supervisor := &fakeSupervisor{}
	manager := NewCodeServerManager(fakeIDEConfig(root), supervisor, BridgeCredentials{}).(*codeServerManager)
	if _, err := manager.EnsureStarted(context.Background()); err != nil {
		t.Fatal(err)
	}
	if _, err := manager.ListViews(context.Background()); err == nil || err.Error() != "managed view rejected (UNAVAILABLE)" {
		t.Fatalf("missing management API error=%v", err)
	}
	supervisor.mu.Lock()
	process := supervisor.proc
	spec := supervisor.specs[0]
	supervisor.mu.Unlock()
	supervisor.terminateErr = errors.New("cannot terminate")
	process.terminateErr = supervisor.terminateErr
	if err := manager.Close(context.Background()); err == nil || err.Error() != "cannot terminate" {
		t.Fatalf("close error=%v", err)
	}
	if runtime.GOOS != "windows" {
		socketIndex := -1
		for index, arg := range spec.Args {
			if arg == "--session-socket" {
				socketIndex = index + 1
				break
			}
		}
		if socketIndex < 0 {
			t.Fatal("session socket argument missing")
		}
		if _, err := os.Stat(filepath.Dir(spec.Args[socketIndex])); err != nil {
			t.Fatalf("IPC directory was removed after failed termination: %v", err)
		}
	}
	process.terminateErr = nil
	if err := manager.Close(context.Background()); err != nil {
		t.Fatalf("close retry=%v", err)
	}
}

type signaledCancelContext struct {
	context.Context
	entered chan struct{}
	cancel  context.CancelFunc
	once    sync.Once
}

func newSignaledCancelContext() *signaledCancelContext {
	ctx, cancel := context.WithCancel(context.Background())
	return &signaledCancelContext{Context: ctx, entered: make(chan struct{}), cancel: cancel}
}
func (ctx *signaledCancelContext) Done() <-chan struct{} {
	ctx.once.Do(func() { close(ctx.entered) })
	return ctx.Context.Done()
}
