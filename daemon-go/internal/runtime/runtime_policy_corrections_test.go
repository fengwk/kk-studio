package runtime

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"github.com/fengwk/kk-studio/daemon/internal/update"
	"google.golang.org/protobuf/proto"
)

// RUN-T-002 closes a SETTINGS handshake with no environment scope and retries on a new connection.
func TestRuntimeRetriesSettingsWithoutEnvironment(t *testing.T) {
	first, second := newTestConnection(), newTestConnection()
	first.in <- &protocol.Envelope{MessageType: protocol.MessageType_SETTINGS, Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{}}}}
	queueHandshake(second)
	factory := &sequenceConnectionFactory{connections: []Connection{first, second}}
	r, err := NewRuntime(RuntimeOptions{
		EnvironmentID: "env", DaemonVersion: "1.0.0", Connections: factory,
		InitialReconnectDelay: time.Millisecond, MaxReconnectDelay: 10 * time.Millisecond,
		ApplyConfig: func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: version}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	select {
	case <-first.closed:
	case <-time.After(time.Second):
		cancel()
		t.Fatal("invalid SETTINGS connection was not closed")
	}
	if first.sent(protocol.MessageType_READY) {
		cancel()
		t.Fatal("READY was sent for SETTINGS without an environment")
	}
	second.waitFor(t, func(frames []*protocol.Envelope) bool { return countFrames(frames, protocol.MessageType_READY) == 1 })
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

type lateProgressUpdateRunner struct {
	callback chan func(update.Progress)
}

func (r *lateProgressUpdateRunner) Configured() bool                    { return true }
func (r *lateProgressUpdateRunner) BusyReason() string                  { return "" }
func (r *lateProgressUpdateRunner) StopForUpdate(context.Context) error { return nil }
func (r *lateProgressUpdateRunner) HandleUpdate(_ context.Context, _ update.Command, _ config.DaemonConfig, report func(update.Progress)) (update.Outcome, error) {
	r.callback <- report
	return update.Outcome{Phase: update.PhaseFailed, Message: "update failed"}, nil
}

// RUN-T-034 ignores a saved progress callback after the matching update has failed.
func TestRuntimeIgnoresLateUpdateProgressAfterFailure(t *testing.T) {
	connection := newTestConnection()
	queueHandshake(connection)
	connection.in <- updateEnvelope(testUpdateOperationID, "2.0.0")
	runner := &lateProgressUpdateRunner{callback: make(chan func(update.Progress), 1)}
	r := newUpdateTestRuntime(t, connection, runner)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countUpdatePhase(frames, protocol.UpdatePhase_UPDATE_FAILED) == 1
	})
	callback := <-runner.callback
	before := r.status.Snapshot()
	callback(update.Progress{DownloadedBytes: 7})
	after := r.status.Snapshot()
	if before.Revision != after.Revision {
		cancel()
		t.Fatalf("late progress changed status revision from %d to %d", before.Revision, after.Revision)
	}
	if progress, ok := r.status.Progress(); ok {
		cancel()
		t.Fatalf("late progress restored a cleared snapshot: %+v", progress)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-035 confirms failed update preparation releases the invocation admission gate.
func TestRuntimeUpdateFailureReopensInvocationAdmission(t *testing.T) {
	connection := newTestConnection()
	queueHandshake(connection)
	connection.in <- updateEnvelope(testUpdateOperationID, "2.0.0")
	runner := &testUpdateRunner{outcome: update.Outcome{Phase: update.PhaseFailed, Message: "update failed"}}
	r := newUpdateTestRuntime(t, connection, runner)
	r.options.Capabilities = map[string]CapabilityExecutor{"echo": func(_ context.Context, call CapabilityCall, _ CapabilityContext) (CapabilityResult, error) {
		return CapabilityResult{CallID: call.InvocationID}, nil
	}}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countUpdatePhase(frames, protocol.UpdatePhase_UPDATE_FAILED) == 1
	})
	connection.in <- invocationEnvelope()
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countFrames(frames, protocol.MessageType_COMPLETED) == 1
	})
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-038 keeps update admission closed for protected work and preserves its exit fact for RECOVER.
func TestRuntimeProtectedExecutionRejectsUpdateAndRecoversExit(t *testing.T) {
	connection := newTestConnection()
	queueHandshake(connection)
	connection.in <- invocationEnvelope()
	confirmed, release := make(chan struct{}), make(chan struct{})
	runner := &testUpdateRunner{outcome: update.Outcome{Phase: update.PhasePrepared}}
	r := newUpdateTestRuntime(t, connection, runner)
	r.options.Capabilities = map[string]CapabilityExecutor{"echo": func(_ context.Context, call CapabilityCall, callContext CapabilityContext) (CapabilityResult, error) {
		callContext.ConfirmExecutionExit()
		close(confirmed)
		<-release
		return CapabilityResult{CallID: call.InvocationID}, nil
	}}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	select {
	case <-confirmed:
	case <-time.After(time.Second):
		cancel()
		close(release)
		t.Fatal("protected executor did not start")
	}
	connection.in <- updateEnvelope(testUpdateOperationID, "2.0.0")
	frames := connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countUpdatePhase(frames, protocol.UpdatePhase_UPDATE_FAILED) == 1
	})
	if runner.runCalls.Load() != 0 {
		cancel()
		close(release)
		t.Fatal("update runner started while protected work was active")
	}
	var message string
	for _, frame := range frames {
		if frame.MessageType == protocol.MessageType_UPDATE_RESULT && frame.GetUpdateResult().GetPhase() == protocol.UpdatePhase_UPDATE_FAILED {
			message = frame.GetUpdateResult().GetMessage()
		}
	}
	if message != "update is busy: protected execution is still running" {
		cancel()
		close(release)
		t.Fatalf("busy update message = %q", message)
	}
	close(release)
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countFrames(frames, protocol.MessageType_COMPLETED) == 1 && countFrames(frames, protocol.MessageType_EXECUTION_EXITED) == 1
	})
	connection.in <- &protocol.Envelope{MessageType: protocol.MessageType_RECOVER, EnvironmentId: stringPtr("env"), InvocationId: stringPtr(testInvocationID), Payload: &protocol.Envelope_Recover{Recover: &protocol.Recover{}}}
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countFrames(frames, protocol.MessageType_COMPLETED) == 2 && countFrames(frames, protocol.MessageType_EXECUTION_EXITED) == 2
	})
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-065 and RUN-T-080 verify shutdown waits for an invocation and Go's shutdown reason is cancelled.
func TestRuntimeShutdownCancelsAndWaitsForInvocation(t *testing.T) {
	connection := newTestConnection()
	queueHandshake(connection)
	connection.in <- invocationEnvelope()
	started, release := make(chan struct{}), make(chan struct{})
	r := newInvocationRuntime(t, connection, func(ctx context.Context, call CapabilityCall, _ CapabilityContext) (CapabilityResult, error) {
		close(started)
		<-ctx.Done()
		<-release
		return CapabilityResult{}, ctx.Err()
	})
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	select {
	case <-started:
	case <-time.After(time.Second):
		cancel()
		t.Fatal("invocation did not start")
	}
	cancel()
	select {
	case <-connection.closed:
	case <-time.After(time.Second):
		close(release)
		t.Fatal("shutdown did not close the connection")
	}
	select {
	case err := <-done:
		close(release)
		t.Fatalf("Runtime returned before its invocation settled: %v", err)
	default:
	}
	close(release)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	entry, ok := r.journal.Get(testInvocationID)
	if !ok || entry.State != InvocationCancelled {
		t.Fatalf("shutdown invocation journal = %+v, %v", entry, ok)
	}
	var terminal protocol.Envelope
	if err := proto.Unmarshal(entry.Terminal, &terminal); err != nil {
		t.Fatal(err)
	}
	cancelled := terminal.GetCancelled()
	if terminal.MessageType != protocol.MessageType_CANCELLED || cancelled == nil || cancelled.GetReason() != "cancelled" {
		t.Fatalf("shutdown cancellation = %+v, want reason cancelled", cancelled)
	}
}

