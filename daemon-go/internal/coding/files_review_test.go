package coding

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// COD-N-021
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
	if preview.SHA256 == nil || *preview.SHA256 == "" || !strings.Contains(preview.Diff, "- 2|old") || !strings.Contains(preview.Diff, "+ 2|new") {
		t.Fatalf("unexpected review: %#v", preview)
	}
	if _, err := Write(path, "first\nnew\nlast\n", &ExpectedFile{Path: preview.Path, SHA256: *preview.SHA256}); err != nil {
		t.Fatal(err)
	}
	if _, err := Write(path, "stale\n", &ExpectedFile{Path: preview.Path, SHA256: *preview.SHA256}); err == nil || err.Error() != staleWriteReviewMessage {
		t.Fatalf("expected stale review refusal, got %v", err)
	}
}

// COD-F-008: a staging fsync failure leaves the destination unchanged and removes the temporary file.
func TestAtomicWriteSyncFailureRemovesStaging(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "target.txt")
	if err := os.WriteFile(path, []byte("old"), 0o600); err != nil {
		t.Fatal(err)
	}
	injected := errors.New("injected fsync failure")
	calls := 0
	staging := ""
	err := atomicWriteWithSync(path, []byte("new"), 0o600, func(file *os.File) error {
		calls++
		staging = file.Name()
		if err := file.Sync(); err != nil {
			return err
		}
		return injected
	})
	if !errors.Is(err, injected) || calls != 1 {
		t.Fatalf("sync error=%v calls=%d", err, calls)
	}
	contents, err := os.ReadFile(path)
	if err != nil || string(contents) != "old" {
		t.Fatalf("target contents=%q err=%v", contents, err)
	}
	if _, err := os.Stat(staging); !os.IsNotExist(err) {
		t.Fatalf("staging file remains after fsync failure: %q err=%v", staging, err)
	}
}

// COD-F-007
func TestWriteCreatesMissingParentDirectories(t *testing.T) {
	path := filepath.Join(t.TempDir(), "nested", "deeper", "new.txt")
	if _, err := Write(path, "new contents\n", nil); err != nil {
		t.Fatal(err)
	}
	contents, err := os.ReadFile(path)
	if err != nil || string(contents) != "new contents\n" {
		t.Fatalf("contents=%q err=%v", contents, err)
	}
}

// COD-F-019: a missing write target resolves its existing symlinked parent to the real path.
func TestWriteCapabilityResolvesMissingTargetThroughSymlinkedParent(t *testing.T) {
	root := t.TempDir()
	realParent := filepath.Join(root, "real")
	if err := os.Mkdir(realParent, 0o700); err != nil {
		t.Fatal(err)
	}
	alias := filepath.Join(root, "alias")
	if err := os.Symlink(realParent, alias); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	path := filepath.Join(alias, "nested", "new.txt")
	preview, err := PreviewWrite(path, "approved")
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(resolvedTestPath(t, realParent), "nested", "new.txt")
	if preview.Path != want {
		t.Fatalf("preview path=%q want resolved path=%q", preview.Path, want)
	}
	result, err := Write(path, "approved", &ExpectedFile{Path: preview.Path})
	if err != nil || result.Path != want {
		t.Fatalf("write review=%#v err=%v want path=%q", result, err, want)
	}
	if data, err := os.ReadFile(want); err != nil || string(data) != "approved" {
		t.Fatalf("resolved write contents=%q err=%v", data, err)
	}
}

// COD-N-021
func TestWriteReviewForNewFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "new.txt")
	review, err := PreviewWrite(path, "new contents\n")
	if err != nil {
		t.Fatal(err)
	}
	if !review.NewFile || review.Content != "new contents\n" || !strings.Contains(review.Diff, "+ 1|new contents") || review.SHA256 != nil {
		t.Fatalf("unexpected new-file review: %#v", review)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("preview created file: %v", err)
	}
}

func resolvedTestPath(t *testing.T, path string) string {
	t.Helper()
	resolved, err := filepath.EvalSymlinks(path)
	if err != nil {
		t.Fatal(err)
	}
	return resolved
}
