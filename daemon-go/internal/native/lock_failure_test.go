package native

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

type fakeDirectoryLockHandle struct {
	locked      bool
	tryErr      error
	unlockErr   error
	closeErr    error
	unlockCalls int
	closeCalls  int
}

func (h *fakeDirectoryLockHandle) TryLock() (bool, error) { return h.locked, h.tryErr }
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

// Acquisition errors must close any opened kernel handle and preserve both causes.
func TestDirectoryLock_AcquisitionFailureCleanup(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	openErr := errors.New("open failed")
	if _, err := acquireDirectoryLock(dir, DaemonLockFileName, func(string) (directoryLockHandle, error) {
		return nil, openErr
	}); !errors.Is(err, openErr) {
		t.Fatalf("open failure = %v", err)
	}
	if _, err := acquireDirectoryLock(dir, "unknown.lock", func(string) (directoryLockHandle, error) {
		t.Fatal("opened handle for an invalid lock name")
		return nil, nil
	}); err == nil {
		t.Fatal("invalid lock name was accepted")
	}

	tryErr, closeErr := errors.New("try failed"), errors.New("close failed")
	handle := &fakeDirectoryLockHandle{tryErr: tryErr, closeErr: closeErr}
	if _, err := acquireDirectoryLock(dir, DaemonLockFileName, func(string) (directoryLockHandle, error) {
		return handle, nil
	}); !errors.Is(err, tryErr) || !errors.Is(err, closeErr) {
		t.Fatalf("try-lock failure = %v", err)
	}
	if handle.closeCalls != 1 {
		t.Fatalf("unacquired handle close calls = %d", handle.closeCalls)
	}
}

// Busy acquisition and post-lock path replacement both fail closed.
func TestDirectoryLock_BusyAndReplacedPath(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	busy := &fakeDirectoryLockHandle{}
	if _, err := acquireDirectoryLock(dir, DaemonLockFileName, func(string) (directoryLockHandle, error) {
		return busy, nil
	}); !errors.Is(err, ErrDirectoryLockBusy) {
		t.Fatalf("busy acquisition = %v", err)
	}
	if busy.closeCalls != 1 || busy.unlockCalls != 0 {
		t.Fatalf("busy handle close/unlock calls = %d/%d", busy.closeCalls, busy.unlockCalls)
	}

	path := filepath.Join(dir, DaemonLockFileName)
	handle := &fakeDirectoryLockHandle{locked: true}
	_, err := acquireDirectoryLock(dir, DaemonLockFileName, func(string) (directoryLockHandle, error) {
		if err := os.Remove(path); err != nil {
			t.Fatal(err)
		}
		if err := os.Mkdir(path, 0700); err != nil {
			t.Fatal(err)
		}
		return handle, nil
	})
	if err == nil {
		t.Fatal("lock path replaced with a directory after locking was accepted")
	}
	if handle.unlockCalls != 1 || handle.closeCalls != 1 {
		t.Fatalf("replaced-path handle unlock/close calls = %d/%d", handle.unlockCalls, handle.closeCalls)
	}
}

// Wait rejects invalid deadlines and propagates cancellation while a lock is busy.
func TestDirectoryLock_WaitInvalidAndCancelled(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	if err := WaitForDirectoryLock(context.Background(), dir, DaemonLockFileName, -time.Millisecond); err == nil {
		t.Fatal("negative wait duration was accepted")
	}
	lock, err := AcquireDirectoryLock(dir, DaemonLockFileName)
	if err != nil {
		t.Fatal(err)
	}
	defer lock.Close()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := WaitForDirectoryLock(ctx, dir, DaemonLockFileName, time.Second); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled wait = %v", err)
	}
}
