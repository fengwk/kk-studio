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

type temporarySweeperFunc func(time.Duration) (int, error)

func (f temporarySweeperFunc) Sweep(ttl time.Duration) (int, error) { return f(ttl) }

type blockingIDEAdmission struct {
	entered         chan struct{}
	release         chan struct{}
	leaseStarted    chan time.Time
	commandReceived chan *protocol.IdeCommand
	calls           atomic.Int32
	hasViews        atomic.Bool
	canUpdate       bool
}

func (s *blockingIDEAdmission) HandleCommand(ctx context.Context, command *protocol.IdeCommand) *protocol.IdeResult {
	s.calls.Add(1)
	if s.commandReceived != nil {
		s.commandReceived <- command
	}
	select {
	case s.entered <- struct{}{}:
	default:
	}
	select {
	case <-s.release:
		return &protocol.IdeResult{Ok: boolPtr(true), Revision: int64Ptr(1)}
	case <-ctx.Done():
		return failedIDEResult("BUSY", "IDE command cancelled")
	}
}

func (s *blockingIDEAdmission) ApplyLease(_ *protocol.IdeLease, roundStartedAt time.Time) {
	if s.leaseStarted != nil {
		s.leaseStarted <- roundStartedAt
	}
}
func (s *blockingIDEAdmission) CanStartUpdate() bool { return s.canUpdate }
func (s *blockingIDEAdmission) HasActiveViews() bool { return s.hasViews.Load() }

type TypeError struct{ message string }

func (e *TypeError) Error() string { return e.message }

// RUN-T-097 confirms an inconsistent bootstrap configuration terminates instead of reconnecting.
func TestRuntimeFatalConfigurationDoesNotReconnect(t *testing.T) {
	initial := initialRuntimeConfig()
	status := NewStatusTracker()
	manager, err := NewEnvironmentConfigRuntime(EnvironmentConfigOptions{
		Initial: initial,
		Cache:   &testCacheWriter{},
		Prepare: (&testConfigurationPreparer{}).prepare,
		Status:  status,
	})
	if err != nil {
		t.Fatal(err)
	}
	connection := newTestConnection()
	connection.in <- &protocol.Envelope{MessageType: protocol.MessageType_SETTINGS, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{}}}}
	connection.in <- &protocol.Envelope{MessageType: protocol.MessageType_ENVIRONMENT_CONFIG, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(0), Config: configurationOf(initial)}}}
	factory := &sequenceConnectionFactory{connections: []Connection{connection}}
	r, err := NewRuntime(RuntimeOptions{EnvironmentID: "env", Connections: factory, InitialReconnectDelay: time.Millisecond, MaxReconnectDelay: 2 * time.Millisecond, EnvironmentConfig: manager, Status: status})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	if err := r.Run(ctx); !errors.Is(err, ErrRuntimeFatal) || err.Error() != "runtime entered a fatal state: environment configuration is inconsistent" {
		t.Fatalf("fatal configuration run error = %v", err)
	}
	if len(factory.connections) != 0 {
		t.Fatalf("connection was not consumed: %d remain", len(factory.connections))
	}
	select {
	case <-connection.closed:
	default:
		t.Fatal("fatal configuration did not close its connection")
	}
}

// RUN-T-096 verifies a forbidden server frame receives a diagnostic and closes its generation.
func TestRuntimeForbiddenInboundFrameDisconnects(t *testing.T) {
	r, connection := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	active := r.connection
	connection.in <- &protocol.Envelope{MessageType: protocol.MessageType_READY, EnvironmentId: stringPtr("env")}
	if err := r.serveConnection(context.Background(), active); !errors.Is(err, ErrForbiddenInboundFrame) {
		t.Fatalf("forbidden inbound frame error = %v", err)
	}
	r.dropConnection(active)
	select {
	case <-connection.closed:
	default:
		t.Fatal("forbidden inbound frame left the connection open")
	}
	frames := connection.frames()
	found := false
	for _, frame := range frames {
		if frame.MessageType == protocol.MessageType_ERROR && frame.GetError().GetMessage() == "daemon must not receive READY from server" {
			found = true
		}
	}
	if !found {
		t.Fatalf("forbidden inbound frame diagnostic missing: %+v", frames)
	}
}

