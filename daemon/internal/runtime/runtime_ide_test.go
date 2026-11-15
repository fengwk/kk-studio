package runtime

import (
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// IDE command admission stays bounded and returns a correlated BUSY receipt at capacity.
func TestRuntimeIDECommandInFlightLimit(t *testing.T) {
	ide := &blockingIDEAdmission{entered: make(chan struct{}, maxInFlightIDECommands), release: make(chan struct{}), canUpdate: true}
	options := runtimeOptionsForTest()
	options.IDE = ide
	r, connection := newReadyRuntimeForTest(t, options)
	for index := 1; index <= maxInFlightIDECommands; index++ {
		if err := r.handle(context.Background(), ideCommandFrame(index, protocol.IdeAction_LIST_VIEWS, nil)); err != nil {
			t.Fatalf("accept IDE command %d: %v", index, err)
		}
	}
	for range maxInFlightIDECommands {
		select {
		case <-ide.entered:
		case <-time.After(time.Second):
			t.Fatal("admitted IDE commands did not start")
		}
	}
	if err := r.handle(context.Background(), ideCommandFrame(maxInFlightIDECommands+1, protocol.IdeAction_LIST_VIEWS, nil)); err != nil {
		t.Fatal(err)
	}
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_IDE_RESULT && frame.GetIdeResult().GetCode() == "BUSY" && frame.GetIdeResult().GetMessage() == "too many in-flight IDE commands" {
				return true
			}
		}
		return false
	})
	if got := ide.calls.Load(); got != maxInFlightIDECommands {
		t.Fatalf("executed commands at capacity = %d, want %d", got, maxInFlightIDECommands)
	}
	close(ide.release)
	r.ideWG.Wait()
}

// Heartbeats advertise a lease round only while the IDE service has active views.
func TestRuntimeHeartbeatPublishesLeaseRoundOnlyForActiveViews(t *testing.T) {
	ide := &blockingIDEAdmission{}
	ide.hasViews.Store(true)
	options := runtimeOptionsForTest()
	options.IDE = ide
	options.HeartbeatInterval = 5 * time.Millisecond
	r, connection := newReadyRuntimeForTest(t, options)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go r.heartbeatLoop(ctx, done)
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_HEARTBEAT && frame.GetHeartbeat().IdeLeaseRound != nil {
				return true
			}
		}
		return false
	})
	ide.hasViews.Store(false)
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		var heartbeats []*protocol.Envelope
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_HEARTBEAT {
				heartbeats = append(heartbeats, frame)
			}
		}
		return len(heartbeats) >= 2 && heartbeats[len(heartbeats)-1].GetHeartbeat().IdeLeaseRound == nil
	})
	cancel()
	<-done
}

// IDE_LEASE applies only once for the current fresh heartbeat round and uses its send time.
func TestRuntimeIDELeaseRequiresCurrentFreshRound(t *testing.T) {
	ide := &blockingIDEAdmission{leaseStarted: make(chan time.Time, 1), canUpdate: true}
	options := runtimeOptionsForTest()
	options.IDE = ide
	r, _ := newReadyRuntimeForTest(t, options)
	round, sentAt := r.newIDELeaseRound()
	lease := &protocol.IdeLease{Round: int64Ptr(round)}
	frame := &protocol.Envelope{MessageType: protocol.MessageType_IDE_LEASE, Payload: &protocol.Envelope_IdeLease{IdeLease: lease}}
	if err := r.handle(context.Background(), frame); err != nil {
		t.Fatal(err)
	}
	select {
	case got := <-ide.leaseStarted:
		if !got.Equal(sentAt) {
			t.Fatalf("lease applied from %s, want heartbeat send time %s", got, sentAt)
		}
	case <-time.After(time.Second):
		t.Fatal("fresh IDE lease was not applied")
	}
	if err := r.handle(context.Background(), frame); err == nil || err.Error() != "IDE_LEASE round is not the current unexpired round" {
		t.Fatalf("replayed lease error = %v", err)
	}

	round, _ = r.newIDELeaseRound()
	r.ideLeaseMu.Lock()
	r.ideLeaseSentAt = time.Now().Add(-ideLeaseRoundTTL)
	r.ideLeaseMu.Unlock()
	stale := &protocol.Envelope{MessageType: protocol.MessageType_IDE_LEASE, Payload: &protocol.Envelope_IdeLease{IdeLease: &protocol.IdeLease{Round: int64Ptr(round)}}}
	if err := r.handle(context.Background(), stale); err == nil || err.Error() != "IDE_LEASE round is not the current unexpired round" {
		t.Fatalf("expired lease error = %v", err)
	}
	if len(ide.leaseStarted) != 0 {
		t.Fatal("stale lease was applied")
	}
}

