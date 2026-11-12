//go:build darwin

package native

import (
	"context"
	"os"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

// NAT-T-048 verifies that the Darwin process snapshot carries fencing identity and parent data.
func TestDarwinSessionSnapshotIncludesParentAndIdentity(t *testing.T) {
	pid := os.Getpid()
	sid, err := unix.Getsid(pid)
	if err != nil {
		t.Fatalf("getsid(%d) error = %v", pid, err)
	}
	members, err := sessionMembers(sid)
	if err != nil {
		t.Fatalf("sessionMembers(%d) error = %v", sid, err)
	}
	for _, member := range members {
		if member.PID == pid {
			if member.Parent != os.Getppid() {
				t.Errorf("parent = %d, want %d", member.Parent, os.Getppid())
			}
			if member.Identity == "" {
				t.Error("process identity is empty")
			}
			return
		}
	}
	t.Fatalf("process %d missing from session %d", pid, sid)
}

// NAT-T-036, NAT-T-037 verify Darwin's lack of Linux child-subreaper adoption.
func TestDarwinDoesNotAdoptOrphans(t *testing.T) {
	if err := enableChildSubreaper(); err != nil {
		t.Fatalf("Darwin subreaper capability check failed: %v", err)
	}

	for _, testCase := range []struct {
		name         string
		excludedPIDs []int
	}{
		{name: "no exclusions"},
		{name: "excluded children", excludedPIDs: []int{os.Getpid()}},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			children, err := directChildren(os.Getpid(), testCase.excludedPIDs)
			if err != nil {
				t.Fatalf("directChildren() error = %v", err)
			}
			if len(children) != 0 {
				t.Fatalf("directChildren() returned %d adopted children on Darwin", len(children))
			}

			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			if err := convergeAdoptedChildren(ctx, os.Getpid(), 0, testCase.excludedPIDs); err != nil {
				t.Fatalf("convergeAdoptedChildren() error = %v", err)
			}
		})
	}
}
