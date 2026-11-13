package runtime

import (
	"errors"
	"sync"
)

type InvocationState string

const (
	InvocationRunning   InvocationState = "RUNNING"
	InvocationCompleted InvocationState = "COMPLETED"
	InvocationFailed    InvocationState = "FAILED"
	InvocationCancelled InvocationState = "CANCELLED"
)

type JournalEntry struct {
	InvocationID string
	State        InvocationState
	Terminal     []byte
	Exited       bool
}
type JournalStart struct {
	Entry   JournalEntry
	Created bool
}

// InvocationJournal is a bounded in-memory deduplication journal; running work is never evicted.
type InvocationJournal struct {
	mu            sync.Mutex
	capacity      int
	entries       map[string]JournalEntry
	terminalOrder []string
}

func NewInvocationJournal(capacity int) (*InvocationJournal, error) {
	if capacity <= 0 {
		return nil, errors.New("journal capacity must be positive")
	}
	return &InvocationJournal{capacity: capacity, entries: make(map[string]JournalEntry)}, nil
}
func (j *InvocationJournal) Start(id string) JournalStart {
	j.mu.Lock()
	defer j.mu.Unlock()
	if e, ok := j.entries[id]; ok {
		return JournalStart{Entry: cloneEntry(e)}
	}
	e := JournalEntry{InvocationID: id, State: InvocationRunning}
	j.entries[id] = e
	return JournalStart{Entry: cloneEntry(e), Created: true}
}
func (j *InvocationJournal) Complete(id string, state InvocationState, terminal []byte) bool {
	if state != InvocationCompleted && state != InvocationFailed && state != InvocationCancelled {
		return false
	}
	j.mu.Lock()
	defer j.mu.Unlock()
	e, ok := j.entries[id]
	if !ok || e.State != InvocationRunning {
		return false
	}
	e.State = state
	e.Terminal = append([]byte(nil), terminal...)
	j.entries[id] = e
	j.terminalOrder = append(j.terminalOrder, id)
	j.evict()
	return true
}
func (j *InvocationJournal) ConfirmExit(id string) bool {
	j.mu.Lock()
	defer j.mu.Unlock()
	e, ok := j.entries[id]
	if !ok || e.Exited {
		return false
	}
	e.Exited = true
	j.entries[id] = e
	return true
}
func (j *InvocationJournal) Get(id string) (JournalEntry, bool) {
	j.mu.Lock()
	defer j.mu.Unlock()
	e, ok := j.entries[id]
	return cloneEntry(e), ok
}
func (j *InvocationJournal) evict() {
	for len(j.terminalOrder) > j.capacity {
		id := j.terminalOrder[0]
		j.terminalOrder = j.terminalOrder[1:]
		if e, ok := j.entries[id]; ok && e.State != InvocationRunning {
			delete(j.entries, id)
		}
	}
}
func cloneEntry(e JournalEntry) JournalEntry {
	e.Terminal = append([]byte(nil), e.Terminal...)
	return e
}
