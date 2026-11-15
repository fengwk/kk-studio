//go:build !linux && !darwin && !windows

package native

import "testing"

func TestPlatform_UnsupportedLockImplementation(t *testing.T) {
	if handle, err := openPlatformLockHandle("ignored"); handle != nil || err == nil {
		t.Fatalf("unsupported lock handle = %#v, %v; want an error", handle, err)
	}
}
