package runtime

import "testing"

// RUN-T-222/223/224/226/227 asserts terminal transitions, state mapping, and independent exit evidence.
func TestJournalTerminalStateAndExitEvidenceEdges(t *testing.T) {
	if _, err := NewInvocationJournal(0); err == nil {
		t.Fatal("journal accepted zero retention capacity")
	}
	journal, err := NewInvocationJournal(3)
	if err != nil {
		t.Fatal(err)
	}
	if journal.Complete("missing", InvocationFailed, []byte("failed")) {
		t.Fatal("unknown invocation was completed")
	}
	for _, terminal := range []struct {
		state InvocationState
		want  InvocationState
	}{
		{InvocationCompleted, InvocationCompleted},
		{InvocationFailed, InvocationFailed},
		{InvocationCancelled, InvocationCancelled},
	} {
		started := journal.Start(string(terminal.state))
		if !started.Created {
			t.Fatalf("start %q failed", terminal.state)
		}
		if journal.Complete(string(terminal.state), InvocationRunning, []byte("not terminal")) {
			t.Fatalf("RUNNING state completed as terminal for %q", terminal.state)
		}
		if !journal.Complete(string(terminal.state), terminal.state, []byte(string(terminal.state))) {
			t.Fatalf("terminal transition %q failed", terminal.state)
		}
		if journal.Complete(string(terminal.state), InvocationFailed, []byte("replacement")) {
			t.Fatalf("terminal state %q was replaced", terminal.state)
		}
		entry, ok := journal.Get(string(terminal.state))
		if !ok || entry.State != terminal.want || string(entry.Terminal) != string(terminal.state) {
			t.Fatalf("terminal mapping for %q = %+v, present=%t", terminal.state, entry, ok)
		}
	}
	if journal.ConfirmExit("unknown") {
		t.Fatal("exit was confirmed for unknown invocation")
	}
	if !journal.ConfirmExit(string(InvocationCompleted)) {
		t.Fatal("exit confirmation did not apply to a terminal invocation")
	}
	entry, ok := journal.Get(string(InvocationCompleted))
	if !ok || !entry.Exited || entry.State != InvocationCompleted {
		t.Fatalf("exit confirmation changed terminal state: %+v", entry)
	}
	if journal.ConfirmExit(string(InvocationCompleted)) {
		t.Fatal("duplicate exit confirmation succeeded")
	}
}
