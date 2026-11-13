package native

import "testing"

// NAT-T-032 verifies unsupported platform supervisor creation fails with the stable error.
func TestPlatform_UnsupportedSupervisor(t *testing.T) {
	supervisor, err := newUnsupportedSupervisor("plan9")
	if supervisor != nil || err == nil || err.Error() != "the native process supervisor does not support plan9" {
		t.Fatalf("unsupported supervisor = %#v, %v", supervisor, err)
	}
}

// NAT-T-035 verifies unsupported platform lock acquisition fails with the stable error.
func TestPlatform_UnsupportedLock(t *testing.T) {
	err := unsupportedDirectoryLockError("plan9")
	if err == nil || err.Error() != "the directory lock is not supported on plan9" {
		t.Fatalf("unsupported lock error = %v", err)
	}
}
