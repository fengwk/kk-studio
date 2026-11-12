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
