//go:build linux || darwin

package native

import "testing"

// NAT-T-047 verifies descendant checks follow the parent chain and fail closed on ambiguous data.
func TestSession_DescendantOf(t *testing.T) {
	members := []sessionMember{{PID: 10, Parent: 1}, {PID: 20, Parent: 10}, {PID: 30, Parent: 20}}
	if !isDescendantOf(members, 30, 1) {
		t.Fatal("transitive descendant was not recognized")
	}
	if isDescendantOf(members, 30, 99) || isDescendantOf(members, 1, 1) {
		t.Fatal("unrelated or identical PID was accepted as a descendant")
	}
	if isDescendantOf([]sessionMember{{PID: 10, Parent: 20}, {PID: 20, Parent: 10}}, 10, 99) {
		t.Fatal("cyclic parent chain was accepted")
	}
}

// NAT-F-007 verifies ancestry accepts exactly 64 links and rejects duplicate or malformed snapshots.
func TestSession_DescendantDepthAndIdentityBoundaries(t *testing.T) {
	chain := func(edges int) []sessionMember {
		members := make([]sessionMember, edges)
		for index := range members {
			members[index] = sessionMember{PID: index + 2, Parent: index + 1}
		}
		return members
	}
	if !isDescendantOf(chain(maxSessionTreeDepth), maxSessionTreeDepth+1, 1) {
		t.Fatal("64-edge ancestry was rejected")
	}
	if isDescendantOf(chain(maxSessionTreeDepth+1), maxSessionTreeDepth+2, 1) {
		t.Fatal("ancestry beyond the exact depth limit was accepted")
	}
	for _, members := range [][]sessionMember{
		{{PID: 0, Parent: 1}},
		{{PID: 2, Parent: 1}, {PID: 2, Parent: 1}},
		{{PID: 2, Parent: 0}},
	} {
		if isDescendantOf(members, 2, 1) {
			t.Fatalf("invalid ancestry was accepted: %+v", members)
		}
	}
}
