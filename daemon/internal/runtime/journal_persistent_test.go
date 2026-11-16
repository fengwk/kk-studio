package runtime

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// A restarted journal suppresses re-execution and preserves only replayable rejection evidence.
func TestPersistentInvocationJournalRebuildsFactsWithoutPersistingResults(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state", "invocations.json")
	journal, err := NewPersistentInvocationJournal(path, 1)
	if err != nil {
		t.Fatal(err)
	}
	if start := journal.Start("rejected"); start.Err != nil || !start.Created {
		t.Fatalf("record rejection receipt: %+v", start)
	}
	if completed, err := journal.CompleteWithExitDurable("rejected", InvocationFailed, []byte("replayable failure"), true); err != nil || !completed {
		t.Fatalf("record rejection and exit proof: completed=%t err=%v", completed, err)
	}
	if start := journal.Start("uncertain"); start.Err != nil || !start.Created {
		t.Fatalf("record in-flight receipt: %+v", start)
	}
	if start := journal.Start("result"); start.Err != nil || !start.Created {
		t.Fatalf("record completed receipt: %+v", start)
	}
	if _, err := journal.CompleteDurable("result", InvocationCompleted, []byte("secret result payload"), false); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), "secret result payload") {
		t.Fatal("non-replayable execution result was written to the journal")
	}

	restarted, err := NewPersistentInvocationJournal(path, 1)
	if err != nil {
		t.Fatal(err)
	}
	if start := restarted.Start("rejected"); start.Err != nil || start.Created || !start.Entry.Recovered || !start.Entry.Exited || string(start.Entry.Terminal) != "replayable failure" {
		t.Fatalf("rejected invocation facts were not restored: %+v", start)
	}
	if start := restarted.Start("uncertain"); start.Err != nil || start.Created || !start.Entry.Recovered || start.Entry.State != InvocationRunning {
		t.Fatalf("in-flight receipt was not restored as uncertain: %+v", start)
	}
	if start := restarted.Start("result"); start.Err != nil || start.Created || !start.Entry.Recovered || len(start.Entry.Terminal) != 0 {
		t.Fatalf("non-replayable terminal was not restored as a tombstone: %+v", start)
	}
}
