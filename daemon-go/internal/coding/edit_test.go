package coding

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

func invokeCoding(t *testing.T, provider Provider, id string, args any, expected *runtime.ExpectedFile) runtime.CapabilityResult {
	t.Helper()
	encoded, err := json.Marshal(args)
	if err != nil {
		t.Fatal(err)
	}
	call := runtime.CapabilityCall{InvocationID: "test", CapabilityID: id, Arguments: encoded, ExpectedFile: expected}
	result, err := provider.Capabilities()[id](context.Background(), call, runtime.CapabilityContext{})
	if err != nil {
		t.Fatal(err)
	}
	return result
}

// COD-T-055
func TestEditPreviewDoesNotWriteAndApprovesSnapshot(t *testing.T) {
	path := filepath.Join(t.TempDir(), "edit.txt")
	original := []byte("head\nold\ntail\n")
	if err := os.WriteFile(path, original, 0o600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.edit.preview", map[string]any{"path": path, "old_string": "old", "new_string": "new"}, nil)
	if result.Error {
		t.Fatalf("preview failed: %#v", result)
	}
	var review struct{ Path, Diff, SHA256 string }
	if err := json.Unmarshal(result.Contents[0].JSON, &review); err != nil {
		t.Fatal(err)
	}
	hash := sha256.Sum256(original)
	if review.SHA256 != hex.EncodeToString(hash[:]) || !strings.Contains(review.Diff, "- 2|old") || !strings.Contains(review.Diff, "+ 2|new") {
		t.Fatalf("review=%#v", review)
	}
	after, _ := os.ReadFile(path)
	if string(after) != string(original) {
		t.Fatalf("preview modified file: %q", after)
	}
	approved := invokeCoding(t, Provider{}, "fs.edit", map[string]any{"path": path, "old_string": "old", "new_string": "new"}, &runtime.ExpectedFile{Path: review.Path, SHA256: review.SHA256})
	if approved.Error || !strings.Contains(approved.Contents[0].Text, "Replacements: 1") {
		t.Fatalf("commit=%#v", approved)
	}
	var details struct {
		EditReview struct{ Diff string } `json:"editReview"`
	}
	if err := json.Unmarshal(approved.Details, &details); err != nil {
		t.Fatal(err)
	}
	if details.EditReview.Diff != review.Diff {
		t.Fatalf("preview/commit diff differ: %q vs %q", review.Diff, details.EditReview.Diff)
	}
}

// COD-T-073
func TestEditMultihunkDiffContext(t *testing.T) {
	path := filepath.Join(t.TempDir(), "multi.txt")
	lines := []string{"old A"}
	for i := 0; i < 9; i++ {
		lines = append(lines, "middle")
	}
	lines = append(lines, "old B")
	if err := os.WriteFile(path, []byte(strings.Join(lines, "\n")+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.edit.preview", map[string]any{"path": path, "old_string": "old", "new_string": "new", "replace_all": true}, nil)
	if result.Error {
		t.Fatal(result.Contents)
	}
	got := string(result.Contents[0].JSON)
	if !strings.Contains(got, "...") || strings.Contains(got, "middle\"\n  6") {
		t.Fatalf("expected elided middle context: %s", got)
	}
}

// COD-T-098
func TestEditPreservesMixedLineEndings(t *testing.T) {
	path := filepath.Join(t.TempDir(), "mixed.txt")
	if err := os.WriteFile(path, []byte("one\r\nold\nlast\r\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.edit", map[string]any{"path": path, "old_string": "old", "new_string": "new"}, nil)
	if result.Error {
		t.Fatalf("edit failed: %#v", result)
	}
	written, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(written) != "one\r\nnew\nlast\r\n" {
		t.Fatalf("line endings changed: %q", written)
	}
}

// COD-N-018
func TestWriteApprovalPreviewAndStaleHash(t *testing.T) {
	path := filepath.Join(t.TempDir(), "write.txt")
	if err := os.WriteFile(path, []byte("old\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	provider := Provider{}
	preview := invokeCoding(t, provider, "fs.write.preview", map[string]any{"path": path, "content": "new\n"}, nil)
	if preview.Error || len(preview.Contents) != 1 {
		t.Fatalf("preview=%#v", preview)
	}
	var review WriteReview
	if err := json.Unmarshal(preview.Contents[0].JSON, &review); err != nil {
		t.Fatal(err)
	}
	if review.SHA256 == "" || !strings.Contains(review.Diff, "- 1|old") || !strings.Contains(review.Diff, "+ 1|new") {
		t.Fatalf("review=%#v", review)
	}
	if result := invokeCoding(t, provider, "fs.write", map[string]any{"path": path, "content": "new\n"}, &runtime.ExpectedFile{Path: review.Path, SHA256: review.SHA256}); result.Error {
		t.Fatalf("approved write failed: %#v", result)
	}
	if result := invokeCoding(t, provider, "fs.write", map[string]any{"path": path, "content": "stale"}, &runtime.ExpectedFile{Path: review.Path, SHA256: review.SHA256}); !result.Error || !strings.Contains(result.Contents[0].Text, staleWriteReviewMessage) {
		t.Fatalf("stale write accepted: %#v", result)
	}
}

// COD-N-018
func TestWriteApprovalPreviewNewTarget(t *testing.T) {
	path := filepath.Join(t.TempDir(), "new.txt")
	result := invokeCoding(t, Provider{}, "fs.write.preview", map[string]any{"path": path, "content": "new file\n"}, nil)
	if result.Error {
		t.Fatalf("preview=%#v", result)
	}
	var review WriteReview
	if err := json.Unmarshal(result.Contents[0].JSON, &review); err != nil {
		t.Fatal(err)
	}
	if !review.NewFile || review.Content != "new file\n" {
		t.Fatalf("review=%#v", review)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("preview created file: %v", err)
	}
}
