package runtime

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"sync"

	"github.com/fengwk/kk-studio/daemon/internal/native"
)

type InvocationState string

const (
	InvocationRunning   InvocationState = "RUNNING"
	InvocationCompleted InvocationState = "COMPLETED"
	InvocationFailed    InvocationState = "FAILED"
	InvocationCancelled InvocationState = "CANCELLED"
)

var errInvocationJournalFull = errors.New("invocation journal is full awaiting execution-exit acknowledgements")

type JournalEntry struct {
	InvocationID string
	State        InvocationState
	Terminal     []byte
	Exited       bool
	Acknowledged bool
	Replayable   bool
	Recovered    bool `json:"-"`
}
type JournalStart struct {
	Entry   JournalEntry
	Created bool
	Err     error
}

// InvocationJournal durably retains each accepted invocation until its terminal result, exit proof,
// and Studio acknowledgement are all recorded. Unconfirmed facts are never evicted for capacity.
type InvocationJournal struct {
	mu       sync.Mutex
	capacity int
	entries  map[string]JournalEntry
	path     string
}

func NewInvocationJournal(capacity int) (*InvocationJournal, error) {
	return newInvocationJournal(capacity, "")
}

func NewPersistentInvocationJournal(path string, capacity int) (*InvocationJournal, error) {
	if path == "" || !filepath.IsAbs(path) {
		return nil, errors.New("persistent journal path must be absolute")
	}
	if err := native.EnsurePrivateDirectory(filepath.Dir(path)); err != nil {
		return nil, err
	}
	return newInvocationJournal(capacity, path)
}

func newInvocationJournal(capacity int, path string) (*InvocationJournal, error) {
	if capacity <= 0 {
		return nil, errors.New("journal capacity must be positive")
	}
	journal := &InvocationJournal{capacity: capacity, entries: make(map[string]JournalEntry), path: path}
	if path == "" {
		return journal, nil
	}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return journal, nil
	}
	if err != nil {
		return nil, err
	}
	var entries []JournalEntry
	if err := json.Unmarshal(data, &entries); err != nil {
		return nil, errors.New("persistent invocation journal is invalid")
	}
	for _, entry := range entries {
		if entry.InvocationID == "" || journal.entries[entry.InvocationID].InvocationID != "" {
			return nil, errors.New("persistent invocation journal contains an invalid identity")
		}
		if entry.Acknowledged && !entry.Exited {
			return nil, errors.New("persistent invocation journal contains an invalid exit acknowledgement")
		}
		entry.Recovered = true
		journal.entries[entry.InvocationID] = cloneEntry(entry)
	}
	return journal, nil
}

func (j *InvocationJournal) Start(id string) JournalStart {
	j.mu.Lock()
	defer j.mu.Unlock()
	if entry, ok := j.entries[id]; ok {
		return JournalStart{Entry: cloneEntry(entry)}
	}
	if len(j.entries) >= j.capacity {
		return JournalStart{Err: errInvocationJournalFull}
	}
	entry := JournalEntry{InvocationID: id, State: InvocationRunning}
	j.entries[id] = entry
	if err := j.persistLocked(); err != nil {
		delete(j.entries, id)
		return JournalStart{Err: err}
	}
	return JournalStart{Entry: cloneEntry(entry), Created: true}
}

func (j *InvocationJournal) Complete(id string, state InvocationState, terminal []byte) bool {
	completed, _ := j.CompleteDurable(id, state, terminal, true)
	return completed
}

func (j *InvocationJournal) CompleteDurable(id string, state InvocationState, terminal []byte, replayable bool) (bool, error) {
	return j.completeDurable(id, state, terminal, replayable, false)
}

func (j *InvocationJournal) CompleteWithExitDurable(id string, state InvocationState, terminal []byte, replayable bool) (bool, error) {
	return j.completeDurable(id, state, terminal, replayable, true)
}

func (j *InvocationJournal) completeDurable(id string, state InvocationState, terminal []byte, replayable, exited bool) (bool, error) {
	if state != InvocationCompleted && state != InvocationFailed && state != InvocationCancelled {
		return false, nil
	}
	j.mu.Lock()
	defer j.mu.Unlock()
	entry, ok := j.entries[id]
	if !ok || entry.State != InvocationRunning {
		return false, nil
	}
	previous := cloneEntry(entry)
	entry.State = state
	entry.Terminal = append([]byte(nil), terminal...)
	entry.Replayable = replayable
	entry.Exited = entry.Exited || exited
	if entry.Exited && entry.Acknowledged {
		delete(j.entries, id)
	} else {
		j.entries[id] = entry
	}
	if err := j.persistLocked(); err != nil {
		j.entries[id] = previous
		return false, err
	}
	return true, nil
}

func (j *InvocationJournal) ConfirmExit(id string) bool {
	confirmed, _ := j.ConfirmExitDurable(id)
	return confirmed
}

func (j *InvocationJournal) ConfirmExitDurable(id string) (bool, error) {
	j.mu.Lock()
	defer j.mu.Unlock()
	entry, ok := j.entries[id]
	if !ok || entry.Exited {
		return false, nil
	}
	previous := cloneEntry(entry)
	entry.Exited = true
	if entry.State != InvocationRunning && entry.Acknowledged {
		delete(j.entries, id)
	} else {
		j.entries[id] = entry
	}
	if err := j.persistLocked(); err != nil {
		j.entries[id] = previous
		return false, err
	}
	return true, nil
}

// AcknowledgeExit records only an acknowledgement for an exit fact already durably recorded.
// The entry remains reserved until its terminal fact is also present.
func (j *InvocationJournal) AcknowledgeExit(id string) (bool, error) {
	j.mu.Lock()
	defer j.mu.Unlock()
	entry, ok := j.entries[id]
	if !ok || !entry.Exited || entry.Acknowledged {
		return false, nil
	}
	previous := cloneEntry(entry)
	entry.Acknowledged = true
	if entry.State != InvocationRunning {
		delete(j.entries, id)
	} else {
		j.entries[id] = entry
	}
	if err := j.persistLocked(); err != nil {
		j.entries[id] = previous
		return false, err
	}
	return true, nil
}

func (j *InvocationJournal) Get(id string) (JournalEntry, bool) {
	j.mu.Lock()
	defer j.mu.Unlock()
	entry, ok := j.entries[id]
	return cloneEntry(entry), ok
}

func (j *InvocationJournal) UnacknowledgedExits() []JournalEntry {
	j.mu.Lock()
	defer j.mu.Unlock()
	entries := make([]JournalEntry, 0)
	for _, entry := range j.entries {
		if entry.Exited && !entry.Acknowledged {
			entries = append(entries, cloneEntry(entry))
		}
	}
	sort.Slice(entries, func(i, k int) bool { return entries[i].InvocationID < entries[k].InvocationID })
	return entries
}

func (j *InvocationJournal) persistLocked() error {
	if j.path == "" {
		return nil
	}
	entries := make([]JournalEntry, 0, len(j.entries))
	for _, entry := range j.entries {
		persisted := cloneEntry(entry)
		persisted.Recovered = false
		if !persisted.Replayable {
			persisted.Terminal = nil
		}
		entries = append(entries, persisted)
	}
	data, err := json.Marshal(entries)
	if err != nil {
		return err
	}
	return native.WritePrivateFile(j.path, data)
}

func cloneEntry(entry JournalEntry) JournalEntry {
	entry.Terminal = append([]byte(nil), entry.Terminal...)
	return entry
}
