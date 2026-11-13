package runtime

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"github.com/fengwk/kk-studio/daemon/internal/update"
)

// upgradeRunner records the managed update a 426 rejection demanded and can hold it open, so a test
// can prove the update is not cancelled by the shutdown the rejection itself triggers.
type upgradeRunner struct {
	configured bool
	started    chan update.Command
	release    chan struct{}
	finished   chan struct{}
	stopCalls  atomic.Int32
	runCalls   atomic.Int32
	signalSeen atomic.Bool
	// prepareFailure makes the runner finish without accepting the handover, like an installer that
	// never became ready.
	prepareFailure error
}

func newUpgradeRunner() *upgradeRunner {
	return &upgradeRunner{
		configured: true,
		started:    make(chan update.Command, 4),
		release:    make(chan struct{}),
		finished:   make(chan struct{}, 4),
	}
}

func (r *upgradeRunner) Configured() bool   { return r.configured }
func (r *upgradeRunner) BusyReason() string { return "" }
func (r *upgradeRunner) StopForUpdate(context.Context) error {
	r.stopCalls.Add(1)
	return nil
}

func (r *upgradeRunner) HandleUpdate(ctx context.Context, command update.Command, _ config.DaemonConfig, _ func(update.Progress)) (update.Outcome, error) {
	r.runCalls.Add(1)
	r.started <- command
	if r.prepareFailure != nil {
		r.finished <- struct{}{}
		return update.Outcome{Phase: update.PhaseFailed, Message: r.prepareFailure.Error()}, nil
	}
	select {
	case <-r.release:
		r.signalSeen.Store(true)
		r.finished <- struct{}{}
		return update.Outcome{Phase: update.PhasePrepared}, nil
	case <-ctx.Done():
		r.finished <- struct{}{}
		return update.Outcome{}, ctx.Err()
	}
}

// rejectingAttemptConnectionFactory fails every dial after reporting the handshake outcome the test
// asks for, so the runtime's recovery policy runs exactly as it would after a real rejection.
type rejectingAttemptConnectionFactory struct {
	reject func(ControlObserver)
}

func (f *rejectingAttemptConnectionFactory) Connect(_ context.Context, observer ControlObserver) (Connection, error) {
	f.reject(observer)
	return nil, errors.New("handshake rejected")
}

func upgradeRequired(targetVersion string) *protocol.UpgradeRequired {
	if targetVersion == "" {
		return nil
	}
	return &protocol.UpgradeRequired{TargetVersion: stringPtr(targetVersion)}
}

// RUN-T-057 a 426 rejection with a target version runs the managed update for that version and keeps
// the daemon alive: the update is not aborted by the shutdown the rejection would otherwise trigger.
func TestRuntimeRunsManagedUpdateOnUpgradeRequired(t *testing.T) {
	runner := newUpgradeRunner()
	factory := &rejectingAttemptConnectionFactory{reject: func(observer ControlObserver) {
		observer.NotifyUpgradeFailed(statusUpgradeRequired, upgradeRequired("1.0.1"))
	}}
	r, err := NewRuntime(RuntimeOptions{
		EnvironmentID:         "env",
		DaemonVersion:         "1.0.0",
		Connections:           factory,
		Update:                runner,
		InitialReconnectDelay: time.Hour,
		MaxReconnectDelay:     time.Hour,
		ApplyConfig: func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: version}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	var command update.Command
	select {
	case command = <-runner.started:
	case err := <-done:
		t.Fatalf("daemon stopped instead of running the managed update: %v", err)
	case <-time.After(2 * time.Second):
		cancel()
		t.Fatal("426 rejection did not start the managed update")
	}
	if command.TargetVersion != "1.0.1" {
		cancel()
		t.Fatalf("managed update target=%q, want the rejection's target version", command.TargetVersion)
	}
	if err := update.ValidateCommand(command); err != nil {
		cancel()
		t.Fatalf("generated update command is not canonical: %v", err)
	}
	select {
	case err := <-done:
		cancel()
		t.Fatalf("daemon stopped while the managed update was still preparing: %v", err)
	case <-time.After(50 * time.Millisecond):
	}
	if progress, ok := r.status.Progress(); !ok || progress.TargetVersion != "1.0.1" {
		cancel()
		t.Fatalf("runtime status update=%+v ok=%t, want the target version published", progress, ok)
	}
	// Releasing the runner proves the update context is still live: a rejection-owned shutdown must
	// not have cancelled it.
	close(runner.release)
	select {
	case <-runner.finished:
	case <-time.After(2 * time.Second):
		cancel()
		t.Fatal("managed update was cancelled by the rejection-triggered shutdown")
	}
	if !runner.signalSeen.Load() {
		cancel()
		t.Fatal("managed update context was aborted before the runner could finish")
	}
	waitForUpgradeReceipt(t, r, update.PhasePrepared)
	cancel()
	if err := <-done; err != nil {
		t.Fatalf("daemon shutdown after a prepared upgrade: %v", err)
	}
	if runner.runCalls.Load() != 1 {
		t.Fatalf("managed update ran %d times, want exactly once", runner.runCalls.Load())
	}
}

// RUN-T-057 a 426 rejection without a parsable target version cannot be acted on: the daemon stops
// with the protocol-upgrade failure instead of retrying the same rejected handshake forever.
func TestRuntimeStopsOnUpgradeRequiredWithoutTargetVersion(t *testing.T) {
	for _, tc := range []struct {
		name   string
		target string
	}{
		{"no upgrade body", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			runner := newUpgradeRunner()
			factory := &rejectingAttemptConnectionFactory{reject: func(observer ControlObserver) {
				observer.NotifyUpgradeFailed(statusUpgradeRequired, upgradeRequired(tc.target))
			}}
			r, err := NewRuntime(RuntimeOptions{
				EnvironmentID:         "env",
				DaemonVersion:         "1.0.0",
				Connections:           factory,
				Update:                runner,
				InitialReconnectDelay: time.Millisecond,
				MaxReconnectDelay:     time.Millisecond,
				ApplyConfig: func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
					return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: version}, nil
				},
			})
			if err != nil {
				t.Fatal(err)
			}
			done := make(chan error, 1)
			go func() { done <- r.Run(context.Background()) }()
			select {
			case err := <-done:
				if !errors.Is(err, errProtocolUpgradeRequired) {
					t.Fatalf("426 without a target version returned %v, want the protocol-upgrade failure", err)
				}
			case <-time.After(2 * time.Second):
				t.Fatal("426 without a target version did not stop the daemon")
			}
			if runner.runCalls.Load() != 0 {
				t.Fatalf("managed update ran %d times for a rejection with no target version", runner.runCalls.Load())
			}
		})
	}
}

