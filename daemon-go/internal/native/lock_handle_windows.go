//go:build windows

package native

import (
	"os"

	"golang.org/x/sys/windows"
)

type platformLockHandle struct {
	file       *os.File
	overlapped windows.Overlapped
}

func openPlatformLockHandle(path string) (directoryLockHandle, error) {
	handle, err := windows.CreateFile(
		windows.StringToUTF16Ptr(path),
		windows.GENERIC_READ|windows.GENERIC_WRITE,
		windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE,
		nil,
		windows.OPEN_EXISTING,
		windows.FILE_FLAG_OPEN_REPARSE_POINT|windows.FILE_ATTRIBUTE_NORMAL,
		0,
	)
	if err != nil {
		return nil, err
	}
	var info windows.ByHandleFileInformation
	if err := windows.GetFileInformationByHandle(handle, &info); err != nil {
		_ = windows.CloseHandle(handle)
		return nil, err
	}
	if info.FileAttributes&windows.FILE_ATTRIBUTE_REPARSE_POINT != 0 || info.FileAttributes&windows.FILE_ATTRIBUTE_DIRECTORY != 0 {
		_ = windows.CloseHandle(handle)
		return nil, os.ErrInvalid
	}
	return &platformLockHandle{file: os.NewFile(uintptr(handle), path)}, nil
}

func (h *platformLockHandle) TryLock() (bool, error) {
	err := windows.LockFileEx(
		windows.Handle(h.file.Fd()),
		windows.LOCKFILE_EXCLUSIVE_LOCK|windows.LOCKFILE_FAIL_IMMEDIATELY,
		0,
		1,
		0,
		&h.overlapped,
	)
	if err == windows.ERROR_LOCK_VIOLATION {
		return false, nil
	}
	return err == nil, err
}

func (h *platformLockHandle) Unlock() error {
	return windows.UnlockFileEx(windows.Handle(h.file.Fd()), 0, 1, 0, &h.overlapped)
}

func (h *platformLockHandle) Close() error { return h.file.Close() }
