package coding

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/coding/search"
	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// COD-T-189, COD-T-190
func TestSearchTimeoutEmptyResultsAreNonError(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "a.txt"), []byte("hello\n"), 0600); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithDeadline(context.Background(), time.Unix(1, 0))
	defer cancel()
	for _, test := range []struct {
		name     string
		executor runtime.CapabilityExecutor
		args     map[string]any
		empty    string
	}{
		{name: "find", executor: findCapability, args: map[string]any{"path": root, "pattern": "*.zzz", "limit": 10}, empty: "No files found matching pattern"},
		{name: "grep", executor: grepCapability, args: map[string]any{"path": root, "pattern": "zzz", "literal": true, "limit": 10}, empty: "No matches found"},
	} {
		t.Run(test.name, func(t *testing.T) {
			arguments, err := json.Marshal(test.args)
			if err != nil {
				t.Fatal(err)
			}
			call := runtime.CapabilityCall{InvocationID: test.name, Arguments: arguments, TimeoutMillis: 2000}
			result, err := test.executor(ctx, call, runtime.CapabilityContext{})
			if err != nil || result.Error {
				t.Fatalf("result=%#v err=%v", result, err)
			}
			if !strings.Contains(result.Contents[0].Text, "[Search timed out after 2s; results are incomplete. Narrow the path or pattern, or raise timeout_seconds.]") || strings.Contains(result.Contents[0].Text, test.empty) {
				t.Fatalf("text=%q", result.Contents[0].Text)
			}
			var details struct {
				Search struct {
					Outcome    string `json:"outcome"`
					Incomplete bool   `json:"incomplete"`
				} `json:"search"`
			}
			if err := json.Unmarshal(result.Details, &details); err != nil {
				t.Fatal(err)
			}
			if details.Search.Outcome != "TIMED_OUT" || !details.Search.Incomplete {
				t.Fatalf("details=%s", result.Details)
			}
		})
	}
}

// COD-T-188
func TestSearchCancellationReturnsCancelledError(t *testing.T) {
	root := t.TempDir()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	arguments, err := json.Marshal(map[string]any{"path": root, "pattern": "x"})
	if err != nil {
		t.Fatal(err)
	}
	call := runtime.CapabilityCall{InvocationID: "cancel", Arguments: arguments}
	result, err := findCapability(ctx, call, runtime.CapabilityContext{})
	if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "Operation cancelled") {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	var details map[string]any
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	searchDetails := details["search"].(map[string]any)
	if searchDetails["outcome"] != "CANCELLED" || searchDetails["incomplete"] != true {
		t.Fatalf("details=%v", details)
	}
}

// COD-F-010, COD-T-178
func TestSearchLimitNoticeContainsOnlyFirstNResults(t *testing.T) {
	root := t.TempDir()
	for name, content := range map[string]string{"a.txt": "x\nx\nx\n", "b.txt": "x\n"} {
		if err := os.WriteFile(filepath.Join(root, name), []byte(content), 0600); err != nil {
			t.Fatal(err)
		}
	}
	for _, test := range []struct {
		name     string
		executor runtime.CapabilityExecutor
		args     map[string]any
		want     string
	}{
		{name: "find", executor: findCapability, args: map[string]any{"path": root, "pattern": "*.txt", "limit": 1}, want: "[1 results limit reached. Refine the pattern or raise limit.]"},
		{name: "grep", executor: grepCapability, args: map[string]any{"path": root, "pattern": "x", "literal": true, "limit": 2}, want: "[2 matches limit reached. Refine the pattern or raise limit.]"},
	} {
		t.Run(test.name, func(t *testing.T) {
			arguments, err := json.Marshal(test.args)
			if err != nil {
				t.Fatal(err)
			}
			result, err := test.executor(context.Background(), runtime.CapabilityCall{InvocationID: test.name, Arguments: arguments}, runtime.CapabilityContext{})
			if err != nil || result.Error || !strings.HasSuffix(result.Contents[0].Text, test.want) {
				t.Fatalf("result=%#v err=%v", result, err)
			}
			if test.name == "find" && strings.Contains(result.Contents[0].Text, "b.txt") {
				t.Fatalf("find returned beyond limit: %q", result.Contents[0].Text)
			}
			if test.name == "grep" && strings.Contains(result.Contents[0].Text, "a.txt:3:x") {
				t.Fatalf("grep returned beyond limit: %q", result.Contents[0].Text)
			}
		})
	}
}

