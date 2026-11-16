package coding

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

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

// A failed fsync is reported after the bytes were written: an in-place write leaves no staging
// file and does not restore the previous content.
func TestWriteInPlaceReportsSyncFailure(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "target.txt")
	if err := os.WriteFile(path, []byte("old"), 0o600); err != nil {
		t.Fatal(err)
	}
	injected := errors.New("injected fsync failure")
	calls := 0
	err := writeInPlaceWithSync(path, []byte("new"), true, func(file *os.File) error {
		calls++
		if syncErr := file.Sync(); syncErr != nil {
			return syncErr
		}
		return injected
	})
	if !errors.Is(err, injected) || calls != 1 {
		t.Fatalf("sync error=%v calls=%d", err, calls)
	}
	contents, err := os.ReadFile(path)
	if err != nil || string(contents) != "new" {
		t.Fatalf("contents=%q err=%v", contents, err)
	}
	entries, err := os.ReadDir(dir)
	if err != nil || len(entries) != 1 || entries[0].Name() != "target.txt" {
		t.Fatalf("directory entries=%v err=%v", entries, err)
	}
}

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

// A missing write target resolves its existing symlinked parent to the real path.
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

// A write whose review cannot travel in one result frame is refused before the target is touched,
// so the approval never shows a diff the call could not deliver.
func TestWriteReviewTransportLimitRefusesBeforeWriting(t *testing.T) {
	path := filepath.Join(t.TempDir(), "large.txt")
	original := []byte(strings.Repeat("a", 600*1024) + "\n")
	if err := os.WriteFile(path, original, 0o600); err != nil {
		t.Fatal(err)
	}
	const limitMessage = "Complete write diff exceeds the review transport limit; split the change into several smaller write calls."
	content := strings.Repeat("b", 600*1024) + "\n"
	preview := invokeCoding(t, Provider{}, "fs.write.preview", map[string]any{"path": path, "content": content}, nil)
	if !preview.Error || !strings.Contains(preview.Contents[0].Text, limitMessage) {
		t.Fatalf("preview=%#v", preview)
	}
	hash := sha256Hex(original)
	commit := invokeCoding(t, Provider{}, "fs.write", map[string]any{"path": path, "content": content}, &runtime.ExpectedFile{Path: resolvedTestPath(t, path), SHA256: hash})
	if !commit.Error || !strings.Contains(commit.Contents[0].Text, limitMessage) {
		t.Fatalf("commit=%#v", commit)
	}
	actual, err := os.ReadFile(path)
	if err != nil || string(actual) != string(original) {
		t.Fatalf("refused write changed file: err=%v len=%d", err, len(actual))
	}
}

func TestWriteReviewForNewFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "new.txt")
	review, err := PreviewWrite(path, "new contents\n")
	if err != nil {
		t.Fatal(err)
	}
	if !review.NewFile || !strings.Contains(review.Diff, "+ 1|new contents") || review.SHA256 != nil {
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
