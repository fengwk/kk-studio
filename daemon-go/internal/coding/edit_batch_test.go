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

// writeEditFile seeds a file with content and returns its path.
func writeEditFile(t *testing.T, name, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

// readEditFile returns the current bytes of path.
func readEditFile(t *testing.T, path string) string {
	t.Helper()
	written, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(written)
}

// editRejectedTail is the guidance every input rejection appends after the problem text.
const editRejectedTail = " The tool was not executed. Correct the arguments to match the tool schema, then call the tool again."

// requireEditProblem asserts the call is rejected with exactly the expected problem lines and no
// write. Messages name the resolved path, so the expectation is built from the same resolution.
func requireEditProblem(t *testing.T, path, original string, args any, message func(resolved string) string) {
	t.Helper()
	resolved, err := filepath.EvalSymlinks(path)
	if err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.edit", args, nil)
	if !result.Error {
		t.Fatalf("edit accepted: %#v", result)
	}
	want := errorSentence(message(resolved))
	text := result.Contents[0].Text
	if !strings.HasPrefix(text, want) || !strings.HasSuffix(text, editRejectedTail) {
		t.Fatalf("result text=%q want=%q + %q", text, want, editRejectedTail)
	}
	if written := readEditFile(t, path); written != original {
		t.Fatalf("rejected edit changed the file to %q", written)
	}
}

// Every entry matches the original snapshot, not the result of an earlier entry.
func TestEditEntriesMatchOriginalSnapshot(t *testing.T) {
	path := writeEditFile(t, "snapshot.txt", "alpha beta\n")
	result := invokeCoding(t, Provider{}, "fs.edit", editArgs(path,
		replace("alpha", "beta"),
		replace("beta", "gamma"),
	), nil)
	if result.Error {
		t.Fatalf("edit failed: %#v", result)
	}
	if written := readEditFile(t, path); written != "beta gamma\n" {
		t.Fatalf("written=%q", written)
	}
	if result.Contents[0].Text != "Edited successfully.\nReplacements: 2" {
		t.Fatalf("result text=%q", result.Contents[0].Text)
	}
}

// One rejected entry reports every problem in a single result so the model can fix them at once.
func TestEditReportsAllProblemsInOneResult(t *testing.T) {
	path := writeEditFile(t, "problems.txt", "keep this line\n")
	requireEditProblem(t, path, "keep this line\n", editArgs(path,
		replace("", "x"),
		replace("absent", "y"),
		replace("keep this line", "keep this line"),
	), func(resolved string) string {
		return strings.Join([]string{
			"Edit 1: old_string must not be empty",
			"Edit 2: Could not find old_string in " + resolved + ".",
			"Edit 3: old_string and new_string must differ after line-ending normalization",
		}, "\n")
	})
}

// A failing entry that copied read line-number prefixes gets one hint naming the first prefix.
func TestEditHintForReadLineNumberPrefixes(t *testing.T) {
	path := writeEditFile(t, "prefix.txt", "first\nsecond\n")
	requireEditProblem(t, path, "first\nsecond\n", editArgs(path,
		replace("  1|first\n  2|second", "replaced"),
	), func(resolved string) string {
		return "Edit 1: Could not find old_string in " + resolved + "." +
			` It includes read line-number prefixes such as "  1|"; copy only the text after the first "|" on each line.`
	})
}

// A whitespace-only difference names the single matching line.
func TestEditHintForWhitespaceIgnoredSingleLine(t *testing.T) {
	content := "one\n\tconst value = 1\nthree\n"
	path := writeEditFile(t, "spaces.txt", content)
	requireEditProblem(t, path, content, editArgs(path,
		replace("const value=1", "const value = 2"),
	), func(resolved string) string {
		return "Edit 1: Could not find old_string in " + resolved +
			". Lines 2 match it when whitespace is ignored; copy the exact text from read."
	})
}

// A whitespace-only difference across a multi-line span names the range plus further matches.
func TestEditHintForWhitespaceIgnoredMultipleRanges(t *testing.T) {
	block := "func a() {\n\treturn 1\n}\n"
	content := strings.Repeat(block, 4)
	path := writeEditFile(t, "ranges.txt", content)
	requireEditProblem(t, path, content, editArgs(path,
		replace("funca(){return1}", "func a() { return 2 }"),
	), func(resolved string) string {
		return "Edit 1: Could not find old_string in " + resolved +
			". Lines 1-3, 4-6, 7-9 and 1 more match it when whitespace is ignored; copy the exact text from read."
	})
}

// Duplicate matches list at most ten line numbers and report the remaining count.
func TestEditHintForDuplicateMatchesCapsLineList(t *testing.T) {
	lines := make([]string, 13)
	for index := range lines {
		lines[index] = "dup = 1"
	}
	content := strings.Join(lines, "\n") + "\n"
	path := writeEditFile(t, "duplicates.txt", content)
	requireEditProblem(t, path, content, editArgs(path, replace("dup = 1", "dup = 2")), func(resolved string) string {
		return "Edit 1: Found 13 exact matches for old_string in " + resolved +
			" at lines 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 and 3 more. Add surrounding context to make it unique, or set replace_all to true."
	})
}

// replace_all refuses self-overlapping occurrences instead of dropping replacements.
func TestEditReplaceAllRejectsSelfOverlap(t *testing.T) {
	path := writeEditFile(t, "overlap.txt", "aaa\n")
	requireEditProblem(t, path, "aaa\n", editArgs(path, replaceAll("aa", "x")), func(resolved string) string {
		return "Edit 1: Found overlapping exact matches for old_string in " + resolved +
			" at lines 1, 1; replace_all cannot apply overlapping replacements. Use a more specific old_string."
	})
}

// Overlaps between entries are reported once per pair, including replace_all expansion.
func TestEditReportsCrossEntryOverlap(t *testing.T) {
	path := writeEditFile(t, "cross.txt", "abab\n")
	requireEditProblem(t, path, "abab\n", editArgs(path,
		replaceAll("ab", "XY"),
		replace("aba", "ABA"),
	), func(resolved string) string {
		return "Edits 1 and 2 overlap at line 1 in " + resolved + ". Entries must change separate parts of the original file."
	})
}

// Two entries with the same old_string overlap each other.
func TestEditReportsIdenticalOldStringAcrossEntries(t *testing.T) {
	path := writeEditFile(t, "identical.txt", "value\n")
	requireEditProblem(t, path, "value\n", editArgs(path,
		replace("value", "one"),
		replace("value", "two"),
	), func(resolved string) string {
		return "Edits 1 and 2 overlap at line 1 in " + resolved + ". Entries must change separate parts of the original file."
	})
}

// Ranges that only touch stay valid because they change separate parts of the file.
func TestEditAllowsTouchingRanges(t *testing.T) {
	path := writeEditFile(t, "touching.txt", "ab\n")
	result := invokeCoding(t, Provider{}, "fs.edit", editArgs(path,
		replace("a", "A"),
		replace("b", "B"),
	), nil)
	if result.Error {
		t.Fatalf("edit failed: %#v", result)
	}
	if written := readEditFile(t, path); written != "AB\n" {
		t.Fatalf("written=%q", written)
	}
}

// An empty edits array is rejected before touching the file system.
func TestEditRejectsEmptyEdits(t *testing.T) {
	path := writeEditFile(t, "empty.txt", "content\n")
	expected := "edits must contain at least one entry; use one entry for a single change"
	requireEditProblem(t, path, "content\n", map[string]any{"path": path, "edits": []any{}}, func(string) string { return expected })
	requireEditProblem(t, path, "content\n", map[string]any{"path": path}, func(string) string { return expected })
}

// Fields outside the new contract are rejected at both levels instead of being ignored.
func TestEditRejectsUnknownFields(t *testing.T) {
	path := writeEditFile(t, "unknown.txt", "before\n")
	invalid := func(string) string { return "invalid arguments" }
	requireEditProblem(t, path, "before\n", map[string]any{"path": path, "old_string": "before", "new_string": "after"}, invalid)
	requireEditProblem(t, path, "before\n", map[string]any{"path": path, "edits": []any{map[string]any{"old_string": "before", "new_string": "after", "extra": true}}}, invalid)
}

// Arguments must be exactly one JSON object; trailing content is not silently ignored.
func TestEditRejectsTrailingArguments(t *testing.T) {
	path := writeEditFile(t, "trailing.txt", "before\n")
	arguments := []byte(`{"path":"` + path + `","edits":[{"old_string":"before","new_string":"after"}]}{}`)
	result, err := editCapability(context.Background(), runtime.CapabilityCall{InvocationID: "trailing", Arguments: arguments}, runtime.CapabilityContext{})
	if err != nil || !result.Error || !strings.HasPrefix(result.Contents[0].Text, "invalid arguments") {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	if written := readEditFile(t, path); written != "before\n" {
		t.Fatalf("rejected edit changed the file to %q", written)
	}
}

// Entries that normalize to the same text are rejected, so no entry can silently do nothing.
func TestEditRejectsEntryWithOnlyLineEndingChanges(t *testing.T) {
	path := writeEditFile(t, "eol-only.txt", "one\r\ntwo\r\n")
	requireEditProblem(t, path, "one\r\ntwo\r\n", editArgs(path,
		replace("one\r\n", "one\n"),
		replace("two", "TWO"),
	), func(string) string {
		return "Edit 1: old_string and new_string must differ after line-ending normalization"
	})
}

// Touching entries whose combined effect rebuilds the original bytes are rejected as no change.
func TestEditRejectsNoOpCall(t *testing.T) {
	path := writeEditFile(t, "noop.txt", "ab")
	requireEditProblem(t, path, "ab", editArgs(path,
		replace("a", ""),
		replace("b", "ab"),
	), func(string) string {
		return "No changes to apply: the edits would not change the file"
	})
}

// Mixed CRLF, LF and CR preserve every untouched byte and use the span's line break for new lines.
func TestEditMixedLineEndingsUseSpanBreak(t *testing.T) {
	path := writeEditFile(t, "mixed.txt", "x\ny\r\nx\r\n")
	result := invokeCoding(t, Provider{}, "fs.edit", editArgs(path, replace("y", "x\nz")), nil)
	if result.Error {
		t.Fatalf("edit failed: %#v", result)
	}
	if written := readEditFile(t, path); written != "x\nx\r\nz\r\nx\r\n" {
		t.Fatalf("written=%q", written)
	}
}

// BOM and UTF-16 encoding survive a call with several entries.
func TestEditPreservesBOMAndUTF16AcrossEntries(t *testing.T) {
	path := filepath.Join(t.TempDir(), "utf16.txt")
	original, err := EncodeText("one\r\ntwo\r\nthree\r\n", "UTF-16LE", 2)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, original, 0o600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.edit", editArgs(path,
		replace("one", "ONE"),
		replace("three", "THREE"),
	), nil)
	if result.Error {
		t.Fatalf("edit failed: %#v", result)
	}
	decoded, err := DecodeText([]byte(readEditFile(t, path)))
	if err != nil {
		t.Fatal(err)
	}
	if decoded.Charset != "UTF-16LE" || decoded.BOMBytes != 2 || decoded.Text != "ONE\r\ntwo\r\nTHREE\r\n" {
		t.Fatalf("decoded=%#v", decoded)
	}
}