// RUN-T-057 a 426 rejection while no managed updater is configured stops the daemon: there is no
// update path that could satisfy the required protocol upgrade.
func TestRuntimeStopsOnUpgradeRequiredWithoutUpdater(t *testing.T) {
	runner := newUpgradeRunner()
	runner.configured = false
	factory := &rejectingAttemptConnectionFactory{reject: func(observer ControlObserver) {
		observer.NotifyUpgradeFailed(statusUpgradeRequired, upgradeRequired("1.0.1"))
	}}
	r, err := NewRuntime(RuntimeOptions{
		EnvironmentID:         "env",
		DaemonVersion:         "1.0.0",
		Connections:           factory,
		Update:                runner,
		InitialReconnectDelay: time.Millisecond,
		MaxReconnectDelay:     time.Millisecond,
		ApplyConfig: func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: version}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- r.Run(context.Background()) }()
	select {
	case err := <-done:
		if !errors.Is(err, errProtocolUpgradeRequired) {
			t.Fatalf("unconfigured 426 upgrade returned %v, want the protocol-upgrade failure", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("426 without a configured updater did not stop the daemon")
	}
	if runner.runCalls.Load() != 0 {
		t.Fatalf("unconfigured updater ran %d times", runner.runCalls.Load())
	}
}

// RUN-T-057 a 426 rejection whose update never prepares stops the daemon, matching the TS
// onUnprepared callback, instead of leaving it connected to a platform it cannot talk to.
func TestRuntimeStopsWhenUpgradeUpdateIsNotPrepared(t *testing.T) {
	runner := newUpgradeRunner()
	close(runner.release)
	runner.prepareFailure = errors.New("official installer preparation failed")
	factory := &rejectingAttemptConnectionFactory{reject: func(observer ControlObserver) {
		observer.NotifyUpgradeFailed(statusUpgradeRequired, upgradeRequired("1.0.1"))
	}}
	r, err := NewRuntime(RuntimeOptions{
		EnvironmentID:         "env",
		DaemonVersion:         "1.0.0",
		Connections:           factory,
		Update:                runner,
		InitialReconnectDelay: time.Millisecond,
		MaxReconnectDelay:     time.Millisecond,
		ApplyConfig: func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: version}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- r.Run(context.Background()) }()
	select {
	case err := <-done:
		if !errors.Is(err, errProtocolUpgradeRequired) {
			t.Fatalf("unprepared upgrade returned %v, want the protocol-upgrade failure", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("an update that never prepared did not stop the daemon")
	}
	if runner.runCalls.Load() != 1 {
		t.Fatalf("managed update ran %d times, want exactly once", runner.runCalls.Load())
	}
}

// RUN-T-057 a non-426 handshake rejection keeps the ordinary reconnect behavior: only a protocol
// upgrade demand changes the recovery policy.
func TestRuntimeReconnectsWithoutUpgradeForOtherRejections(t *testing.T) {
	runner := newUpgradeRunner()
	connection := newTestConnection()
	queueHandshake(connection)
	var attempts atomic.Int32
	factory := &rejectingAttemptConnectionFactory{reject: func(observer ControlObserver) {
		if attempts.Add(1) == 1 {
			observer.NotifyUpgradeFailed(503, nil)
		}
	}}
	r, err := NewRuntime(RuntimeOptions{
		EnvironmentID:         "env",
		DaemonVersion:         "1.0.0",
		Connections:           &firstRejectThenServe{factory: factory, connection: connection},
		Update:                runner,
		InitialReconnectDelay: time.Millisecond,
		MaxReconnectDelay:     time.Millisecond,
		ApplyConfig: func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: version}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countFrames(frames, protocol.MessageType_READY) == 1
	})
	if runner.runCalls.Load() != 0 {
		cancel()
		t.Fatalf("non-426 rejection ran the managed update %d times", runner.runCalls.Load())
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// firstRejectThenServe rejects the first dial (reporting a non-426 status) and then serves traffic on
// the provided connection, so the ordinary reconnect path can be observed.
type firstRejectThenServe struct {
	factory    *rejectingAttemptConnectionFactory
	connection *testConnection
	calls      atomic.Int32
}

func (f *firstRejectThenServe) Connect(ctx context.Context, observer ControlObserver) (Connection, error) {
	if f.calls.Add(1) == 1 {
		return f.factory.Connect(ctx, observer)
	}
	return f.connection, nil
}

// waitForUpgradeReceipt waits until the runtime published a receipt for the given update phase.
func waitForUpgradeReceipt(t *testing.T, r *Runtime, phase update.Phase) {
	t.Helper()
	receipt, ok := r.updateOps.ReceiptToResend()
	if !ok || receipt.Phase != phase {
		t.Fatalf("update receipt=%+v ok=%t, want phase %s", receipt, ok, phase)
	}
}