// COD-F-012, COD-F-014
func TestInvalidSearchRegexIsInputRejection(t *testing.T) {
	arguments, err := json.Marshal(map[string]any{"path": t.TempDir(), "pattern": "[", "literal": false})
	if err != nil {
		t.Fatal(err)
	}
	result, err := grepCapability(context.Background(), runtime.CapabilityCall{InvocationID: "invalid-regex", Arguments: arguments}, runtime.CapabilityContext{})
	if err != nil || !result.Error {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	var details map[string]string
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	text := result.Contents[0].Text
	if details["code"] != "INVALID_ARGUMENT" || !strings.Contains(text, "Invalid regex:") || !strings.Contains(text, "Use literal=true for exact text, or escape regex metacharacters.") || !strings.Contains(text, "The tool was not executed.") {
		t.Fatalf("details=%v text=%q", details, text)
	}
}

// COD-F-011 COD-F-012 COD-T-172 COD-T-173 COD-T-181 COD-T-184: glob filtering, direct files, binary errors, and Git metadata are asserted.
func TestGrepGlobAndDirectFileBehavior(t *testing.T) {
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, ".git"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, ".git", "config"), []byte("x\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, ".gitignore"), []byte("ignored.ts\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	for name, content := range map[string]string{"keep.ts": "x\n", "ignored.ts": "x\n", "other.js": "x\n"} {
		if err := os.WriteFile(filepath.Join(root, name), []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	args := map[string]any{"path": root, "pattern": "x", "literal": true, "glob": "*.ts"}
	included := invokeCoding(t, Provider{}, "fs.grep", args, nil)
	if included.Error || included.Contents[0].Text != "keep.ts:1:x" {
		t.Fatalf("glob result=%#v", included)
	}

	file := filepath.Join(root, "keep.ts")
	resolvedFile, err := filepath.EvalSymlinks(file)
	if err != nil {
		t.Fatal(err)
	}
	direct := invokeCoding(t, Provider{}, "fs.grep", map[string]any{"path": file, "pattern": "x", "literal": true, "glob": "*.js"}, nil)
	if direct.Error || direct.Contents[0].Text != filepath.ToSlash(resolvedFile)+":1:x" {
		t.Fatalf("direct-file result=%#v", direct)
	}

	for _, metadataPath := range []string{filepath.Join(root, ".git"), filepath.Join(root, ".git", "config")} {
		metadata := invokeCoding(t, Provider{}, "fs.grep", map[string]any{"path": metadataPath, "pattern": "x", "literal": true}, nil)
		if metadata.Error || metadata.Contents[0].Text != "No matches found" {
			t.Fatalf("Git metadata path %q result=%#v", metadataPath, metadata)
		}
	}

	binaryFile := filepath.Join(root, "binary.bin")
	if err := os.WriteFile(binaryFile, []byte{'x', 0, 'y'}, 0o600); err != nil {
		t.Fatal(err)
	}
	binaryResult := invokeCoding(t, Provider{}, "fs.grep", map[string]any{"path": binaryFile, "pattern": "x", "literal": true}, nil)
	if !binaryResult.Error || !strings.Contains(binaryResult.Contents[0].Text, "ripgrep failed while searching") {
		t.Fatalf("direct binary result=%#v", binaryResult)
	}
}

// COD-T-043: skipped multiline files remain visible in the standard search summary.
func TestFormatSearchSkippedPathSummary(t *testing.T) {
	dataDir := t.TempDir()
	for _, tc := range []struct {
		name    string
		skipped []string
		want    string
	}{
		{"single", []string{"large.txt"}, "[1 path(s) could not be searched: large.txt]"},
		{"truncated", []string{"a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"}, "[11 path(s) could not be searched: a, b, c, d, e, f, g, h, i, j, ...]"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			result := formatSearchResult(runtime.CapabilityCall{InvocationID: tc.name}, dataDir, search.SearchOutcome{Skipped: tc.skipped}, 10, "No matches found", "matches")
			if result.Error || result.Contents[0].Text != tc.want {
				t.Fatalf("result=%#v want text=%q", result, tc.want)
			}
		})
	}
}

// COD-T-174: removed grep argument names are rejected as unknown arguments.
func TestGrepRejectsRemovedArguments(t *testing.T) {
	root := t.TempDir()
	for _, argument := range []string{"multiline", "include"} {
		result := invokeCoding(t, Provider{}, "fs.grep", map[string]any{
			"path": root, "pattern": "x", argument: true,
		}, nil)
		if !result.Error || !strings.Contains(result.Contents[0].Text, "unknown argument: "+argument) {
			t.Errorf("argument %q result=%#v", argument, result)
		}
	}
}

// COD-F-015
func TestLargeSearchResultUsesDurableOutput(t *testing.T) {
	dataDir := t.TempDir()
	lines := make([]string, inlineOutputLines+1)
	for i := range lines {
		lines[i] = "path.txt:1:match"
	}
	text := strings.Join(lines, "\n")
	result := formatSearchResult(runtime.CapabilityCall{InvocationID: "large"}, dataDir, search.SearchOutcome{Results: lines}, 5000, "No matches found", "matches")
	if result.Error || !strings.Contains(result.Contents[0].Text, "[Full output:") {
		t.Fatalf("result=%#v", result)
	}
	var details struct {
		TextOutput struct {
			Path string `json:"path"`
		} `json:"textOutput"`
	}
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	full, err := os.ReadFile(details.TextOutput.Path)
	if err != nil {
		t.Fatal(err)
	}
	if string(full) != text {
		t.Fatalf("stored bytes=%d want=%d", len(full), len(text))
	}
}
