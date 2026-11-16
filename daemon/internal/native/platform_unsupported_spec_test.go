//go:build !linux && !darwin && !windows

package native

import (
	"runtime"
	"testing"
)

// Verifies an unsupported platform refuses the supervisor with its stable error.
func TestPlatform_UnsupportedSupervisor(t *testing.T) {
	supervisor, err := NewSupervisor()
	if supervisor != nil || err == nil || err.Error() != "the native process supervisor does not support "+runtime.GOOS {
		t.Fatalf("unsupported supervisor = %#v, %v", supervisor, err)
	}
}

func TestPlatform_UnsupportedLock(t *testing.T) {
	if _, err := AcquireDirectoryLock(t.TempDir(), DaemonLockFileName); err == nil || err.Error() != "the directory lock is not supported on "+runtime.GOOS {
		t.Fatalf("unsupported lock error = %v", err)
	}
}

func TestPlatform_UnsupportedLockImplementation(t *testing.T) {
	if handle, err := openPlatformLockHandle("ignored"); handle != nil || err == nil {
		t.Fatalf("unsupported lock handle = %#v, %v; want an error", handle, err)
	}
}
