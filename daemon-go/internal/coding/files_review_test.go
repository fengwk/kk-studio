package coding

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// COD-N-018
func TestWriteReviewAndExpectedFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "review.txt")
	if err := os.WriteFile(path, []byte("first\nold\nlast\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	preview, err := PreviewWrite(path, "first\nnew\nlast\n")
	if err != nil {
		t.Fatal(err)
	}
	contents, err := os.ReadFile(path)
	if err != nil || string(contents) != "first\nold\nlast\n" {
		t.Fatalf("preview changed file: %q, %v", contents, err)
	}
	if preview.SHA256 == "" || !strings.Contains(preview.Diff, "- 2|old") || !strings.Contains(preview.Diff, "+ 2|new") {
		t.Fatalf("unexpected review: %#v", preview)
	}
	if _, err := Write(path, "first\nnew\nlast\n", &ExpectedFile{Path: path, SHA256: preview.SHA256}); err != nil {
		t.Fatal(err)
	}
	if _, err := Write(path, "stale\n", &ExpectedFile{Path: path, SHA256: preview.SHA256}); err == nil || err.Error() != staleWriteReviewMessage {
		t.Fatalf("expected stale review refusal, got %v", err)
	}
}

// COD-N-018
func TestWriteReviewForNewFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "new.txt")
	review, err := PreviewWrite(path, "new contents\n")
	if err != nil {
		t.Fatal(err)
	}
	if !review.NewFile || review.Content != "new contents\n" || review.Diff != "" || review.SHA256 != "" {
		t.Fatalf("unexpected new-file review: %#v", review)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("preview created file: %v", err)
	}
}
