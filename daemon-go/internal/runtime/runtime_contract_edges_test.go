package runtime

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"google.golang.org/protobuf/proto"
)

// RUN-T-069 ensures a terminal result is not an execution-exit confirmation.
func TestRuntimeDoesNotConfirmExitWithoutExecutorConfirmation(t *testing.T) {
	r, connection := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	if !r.journal.Start("terminal-without-exit").Created {
		t.Fatal("invocation was not started")
	}
	if err := r.finish("terminal-without-exit", protocol.MessageType_FAILED, &protocol.Envelope_Failed{Failed: &protocol.Failed{}}, InvocationFailed); err != nil {
		t.Fatal(err)
	}
	if got := countFrames(connection.frames(), protocol.MessageType_FAILED); got != 1 {
		t.Fatalf("FAILED frames = %d, want 1", got)
	}
	if got := countFrames(connection.frames(), protocol.MessageType_EXECUTION_EXITED); got != 0 {
		t.Fatalf("EXECUTION_EXITED frames = %d, want 0 before executor confirmation", got)
	}
}

// A delivered terminal result must release protected execution before update admission.
func TestRuntimeTerminalReleasesProtectedExecution(t *testing.T) {
	r, _ := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	r.callsMu.Lock()
	r.calls["terminal"] = &runningCall{cancel: func() {}}
	r.callsMu.Unlock()
	if !r.journal.Start("terminal").Created {
		t.Fatal("invocation was not started")
	}
	if err := r.finish("terminal", protocol.MessageType_FAILED, &protocol.Envelope_Failed{Failed: &protocol.Failed{}}, InvocationFailed); err != nil {
		t.Fatal(err)
	}
	r.callsMu.Lock()
	_, active := r.calls["terminal"]
	r.callsMu.Unlock()
	if active {
		t.Fatal("terminal invocation remained protected")
	}
	if reason := r.updateBusyReason(nil); reason != "" {
		t.Fatalf("update busy reason=%q after terminal", reason)
	}
}

// RUN-T-071 replays the stored terminal and exit fact in response to CANCEL after completion.
func TestRuntimeCancelReplaysStoredExitAfterTerminal(t *testing.T) {
	r, connection := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	if !r.journal.Start("cancel-after-terminal").Created {
		t.Fatal("invocation was not started")
	}
	terminal := &protocol.Envelope{MessageType: protocol.MessageType_FAILED, InvocationId: stringPtr("cancel-after-terminal"), Payload: &protocol.Envelope_Failed{Failed: &protocol.Failed{Message: stringPtr("failed")}}}
	encoded, err := proto.Marshal(terminal)
	if err != nil {
		t.Fatal(err)
	}
	if !r.journal.Complete("cancel-after-terminal", InvocationFailed, encoded) || !r.journal.ConfirmExit("cancel-after-terminal") {
		t.Fatal("terminal result and exit confirmation were not recorded")
	}
	if err := r.handleCancel(&protocol.Envelope{InvocationId: stringPtr("cancel-after-terminal")}); err != nil {
		t.Fatal(err)
	}
	if got := countFrames(connection.frames(), protocol.MessageType_FAILED); got != 1 {
		t.Fatalf("replayed FAILED frames = %d, want 1", got)
	}
	if got := countFrames(connection.frames(), protocol.MessageType_EXECUTION_EXITED); got != 1 {
		t.Fatalf("replayed EXECUTION_EXITED frames = %d, want 1", got)
	}
}

type waitingUploader struct {
	started   chan struct{}
	cancelled chan error
}

func (u waitingUploader) Upload(ctx context.Context, _ string, _ ResultContent) (ResultContent, error) {
	close(u.started)
	<-ctx.Done()
	u.cancelled <- ctx.Err()
	return ResultContent{}, ctx.Err()
}

