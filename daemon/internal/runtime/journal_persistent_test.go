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
	journal, err := NewPersistentInvocationJournal(path, 4)
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

	restarted, err := NewPersistentInvocationJournal(path, 4)
	if err != nil {
		t.Fatal(err)
	}
	if start := restarted.Start("rejected"); start.Err != nil || start.Created || !start.Entry.Recovered || !start.Entry.Exited || start.Entry.Acknowledged || string(start.Entry.Terminal) != "replayable failure" {
		t.Fatalf("rejected invocation facts were not restored: %+v", start)
	}
	if acknowledged, err := restarted.AcknowledgeExit("rejected"); err != nil || !acknowledged {
		t.Fatalf("acknowledge restored exit proof: acknowledged=%t err=%v", acknowledged, err)
	}
	if _, exists := restarted.Get("rejected"); exists {
		t.Fatal("terminal, exit, and acknowledgement did not release the durable record")
	}
	if start := restarted.Start("uncertain"); start.Err != nil || start.Created || !start.Entry.Recovered || start.Entry.State != InvocationRunning {
		t.Fatalf("in-flight receipt was not restored as uncertain: %+v", start)
	}
	if acknowledged, err := restarted.AcknowledgeExit("uncertain"); err != nil || acknowledged {
		t.Fatalf("unknown exit fact was acknowledged: acknowledged=%t err=%v", acknowledged, err)
	}
	if exited, err := restarted.ConfirmExitDurable("uncertain"); err != nil || !exited {
		t.Fatalf("record early exit fact: exited=%t err=%v", exited, err)
	}
	if acknowledged, err := restarted.AcknowledgeExit("uncertain"); err != nil || !acknowledged {
		t.Fatalf("record early acknowledgement: acknowledged=%t err=%v", acknowledged, err)
	}
	afterEarlyAck, err := NewPersistentInvocationJournal(path, 4)
	if err != nil {
		t.Fatal(err)
	}
	if start := afterEarlyAck.Start("uncertain"); start.Err != nil || start.Created || !start.Entry.Acknowledged || !start.Entry.Exited {
		t.Fatalf("early acknowledgement was not restored before terminal: %+v", start)
	}
	if completed, err := afterEarlyAck.CompleteDurable("uncertain", InvocationFailed, nil, false); err != nil || !completed {
		t.Fatalf("complete after early ACK: completed=%t err=%v", completed, err)
	}
	if _, exists := afterEarlyAck.Get("uncertain"); exists {
		t.Fatal("early ACK did not wait for terminal before releasing its record")
	}
	if start := afterEarlyAck.Start("result"); start.Err != nil || start.Created || !start.Entry.Recovered || len(start.Entry.Terminal) != 0 {
		t.Fatalf("non-replayable terminal was not restored as a tombstone: %+v", start)
	}
	if acknowledged, err := afterEarlyAck.AcknowledgeExit("result"); err != nil || acknowledged {
		t.Fatalf("terminal without exit was acknowledged: acknowledged=%t err=%v", acknowledged, err)
	}
}