// Preview and execution compute the same review for a multi-entry call.
func TestEditPreviewDiffMatchesExecution(t *testing.T) {
	path := writeEditFile(t, "review.txt", "one\ntwo\nthree\n")
	args := editArgs(path, replace("one", "ONE"), replace("three", "THREE"))
	preview := invokeCoding(t, Provider{}, "fs.edit.preview", args, nil)
	if preview.Error {
		t.Fatalf("preview=%#v", preview)
	}
	var review struct{ Path, Diff, SHA256 string }
	if err := json.Unmarshal(preview.Contents[0].JSON, &review); err != nil {
		t.Fatal(err)
	}
	commit := invokeCoding(t, Provider{}, "fs.edit", args, &runtime.ExpectedFile{Path: review.Path, SHA256: review.SHA256})
	if commit.Error {
		t.Fatalf("commit=%#v", commit)
	}
	var details struct {
		EditReview struct{ Path, Diff, SHA256 string } `json:"editReview"`
	}
	if err := json.Unmarshal(commit.Details, &details); err != nil {
		t.Fatal(err)
	}
	if details.EditReview.Diff != review.Diff || details.EditReview.SHA256 != review.SHA256 {
		t.Fatalf("preview=%#v commit=%#v", review, details.EditReview)
	}
	hash := sha256.Sum256([]byte("one\ntwo\nthree\n"))
	if review.SHA256 != hex.EncodeToString(hash[:]) {
		t.Fatalf("preview hash=%q", review.SHA256)
	}
}
