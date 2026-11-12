package runtime

import "testing"

// RUN-T-221 checks that journal start is atomic and duplicates reuse the running entry.
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

// RUN-T-225 ensures terminal retention is bounded without evicting active invocations.
func TestJournalEvictsOldestTerminalOnly(t *testing.T) {
	j, _ := NewInvocationJournal(1)
	j.Start("active")
	j.Start("old")
	if !j.Complete("old", InvocationCompleted, []byte("done")) {
		t.Fatal("completion failed")
	}
	j.Start("new")
	if !j.Complete("new", InvocationFailed, []byte("bad")) {
		t.Fatal("completion failed")
	}
	if _, ok := j.Get("old"); ok {
		t.Fatal("old terminal retained")
	}
	if e, ok := j.Get("active"); !ok || e.State != InvocationRunning {
		t.Fatal("running entry evicted")
	}
}
