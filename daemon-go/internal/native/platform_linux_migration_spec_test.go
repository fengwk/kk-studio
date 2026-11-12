//go:build linux

package native

import (
	"context"
	"testing"
)

// NAT-T-246 verifies Linux selects the POSIX supervisor and closes an empty scope.
func TestPlatform_LinuxSupervisor(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := supervisor.(*PosixSupervisor); !ok {
		t.Fatalf("Linux supervisor type = %T, want *PosixSupervisor", supervisor)
	}
	if err := supervisor.Close(context.Background()); err != nil {
		t.Fatalf("close empty Linux supervisor: %v", err)
	}
}
