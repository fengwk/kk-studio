package runtime

import (
	"context"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

type emptyConnectionFactory struct{}

func (emptyConnectionFactory) Connect(context.Context) (Connection, error) { return nil, nil }

// RUN-F-011 prevents a stopped runtime from being entered again and rejects empty connections.
func TestRuntimeRunLifecycleGuards(t *testing.T) {
	o := runtimeOptionsForTest()
	o.Connections = emptyConnectionFactory{}
	o.HeartbeatInterval = -1
	if _, err := NewRuntime(o); err == nil {
		t.Fatal("negative heartbeat was accepted")
	}
	o.HeartbeatInterval = 0
	r, err := NewRuntime(o)
	if err != nil {
		t.Fatal(err)
	}
	if err := r.Run(context.Background()); err == nil || err.Error() != "connection factory returned an empty connection" {
		t.Fatalf("empty connection error = %v", err)
	}
	if err := r.Run(context.Background()); err == nil || err.Error() != "runtime can only be run once" {
		t.Fatalf("second run error = %v", err)
	}
}

// RUN-T-060 prevents a stale connection generation from mutating runtime state.
func TestRuntimeServeConnectionRejectsEmptyAndStaleFrames(t *testing.T) {
	r, _ := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	first := newTestConnection()
	active := r.attachConnection(first)
	first.in <- nil
	if err := r.serveConnection(context.Background(), active); err == nil || err.Error() != "control connection returned an empty envelope" {
		t.Fatalf("empty envelope error = %v", err)
	}
	staleConnection := newTestConnection()
	staleConnection.in <- &protocol.Envelope{MessageType: protocol.MessageType_HEARTBEAT}
	stale := r.attachConnection(staleConnection)
	r.attachConnection(newTestConnection())
	if err := r.serveConnection(context.Background(), stale); err == nil || err.Error() != "stale control connection generation" {
		t.Fatalf("stale generation error = %v", err)
	}
}

// RUN-T-011 replays terminal state and its independent exit fact, while malformed terminal bytes fail closed.
func TestRuntimeReplayAndTerminalEdges(t *testing.T) {
	r, connection := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	if err := r.finish("missing", protocol.MessageType_FAILED, &protocol.Envelope_Failed{Failed: &protocol.Failed{}}, InvocationFailed); err != nil {
		t.Fatalf("duplicate terminal without an active journal record = %v", err)
	}
	if err := r.finish("unsupported", protocol.MessageType_FAILED, &protocol.Envelope_Settings{}, InvocationFailed); err == nil || err.Error() != "unsupported terminal payload" {
		t.Fatalf("unsupported terminal payload error = %v", err)
	}
	r.journal.Start("finished")
	terminal := &protocol.Envelope_Failed{Failed: &protocol.Failed{Message: stringPtr("failed")}}
	if err := r.finish("finished", protocol.MessageType_FAILED, terminal, InvocationFailed); err != nil {
		t.Fatal(err)
	}
	if err := r.journal.ConfirmExit("finished"); !err {
		t.Fatal("could not record exit confirmation")
	}
	entry, ok := r.journal.Get("finished")
	if !ok {
		t.Fatal("terminal entry disappeared")
	}
	if err := r.replay(entry); err != nil {
		t.Fatal(err)
	}
	if err := r.handleCancel(&protocol.Envelope{}); err != nil {
		t.Fatalf("CANCEL without invocation ID = %v", err)
	}
	if err := r.handleCancel(&protocol.Envelope{InvocationId: stringPtr("unknown")}); err != nil {
		t.Fatalf("CANCEL for unknown invocation = %v", err)
	}
	r.journal.Start("broken")
	r.journal.Complete("broken", InvocationFailed, []byte("not a protobuf"))
	broken, _ := r.journal.Get("broken")
	if err := r.replay(broken); err == nil || err.Error() != "journal terminal frame is invalid" {
		t.Fatalf("malformed journal frame error = %v", err)
	}
	if got := countFrames(connection.frames(), protocol.MessageType_FAILED); got != 2 {
		t.Fatalf("failed frames after replay=%d, want 2", got)
	}
}

// RUN-T-068/073 confirms exit publication is delayed until the executor reports its scope exit.
func TestRuntimeExitConfirmationAfterTerminal(t *testing.T) {
	r, connection := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	r.journal.Start("exit-after-terminal")
	if err := r.finish("exit-after-terminal", protocol.MessageType_COMPLETED, &protocol.Envelope_Completed{Completed: &protocol.Completed{}}, InvocationCompleted); err != nil {
		t.Fatal(err)
	}
	r.noteExecutionExit("exit-after-terminal")
	r.publishRequestedExit("exit-after-terminal")
	r.publishRequestedExit("exit-after-terminal")
	if got := countFrames(connection.frames(), protocol.MessageType_COMPLETED); got != 1 {
		t.Fatalf("completed terminals=%d, want one", got)
	}
	if got := countFrames(connection.frames(), protocol.MessageType_EXECUTION_EXITED); got != 1 {
		t.Fatalf("exit confirmations=%d, want one", got)
	}
	r.publishRequestedExit("no-exit")
}

// RUN-T-110 makes cancellation wait for a tracked invocation to return.
func TestRuntimeCancelAndWaitBlocksUntilCallReturns(t *testing.T) {
	r, _ := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	started, release := make(chan struct{}), make(chan struct{})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	finished := make(chan struct{})
	r.callsMu.Lock()
	r.calls["tracked"] = &runningCall{cancel: cancel}
	r.wg.Add(1)
	r.callsMu.Unlock()
	go func() {
		close(started)
		<-ctx.Done()
		<-release
		r.wg.Done()
		close(finished)
	}()
	<-started
	done := make(chan struct{})
	go func() { r.cancelAndWait(); close(done) }()
	select {
	case <-done:
		t.Fatal("cancelAndWait returned before the call converged")
	case <-time.After(time.Millisecond * 10):
	}
	close(release)
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("cancelAndWait did not converge")
	}
	<-finished
}
