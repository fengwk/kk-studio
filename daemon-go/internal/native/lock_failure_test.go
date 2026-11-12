package native

import (
	"errors"
	"testing"
)

type fakeDirectoryLockHandle struct {
	unlockErr   error
	closeErr    error
	unlockCalls int
	closeCalls  int
}

func (h *fakeDirectoryLockHandle) TryLock() (bool, error) { return true, nil }
func (h *fakeDirectoryLockHandle) Unlock() error {
	h.unlockCalls++
	return h.unlockErr
}
func (h *fakeDirectoryLockHandle) Close() error {
	h.closeCalls++
	return h.closeErr
}

// NAT-T-021
func TestDirectoryLock_UnlockFailure(t *testing.T) {
	unlockErr := errors.New("unlock failed")
	handle := &fakeDirectoryLockHandle{unlockErr: unlockErr}
	lock := &directoryLock{handle: handle}
	if err := lock.Close(); !errors.Is(err, unlockErr) {
		t.Fatalf("close error = %v", err)
	}
	if handle.closeCalls != 1 {
		t.Fatalf("handle close calls = %d", handle.closeCalls)
	}
	if err := lock.Close(); err != nil {
		t.Fatalf("repeated close error = %v", err)
	}
	if handle.unlockCalls != 1 || handle.closeCalls != 1 {
		t.Fatalf("unlock/close calls = %d/%d", handle.unlockCalls, handle.closeCalls)
	}
}

// NAT-T-198
func TestDirectoryLock_CloseAggregates(t *testing.T) {
	unlockErr := errors.New("unlock failed")
	closeErr := errors.New("handle close failed")
	handle := &fakeDirectoryLockHandle{unlockErr: unlockErr, closeErr: closeErr}
	lock := &directoryLock{handle: handle}
	err := lock.Close()
	if !errors.Is(err, unlockErr) || !errors.Is(err, closeErr) {
		t.Fatalf("close error = %v", err)
	}
	if handle.closeCalls != 1 {
		t.Fatalf("handle close calls = %d", handle.closeCalls)
	}
}