// RUN-T-043 rejects lease frames when no IDE service is configured.
func TestRuntimeReportsMissingIDELeaseHandler(t *testing.T) {
	r, _ := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	if err := r.handle(context.Background(), ideLeaseEnvelope(1)); err == nil || err.Error() != "IDE lease requires a READY bound connection" {
		t.Fatalf("missing IDE lease handler error = %v", err)
	}
}

// RUN-T-090 rejects a temporary-resource policy until the environment is READY.
func TestRuntimeTemporaryResourcePolicyRequiresReady(t *testing.T) {
	r, connection := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	r.mu.Lock()
	r.ready = false
	r.mu.Unlock()
	err := r.handle(context.Background(), &protocol.Envelope{
		MessageType:   protocol.MessageType_TEMPORARY_RESOURCE_POLICY,
		EnvironmentId: stringPtr("env"),
		Payload: &protocol.Envelope_TemporaryResourcePolicy{TemporaryResourcePolicy: &protocol.TemporaryResourcePolicy{
			TemporaryResourceTtlSeconds: int64Ptr(7), TemporaryResourceCleanupIntervalSeconds: int64Ptr(1),
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if !connection.sent(protocol.MessageType_ERROR) {
		t.Fatal("pre-READY temporary resource policy did not receive an ERROR")
	}
	if ttl, interval, active := r.temporaryResourcePolicy(); ttl != 0 || interval != 0 || active {
		t.Fatalf("pre-READY policy changed active sweep configuration: ttl=%s interval=%s active=%t", ttl, interval, active)
	}
}

// RUN-T-130 retains the exact busy diagnostics provided by active IDE command and view state.
func TestRuntimeUpdateRejectsBusyIDEWithExactReason(t *testing.T) {
	cases := []struct {
		name       string
		commandNum int
		canStart   bool
		want       string
	}{
		{name: "IDE command", commandNum: 1, canStart: true, want: "update is busy: IDE command is in progress"},
		{name: "IDE views", canStart: false, want: "update is busy: IDE views, cleanup or data streams are active"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			options := runtimeOptionsForTest()
			options.Update = &testUpdateRunner{}
			options.IDECommandCount = func() int { return tc.commandNum }
			options.IDECanStartUpdate = func() bool { return tc.canStart }
			r, _ := newReadyRuntimeForTest(t, options)
			decision, err := r.updateOps.Decide(updateCommandForTest(), "1.0.0", true, r.updateBusyReason(options.Update))
			if err != nil {
				t.Fatal(err)
			}
			if decision.Action != update.ActionReject || decision.Receipt == nil || decision.Receipt.Message != tc.want {
				t.Fatalf("IDE busy decision = %+v, want rejection %q", decision, tc.want)
			}
		})
	}
}

// RUN-T-107 validates READY and configuration-switch admission for IDE work.
func TestRuntimeSuspendsIDECommandAndLeaseDuringConfigurationSwitch(t *testing.T) {
	ide := &blockingIDEAdmission{entered: make(chan struct{}), release: make(chan struct{}), canUpdate: true}
	options := runtimeOptionsForTest()
	options.IDE = ide
	r, connection := newReadyRuntimeForTest(t, options)
	r.mu.Lock()
	r.ready = false
	r.mu.Unlock()
	if err := r.handle(context.Background(), &protocol.Envelope{
		MessageType:  protocol.MessageType_IDE_COMMAND,
		InvocationId: stringPtr(testInvocationID),
		Payload:      &protocol.Envelope_IdeCommand{IdeCommand: &protocol.IdeCommand{}},
	}); err != nil {
		t.Fatalf("pre-READY IDE command error frame failed: %v", err)
	}
	if err := r.handle(context.Background(), ideLeaseEnvelope(1)); err == nil || err.Error() != "IDE lease requires a READY bound connection" {
		t.Fatalf("pre-READY IDE lease error = %v", err)
	}
	r.mu.Lock()
	r.ready = true
	r.mu.Unlock()
	r.admissionMu.Lock()
	r.admissionPaused = true
	r.admissionMu.Unlock()

	commandErr := r.handle(context.Background(), &protocol.Envelope{
		MessageType:  protocol.MessageType_IDE_COMMAND,
		InvocationId: stringPtr(testInvocationID),
		Payload:      &protocol.Envelope_IdeCommand{IdeCommand: &protocol.IdeCommand{}},
	})
	if commandErr != nil {
		t.Fatalf("switching IDE command error frame failed: %v", commandErr)
	}
	leaseErr := r.handle(context.Background(), ideLeaseEnvelope(1))
	if leaseErr != nil {
		t.Fatalf("switching IDE lease error frame failed: %v", leaseErr)
	}
	select {
	case <-ide.entered:
		t.Fatal("IDE command started while configuration admission was suspended")
	default:
	}
	if r.options.IDECommandCount() != 0 {
		t.Fatal("rejected IDE command was counted as active")
	}
	messages := []string{}
	for _, frame := range connection.frames() {
		if frame.MessageType == protocol.MessageType_ERROR {
			messages = append(messages, frame.GetError().GetMessage())
		}
	}
	want := map[string]int{
		"IDE command requires a READY bound connection":                    1,
		"environment configuration is switching; new work is not admitted": 2,
	}
	for _, message := range messages {
		want[message]--
	}
	for message, count := range want {
		if count != 0 {
			t.Fatalf("diagnostic %q count mismatch; messages=%v", message, messages)
		}
	}
}

// RUN-T-107 verifies candidate suspension, abort recovery, and successful reconnect hold for IDE writes.
func TestRuntimeConfigCandidateControlsIDEAdmission(t *testing.T) {
	initial := initialRuntimeConfig()
	status := NewStatusTracker()
	prepareStarted, releasePrepare := make(chan struct{}), make(chan struct{})
	manager, err := NewEnvironmentConfigRuntime(EnvironmentConfigOptions{
		Initial: initial,
		Cache:   &testCacheWriter{},
		Prepare: func(_ context.Context, candidate config.DaemonConfig, _ *config.DaemonConfig) (PreparedConfiguration, error) {
			if candidate.ConfigVersion == 2 {
				close(prepareStarted)
				<-releasePrepare
				return nil, errors.New("candidate preparation failed")
			}
			return &testPreparedConfiguration{config: candidate}, nil
		},
		Status: status,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		select {
		case <-releasePrepare:
		default:
			close(releasePrepare)
		}
		manager.Close()
	}()
	ide := &blockingIDEAdmission{entered: make(chan struct{}), release: make(chan struct{}), canUpdate: true}
	options := runtimeOptionsForTest()
	options.IDE = ide
	options.Status = status
	options.EnvironmentConfig = manager
	r, connection := newReadyRuntimeForTest(t, options)

	failed := make(chan ConfigApplyResult, 1)
	go func() {
		result, _ := manager.Submit(context.Background(), 2, changedConfiguration(initial, "https://failed.example"))
		failed <- result
	}()
	select {
	case <-prepareStarted:
	case <-time.After(time.Second):
		t.Fatal("candidate preparation did not start")
	}
	r.admissionMu.Lock()
	pausedDuringPrepare := r.admissionPaused
	r.admissionMu.Unlock()
	if !pausedDuringPrepare {
		t.Fatal("IDE admission was not paused before endpoint candidate preparation")
	}
	if err := r.handle(context.Background(), &protocol.Envelope{
		MessageType:  protocol.MessageType_IDE_COMMAND,
		InvocationId: stringPtr(testInvocationID),
		Payload:      &protocol.Envelope_IdeCommand{IdeCommand: &protocol.IdeCommand{}},
	}); err != nil {
		t.Fatalf("switching IDE command error frame failed: %v", err)
	}
	close(releasePrepare)
	select {
	case result := <-failed:
		if result.Kind != ConfigFailed {
			t.Fatalf("failed candidate result = %+v", result)
		}
	case <-time.After(time.Second):
		t.Fatal("failed candidate did not converge")
	}
	r.admissionMu.Lock()
	pausedAfterFailure := r.admissionPaused
	r.admissionMu.Unlock()
	if pausedAfterFailure {
		t.Fatal("failed candidate did not restore prior IDE admission")
	}

	commandDone := make(chan error, 1)
	go func() {
		commandDone <- r.handle(context.Background(), &protocol.Envelope{
			MessageType:  protocol.MessageType_IDE_COMMAND,
			InvocationId: stringPtr(testInvocationID),
			Payload:      &protocol.Envelope_IdeCommand{IdeCommand: &protocol.IdeCommand{}},
		})
	}()
	select {
	case <-ide.entered:
	case <-time.After(time.Second):
		t.Fatal("IDE command was not admitted after candidate failure")
	}
	close(ide.release)
	if err := <-commandDone; err != nil {
		t.Fatalf("IDE command after candidate failure: %v", err)
	}

	result, err := manager.Submit(context.Background(), 3, changedConfiguration(initial, "https://applied.example"))
	if err != nil || result.Kind != ConfigApplied {
		t.Fatalf("successful candidate result = %+v, %v", result, err)
	}
	r.admissionMu.Lock()
	pausedAfterApply := r.admissionPaused
	r.admissionMu.Unlock()
	if !pausedAfterApply {
		t.Fatal("applied endpoint change did not keep IDE admission paused for reconnect")
	}
	r.mu.RLock()
	connectionDropped := r.connection == nil
	r.mu.RUnlock()
	if !connectionDropped {
		t.Fatal("endpoint change did not clear the old control connection")
	}
	select {
	case <-connection.closed:
	default:
		t.Fatal("endpoint change did not close the old control connection")
	}
}

// RUN-T-042/052/130 verifies update admission, duplicate suppression, and OPEN_VIEW rejection.
func TestRuntimeDerivesUpdateAdmissionFromIDEService(t *testing.T) {
	ide := &blockingIDEAdmission{entered: make(chan struct{}, 1), release: make(chan struct{}), canUpdate: true}
	options := runtimeOptionsForTest()
	options.IDE = ide
	options.Update = &testUpdateRunner{}
	r, connection := newReadyRuntimeForTest(t, options)
	invocationID := "123e4567-e89b-42d3-a456-426614174000"
	command := &protocol.IdeCommand{Action: protocol.IdeAction_LIST_VIEWS}
	frame := func(action protocol.IdeAction, id string) *protocol.Envelope {
		return &protocol.Envelope{MessageType: protocol.MessageType_IDE_COMMAND, InvocationId: stringPtr(id), Payload: &protocol.Envelope_IdeCommand{IdeCommand: &protocol.IdeCommand{Action: action}}}
	}
	if err := r.handle(context.Background(), frame(command.Action, invocationID)); err != nil {
		t.Fatal(err)
	}
	select {
	case <-ide.entered:
	case <-time.After(time.Second):
		t.Fatal("IDE command did not start")
	}
	if err := r.handle(context.Background(), frame(command.Action, invocationID)); err != nil {
		t.Fatal(err)
	}
	if got := ide.calls.Load(); got != 1 {
		t.Fatalf("duplicate invocation executions = %d, want 1", got)
	}
	if got := r.options.IDECommandCount(); got != 1 {
		t.Fatalf("active IDE command count = %d, want 1", got)
	}
	if got := r.updateBusyReason(nil); got != "IDE command is in progress" {
		t.Fatalf("active IDE command busy reason = %q", got)
	}
	if err := r.handle(context.Background(), updateEnvelope(testUpdateOperationID, "2.0.0")); err != nil {
		t.Fatalf("IDE-busy update error receipt failed: %v", err)
	}
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_UPDATE_RESULT && frame.GetUpdateResult().GetOperationId() == testUpdateOperationID {
				return frame.GetUpdateResult().GetPhase() == protocol.UpdatePhase_UPDATE_FAILED && frame.GetUpdateResult().GetMessage() == "update is busy: IDE command is in progress"
			}
		}
		return false
	})
	decision, err := r.updateOps.Decide(update.Command{OperationID: "123e4567-e89b-42d3-a456-426614174099", TargetVersion: "3.0.0"}, "1.0.0", true, "")
	if err != nil || decision.Action != update.ActionAdmit {
		t.Fatalf("update admission decision = %+v, %v", decision, err)
	}
	if err := r.handle(context.Background(), frame(protocol.IdeAction_OPEN_VIEW, "123e4567-e89b-42d3-a456-426614174001")); err != nil {
		t.Fatal(err)
	}
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, received := range frames {
			if received.MessageType == protocol.MessageType_IDE_RESULT && received.GetIdeResult().GetCode() == "BUSY" && received.GetIdeResult().GetMessage() == "environment update is in progress" {
				return true
			}
		}
		return false
	})
	close(ide.release)
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, received := range frames {
			if received.MessageType == protocol.MessageType_IDE_RESULT && received.GetInvocationId() == invocationID {
				return true
			}
		}
		return false
	})
	r.ideWG.Wait()
	if got := r.options.IDECommandCount(); got != 0 {
		t.Fatalf("active IDE command count after completion = %d, want 0", got)
	}
	if ide.calls.Load() != 1 {
		t.Fatalf("IDE commands executed = %d, want 1", ide.calls.Load())
	}
	ide.canUpdate = false
	if got := r.updateBusyReason(nil); got != "IDE views, cleanup or data streams are active" {
		t.Fatalf("IDE service busy reason = %q", got)
	}
}

