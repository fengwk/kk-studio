package coding

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

func TestEditReviewRejectsChangedHashWithoutWriting(t *testing.T) {
	path := filepath.Join(t.TempDir(), "file.txt")
	original := []byte("initial content\n")
	if err := os.WriteFile(path, original, 0o600); err != nil {
		t.Fatal(err)
	}
	hash := sha256.Sum256(original)
	changed := []byte("external change\n")
	if err := os.WriteFile(path, changed, 0o600); err != nil {
		t.Fatal(err)
	}
	resolved, err := filepath.EvalSymlinks(path)
	if err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.edit", editArgs(path, replace("external", "modified")), &runtime.ExpectedFile{Path: resolved, SHA256: hex.EncodeToString(hash[:])})
	if !result.Error || !strings.Contains(result.Contents[0].Text, staleWriteReviewMessage) {
		t.Fatalf("result=%#v", result)
	}
	actual, err := os.ReadFile(path)
	if err != nil || string(actual) != string(changed) {
		t.Fatalf("rejected edit changed file to %q: %v", actual, err)
	}
}

func TestEditUTF16ReviewPreservesBOMCRLFAndExpectedHash(t *testing.T) {
	path := filepath.Join(t.TempDir(), "utf16.txt")
	original, err := encodeFixture("第一行\r\n第二行\r\n第三行\r\n", "UTF-16LE", 2)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, original, 0o600); err != nil {
		t.Fatal(err)
	}
	hash := sha256.Sum256(original)
	resolved, err := filepath.EvalSymlinks(path)
	if err != nil {
		t.Fatal(err)
	}
	args := editArgs(path, replace("第二行", "修改行"))
	preview := invokeCoding(t, Provider{}, "fs.edit.preview", args, nil)
	if preview.Error {
		t.Fatalf("preview=%#v", preview)
	}
	var review struct {
		Path string `json:"path"`
		SHA  string `json:"sha256"`
		Diff string `json:"diff"`
	}
	if err := json.Unmarshal(preview.Contents[0].JSON, &review); err != nil {
		t.Fatal(err)
	}
	if review.Path != resolved || review.SHA != hex.EncodeToString(hash[:]) || !strings.Contains(review.Diff, "- 2|第二行") || !strings.Contains(review.Diff, "+ 2|修改行") {
		t.Fatalf("review=%#v", review)
	}
	commit := invokeCoding(t, Provider{}, "fs.edit", args, &runtime.ExpectedFile{Path: resolved, SHA256: review.SHA})
	var committed struct {
		EditReview struct {
			Path string `json:"path"`
			SHA  string `json:"sha256"`
			Diff string `json:"diff"`
		} `json:"editReview"`
	}
	if err := json.Unmarshal(commit.Details, &committed); err != nil {
		t.Fatal(err)
	}
	if commit.Error || committed.EditReview != review {
		t.Fatalf("commit review=%#v preview review=%#v error=%v", committed.EditReview, review, commit.Error)
	}
	written, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	decoded, encoding, err := decodeFixture(written)
	if err != nil || encoding.Name != "UTF-16LE" || !encoding.BOM || decoded != "第一行\r\n修改行\r\n第三行\r\n" {
		t.Fatalf("decoded=%q encoding=%#v err=%v", decoded, encoding, err)
	}
}

