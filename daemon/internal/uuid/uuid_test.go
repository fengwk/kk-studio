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
		if !canonical.MatchString(id) || !Canonical(id) {
			t.Fatalf("New() = %q, want a canonical version 4 UUID", id)
		}
	}
	if first == second {
		t.Fatalf("New() returned %q twice", first)
	}
}

// Canonical accepts only the lower-case 8-4-4-4-12 form the platform echoes back.
func TestCanonicalAcceptsOnlyLowercaseHyphenatedForm(t *testing.T) {
	for _, test := range []struct {
		value string
		want  bool
	}{
		{value: "123e4567-e89b-42d3-a456-426614174000", want: true},
		{value: "123E4567-E89B-42D3-A456-426614174000"},
		{value: "123e4567e89b42d3a456426614174000"},
		{value: "123e4567-e89b-42d3-a456-42661417400"},
		{value: "123e4567-e89b-42d3-a456-42661417400z"},
		{value: "123e4567+e89b-42d3-a456-426614174000"},
		{value: ""},
	} {
		if got := Canonical(test.value); got != test.want {
			t.Errorf("Canonical(%q)=%v, want %v", test.value, got, test.want)
		}
	}
}
