//go:build windows

package native

import (
	"context"
	"testing"
)

// NAT-T-034 verifies Windows selects the Job supervisor and closes an empty scope.
func TestPlatform_WindowsSupervisor(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := supervisor.(*windowsSupervisor); !ok {
		t.Fatalf("Windows supervisor type = %T, want *windowsSupervisor", supervisor)
	}
	if err := supervisor.Close(context.Background()); err != nil {
		t.Fatalf("close empty Windows supervisor: %v", err)
	}
}
