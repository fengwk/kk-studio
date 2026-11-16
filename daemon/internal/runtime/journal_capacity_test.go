package runtime

import (
	"path/filepath"
	"testing"
)

// Persistent journal capacity survives restart and never evicts an unacknowledged exit fact.
func TestPersistentJournalBackpressureAndAcknowledgedCollection(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state", "invocations.json")
	journal, err := NewPersistentInvocationJournal(path, 1)
	if err != nil {
		t.Fatal(err)
	}
	if start := journal.Start("first"); start.Err != nil || !start.Created {
		t.Fatalf("record first invocation: %+v", start)
	}
	if completed, err := journal.CompleteWithExitDurable("first", InvocationFailed, nil, false); err != nil || !completed {
		t.Fatalf("record first terminal and exit: completed=%t err=%v", completed, err)
	}
	if start := journal.Start("blocked"); start.Err != errInvocationJournalFull {
		t.Fatalf("full journal did not backpressure: %+v", start)
	}

	journal, err = NewPersistentInvocationJournal(path, 1)
	if err != nil {
		t.Fatal(err)
	}
	if start := journal.Start("blocked"); start.Err != errInvocationJournalFull {
		t.Fatalf("restart evicted unacknowledged fact: %+v", start)
	}
	if acknowledged, err := journal.AcknowledgeExit("first"); err != nil || !acknowledged {
		t.Fatalf("acknowledge first exit: acknowledged=%t err=%v", acknowledged, err)
	}
	if start := journal.Start("blocked"); start.Err != nil || !start.Created {
		t.Fatalf("acknowledged record did not free capacity: %+v", start)
	}
}
