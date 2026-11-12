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

const testUpdateOperationID = "123e4567-e89b-42d3-a456-426614174000"

// RUN-T-124 sends UPDATE_ACCEPTED before starting the runner and retains admission after UPDATE_PREPARED.
func TestRuntimeUpdateSendsAcceptedThenPreparedAndHoldsAdmission(t *testing.T) {
	connection := newTestConnection()
	queueHandshake(connection)
	connection.in <- updateEnvelope(testUpdateOperationID, "2.0.0")
	runner := &testUpdateRunner{outcome: update.Outcome{Phase: update.PhasePrepared, Message: "installer ready"}, progress: &update.Progress{DownloadedBytes: 5}}
	runtime := newUpdateTestRuntime(t, connection, runner)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- runtime.Run(ctx) }()
	frames := connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countUpdateResults(frames) == 2
	})
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_RUNTIME_STATUS && frame.GetRuntimeStatus().GetRuntimeStatus().GetStatus().GetUpdate().GetDownloadedBytes() == 5 {
				return true
			}
		}
		return false
	})
	var phases []protocol.UpdatePhase
	for _, frame := range frames {
		if frame.MessageType == protocol.MessageType_UPDATE_RESULT {
			phases = append(phases, frame.GetUpdateResult().GetPhase())
		}
	}
	if len(phases) != 2 || phases[0] != protocol.UpdatePhase_UPDATE_ACCEPTED || phases[1] != protocol.UpdatePhase_UPDATE_PREPARED {
		cancel()
		t.Fatalf("update receipt order = %v", phases)
	}
	if _, ok := runtime.updateOps.Active(); !ok {
		cancel()
		t.Fatal("PREPARED update released its admission hold")
	}
	if runner.stopCalls.Load() != 1 || runner.runCalls.Load() != 1 {
		cancel()
		t.Fatalf("runner stop calls=%d run calls=%d", runner.stopCalls.Load(), runner.runCalls.Load())
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-128/132 replay PREPARED on duplicate operation IDs without rerunning the update.
func TestRuntimeUpdateReplaysPreparedReceiptAndPermit(t *testing.T) {
	connection := newTestConnection()
	queueHandshake(connection)
	connection.in <- updateEnvelope(testUpdateOperationID, "2.0.0")
	var releases atomic.Int32
	runner := &testUpdateRunner{outcome: update.Outcome{
		Phase:         update.PhasePrepared,
		ReleasePermit: func() error { releases.Add(1); return nil },
	}}
	runtime := newUpdateTestRuntime(t, connection, runner)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- runtime.Run(ctx) }()
	connection.waitFor(t, func(frames []*protocol.Envelope) bool { return countUpdateResults(frames) == 2 })
	connection.in <- updateEnvelope(testUpdateOperationID, "2.0.0")
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countUpdatePhase(frames, protocol.UpdatePhase_UPDATE_PREPARED) == 2
	})
	if runner.runCalls.Load() != 1 || releases.Load() != 2 {
		cancel()
		t.Fatalf("runner calls=%d permit releases=%d", runner.runCalls.Load(), releases.Load())
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-125 reports an UPDATE_FAILED receipt and releases the admission hold.
func TestRuntimeUpdateFailureClearsAdmission(t *testing.T) {
	connection := newTestConnection()
	queueHandshake(connection)
	connection.in <- updateEnvelope(testUpdateOperationID, "2.0.0")
	runner := &testUpdateRunner{outcome: update.Outcome{Phase: update.PhaseFailed, Message: "official installer preparation failed; check daemon logs and network"}}
	runtime := newUpdateTestRuntime(t, connection, runner)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- runtime.Run(ctx) }()
	frames := connection.waitFor(t, func(frames []*protocol.Envelope) bool { return countUpdateResults(frames) == 2 })
	if frames[len(frames)-1].GetUpdateResult().GetPhase() != protocol.UpdatePhase_UPDATE_FAILED {
		cancel()
		t.Fatalf("terminal update phase = %v", frames[len(frames)-1].GetUpdateResult().GetPhase())
	}
	if _, ok := runtime.updateOps.Active(); ok {
		cancel()
		t.Fatal("failed update retained its admission hold")
	}
	if _, ok := runtime.status.Progress(); ok {
		cancel()
		t.Fatal("failed update retained progress status")
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-130 rejects an update when no managed updater is configured.
func TestRuntimeUpdateRejectsWhenNotConfigured(t *testing.T) {
	connection := newTestConnection()
	queueHandshake(connection)
	connection.in <- updateEnvelope(testUpdateOperationID, "2.0.0")
	runtime := newUpdateTestRuntime(t, connection, nil)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- runtime.Run(ctx) }()
	frames := connection.waitFor(t, func(frames []*protocol.Envelope) bool { return countUpdateResults(frames) == 1 })
	result := frames[len(frames)-1].GetUpdateResult()
	if result.GetPhase() != protocol.UpdatePhase_UPDATE_FAILED || result.GetMessage() != "managed update is not configured" {
		cancel()
		t.Fatalf("unconfigured update result = %+v", result)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-126/127 preserve thrown error messages and use the safe fallback for blank failures.
func TestRuntimeUpdateThrownErrorsUseSafeMessages(t *testing.T) {
	for _, tc := range []struct {
		name    string
		err     error
		message string
	}{
		{name: "message", err: errors.New("installer verification failed"), message: "installer verification failed"},
		{name: "blank", err: errors.New("  "), message: "capability execution failed"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			connection := newTestConnection()
			queueHandshake(connection)
			connection.in <- updateEnvelope(testUpdateOperationID, "2.0.0")
			runner := &testUpdateRunner{runError: tc.err}
			runtime := newUpdateTestRuntime(t, connection, runner)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			done := make(chan error, 1)
			go func() { done <- runtime.Run(ctx) }()
			frames := connection.waitFor(t, func(frames []*protocol.Envelope) bool { return countUpdateResults(frames) == 2 })
			failed := frames[len(frames)-1].GetUpdateResult()
			if failed.GetPhase() != protocol.UpdatePhase_UPDATE_FAILED || failed.GetMessage() != tc.message {
				cancel()
				t.Fatalf("failed update result = %+v", failed)
			}
			cancel()
			if err := <-done; err != nil {
				t.Fatal(err)
			}
		})
	}
}

// RUN-T-066 waits for an in-flight managed update to honor shutdown cancellation.
func TestRuntimeWaitsForUpdateShutdown(t *testing.T) {
	connection := newTestConnection()
	queueHandshake(connection)
	connection.in <- updateEnvelope(testUpdateOperationID, "2.0.0")
	runner := &blockingUpdateRunner{started: make(chan struct{}), stopped: make(chan struct{})}
	runtime := newUpdateTestRuntime(t, connection, runner)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- runtime.Run(ctx) }()
	select {
	case <-runner.started:
	case <-time.After(2 * time.Second):
		cancel()
		t.Fatal("update runner did not start")
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	select {
	case <-runner.stopped:
	default:
		t.Fatal("Runtime returned before the update runner settled")
	}
}

type blockingUpdateRunner struct {
	started chan struct{}
	stopped chan struct{}
}

func (r *blockingUpdateRunner) Configured() bool                    { return true }
func (r *blockingUpdateRunner) BusyReason() string                  { return "" }
func (r *blockingUpdateRunner) StopForUpdate(context.Context) error { return nil }
func (r *blockingUpdateRunner) HandleUpdate(ctx context.Context, _ update.Command, _ config.DaemonConfig, _ func(update.Progress)) (update.Outcome, error) {
	close(r.started)
	<-ctx.Done()
	close(r.stopped)
	return update.Outcome{}, ctx.Err()
}

// A watcher callback records a post-PREPARED installer failure and releases update admission.
func TestRuntimeUpdateWatchedInstallerFailure(t *testing.T) {
	connection := newTestConnection()
	queueHandshake(connection)
	connection.in <- updateEnvelope(testUpdateOperationID, "2.0.0")
	var fail func(string)
	runner := &testUpdateRunner{outcome: update.Outcome{Phase: update.PhasePrepared, WatchFailure: func(callback func(string)) func() {
		fail = callback
		return func() {}
	}}}
	runtime := newUpdateTestRuntime(t, connection, runner)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- runtime.Run(ctx) }()
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countUpdatePhase(frames, protocol.UpdatePhase_UPDATE_PREPARED) == 1
	})
	if fail == nil {
		cancel()
		t.Fatal("installer failure watcher was not registered")
	}
	fail("sensitive installer detail")
	frames := connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countUpdatePhase(frames, protocol.UpdatePhase_UPDATE_FAILED) == 1
	})
	failed := frames[len(frames)-1].GetUpdateResult()
	if failed.GetMessage() != "sensitive installer detail" {
		cancel()
		t.Fatalf("watch failure diagnostic = %q", failed.GetMessage())
	}
	if _, ok := runtime.updateOps.Active(); ok {
		cancel()
		t.Fatal("watch failure retained update admission")
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-133 requires READY scope and environment identity before accepting UPDATE.
func TestRuntimeUpdateRequiresEnvironmentID(t *testing.T) {
	connection := newTestConnection()
	queueHandshake(connection)
	invalid := updateEnvelope(testUpdateOperationID, "2.0.0")
	invalid.EnvironmentId = nil
	connection.in <- invalid
	runner := &testUpdateRunner{outcome: update.Outcome{Phase: update.PhasePrepared}}
	runtime := newUpdateTestRuntime(t, connection, runner)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- runtime.Run(ctx) }()
	frames := connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_ERROR {
				return true
			}
		}
		return false
	})
	if runner.runCalls.Load() != 0 {
		cancel()
		t.Fatal("unscoped UPDATE started the runner")
	}
	var message string
	for _, frame := range frames {
		if frame.MessageType == protocol.MessageType_ERROR {
			message = frame.GetError().GetMessage()
		}
	}
	if message != "UPDATE requires a READY bound connection" {
		cancel()
		t.Fatalf("unscoped UPDATE error = %q", message)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

type testUpdateRunner struct {
	outcome   update.Outcome
	progress  *update.Progress
	stopError error
	runError  error
	stopCalls atomic.Int32
	runCalls  atomic.Int32
}

func (r *testUpdateRunner) Configured() bool   { return true }
func (r *testUpdateRunner) BusyReason() string { return "" }
func (r *testUpdateRunner) StopForUpdate(context.Context) error {
	r.stopCalls.Add(1)
	return r.stopError
}
func (r *testUpdateRunner) HandleUpdate(_ context.Context, _ update.Command, _ config.DaemonConfig, report func(update.Progress)) (update.Outcome, error) {
	r.runCalls.Add(1)
	if r.progress != nil {
		report(*r.progress)
	}
	return r.outcome, r.runError
}

func newUpdateTestRuntime(t *testing.T, connection *testConnection, runner update.Runner) *Runtime {
	t.Helper()
	runtime, err := NewRuntime(RuntimeOptions{
		EnvironmentID: "env",
		DaemonVersion: "1.0.0",
		Connections:   fixedConnectionFactory{connection: connection},
		Update:        runner,
		ApplyConfig: func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: version}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	return runtime
}

func updateEnvelope(operationID, targetVersion string) *protocol.Envelope {
	return &protocol.Envelope{MessageType: protocol.MessageType_UPDATE, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_Update{Update: &protocol.Update{OperationId: stringPtr(operationID), TargetVersion: stringPtr(targetVersion)}}}
}

func countUpdateResults(frames []*protocol.Envelope) int {
	count := 0
	for _, frame := range frames {
		if frame.MessageType == protocol.MessageType_UPDATE_RESULT {
			count++
		}
	}
	return count
}

func countUpdatePhase(frames []*protocol.Envelope, phase protocol.UpdatePhase) int {
	count := 0
	for _, frame := range frames {
		if frame.MessageType == protocol.MessageType_UPDATE_RESULT && frame.GetUpdateResult().GetPhase() == phase {
			count++
		}
	}
	return count
}