// IDE commands are not part of the Agent journal and are cancelled when their connection ends.
func TestRuntimeIDECommandCancelsOnDisconnect(t *testing.T) {
	ide := &blockingIDEAdmission{entered: make(chan struct{}, 1), release: make(chan struct{}), canUpdate: true}
	options := runtimeOptionsForTest()
	options.IDE = ide
	r, connection := newReadyRuntimeForTest(t, options)
	active := r.connection
	invocationID := "123e4567-e89b-42d3-a456-426614174000"
	if err := r.handle(context.Background(), &protocol.Envelope{MessageType: protocol.MessageType_IDE_COMMAND, InvocationId: stringPtr(invocationID), Payload: &protocol.Envelope_IdeCommand{IdeCommand: &protocol.IdeCommand{Action: protocol.IdeAction_CLOSE_VIEW, Discard: boolPtr(false)}}}); err != nil {
		t.Fatal(err)
	}
	select {
	case <-ide.entered:
	case <-time.After(time.Second):
		t.Fatal("IDE command did not start")
	}
	r.dropConnection(active)
	r.ideWG.Wait()
	select {
	case <-connection.closed:
	default:
		t.Fatal("connection was not closed")
	}
	if _, exists := r.journal.Get(invocationID); exists {
		t.Fatal("IDE command was recorded in the Agent journal")
	}
	if got := r.options.IDECommandCount(); got != 0 {
		t.Fatalf("active IDE command count after disconnect = %d, want 0", got)
	}
}