func TestEditMultihunkReviewMatchesCommitDetails(t *testing.T) {
	path := filepath.Join(t.TempDir(), "multi.txt")
	lines := []string{"line 01", "line 02", "line 03", "line 04", "line 05", "line 06", "target alpha", "line 08", "line 09", "line 10", "line 11", "line 12", "target beta", "line 14", "line 15", "line 16", "line 17", "line 18", "line 19"}
	if err := os.WriteFile(path, []byte(strings.Join(lines, "\n")+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	args := editArgs(path, replaceAll("target", "replaced"))
	preview := invokeCoding(t, Provider{}, "fs.edit.preview", args, nil)
	if preview.Error {
		t.Fatalf("preview=%#v", preview)
	}
	var review struct {
		Path string `json:"path"`
		SHA  string `json:"sha256"`
		Diff string `json:"diff"`
	}
	if err := json.Unmarshal(preview.Contents[0].JSON, &review); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(review.Diff, "- 7|target alpha") || !strings.Contains(review.Diff, "+ 7|replaced alpha") || !strings.Contains(review.Diff, "-13|target beta") || !strings.Contains(review.Diff, "+13|replaced beta") || !strings.Contains(review.Diff, "...") {
		t.Fatalf("multihunk diff=%q", review.Diff)
	}
	commit := invokeCoding(t, Provider{}, "fs.edit", args, &runtime.ExpectedFile{Path: review.Path, SHA256: review.SHA})
	var committed struct {
		EditReview struct {
			Path string `json:"path"`
			SHA  string `json:"sha256"`
			Diff string `json:"diff"`
		} `json:"editReview"`
	}
	if err := json.Unmarshal(commit.Details, &committed); err != nil {
		t.Fatal(err)
	}
	if commit.Error || committed.EditReview != review || !strings.Contains(commit.Contents[0].Text, "Replacements: 2") {
		t.Fatalf("commit=%#v preview=%#v", committed, review)
	}
	updated, err := os.ReadFile(path)
	if err != nil || !strings.Contains(string(updated), "replaced alpha") || !strings.Contains(string(updated), "replaced beta") || strings.Contains(string(updated), "target") {
		t.Fatalf("updated=%q err=%v", updated, err)
	}
}

func TestEditReviewRejectsWrongPathWithoutWriting(t *testing.T) {
	path := filepath.Join(t.TempDir(), "file.txt")
	original := []byte("initial content\n")
	if err := os.WriteFile(path, original, 0o600); err != nil {
		t.Fatal(err)
	}
	hash := sha256.Sum256(original)
	result := invokeCoding(t, Provider{}, "fs.edit", editArgs(path, replace("initial", "modified")), &runtime.ExpectedFile{
		Path:   filepath.Join(filepath.Dir(path), "other.txt"),
		SHA256: hex.EncodeToString(hash[:]),
	})
	if !result.Error || !strings.Contains(result.Contents[0].Text, staleWriteReviewMessage) {
		t.Fatalf("result=%#v", result)
	}
	actual, err := os.ReadFile(path)
	if err != nil || string(actual) != string(original) {
		t.Fatalf("rejected edit changed file to %q: %v", actual, err)
	}
}

func TestEditReviewConcurrentApprovalsCommitOnlyOnce(t *testing.T) {
	path := filepath.Join(t.TempDir(), "file.txt")
	original := []byte("initial\n")
	if err := os.WriteFile(path, original, 0o600); err != nil {
		t.Fatal(err)
	}
	resolved, err := filepath.EvalSymlinks(path)
	if err != nil {
		t.Fatal(err)
	}
	hash := sha256.Sum256(original)
	expected := &runtime.ExpectedFile{Path: resolved, SHA256: hex.EncodeToString(hash[:])}
	args := []map[string]any{
		editArgs(path, replace("initial", "winner one")),
		editArgs(path, replace("initial", "winner two")),
	}
	results := make([]runtime.CapabilityResult, len(args))
	var wg sync.WaitGroup
	for i := range args {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			encoded, err := json.Marshal(args[i])
			if err != nil {
				t.Errorf("marshal arguments: %v", err)
				return
			}
			call := runtime.CapabilityCall{InvocationID: fmt.Sprintf("edit-%d", i), CapabilityID: "fs.edit", Arguments: encoded, ExpectedFile: expected}
			result, err := Provider{}.Capabilities()["fs.edit"](t.Context(), call, runtime.CapabilityContext{})
			if err != nil {
				t.Errorf("edit call: %v", err)
				return
			}
			results[i] = result
		}(i)
	}
	wg.Wait()
	if results[0].Error == results[1].Error {
		t.Fatalf("exactly one approval should commit: %#v", results)
	}
	failed := results[0]
	if !failed.Error {
		failed = results[1]
	}
	if !strings.Contains(failed.Contents[0].Text, staleWriteReviewMessage) {
		t.Fatalf("losing approval result=%#v", failed)
	}
	actual, err := os.ReadFile(path)
	if err != nil || (string(actual) != "winner one\n" && string(actual) != "winner two\n") {
		t.Fatalf("final file=%q err=%v", actual, err)
	}
}

func TestEditReviewLongChangedLineIsComplete(t *testing.T) {
	path := filepath.Join(t.TempDir(), "long.txt")
	if err := os.WriteFile(path, []byte("old\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	largeLine := strings.Repeat("x", 30*1024-1) + "😀"
	result := invokeCoding(t, Provider{}, "fs.edit.preview", editArgs(path, replace("old", largeLine)), nil)
	if result.Error {
		t.Fatalf("preview=%#v", result)
	}
	var review struct{ Diff string }
	if err := json.Unmarshal(result.Contents[0].JSON, &review); err != nil {
		t.Fatal(err)
	}
	if len(review.Diff) <= 30*1024 || !strings.Contains(review.Diff, "+ 1|"+largeLine) || strings.Contains(review.Diff, "truncated") {
		t.Fatalf("long diff length=%d", len(review.Diff))
	}
}

func TestEditReviewTransportLimitRejectsPreviewAndCommit(t *testing.T) {
	path := filepath.Join(t.TempDir(), "large.txt")
	oldLine := strings.Repeat("a", 600*1024)
	newLine := strings.Repeat("b", 600*1024)
	original := []byte(oldLine + "\n")
	if err := os.WriteFile(path, original, 0o600); err != nil {
		t.Fatal(err)
	}
	args := editArgs(path, replace(oldLine, newLine))
	preview := invokeCoding(t, Provider{}, "fs.edit.preview", args, nil)
	const limitMessage = "Complete edit diff exceeds the review transport limit; split the change into several smaller edit calls."
	if !preview.Error || !strings.Contains(preview.Contents[0].Text, limitMessage) {
		t.Fatalf("preview=%#v", preview)
	}
	hash := sha256.Sum256(original)
	resolved, err := filepath.EvalSymlinks(path)
	if err != nil {
		t.Fatal(err)
	}
	commit := invokeCoding(t, Provider{}, "fs.edit", args, &runtime.ExpectedFile{Path: resolved, SHA256: hex.EncodeToString(hash[:])})
	if !commit.Error || !strings.Contains(commit.Contents[0].Text, limitMessage) {
		t.Fatalf("commit=%#v", commit)
	}
	actual, err := os.ReadFile(path)
	if err != nil || string(actual) != string(original) {
		t.Fatalf("rejected edit changed file: err=%v", err)
	}
}

func TestEditWithoutExpectedHashReturnsReview(t *testing.T) {
	path := filepath.Join(t.TempDir(), "yolo.txt")
	original := []byte("before\n")
	if err := os.WriteFile(path, original, 0o600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.edit", editArgs(path, replace("before", "after")), nil)
	if result.Error || result.Contents[0].Text != "Edited successfully.\nReplacements: 1" {
		t.Fatalf("result=%#v", result)
	}
	var details struct {
		EditReview struct {
			Path   string `json:"path"`
			SHA256 string `json:"sha256"`
			Diff   string `json:"diff"`
		} `json:"editReview"`
	}
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	hash := sha256.Sum256(original)
	resolved, err := filepath.EvalSymlinks(path)
	if err != nil {
		t.Fatal(err)
	}
	if details.EditReview.Path != resolved || details.EditReview.SHA256 != hex.EncodeToString(hash[:]) || !strings.Contains(details.EditReview.Diff, "+ 1|after") {
		t.Fatalf("edit review=%#v", details.EditReview)
	}
	actual, err := os.ReadFile(path)
	if err != nil || string(actual) != "after\n" {
		t.Fatalf("written file=%q err=%v", actual, err)
	}
}

func TestNumberedDiff1500LineChangesRemainMinimal(t *testing.T) {
	oldLines, newLines := make([]string, 1500), make([]string, 1500)
	for i := range oldLines {
		oldLines[i], newLines[i] = fmt.Sprintf("mid_%d", i), fmt.Sprintf("mid_%d", i)
	}
	oldLines[0], oldLines[1499] = "first_old", "last_old"
	newLines[0], newLines[1499] = "first_new", "last_new"
	assertEdgeDiff(t, oldLines, newLines, 1500)
}

func TestNumberedDiff50000LineChangesRemainMinimal(t *testing.T) {
	oldLines, newLines := make([]string, 50000), make([]string, 50000)
	for i := range oldLines {
		oldLines[i], newLines[i] = fmt.Sprintf("line_%d", i), fmt.Sprintf("line_%d", i)
	}
	oldLines[0], oldLines[49999] = "first_old", "last_old"
	newLines[0], newLines[49999] = "first_new", "last_new"
	assertEdgeDiff(t, oldLines, newLines, 50000)
}

func assertEdgeDiff(t *testing.T, oldLines, newLines []string, count int) {
	t.Helper()
	diff, err := numberedDiff(strings.Join(oldLines, "\n")+"\n", strings.Join(newLines, "\n")+"\n")
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(diff, "\n")
	var removed, added []string
	for _, line := range lines {
		if strings.HasPrefix(line, "-") {
			removed = append(removed, line)
		}
		if strings.HasPrefix(line, "+") {
			added = append(added, line)
		}
	}
	if len(removed) != 2 || len(added) != 2 || !strings.Contains(removed[0], " 1|first_old") || !strings.Contains(added[0], " 1|first_new") || !strings.Contains(removed[1], fmt.Sprintf("%d|last_old", count)) || !strings.Contains(added[1], fmt.Sprintf("%d|last_new", count)) || !strings.Contains(diff, "...") {
		t.Fatalf("count=%d removed=%v added=%v diff-prefix=%q", count, removed, added, diff[:min(300, len(diff))])
	}
}

func TestNumberedDiffLargeChangedRegionEmitsCompleteReplacements(t *testing.T) {
	oldLines, newLines := make([]string, 1500), make([]string, 1500)
	for i := range oldLines {
		oldLines[i] = fmt.Sprintf("old_%d", i)
		newLines[i] = fmt.Sprintf("new_%d", i)
	}
	diff, err := numberedDiff(strings.Join(oldLines, "\n")+"\n", strings.Join(newLines, "\n")+"\n")
	if err != nil {
		t.Fatal(err)
	}
	removed, added := 0, 0
	for _, line := range strings.Split(diff, "\n") {
		if strings.HasPrefix(line, "-") {
			removed++
		}
		if strings.HasPrefix(line, "+") {
			added++
		}
	}
	if removed != 1500 || added != 1500 || !strings.Contains(diff, "-   1|old_0") || !strings.Contains(diff, "-1500|old_1499") || !strings.Contains(diff, "+   1|new_0") || !strings.Contains(diff, "+1500|new_1499") {
		t.Fatalf("large diff has %d removals and %d additions; prefix=%q suffix=%q", removed, added, diff[:min(80, len(diff))], diff[max(0, len(diff)-80):])
	}
}

func TestNumberedDiffPureAddAndDelete(t *testing.T) {
	add, err := numberedDiff("header\n", "header\nline1\nline2\nline3\n")
	if err != nil {
		t.Fatal(err)
	}
	if add != "  1|header\n+ 2|line1\n+ 3|line2\n+ 4|line3" {
		t.Fatalf("add diff=%q", add)
	}
	remove, err := numberedDiff("line1\nline2\nline3\n", "")
	if err != nil {
		t.Fatal(err)
	}
	if remove != "- 1|line1\n- 2|line2\n- 3|line3" {
		t.Fatalf("delete diff=%q", remove)
	}
}

func TestNumberedDiffPureInsertionAndDeletion(t *testing.T) {
	insert, err := numberedDiff("alpha\nomega\n", "alpha\ninserted1\ninserted2\nomega\n")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(insert, "+ 2|inserted1") || !strings.Contains(insert, "+ 3|inserted2") || strings.Contains(insert, "-") {
		t.Fatalf("insert diff=%q", insert)
	}
	remove, err := numberedDiff("alpha\ninserted1\ninserted2\nomega\n", "alpha\nomega\n")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(remove, "- 2|inserted1") || !strings.Contains(remove, "- 3|inserted2") || strings.Contains(remove, "+") {
		t.Fatalf("delete diff=%q", remove)
	}
}

func TestNumberedDiffContextAtUnterminatedEOF(t *testing.T) {
	diff, err := numberedDiff("line1\nline2", "LINE1\nline2")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(diff, "- 1|line1") || !strings.Contains(diff, "+ 1|LINE1") || !strings.Contains(diff, " 2|line2") || strings.Contains(diff, `\ No newline at end of file`) {
		t.Fatalf("diff=%q", diff)
	}
}

func TestNumberedDiffRepeatedLinesAlignChangedLine(t *testing.T) {
	diff, err := numberedDiff("dup\ndup\ndup\ndup\n", "dup\nchanged\ndup\ndup\n")
	if err != nil {
		t.Fatal(err)
	}
	removed, added := 0, 0
	for _, line := range strings.Split(diff, "\n") {
		if strings.HasPrefix(line, "-") {
			removed++
		}
		if strings.HasPrefix(line, "+") {
			added++
		}
	}
	if !strings.Contains(diff, "+ 2|changed") || !strings.Contains(diff, "- 2|dup") || removed != 1 || added != 1 {
		t.Fatalf("diff=%q", diff)
	}
}

func TestNumberedDiffLeadingAndTrailingContextBoundaries(t *testing.T) {
	for _, tc := range []struct {
		name    string
		content string
		want    []string
		avoid   []string
		omitted bool
	}{
		{name: "leading-four", content: "c1\nc2\nc3\nc4\nold\n", want: []string{" 1|c1", " 4|c4"}},
		{name: "leading-five", content: "c1\nc2\nc3\nc4\nc5\nold\n", want: []string{"...\n", " 2|c2", " 5|c5"}, avoid: []string{" 1|c1"}, omitted: true},
		{name: "trailing-four", content: "old\nc1\nc2\nc3\nc4\n", want: []string{" 2|c1", " 5|c4"}},
		{name: "trailing-five", content: "old\nc1\nc2\nc3\nc4\nc5\n", want: []string{" 2|c1", " 5|c4", "..."}, avoid: []string{" 6|c5"}, omitted: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			diff, err := numberedDiff(tc.content, strings.Replace(tc.content, "old", "new", 1))
			if err != nil {
				t.Fatal(err)
			}
			if strings.Contains(diff, "...") != tc.omitted {
				t.Fatalf("diff=%q omitted=%v", diff, tc.omitted)
			}
			for _, want := range tc.want {
				if !strings.Contains(diff, want) {
					t.Errorf("diff=%q missing %q", diff, want)
				}
			}
			for _, avoid := range tc.avoid {
				if strings.Contains(diff, avoid) {
					t.Errorf("diff=%q unexpectedly contains %q", diff, avoid)
				}
			}
		})
	}
}

func TestPreviewRejectsNoChangeWithoutEmptyDiff(t *testing.T) {
	path := filepath.Join(t.TempDir(), "same.txt")
	if err := os.WriteFile(path, []byte("a\nb\nc\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.edit.preview", editArgs(path, replace("b", "b")), nil)
	if !result.Error || !strings.Contains(result.Contents[0].Text, "Edit 1: old_string and new_string must differ after line-ending normalization") {
		t.Fatalf("result=%#v", result)
	}
}
