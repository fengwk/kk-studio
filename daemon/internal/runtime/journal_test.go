package runtime

import "testing"

func completeJournalForTest(t *testing.T, journal *InvocationJournal, id string, state InvocationState, terminal []byte) bool {
	t.Helper()
	completed, err := journal.CompleteDurable(id, state, terminal, true)
	if err != nil {
		t.Fatal(err)
	}
	return completed
}

func confirmExitForTest(t *testing.T, journal *InvocationJournal, id string) bool {
	t.Helper()
	confirmed, err := journal.ConfirmExitDurable(id)
	if err != nil {
		t.Fatal(err)
	}
	return confirmed
}

// Checks that journal start is atomic and duplicates reuse the running entry.
func TestJournalDeduplicatesRunning(t *testing.T) {
	j, err := NewInvocationJournal(2)
	if err != nil {
		t.Fatal(err)
	}
	first := j.Start("one")
	second := j.Start("one")
	if !first.Created || second.Created || first.Entry.State != InvocationRunning || second.Entry.State != InvocationRunning {
		t.Fatal("unexpected deduplication result")
	}
}

// Capacity backpressure keeps unacknowledged facts, then frees a slot only after all exit facts arrive.
func TestJournalCapacityWaitsForExitAcknowledgement(t *testing.T) {
	journal, err := NewInvocationJournal(2)
	if err != nil {
		t.Fatal(err)
	}
	if start := journal.Start("active"); !start.Created || start.Err != nil {
		t.Fatalf("start active: %+v", start)
	}
	if start := journal.Start("old"); !start.Created || start.Err != nil {
		t.Fatalf("start old: %+v", start)
	}
	if !completeJournalForTest(t, journal, "old", InvocationCompleted, []byte("done")) {
		t.Fatal("completion failed")
	}
	if start := journal.Start("new"); start.Err != errInvocationJournalFull {
		t.Fatalf("unacknowledged terminal did not backpressure new invocation: %+v", start)
	}
	if confirmed, err := journal.ConfirmExitDurable("old"); err != nil || !confirmed {
		t.Fatalf("record exit: confirmed=%t err=%v", confirmed, err)
	}
	if acknowledged, err := journal.AcknowledgeExit("old"); err != nil || !acknowledged {
		t.Fatalf("record acknowledgement: acknowledged=%t err=%v", acknowledged, err)
	}
	if _, exists := journal.Get("old"); exists {
		t.Fatal("fully confirmed invocation retained its journal slot")
	}
	if start := journal.Start("new"); !start.Created || start.Err != nil {
		t.Fatalf("released journal slot was not reused: %+v", start)
	}
	if entry, exists := journal.Get("active"); !exists || entry.State != InvocationRunning {
		t.Fatal("running entry was evicted")
	}
}
