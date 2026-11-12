package coding

import (
	"strings"
	"testing"
)

// COD-T-068
func TestNumberedDiffNoNewlineMarker(t *testing.T) {
	got, err := numberedDiff("foo", "bar")
	if err != nil {
		t.Fatal(err)
	}
	want := "- 1|foo\n\\ No newline at end of file\n+ 1|bar\n\\ No newline at end of file"
	if got != want {
		t.Fatalf("diff=%q want=%q", got, want)
	}
}

// COD-T-073
func TestNumberedDiffMultihunkContextBoundary(t *testing.T) {
	makeInput := func(gap int) (string, string) {
		old := []string{"old one"}
		new := []string{"new one"}
		for i := 0; i < gap; i++ {
			line := "middle"
			old = append(old, line)
			new = append(new, line)
		}
		old = append(old, "old two")
		new = append(new, "new two")
		return strings.Join(old, "\n") + "\n", strings.Join(new, "\n") + "\n"
	}
	for _, tc := range []struct {
		gap         int
		wantOmitted bool
	}{{8, false}, {9, true}} {
		old, next := makeInput(tc.gap)
		got, err := numberedDiff(old, next)
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(got, "...") != tc.wantOmitted {
			t.Fatalf("gap=%d diff=%q", tc.gap, got)
		}
	}
}
