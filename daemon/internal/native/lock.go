package native

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"
)

const (
	DaemonLockFileName  = "daemon.lock"
	InstallLockFileName = "install.lock"
	defaultLockPoll     = 100 * time.Millisecond
)

var ErrDirectoryLockBusy = errors.New("the directory is already locked by another process")
var ErrDirectoryLockTimeout = errors.New("the directory lock was not released in time")

// DirectoryLock is a kernel-backed lock released when its owning process exits.
type DirectoryLock interface{ Close() error }

type directoryLockHandle interface {
	TryLock() (bool, error)
	Unlock() error
	Close() error
}

type directoryLock struct {
	mu     sync.Mutex
	handle directoryLockHandle
	closed bool
}

func AcquireDirectoryLock(directory, name string) (DirectoryLock, error) {
	if runtime.GOOS != "linux" && runtime.GOOS != "darwin" && runtime.GOOS != "windows" {
		return nil, fmt.Errorf("the directory lock is not supported on %s", runtime.GOOS)
	}
	return acquireDirectoryLock(directory, name, openPlatformLockHandle)
}

func acquireDirectoryLock(directory, name string, openHandle func(string) (directoryLockHandle, error)) (DirectoryLock, error) {
	if name != DaemonLockFileName && name != InstallLockFileName {
		return nil, errors.New("invalid directory lock file name")
	}
	if err := ensurePrivateDirectory(directory); err != nil {
		return nil, err
	}
	path := filepath.Join(directory, name)
	if _, err := os.Lstat(path); err == nil {
		if err := verifyPrivateFile(path); err != nil {
			return nil, err
		}
	} else if errors.Is(err, os.ErrNotExist) {
		if err := createPrivateFile(path); err != nil && !errors.Is(err, os.ErrExist) {
			return nil, err
		}
		if err := verifyPrivateFile(path); err != nil {
			return nil, err
		}
	} else {
		return nil, err
	}
	handle, err := openHandle(path)
	if err != nil {
		return nil, fmt.Errorf("cannot acquire the directory lock: %w", err)
	}
	locked, lockErr := handle.TryLock()
	if lockErr != nil {
		return nil, errors.Join(fmt.Errorf("cannot acquire the directory lock: %w", lockErr), closeUnacquiredLock(handle))
	}
	if !locked {
		if err := handle.Close(); err != nil {
			return nil, fmt.Errorf("cannot close the busy directory lock handle: %w", err)
		}
		return nil, fmt.Errorf("%w: %s", ErrDirectoryLockBusy, directory)
	}
	if err := verifyPrivateFile(path); err != nil {
		return nil, errors.Join(err, unlockAndClose(handle))
	}
	return &directoryLock{handle: handle}, nil
}

func closeUnacquiredLock(handle directoryLockHandle) error {
	if err := handle.Close(); err != nil {
		return fmt.Errorf("cannot close the directory lock handle: %w", err)
	}
	return nil
}

func unlockAndClose(handle directoryLockHandle) error {
	var failures []error
	if err := handle.Unlock(); err != nil {
		failures = append(failures, fmt.Errorf("cannot release the directory lock: %w", err))
	}
	if err := handle.Close(); err != nil {
		failures = append(failures, fmt.Errorf("cannot close the directory lock handle: %w", err))
	}
	return errors.Join(failures...)
}

func (l *directoryLock) Close() error {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.closed {
		return nil
	}
	l.closed = true
	return unlockAndClose(l.handle)
}

func WaitForDirectoryLock(ctx context.Context, directory, name string, timeout time.Duration) error {
	if timeout < 0 {
		return errors.New("the lock wait timeout must be a non-negative duration")
	}
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	ticker := time.NewTicker(defaultLockPoll)
	defer ticker.Stop()
	for {
		lock, err := AcquireDirectoryLock(directory, name)
		if err == nil {
			return lock.Close()
		}
		if !errors.Is(err, ErrDirectoryLockBusy) {
			return err
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-deadline.C:
			return ErrDirectoryLockTimeout
		case <-ticker.C:
		}
	}
}
