package coding

import (
	"fmt"
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

// COD-T-018: changes at both ends retain their numbered replacement lines and middle omission.
func TestDiffEdgeContext(t *testing.T) {
	oldLines := make([]string, 30)
	newLines := make([]string, 30)
	for i := range oldLines {
		oldLines[i] = fmt.Sprintf("line %02d", i+1)
		newLines[i] = oldLines[i]
	}
	newLines[0] = "first changed"
	newLines[29] = "last changed"
	got, err := numberedDiff(strings.Join(oldLines, "\n")+"\n", strings.Join(newLines, "\n")+"\n")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(got, "- 1|line 01\n+ 1|first changed") || !strings.Contains(got, "-30|line 30\n+30|last changed") || !strings.Contains(got, "...") {
		t.Fatalf("diff=%q", got)
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