// RUN-T-077 ensures execution timeout remains active through result resource delivery.
func TestRuntimeResourceUploadUsesInvocationDeadline(t *testing.T) {
	c := newTestConnection()
	queueHandshake(c)
	invoke := invocationEnvelope()
	invoke.GetInvoke().TimeoutMillis = int64Ptr(100)
	c.in <- invoke
	uploader := waitingUploader{started: make(chan struct{}), cancelled: make(chan error, 1)}
	r := newInvocationRuntime(t, c, func(_ context.Context, call CapabilityCall, _ CapabilityContext) (CapabilityResult, error) {
		return CapabilityResult{CallID: call.InvocationID, Contents: []ResultContent{{Type: "binary", Bytes: []byte("payload")}}}, nil
	})
	r.options.Resources = uploader
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	c.waitFor(t, func(frames []*protocol.Envelope) bool { return countFrames(frames, protocol.MessageType_FAILED) == 1 })
	select {
	case <-uploader.started:
	default:
		t.Fatal("resource upload did not start")
	}
	select {
	case err := <-uploader.cancelled:
		if err != context.DeadlineExceeded {
			t.Fatalf("uploader context error = %v, want deadline exceeded", err)
		}
	case <-time.After(time.Second):
		t.Fatal("invocation deadline did not cancel resource upload")
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-059 verifies an unknown inbound frame produces a safe ERROR response.
func TestRuntimeUnknownInboundFrameSendsError(t *testing.T) {
	r, connection := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	if err := r.handle(context.Background(), &protocol.Envelope{MessageType: protocol.MessageType(999)}); err != nil {
		t.Fatal(err)
	}
	frames := connection.frames()
	if len(frames) != 1 || frames[0].MessageType != protocol.MessageType_ERROR || frames[0].GetError().GetMessage() != "unsupported inbound message type" {
		t.Fatalf("unknown frame response = %+v", frames)
	}
}

// RUN-T-068 confirms exit is published only after the executor returns, even if it confirmed early.
func TestRuntimeExecutionExitWaitsForExecutorReturn(t *testing.T) {
	c := newTestConnection()
	queueHandshake(c)
	c.in <- invocationEnvelope()
	confirmed, release := make(chan struct{}), make(chan struct{})
	r := newInvocationRuntime(t, c, func(_ context.Context, call CapabilityCall, callContext CapabilityContext) (CapabilityResult, error) {
		callContext.ConfirmExecutionExit()
		close(confirmed)
		<-release
		return CapabilityResult{CallID: call.InvocationID}, nil
	})
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	select {
	case <-confirmed:
	case <-time.After(time.Second):
		cancel()
		t.Fatal("executor did not confirm its scope exit")
	}
	if c.sent(protocol.MessageType_EXECUTION_EXITED) {
		close(release)
		cancel()
		t.Fatal("execution exit was published before the executor returned")
	}
	close(release)
	c.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_EXECUTION_EXITED {
				return true
			}
		}
		return false
	})
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-011/T-070 verifies RECOVER and duplicate INVOKE replay terminal state and its separate exit fact.
func TestRuntimeRecoverAndDuplicateInvokeReplayTerminalExit(t *testing.T) {
	r, connection := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	if !r.journal.Start(testInvocationID).Created {
		t.Fatal("invocation was not started")
	}
	terminal := &protocol.Envelope{MessageType: protocol.MessageType_FAILED, InvocationId: stringPtr(testInvocationID), Payload: &protocol.Envelope_Failed{Failed: &protocol.Failed{Message: stringPtr("failed")}}}
	encoded, err := proto.Marshal(terminal)
	if err != nil {
		t.Fatal(err)
	}
	if !r.journal.Complete(testInvocationID, InvocationFailed, encoded) || !r.journal.ConfirmExit(testInvocationID) {
		t.Fatal("terminal result and exit confirmation were not recorded")
	}
	if err := r.handleRecover(&protocol.Envelope{InvocationId: stringPtr("unknown")}); err != nil {
		t.Fatal(err)
	}
	if err := r.handleRecover(&protocol.Envelope{InvocationId: stringPtr(testInvocationID)}); err != nil {
		t.Fatal(err)
	}
	if err := r.handleInvoke(context.Background(), invocationEnvelope()); err != nil {
		t.Fatal(err)
	}
	frames := connection.frames()
	if countFrames(frames, protocol.MessageType_RECOVER_NOT_FOUND) != 1 || countFrames(frames, protocol.MessageType_FAILED) != 2 || countFrames(frames, protocol.MessageType_EXECUTION_EXITED) != 2 {
		t.Fatalf("recovery / duplicate INVOKE replay frames = %+v", frames)
	}
}

type pausedReconnectFactory struct {
	first            Connection
	second           Connection
	calls            atomic.Int32
	reconnectStarted chan struct{}
	allowReconnect   chan struct{}
}

func (f *pausedReconnectFactory) Connect(ctx context.Context, _ ControlObserver) (Connection, error) {
	if f.calls.Add(1) == 1 {
		return f.first, nil
	}
	close(f.reconnectStarted)
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-f.allowReconnect:
		return f.second, nil
	}
}

