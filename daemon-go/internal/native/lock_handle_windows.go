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

type windowsLockHandleAPI interface {
	CreateFile(string) (windows.Handle, error)
	GetFileInformationByHandle(windows.Handle, *windows.ByHandleFileInformation) error
	CloseHandle(windows.Handle) error
	NewFile(uintptr, string) *os.File
}

type nativeWindowsLockHandleAPI struct{}

func (nativeWindowsLockHandleAPI) CreateFile(path string) (windows.Handle, error) {
	return windows.CreateFile(
		windows.StringToUTF16Ptr(path),
		windows.GENERIC_READ|windows.GENERIC_WRITE,
		windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE,
		nil,
		windows.OPEN_EXISTING,
		windows.FILE_FLAG_OPEN_REPARSE_POINT|windows.FILE_ATTRIBUTE_NORMAL,
		0,
	)
}
func (nativeWindowsLockHandleAPI) GetFileInformationByHandle(handle windows.Handle, info *windows.ByHandleFileInformation) error {
	return windows.GetFileInformationByHandle(handle, info)
}
func (nativeWindowsLockHandleAPI) CloseHandle(handle windows.Handle) error {
	return windows.CloseHandle(handle)
}
func (nativeWindowsLockHandleAPI) NewFile(handle uintptr, name string) *os.File {
	return os.NewFile(handle, name)
}

func openPlatformLockHandle(path string) (directoryLockHandle, error) {
	return openPlatformLockHandleWithAPI(path, nativeWindowsLockHandleAPI{})
}

func openPlatformLockHandleWithAPI(path string, api windowsLockHandleAPI) (directoryLockHandle, error) {
	handle, err := api.CreateFile(path)
	if err != nil {
		return nil, err
	}
	var info windows.ByHandleFileInformation
	if err := api.GetFileInformationByHandle(handle, &info); err != nil {
		_ = api.CloseHandle(handle)
		return nil, err
	}
	if info.FileAttributes&windows.FILE_ATTRIBUTE_REPARSE_POINT != 0 || info.FileAttributes&windows.FILE_ATTRIBUTE_DIRECTORY != 0 {
		_ = api.CloseHandle(handle)
		return nil, os.ErrInvalid
	}
	return &platformLockHandle{file: api.NewFile(uintptr(handle), path)}, nil
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