// RUN-T-108 verifies shutdown cancels and waits for IDE commands before Run returns.
func TestRuntimeIDECommandCancelsAndWaitsForShutdown(t *testing.T) {
	connection := newTestConnection()
	ide := &blockingIDEAdmission{entered: make(chan struct{}, 1), release: make(chan struct{}), canUpdate: true}
	options := runtimeOptionsForTest()
	options.Connections = fixedConnectionFactory{connection: connection}
	options.IDE = ide
	connection.in <- &protocol.Envelope{MessageType: protocol.MessageType_SETTINGS, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{}}}}
	connection.in <- &protocol.Envelope{MessageType: protocol.MessageType_ENVIRONMENT_CONFIG, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(1), Config: &protocol.DaemonConfiguration{StudioUrl: "https://studio.example"}}}}
	invocationID := "123e4567-e89b-42d3-a456-426614174000"
	connection.in <- &protocol.Envelope{MessageType: protocol.MessageType_IDE_COMMAND, EnvironmentId: stringPtr("env"), InvocationId: stringPtr(invocationID), Payload: &protocol.Envelope_IdeCommand{IdeCommand: &protocol.IdeCommand{Action: protocol.IdeAction_LIST_VIEWS}}}
	r, err := NewRuntime(options)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	select {
	case <-ide.entered:
	case <-time.After(time.Second):
		cancel()
		t.Fatal("IDE command did not start")
	}
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("runtime returned before IDE command cancellation converged")
	}
	if got := r.options.IDECommandCount(); got != 0 {
		t.Fatalf("active IDE command count after shutdown = %d, want 0", got)
	}
	for _, frame := range connection.frames() {
		if frame.MessageType == protocol.MessageType_IDE_RESULT && frame.GetInvocationId() == invocationID {
			t.Fatal("shutdown sent a stale IDE command result")
		}
	}
}

