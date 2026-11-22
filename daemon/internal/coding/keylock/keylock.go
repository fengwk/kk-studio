// Package keylock provides reclaimable per-key mutual exclusion for Daemon tools.
package keylock

import "sync"

// Locks serializes operations sharing a key. Its zero value is ready to use.
type Locks struct {
	mu      sync.Mutex
	entries map[string]*entry
}

type entry struct {
	mu   sync.Mutex
	refs int // Holders and waiters, guarded by Locks.mu.
}

// Lock takes the lock for key and returns its unlock function, which must be called once.
func (l *Locks) Lock(key string) func() {
	l.mu.Lock()
	if l.entries == nil {
		l.entries = make(map[string]*entry)
	}
	e := l.entries[key]
	if e == nil {
		e = &entry{}
		l.entries[key] = e
	}
	e.refs++
	l.mu.Unlock()

	e.mu.Lock()
	return func() {
		e.mu.Unlock()
		l.mu.Lock()
		e.refs--
		if e.refs == 0 {
			delete(l.entries, key)
		}
		l.mu.Unlock()
	}
}

// Len reports the number of keys with outstanding lock references.
func (l *Locks) Len() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.entries)
}
