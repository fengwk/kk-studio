package search

import (
	"context"
	"strings"
	"testing"
)

func TestLiteralAndRegexAcrossReadBufferBoundary(t *testing.T) {
	root := t.TempDir()
	put(t, root, "boundary.txt", strings.Repeat("x", 256*1024-3)+"needle\n")
	literal, err := GrepContext(context.Background(), root, "needle", true, false, "", 10)
	if err != nil || len(literal.Results) != 1 || !strings.HasSuffix(literal.Results[0], "needle") {
		t.Fatalf("literal cross-buffer match=%d, err=%v", len(literal.Results), err)
	}
	regex, err := GrepContext(context.Background(), root, `needle.*`, false, false, "", 10)
	if err != nil || len(regex.Results) != 1 || !strings.HasSuffix(regex.Results[0], "needle") {
		t.Fatalf("regex cross-buffer match=%d, err=%v", len(regex.Results), err)
	}
}