// IDE commands preserve binding identity when routed from their protocol envelope.
func TestRuntimePassesIDECommandBindingFields(t *testing.T) {
	ide := &blockingIDEAdmission{
		entered:         make(chan struct{}, 1),
		release:         make(chan struct{}),
		commandReceived: make(chan *protocol.IdeCommand, 1),
		canUpdate:       true,
	}
	options := runtimeOptionsForTest()
	options.IDE = ide
	r, _ := newReadyRuntimeForTest(t, options)
	command := &protocol.IdeCommand{
		Action:                   protocol.IdeAction_OPEN_VIEW,
		BrowserSessionId:         stringPtr("browser-session"),
		BindingEpoch:             int64Ptr(7),
		ExpectedBindingEpoch:     int64Ptr(8),
		ExpectedDaemonInstanceId: stringPtr("daemon-instance"),
	}
	if err := r.handle(context.Background(), &protocol.Envelope{
		MessageType: protocol.MessageType_IDE_COMMAND, InvocationId: stringPtr(testIDEInvocationID(1)),
		Payload: &protocol.Envelope_IdeCommand{IdeCommand: command},
	}); err != nil {
		t.Fatal(err)
	}
	select {
	case got := <-ide.commandReceived:
		if got.GetAction() != command.GetAction() || got.GetBrowserSessionId() != "browser-session" || got.GetBindingEpoch() != 7 || got.GetExpectedBindingEpoch() != 8 || got.GetExpectedDaemonInstanceId() != "daemon-instance" {
			t.Fatalf("routed IDE command = %v", got)
		}
	case <-time.After(time.Second):
		t.Fatal("IDE service did not receive the command")
	}
	close(ide.release)
	r.ideWG.Wait()
}

// IDE commands use the configured deadline, except normal CLOSE_VIEW which may wait for a person.
func TestRuntimeIDECommandTimeoutExcludesNormalClose(t *testing.T) {
	ide := &blockingIDEAdmission{entered: make(chan struct{}, 2), release: make(chan struct{}), canUpdate: true}
	options := runtimeOptionsForTest()
	options.IDE = ide
	options.IDECommandTimeout = 20 * time.Millisecond
	r, connection := newReadyRuntimeForTest(t, options)
	if err := r.handle(context.Background(), ideCommandFrame(1, protocol.IdeAction_LIST_VIEWS, nil)); err != nil {
		t.Fatal(err)
	}
	select {
	case <-ide.entered:
	case <-time.After(time.Second):
		t.Fatal("timed IDE command did not start")
	}
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_IDE_RESULT && frame.GetInvocationId() == testIDEInvocationID(1) && frame.GetIdeResult().GetCode() == "BUSY" {
				return true
			}
		}
		return false
	})
	r.ideWG.Wait()

	discard := false
	if err := r.handle(context.Background(), ideCommandFrame(2, protocol.IdeAction_CLOSE_VIEW, &discard)); err != nil {
		t.Fatal(err)
	}
	select {
	case <-ide.entered:
	case <-time.After(time.Second):
		t.Fatal("normal close command did not start")
	}
	time.Sleep(50 * time.Millisecond)
	if got := r.options.IDECommandCount(); got != 1 {
		t.Fatalf("normal close was timed out; active command count = %d", got)
	}
	close(ide.release)
	r.ideWG.Wait()
}

// An absent production IDE service is represented by a correlated IDE_RESULT, not a generic ERROR.
func TestRuntimeIDECommandReportsUnavailableService(t *testing.T) {
	r, connection := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	id := testIDEInvocationID(1)
	if err := r.handle(context.Background(), ideCommandFrame(1, protocol.IdeAction_LIST_VIEWS, nil)); err != nil {
		t.Fatal(err)
	}
	connection.waitFor(t, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_IDE_RESULT && frame.GetInvocationId() == id {
				return frame.GetIdeResult().GetCode() == "UNAVAILABLE" && frame.GetIdeResult().GetMessage() == "IDE service is unavailable"
			}
		}
		return false
	})
}

func ideCommandFrame(index int, action protocol.IdeAction, discard *bool) *protocol.Envelope {
	return &protocol.Envelope{
		MessageType:  protocol.MessageType_IDE_COMMAND,
		InvocationId: stringPtr(testIDEInvocationID(index)),
		Payload:      &protocol.Envelope_IdeCommand{IdeCommand: &protocol.IdeCommand{Action: action, Discard: discard}},
	}
}

func testIDEInvocationID(index int) string {
	return fmt.Sprintf("00000000-0000-4000-8000-%012d", index)
}
