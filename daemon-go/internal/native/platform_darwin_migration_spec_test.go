//go:build darwin

package native

import (
	"context"
	"testing"
)

// NAT-T-033 verifies Darwin selects the POSIX supervisor without subreaper setup.
func TestPlatform_DarwinSupervisor(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := supervisor.(*PosixSupervisor); !ok {
		t.Fatalf("Darwin supervisor type = %T, want *PosixSupervisor", supervisor)
	}
	if err := supervisor.Close(context.Background()); err != nil {
		t.Fatalf("close empty Darwin supervisor: %v", err)
	}
}