type resourceUploadFailureUploader struct{ err error }

func (u resourceUploadFailureUploader) Upload(context.Context, string, ResultContent) (ResultContent, error) {
	return ResultContent{}, u.err
}

// RUN-T-074/T-076 suppress execution-exit confirmation when failed upload cleanup is unproven.
func TestRuntimeUnconfirmedUploadCleanupSuppressesExecutionExit(t *testing.T) {
	connection := newTestConnection()
	queueHandshake(connection)
	connection.in <- invocationEnvelope()
	r := newInvocationRuntime(t, connection, func(_ context.Context, call CapabilityCall, callContext CapabilityContext) (CapabilityResult, error) {
		callContext.ConfirmExecutionExit()
		return CapabilityResult{CallID: call.InvocationID, Contents: []ResultContent{{Type: "binary", Bytes: []byte("payload")}}}, nil
	})
	r.options.Resources = resourceUploadFailureUploader{err: &ResourceUploadError{Stage: "commit", Category: "network", CleanupConfirmed: false}}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		r.callsMu.Lock()
		active := len(r.calls)
		r.callsMu.Unlock()
		return active == 0 && countFrames(frames, protocol.MessageType_FAILED) == 1
	})
	if got := countFrames(connection.frames(), protocol.MessageType_EXECUTION_EXITED); got != 0 {
		cancel()
		t.Fatalf("unproven upload cleanup published %d execution exits", got)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-075 allows exit confirmation after an upload failure with explicit cleanup proof.
func TestRuntimeConfirmedUploadCleanupAllowsExecutionExit(t *testing.T) {
	connection := newTestConnection()
	queueHandshake(connection)
	connection.in <- invocationEnvelope()
	r := newInvocationRuntime(t, connection, func(_ context.Context, call CapabilityCall, callContext CapabilityContext) (CapabilityResult, error) {
		callContext.ConfirmExecutionExit()
		return CapabilityResult{CallID: call.InvocationID, Contents: []ResultContent{{Type: "binary", Bytes: []byte("payload")}}}, nil
	})
	r.options.Resources = resourceUploadFailureUploader{err: &ResourceUploadError{Stage: "commit", Category: "ticket-failed", CleanupConfirmed: true}}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	frames := connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countFrames(frames, protocol.MessageType_FAILED) == 1 && countFrames(frames, protocol.MessageType_EXECUTION_EXITED) == 1
	})
	failedAt, exitedAt := -1, -1
	for index, frame := range frames {
		if frame.MessageType == protocol.MessageType_FAILED {
			failedAt = index
		}
		if frame.MessageType == protocol.MessageType_EXECUTION_EXITED {
			exitedAt = index
		}
	}
	if failedAt < 0 || exitedAt <= failedAt {
		cancel()
		t.Fatalf("upload failure / exit order = %d / %d", failedAt, exitedAt)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

type controlledUploader struct {
	started chan struct{}
	release chan struct{}
	once    sync.Once
}

func (u *controlledUploader) Upload(_ context.Context, _ string, content ResultContent) (ResultContent, error) {
	u.once.Do(func() { close(u.started) })
	<-u.release
	return ResultContent{Type: "resource", UploadID: "upload-1", MediaType: content.MediaType, Size: int64(len(content.Bytes))}, nil
}

// RUN-T-078 confirms result delivery finishes before a successful upload's exit acknowledgement.
func TestRuntimeSuccessfulUploadPrecedesExecutionExit(t *testing.T) {
	connection := newTestConnection()
	queueHandshake(connection)
	connection.in <- invocationEnvelope()
	uploader := &controlledUploader{started: make(chan struct{}), release: make(chan struct{})}
	r := newInvocationRuntime(t, connection, func(_ context.Context, call CapabilityCall, callContext CapabilityContext) (CapabilityResult, error) {
		callContext.ConfirmExecutionExit()
		return CapabilityResult{CallID: call.InvocationID, Contents: []ResultContent{{Type: "binary", Bytes: []byte("payload"), MediaType: "text/plain"}}}, nil
	})
	r.options.Resources = uploader
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	select {
	case <-uploader.started:
	case <-time.After(time.Second):
		cancel()
		t.Fatal("resource upload did not start")
	}
	if connection.sent(protocol.MessageType_COMPLETED) || connection.sent(protocol.MessageType_EXECUTION_EXITED) {
		close(uploader.release)
		cancel()
		t.Fatal("result or exit was published before resource upload completed")
	}
	close(uploader.release)
	frames := connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countFrames(frames, protocol.MessageType_COMPLETED) == 1 && countFrames(frames, protocol.MessageType_EXECUTION_EXITED) == 1
	})
	completedAt, exitedAt := -1, -1
	for index, frame := range frames {
		if frame.MessageType == protocol.MessageType_COMPLETED {
			completedAt = index
		}
		if frame.MessageType == protocol.MessageType_EXECUTION_EXITED {
			exitedAt = index
		}
	}
	if completedAt < 0 || exitedAt <= completedAt {
		cancel()
		t.Fatalf("completed / exit order = %d / %d", completedAt, exitedAt)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-062 verifies the Go runtime's environment gate and invocation journal component accessors.
func TestRuntimeGateAndJournalExposeCurrentScope(t *testing.T) {
	r, _ := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	environmentID, bound := r.gate.BoundEnvironment()
	if !bound || environmentID != "env" {
		t.Fatalf("bound environment = %q, %v", environmentID, bound)
	}
	if !r.journal.Start("scope-accessor").Created {
		t.Fatal("journal did not create invocation")
	}
	if entry, ok := r.journal.Get("scope-accessor"); !ok || entry.InvocationID != "scope-accessor" || entry.State != InvocationRunning {
		t.Fatalf("journal accessor = %+v, %v", entry, ok)
	}
}

var _ update.Runner = (*lateProgressUpdateRunner)(nil)