// RUN-T-012 replays a call completed while the control connection was down.
func TestRuntimeReplaysTerminalCompletedWhileDisconnected(t *testing.T) {
	first, second := newTestConnection(), newTestConnection()
	queueHandshake(first)
	first.in <- invocationEnvelope()
	queueHandshake(second)
	second.in <- &protocol.Envelope{MessageType: protocol.MessageType_RECOVER, EnvironmentId: stringPtr("env"), InvocationId: stringPtr(testInvocationID), Payload: &protocol.Envelope_Recover{Recover: &protocol.Recover{}}}
	factory := &pausedReconnectFactory{first: first, second: second, reconnectStarted: make(chan struct{}), allowReconnect: make(chan struct{})}
	started, release := make(chan struct{}), make(chan struct{})
	r, err := NewRuntime(RuntimeOptions{
		EnvironmentID: "env", DaemonVersion: "1.0.0", Connections: factory,
		InitialReconnectDelay: time.Millisecond, MaxReconnectDelay: 10 * time.Millisecond,
		ApplyConfig: func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: version}, nil
		},
		Capabilities: map[string]CapabilityExecutor{"echo": func(_ context.Context, call CapabilityCall, _ CapabilityContext) (CapabilityResult, error) {
			close(started)
			<-release
			return CapabilityResult{CallID: call.InvocationID}, nil
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	select {
	case <-started:
	case <-time.After(time.Second):
		cancel()
		t.Fatal("invocation did not start")
	}
	first.fail(errors.New("connection lost"))
	select {
	case <-factory.reconnectStarted:
	case <-time.After(time.Second):
		close(release)
		cancel()
		t.Fatal("runtime did not begin reconnecting")
	}
	close(release)
	deadline := time.After(time.Second)
	for {
		entry, ok := r.journal.Get(testInvocationID)
		if ok && entry.State == InvocationCompleted {
			break
		}
		select {
		case <-deadline:
			close(factory.allowReconnect)
			cancel()
			t.Fatal("invocation did not complete while disconnected")
		case <-time.After(time.Millisecond):
		}
	}
	if first.sent(protocol.MessageType_COMPLETED) {
		close(factory.allowReconnect)
		cancel()
		t.Fatal("completed result was sent on the disconnected connection")
	}
	close(factory.allowReconnect)
	second.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_COMPLETED && frame.GetEnvironmentId() == "env" {
				return true
			}
		}
		return false
	})
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-013 keeps an invocation RUNNING while its timed-out executor performs cooperative cleanup.
func TestRuntimeTimeoutDefersTerminalUntilExecutorReturns(t *testing.T) {
	c := newTestConnection()
	queueHandshake(c)
	invoke := invocationEnvelope()
	invoke.GetInvoke().TimeoutMillis = int64Ptr(25)
	c.in <- invoke
	started, cleanupStarted, cleanupRelease := make(chan struct{}), make(chan struct{}), make(chan struct{})
	r := newInvocationRuntime(t, c, func(ctx context.Context, _ CapabilityCall, _ CapabilityContext) (CapabilityResult, error) {
		close(started)
		<-ctx.Done()
		close(cleanupStarted)
		<-cleanupRelease
		return CapabilityResult{}, ctx.Err()
	})
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	select {
	case <-started:
	case <-time.After(time.Second):
		cancel()
		t.Fatal("executor did not start")
	}
	select {
	case <-cleanupStarted:
	case <-time.After(time.Second):
		close(cleanupRelease)
		cancel()
		t.Fatal("executor did not enter cleanup after timeout")
	}
	entry, ok := r.journal.Get(testInvocationID)
	if !ok || entry.State != InvocationRunning {
		close(cleanupRelease)
		cancel()
		t.Fatalf("journal state during cleanup = %v, want RUNNING", entry.State)
	}
	if c.sent(protocol.MessageType_FAILED) || c.sent(protocol.MessageType_CANCELLED) {
		close(cleanupRelease)
		cancel()
		t.Fatal("terminal frame was sent before executor cleanup completed")
	}
	close(cleanupRelease)
	frames := c.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_FAILED {
				return true
			}
		}
		return false
	})
	var timedOut bool
	for _, frame := range frames {
		if frame.MessageType == protocol.MessageType_FAILED {
			timedOut = frame.GetFailed().GetMessage() == "capability execution timed out after 25ms"
		}
	}
	if !timedOut {
		t.Fatal("timeout terminal did not preserve the contracted diagnostic")
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-014 preserves a successful result in each timeout/cancellation race after a committed side effect.
func TestRuntimePreservesSuccessfulResultAcrossCancellationRaces(t *testing.T) {
	for _, scenario := range []struct {
		name              string
		timeoutMillis     int64
		cancelBeforeTimer bool
		cancelAfterTimer  bool
	}{
		{name: "timeout", timeoutMillis: 30},
		{name: "cancel", timeoutMillis: 10000, cancelBeforeTimer: true},
		{name: "timeout-then-cancel", timeoutMillis: 30, cancelAfterTimer: true},
		{name: "cancel-then-timeout", timeoutMillis: 30, cancelBeforeTimer: true},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			c := newTestConnection()
			queueHandshake(c)
			invoke := invocationEnvelope()
			invoke.GetInvoke().TimeoutMillis = int64Ptr(scenario.timeoutMillis)
			c.in <- invoke
			started, cancellationObserved, release := make(chan struct{}), make(chan struct{}), make(chan struct{})
			var sideEffectCommitted atomic.Bool
			r := newInvocationRuntime(t, c, func(ctx context.Context, call CapabilityCall, _ CapabilityContext) (CapabilityResult, error) {
				sideEffectCommitted.Store(true)
				close(started)
				<-ctx.Done()
				close(cancellationObserved)
				<-release
				return CapabilityResult{CallID: call.InvocationID}, nil
			})
			ctx, cancel := context.WithCancel(context.Background())
			done := make(chan error, 1)
			go func() { done <- r.Run(ctx) }()
			select {
			case <-started:
			case <-time.After(time.Second):
				cancel()
				t.Fatal("executor did not start")
			}
			sendCancel := func() {
				c.in <- &protocol.Envelope{MessageType: protocol.MessageType_CANCEL, EnvironmentId: stringPtr("env"), InvocationId: stringPtr(testInvocationID), Payload: &protocol.Envelope_Cancel{Cancel: &protocol.Cancel{}}}
			}
			if scenario.cancelBeforeTimer {
				sendCancel()
			}
			select {
			case <-cancellationObserved:
			case <-time.After(time.Second):
				close(release)
				cancel()
				t.Fatal("timeout or CANCEL did not reach the active call")
			}
			if !sideEffectCommitted.Load() {
				close(release)
				cancel()
				t.Fatal("side effect was not committed before cancellation")
			}
			if scenario.cancelAfterTimer {
				sendCancel()
			}
			if scenario.name == "cancel-then-timeout" {
				time.Sleep(time.Duration(scenario.timeoutMillis+10) * time.Millisecond)
			}
			close(release)
			frames := c.waitFor(t, func(frames []*protocol.Envelope) bool {
				for _, frame := range frames {
					if frame.MessageType == protocol.MessageType_COMPLETED {
						return true
					}
				}
				return false
			})
			for _, frame := range frames {
				if frame.MessageType == protocol.MessageType_FAILED || frame.MessageType == protocol.MessageType_CANCELLED {
					cancel()
					t.Fatalf("successful capability result was replaced by %s", frame.MessageType)
				}
			}
			cancel()
			if err := <-done; err != nil {
				t.Fatal(err)
			}
		})
	}
}

