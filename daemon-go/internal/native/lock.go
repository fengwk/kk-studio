package native

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"time"

	"github.com/gofrs/flock"
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

type directoryLock struct {
	lock   *flock.Flock
	closed bool
}

func AcquireDirectoryLock(directory, name string) (DirectoryLock, error) {
	if runtime.GOOS != "linux" && runtime.GOOS != "darwin" && runtime.GOOS != "windows" {
		return nil, fmt.Errorf("the directory lock is not supported on %s", runtime.GOOS)
	}
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
	lock := flock.New(path)
	locked, err := lock.TryLock()
	if err != nil {
		return nil, fmt.Errorf("cannot acquire the directory lock: %w", err)
	}
	if !locked {
		return nil, fmt.Errorf("%w: %s", ErrDirectoryLockBusy, directory)
	}
	if err := verifyPrivateFile(path); err != nil {
		_ = lock.Unlock()
		return nil, err
	}
	return &directoryLock{lock: lock}, nil
}

func (l *directoryLock) Close() error {
	if l.closed {
		return nil
	}
	if err := l.lock.Unlock(); err != nil {
		return fmt.Errorf("cannot release the directory lock: %w", err)
	}
	l.closed = true
	return nil
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
