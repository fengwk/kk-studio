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
	if string(result.Details) != "{}" {
		t.Fatalf("preview details=%q want empty object", result.Details)
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
	if entries, err := os.ReadDir(filepath.Dir(path)); err != nil || len(entries) != 1 || entries[0].Name() != filepath.Base(path) {
		t.Fatalf("edit left staging files behind: entries=%v err=%v", entries, err)
	}
}

// COD-T-108
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
	var review struct {
		Diff string `json:"diff"`
	}
	if err := json.Unmarshal(result.Contents[0].JSON, &review); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(review.Diff, "...") || strings.Contains(review.Diff, "6|middle") {
		t.Fatalf("expected elided middle context: %s", review.Diff)
	}
}

// COD-T-016
func TestEditMultilineInsert(t *testing.T) {
	path := filepath.Join(t.TempDir(), "insert.txt")
	if err := os.WriteFile(path, []byte("a\r\nc\r\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.edit", map[string]any{"path": path, "old_string": "a\nc", "new_string": "a\nb\nc"}, nil)
	if result.Error || !strings.Contains(result.Contents[0].Text, "Replacements: 1") {
		t.Fatalf("edit=%#v", result)
	}
	written, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(written) != "a\r\nb\r\nc\r\n" {
		t.Fatalf("inserted file=%q", written)
	}
}

// COD-T-017
func TestEditMultilineDelete(t *testing.T) {
	path := filepath.Join(t.TempDir(), "delete.txt")
	if err := os.WriteFile(path, []byte("a\r\nb\r\nc\r\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.edit", map[string]any{"path": path, "old_string": "a\nb\nc", "new_string": "a\nc"}, nil)
	if result.Error || !strings.Contains(result.Contents[0].Text, "Replacements: 1") {
		t.Fatalf("edit=%#v", result)
	}
	written, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(written) != "a\r\nc\r\n" {
		t.Fatalf("deleted file=%q", written)
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

// COD-T-070: editing a file without a final newline reports the EOF marker in review details.
func TestEditNoNewlineEOFReview(t *testing.T) {
	path := filepath.Join(t.TempDir(), "noeol.txt")
	if err := os.WriteFile(path, []byte("no newline"), 0o600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.edit", map[string]any{"path": path, "old_string": "newline", "new_string": "EOF"}, nil)
	if result.Error || result.Contents[0].Text != "Edited successfully.\nReplacements: 1" {
		t.Fatalf("result=%#v", result)
	}
	var details struct {
		EditReview struct {
			Diff string `json:"diff"`
		} `json:"editReview"`
	}
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(details.EditReview.Diff, `\ No newline at end of file`) {
		t.Fatalf("review diff=%q", details.EditReview.Diff)
	}
	written, err := os.ReadFile(path)
	if err != nil || string(written) != "no EOF" {
		t.Fatalf("written=%q err=%v", written, err)
	}
}

// COD-T-093: duplicate matches are rejected without changing the file.
func TestEditMultipleMatchesAreRejected(t *testing.T) {
	path := filepath.Join(t.TempDir(), "multiple.txt")
	if err := os.WriteFile(path, []byte("old old"), 0o600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.edit", map[string]any{"path": path, "old_string": "old", "new_string": "new"}, nil)
	if !result.Error || !strings.Contains(result.Contents[0].Text, "use replace_all") {
		t.Fatalf("result=%#v", result)
	}
	written, err := os.ReadFile(path)
	if err != nil || string(written) != "old old" {
		t.Fatalf("written=%q err=%v", written, err)
	}
}

// COD-T-095 and COD-T-096: missing and empty search strings are rejected without writes.
func TestEditMissingAndEmptyMatchesAreRejected(t *testing.T) {
	path := filepath.Join(t.TempDir(), "unchanged.txt")
	const original = "source"
	if err := os.WriteFile(path, []byte(original), 0o600); err != nil {
		t.Fatal(err)
	}
	for _, test := range []struct {
		name string
		old  string
		want string
	}{{"missing", "absent", "Could not find old_string"}, {"empty", "", "old_string must not be empty"}} {
		t.Run(test.name, func(t *testing.T) {
			result := invokeCoding(t, Provider{}, "fs.edit", map[string]any{"path": path, "old_string": test.old, "new_string": "new"}, nil)
			if !result.Error || !strings.Contains(result.Contents[0].Text, test.want) {
				t.Fatalf("result=%#v", result)
			}
			written, err := os.ReadFile(path)
			if err != nil || string(written) != original {
				t.Fatalf("written=%q err=%v", written, err)
			}
		})
	}
}

// COD-T-097 is asserted by TestWriteCapabilityCreatesOverwritesAndRejectsDirectories.

// COD-N-021
func TestWriteApprovalPreviewAndStaleHash(t *testing.T) {
	if staleWriteReviewMessage != "File changed since review; nothing was written. Review a new change before approving it." {
		t.Fatalf("stale review message=%q", staleWriteReviewMessage)
	}
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
	if review.SHA256 == nil || *review.SHA256 == "" || !strings.Contains(review.Diff, "- 1|old") || !strings.Contains(review.Diff, "+ 1|new") {
		t.Fatalf("review=%#v", review)
	}
	if result := invokeCoding(t, provider, "fs.write", map[string]any{"path": path, "content": "new\n"}, &runtime.ExpectedFile{Path: review.Path, SHA256: *review.SHA256}); result.Error {
		t.Fatalf("approved write failed: %#v", result)
	}
	if result := invokeCoding(t, provider, "fs.write", map[string]any{"path": path, "content": "stale"}, &runtime.ExpectedFile{Path: review.Path, SHA256: *review.SHA256}); !result.Error || !strings.Contains(result.Contents[0].Text, staleWriteReviewMessage) {
		t.Fatalf("stale write accepted: %#v", result)
	}
}

// COD-N-021
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
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(result.Contents[0].JSON, &fields); err != nil {
		t.Fatal(err)
	}
	if review.SHA256 != nil || !strings.Contains(review.Diff, "+ 1|new file") || len(fields) != 3 || fields["path"] == nil || fields["diff"] == nil || string(fields["sha256"]) != "null" {
		t.Fatalf("review=%#v fields=%v", review, fields)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("preview created file: %v", err)
	}
	approved := invokeCoding(t, Provider{}, "fs.write", map[string]any{"path": path, "content": "new file\n"}, &runtime.ExpectedFile{Path: review.Path})
	if approved.Error {
		t.Fatalf("approved new file write failed: %#v", approved)
	}
	stale := invokeCoding(t, Provider{}, "fs.write", map[string]any{"path": path, "content": "replacement"}, &runtime.ExpectedFile{Path: review.Path})
	if !stale.Error || !strings.Contains(stale.Contents[0].Text, staleWriteReviewMessage) {
		t.Fatalf("write without approved hash overwrote existing file: %#v", stale)
	}
}
