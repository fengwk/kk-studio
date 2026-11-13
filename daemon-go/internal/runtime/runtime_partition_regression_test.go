package runtime

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"

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

// RUN-T-052 verifies in-flight deduplication and rejects OPEN_VIEW while update admission is held.
func TestRuntimeDerivesUpdateAdmissionFromIDEService(t *testing.T) {
	ide := &blockingIDEAdmission{entered: make(chan struct{}, 1), release: make(chan struct{}), canUpdate: true}
	options := runtimeOptionsForTest()
	options.IDE = ide
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

	if _, err := r.updateOps.Decide(updateCommandForTest(), "1.0.0", true, ""); err != nil {
		t.Fatal(err)
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
