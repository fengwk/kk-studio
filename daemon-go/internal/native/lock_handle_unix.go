//go:build linux || darwin

package native

import (
	"errors"
	"os"
	"syscall"

	"golang.org/x/sys/unix"
)

type platformLockHandle struct{ file *os.File }

func openPlatformLockHandle(path string) (directoryLockHandle, error) {
	fd, err := unix.Open(path, unix.O_RDWR|unix.O_CLOEXEC|unix.O_NOFOLLOW, 0)
	if err != nil {
		return nil, err
	}
	return &platformLockHandle{file: os.NewFile(uintptr(fd), path)}, nil
}

func (h *platformLockHandle) TryLock() (bool, error) {
	err := unix.Flock(int(h.file.Fd()), unix.LOCK_EX|unix.LOCK_NB)
	if errors.Is(err, syscall.EWOULDBLOCK) || errors.Is(err, syscall.EAGAIN) {
		return false, nil
	}
	return err == nil, err
}

func (h *platformLockHandle) Unlock() error {
	return unix.Flock(int(h.file.Fd()), unix.LOCK_UN)
}

func (h *platformLockHandle) Close() error { return h.file.Close() }
