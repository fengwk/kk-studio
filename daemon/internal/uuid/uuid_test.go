package uuid

import (
	"regexp"
	"testing"
)

// New yields distinct canonical version 4 / RFC variant identifiers, the only format the
// platform accepts for daemon-minted ids.
func TestNewReturnsDistinctVersion4UUIDs(t *testing.T) {
	canonical := regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	first, second := New(), New()
	for _, id := range []string{first, second} {
		if !canonical.MatchString(id) {
			t.Fatalf("New() = %q, want a canonical version 4 UUID", id)
		}
	}
	if first == second {
		t.Fatalf("New() returned %q twice", first)
	}
}
