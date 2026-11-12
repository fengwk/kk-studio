package search

import (
	"strings"
	"testing"
)

func TestLiteralAndRegexAcrossReadBufferBoundary(t *testing.T) {
	root := t.TempDir()
	put(t, root, "boundary.txt", strings.Repeat("x", 256*1024-3)+"needle\n")
	literal, err := Grep(root, "needle", true, false, "", 10)
	if err != nil || len(literal) != 1 || !strings.HasSuffix(literal[0], "needle") {
		t.Fatalf("literal cross-buffer match=%d, err=%v", len(literal), err)
	}
	regex, err := Grep(root, `needle.*`, false, false, "", 10)
	if err != nil || len(regex) != 1 || !strings.HasSuffix(regex[0], "needle") {
		t.Fatalf("regex cross-buffer match=%d, err=%v", len(regex), err)
	}
}