// RUN-T-099/100 hot-updates the sweeper policy and reports failures without exposing their messages.
func TestRuntimeTemporaryResourceSweeperHotPolicyAndSafeFailure(t *testing.T) {
	connection := newTestConnection()
	connection.in <- &protocol.Envelope{MessageType: protocol.MessageType_SETTINGS, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{
		TemporaryResourceTtlSeconds: int64Ptr(7), TemporaryResourceCleanupIntervalSeconds: int64Ptr(60),
	}}}}
	connection.in <- &protocol.Envelope{MessageType: protocol.MessageType_ENVIRONMENT_CONFIG, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{Version: int64Ptr(1), Config: &protocol.DaemonConfiguration{StudioUrl: "https://studio.example"}}}}
	seenTTL := make(chan time.Duration, 1)
	reported := make(chan error, 1)
	factory := &sequenceConnectionFactory{connections: []Connection{connection}}
	options := runtimeOptionsForTest()
	options.Connections = factory
	options.InitialReconnectDelay = time.Millisecond
	options.MaxReconnectDelay = 2 * time.Millisecond
	options.TemporaryResources = temporarySweeperFunc(func(ttl time.Duration) (int, error) {
		select {
		case seenTTL <- ttl:
		default:
		}
		return 0, &TypeError{message: "sweep failed at /tmp/private"}
	})
	options.ReportError = func(err error) { reported <- err }
	r, err := NewRuntime(options)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	connection.waitFor(t, func(frames []*protocol.Envelope) bool { return countFrames(frames, protocol.MessageType_READY) == 1 })
	connection.in <- &protocol.Envelope{MessageType: protocol.MessageType_TEMPORARY_RESOURCE_POLICY, EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_TemporaryResourcePolicy{TemporaryResourcePolicy: &protocol.TemporaryResourcePolicy{
		TemporaryResourceTtlSeconds: int64Ptr(9), TemporaryResourceCleanupIntervalSeconds: int64Ptr(1),
	}}}
	select {
	case got := <-seenTTL:
		if got != 9*time.Second {
			t.Fatalf("sweep TTL = %s, want 9s", got)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("temporary resource sweeper did not run")
	}
	select {
	case err := <-reported:
		if err.Error() != "daemon temporary resource sweep failed: TypeError" {
			t.Fatalf("unsafe sweep diagnostic = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("temporary resource sweep failure was not reported")
	}
	if !connection.sent(protocol.MessageType_READY) {
		t.Fatal("sweeper failure disrupted the READY connection")
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
