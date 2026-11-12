//go:build windows

package native

import (
	"errors"
	"os"
	"path/filepath"
	"testing"

	"golang.org/x/sys/windows"
)

type fakeWindowsLockHandleAPI struct {
	handle   windows.Handle
	file     *os.File
	info     windows.ByHandleFileInformation
	openErr  error
	infoErr  error
	opened   string
	closed   []windows.Handle
	newFiles int
}

func (api *fakeWindowsLockHandleAPI) CreateFile(path string) (windows.Handle, error) {
	api.opened = path
	return api.handle, api.openErr
}

func (api *fakeWindowsLockHandleAPI) GetFileInformationByHandle(handle windows.Handle, info *windows.ByHandleFileInformation) error {
	if handle != api.handle {
		return errors.New("unexpected lock handle")
	}
	if api.infoErr != nil {
		return api.infoErr
	}
	*info = api.info
	return nil
}

func (api *fakeWindowsLockHandleAPI) CloseHandle(handle windows.Handle) error {
	api.closed = append(api.closed, handle)
	if api.file != nil {
		return api.file.Close()
	}
	return nil
}

func (api *fakeWindowsLockHandleAPI) NewFile(handle uintptr, name string) *os.File {
	api.newFiles++
	return os.NewFile(handle, name)
}

func newFakeWindowsLockHandleAPI(t *testing.T) *fakeWindowsLockHandleAPI {
	t.Helper()
	file, err := os.Create(filepath.Join(t.TempDir(), "lock"))
	if err != nil {
		t.Fatal(err)
	}
	return &fakeWindowsLockHandleAPI{handle: windows.Handle(file.Fd()), file: file}
}

// TestWindowsLockHandle_OpenValidatesTheOpenedFile proves each native-open failure is rejected and owned handles close.
func TestWindowsLockHandle_OpenValidatesTheOpenedFile(t *testing.T) {
	apiError := errors.New("injected lock API failure")
	t.Run("open failure", func(t *testing.T) {
		api := &fakeWindowsLockHandleAPI{openErr: apiError}
		if handle, err := openPlatformLockHandleWithAPI("lock", api); handle != nil || !errors.Is(err, apiError) {
			t.Fatalf("open = %v, %v", handle, err)
		}
		if len(api.closed) != 0 || api.newFiles != 0 {
			t.Fatalf("cleanup after open failure = %v/%d", api.closed, api.newFiles)
		}
	})
	t.Run("information failure closes handle", func(t *testing.T) {
		api := newFakeWindowsLockHandleAPI(t)
		api.infoErr = apiError
		if handle, err := openPlatformLockHandleWithAPI("lock", api); handle != nil || !errors.Is(err, apiError) {
			t.Fatalf("open = %v, %v", handle, err)
		}
		if len(api.closed) != 1 || api.newFiles != 0 {
			t.Fatalf("cleanup after information failure = %v/%d", api.closed, api.newFiles)
		}
	})
	for _, attr := range []struct {
		name string
		flag uint32
	}{
		{name: "reparse point", flag: windows.FILE_ATTRIBUTE_REPARSE_POINT},
		{name: "directory", flag: windows.FILE_ATTRIBUTE_DIRECTORY},
	} {
		t.Run(attr.name, func(t *testing.T) {
			api := newFakeWindowsLockHandleAPI(t)
			api.info.FileAttributes = attr.flag
			if handle, err := openPlatformLockHandleWithAPI("lock", api); handle != nil || !errors.Is(err, os.ErrInvalid) {
				t.Fatalf("open = %v, %v", handle, err)
			}
			if len(api.closed) != 1 || api.newFiles != 0 {
				t.Fatalf("cleanup after invalid file = %v/%d", api.closed, api.newFiles)
			}
		})
	}
	t.Run("regular file transfers ownership to lock handle", func(t *testing.T) {
		api := newFakeWindowsLockHandleAPI(t)
		lock, err := openPlatformLockHandleWithAPI("lock", api)
		if err != nil {
			t.Fatal(err)
		}
		if api.opened != "lock" || api.newFiles != 1 || len(api.closed) != 0 {
			t.Fatalf("open/new-file/closed = %q/%d/%v", api.opened, api.newFiles, api.closed)
		}
		if err := lock.Close(); err != nil {
			t.Fatal(err)
		}
	})
}