// RUN-T-033 confirms an already-installed target does not invoke the update runner.
func TestRuntimeSameVersionUpdateDoesNotRunUpdater(t *testing.T) {
	r, _ := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	runner := &testUpdateRunner{}
	r.options.Update = runner
	r.options.DaemonVersion = "1.0.0"
	if err := r.handleUpdate(context.Background(), updateEnvelope(testUpdateOperationID, "1.0.0")); err != nil {
		t.Fatal(err)
	}
	if runner.runCalls.Load() != 0 || runner.stopCalls.Load() != 0 {
		t.Fatalf("already-installed update invoked runner: run=%d stop=%d", runner.runCalls.Load(), runner.stopCalls.Load())
	}
}

// RUN-T-039 asserts the same update-progress snapshot appears in heartbeat, status, and reconnect READY frames.
func TestRuntimeStatusProgressSnapshotsAcrossHeartbeatAndReconnect(t *testing.T) {
	first, second := newTestConnection(), newTestConnection()
	queueHandshake(first)
	queueHandshake(second)
	factory := &sequenceConnectionFactory{connections: []Connection{first, second}}
	r, err := NewRuntime(RuntimeOptions{
		EnvironmentID: "env", DaemonVersion: "1.0.0", Connections: factory,
		InitialReconnectDelay: time.Millisecond, MaxReconnectDelay: 10 * time.Millisecond,
		HeartbeatInterval: time.Millisecond,
		ApplyConfig: func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: version}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := r.status.BeginUpdate("2.0.0"); err != nil {
		t.Fatal(err)
	}
	if err := r.status.UpdateProgress(UpdateProgress{DownloadedBytes: 7, TotalBytes: int64Ptr(12)}); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	first.waitFor(t, func(frames []*protocol.Envelope) bool {
		readyIndex := -1
		for i, frame := range frames {
			if frame.MessageType == protocol.MessageType_READY {
				readyIndex = i
			}
			if readyIndex >= 0 && i > readyIndex && frame.MessageType == protocol.MessageType_HEARTBEAT {
				return true
			}
		}
		return false
	})
	if err := r.sendStatus(); err != nil {
		cancel()
		t.Fatal(err)
	}
	firstFrames := first.frames()
	var firstReady, firstHeartbeat, firstStatus *protocol.RuntimeSnapshot
	for _, frame := range firstFrames {
		switch frame.MessageType {
		case protocol.MessageType_READY:
			firstReady = frame.GetReady().GetRuntimeStatus()
		case protocol.MessageType_HEARTBEAT:
			firstHeartbeat = frame.GetHeartbeat().GetRuntimeStatus()
		case protocol.MessageType_RUNTIME_STATUS:
			firstStatus = frame.GetRuntimeStatus().GetRuntimeStatus()
		}
	}
	if firstReady == nil || firstHeartbeat == nil || firstStatus == nil || !proto.Equal(firstReady, firstHeartbeat) || !proto.Equal(firstReady, firstStatus) {
		cancel()
		t.Fatalf("initial runtime snapshots differ: READY=%+v HEARTBEAT=%+v STATUS=%+v", firstReady, firstHeartbeat, firstStatus)
	}
	first.fail(errors.New("connection lost"))
	secondFrames := second.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countFrames(frames, protocol.MessageType_READY) == 1
	})
	var secondReady *protocol.RuntimeSnapshot
	for _, frame := range secondFrames {
		if frame.MessageType == protocol.MessageType_READY {
			secondReady = frame.GetReady().GetRuntimeStatus()
		}
	}
	if secondReady == nil || !proto.Equal(firstReady, secondReady) {
		cancel()
		t.Fatalf("reconnect runtime snapshot = %+v, initial = %+v", secondReady, firstReady)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// RUN-T-072 withholds EXECUTION_EXITED until a cancelled executor reports and completes its scope exit.
func TestRuntimeCancelledCallPublishesExitAfterExecutorReturns(t *testing.T) {
	c := newTestConnection()
	queueHandshake(c)
	c.in <- invocationEnvelope()
	started, cancelled, release := make(chan struct{}), make(chan struct{}), make(chan struct{})
	r := newInvocationRuntime(t, c, func(ctx context.Context, call CapabilityCall, callContext CapabilityContext) (CapabilityResult, error) {
		close(started)
		<-ctx.Done()
		close(cancelled)
		<-release
		callContext.ConfirmExecutionExit()
		return CapabilityResult{}, ctx.Err()
	})
	runCtx, stopRuntime := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(runCtx) }()
	select {
	case <-started:
	case <-time.After(time.Second):
		stopRuntime()
		t.Fatal("executor did not start")
	}
	c.in <- &protocol.Envelope{MessageType: protocol.MessageType_CANCEL, EnvironmentId: stringPtr("env"), InvocationId: stringPtr(testInvocationID), Payload: &protocol.Envelope_Cancel{Cancel: &protocol.Cancel{}}}
	select {
	case <-cancelled:
	case <-time.After(time.Second):
		close(release)
		stopRuntime()
		t.Fatal("executor did not observe CANCEL")
	}
	if c.sent(protocol.MessageType_EXECUTION_EXITED) {
		close(release)
		stopRuntime()
		t.Fatal("exit confirmation was sent before the cancelled executor returned")
	}
	close(release)
	c.waitFor(t, func(frames []*protocol.Envelope) bool {
		return countFrames(frames, protocol.MessageType_EXECUTION_EXITED) == 1
	})
	stopRuntime()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
