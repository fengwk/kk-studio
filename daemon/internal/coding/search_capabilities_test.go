package coding

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/coding/search"
	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

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
		{name: "find", executor: findCapability, args: map[string]any{"path": root, "glob": "*.zzz", "limit": 10}, empty: "No files matched the glob"},
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

func TestSearchCancellationReturnsCancelledError(t *testing.T) {
	root := t.TempDir()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	arguments, err := json.Marshal(map[string]any{"path": root, "glob": "*"})
	if err != nil {
		t.Fatal(err)
	}
	call := runtime.CapabilityCall{InvocationID: "cancel", Arguments: arguments}
	result, err := findCapability(ctx, call, runtime.CapabilityContext{})
	if err != nil || !result.Error || len(result.Contents) != 1 || result.Contents[0].Text != "[Search cancelled; results are incomplete.]" {
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
		{name: "find", executor: findCapability, args: map[string]any{"path": root, "glob": "*.txt", "limit": 1}, want: "[1 results limit reached. Refine the pattern or raise limit.]"},
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

// Reaching N stops content reads and emits the exact limit notice.
func TestGrepExactLimitNoticeText(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "a.txt"), []byte("x\n"), 0600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.grep", map[string]any{"path": root, "pattern": "x", "literal": true, "limit": 1}, nil)
	want := "a.txt:1:x\n\n[1 matches limit reached. Refine the pattern or raise limit.]"
	if result.Error || result.Contents[0].Text != want {
		t.Fatalf("result=%#v want text %q", result, want)
	}
}

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

// Go RE2's unsupported extended-mode diagnostic is visible to callers.
func TestExtendedRegexModeDiagnosticIsModelVisible(t *testing.T) {
	root := t.TempDir()
	pattern := "(?x)é"
	_, diagnosticErr := search.GrepContext(context.Background(), root, pattern, false, false, "", 0)
	if diagnosticErr == nil || !strings.HasPrefix(diagnosticErr.Error(), "invalid regex:") {
		t.Fatalf("extended-mode search error=%v", diagnosticErr)
	}
	want := "Invalid regex: " + strings.TrimSpace(strings.TrimPrefix(diagnosticErr.Error(), "invalid regex:")) + ". Use literal=true for exact text, or escape regex metacharacters."
	arguments, err := json.Marshal(map[string]any{"path": root, "pattern": pattern, "literal": false})
	if err != nil {
		t.Fatal(err)
	}
	result, err := grepCapability(context.Background(), runtime.CapabilityCall{InvocationID: "extended-regex", Arguments: arguments}, runtime.CapabilityContext{})
	if err != nil || !result.Error {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	var details map[string]string
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	text := result.Contents[0].Text
	if details["code"] != "INVALID_ARGUMENT" || !strings.Contains(text, want) || !strings.Contains(text, "The tool was not executed.") {
		t.Fatalf("details=%v text=%q want diagnostic %q", details, text, want)
	}
}

// Both tools use the same file filter, without expanding past the given root or ignore rules.
func TestSearchCapabilitiesUseRootRelativeGlobs(t *testing.T) {
	root := filepath.Join(t.TempDir(), "src", "repo")
	for _, name := range []string{"root.ts", "src/a.ts", "src/deep/c.ts", "pkg/src/b.ts", "ignored.ts", "!name.txt"} {
		path := filepath.Join(root, filepath.FromSlash(name))
		if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte("needle\n"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(root, ".gitignore"), []byte("ignored.ts\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	all := []string{"pkg/src/b.ts", "root.ts", "src/a.ts", "src/deep/c.ts"}
	for _, tc := range []struct {
		glob, subdir string
		files        []string
	}{
		{glob: "*.ts", files: all},
		{glob: "./*.ts", files: all},
		{glob: "src/*.ts", files: []string{"src/a.ts"}},
		{glob: "./src/*.ts", files: []string{"src/a.ts"}},
		{glob: "././src/*.ts", files: []string{"src/a.ts"}},
		{glob: "src/**/*.ts", files: []string{"src/a.ts", "src/deep/c.ts"}},
		{glob: "**/src/*.ts", files: []string{"pkg/src/b.ts", "src/a.ts"}},
		{glob: "./**/src/*.ts", subdir: "src"},
		{glob: "**/src/repo/*.ts"},
		{glob: filepath.ToSlash(filepath.Join(root, "src", "*.ts"))},
		{glob: "!./*.ts", subdir: "src"},
		{glob: "./!name.txt", files: []string{"!name.txt"}},
	} {
		t.Run(tc.glob, func(t *testing.T) {
			path := filepath.Join(root, tc.subdir)
			find := invokeCoding(t, Provider{}, "fs.find-files", map[string]any{"path": path, "glob": tc.glob}, nil)
			grep := invokeCoding(t, Provider{}, "fs.grep", map[string]any{"path": path, "pattern": "needle", "literal": true, "glob": tc.glob}, nil)
			wantFind, wantGrep := "No files matched the glob", "No matches found"
			if len(tc.files) != 0 {
				wantFind = strings.Join(tc.files, "\n")
				var lines []string
				for _, file := range tc.files {
					lines = append(lines, file+":1:needle")
				}
				wantGrep = strings.Join(lines, "\n")
			}
			if find.Error || find.Contents[0].Text != wantFind || grep.Error || grep.Contents[0].Text != wantGrep {
				t.Fatalf("find=%q (want %q), grep=%q (want %q)", find.Contents[0].Text, wantFind, grep.Contents[0].Text, wantGrep)
			}
		})
	}
}

// A prefix without a pattern is an input error, not a request to search all files.
func TestInvalidSearchGlobIsInputRejection(t *testing.T) {
	root := t.TempDir()
	for _, tc := range []struct{ glob, diagnostic string }{
		{"[", "Invalid glob:"},
		{"./", "expected a pattern after './'"},
		{"././", "expected a pattern after './'"},
		{"!./", "expected a pattern after './'"},
	} {
		t.Run(tc.glob, func(t *testing.T) {
			for _, capability := range []string{"fs.find-files", "fs.grep"} {
				args := map[string]any{"path": root, "glob": tc.glob}
				if capability == "fs.grep" {
					args["pattern"], args["literal"] = "needle", true
				}
				result := invokeCoding(t, Provider{}, capability, args, nil)
				var details map[string]string
				if err := json.Unmarshal(result.Details, &details); err != nil {
					t.Fatal(err)
				}
				text := result.Contents[0].Text
				if !result.Error || details["code"] != "INVALID_ARGUMENT" || !strings.Contains(text, tc.diagnostic) || !strings.Contains(text, "The tool was not executed.") {
					t.Fatalf("%s: details=%v text=%q want rejection containing %q", capability, details, text, tc.diagnostic)
				}
			}
		})
	}
}

// Glob filtering, direct files, binary errors, and Git metadata are asserted.
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
	if !binaryResult.Error || !strings.Contains(binaryResult.Contents[0].Text, "is a binary file (it contains NUL bytes)") {
		t.Fatalf("direct binary result=%#v", binaryResult)
	}
}

// Skipped paths are summarized without failing the result.
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

// Empty search results explain ignored paths, but matches stay uncluttered.
func TestEmptySearchResultIgnoreHint(t *testing.T) {
	dataDir := t.TempDir()
	outcome := search.SearchOutcome{IgnoreSkipped: []string{"/repo/.env"}, IgnoreSkippedCount: 1}
	result := formatSearchResult(runtime.CapabilityCall{InvocationID: "empty"}, dataDir, outcome, 10, "No matches found", "matches")
	want := "No matches found\n[No matches. Not searched because of ignore rules: /repo/.env]"
	if result.Error || result.Contents[0].Text != want {
		t.Fatalf("text=%q want=%q", result.Contents[0].Text, want)
	}

	outcome.Results = []string{"a.txt:1:match"}
	result = formatSearchResult(runtime.CapabilityCall{InvocationID: "match"}, dataDir, outcome, 10, "No matches found", "matches")
	if result.Error || result.Contents[0].Text != "a.txt:1:match" {
		t.Fatalf("non-empty result has ignore hint: %q", result.Contents[0].Text)
	}
}

// Both capabilities attach the real ignored path when no match is found.
func TestEmptyFindAndGrepListIgnoredPaths(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, ".gitignore"), []byte("secret.txt\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "secret.txt"), []byte("not a match\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	resolvedRoot, err := filepath.EvalSymlinks(root)
	if err != nil {
		t.Fatal(err)
	}
	ignored := filepath.ToSlash(filepath.Join(resolvedRoot, "secret.txt"))
	for _, test := range []struct {
		capability string
		args       map[string]any
		want       string
	}{
		{capability: "fs.find-files", args: map[string]any{"path": root, "glob": "secret*.txt"}, want: "No files matched the glob"},
		{capability: "fs.grep", args: map[string]any{"path": root, "pattern": "match", "literal": true, "glob": "secret*.txt"}, want: "No matches found"},
	} {
		result := invokeCoding(t, Provider{}, test.capability, test.args, nil)
		want := test.want + "\n[No matches. Not searched because of ignore rules: " + ignored + "]"
		if result.Error || result.Contents[0].Text != want {
			t.Errorf("%s result=%#v want text=%q", test.capability, result, want)
		}
	}
}

// The hint bounds visible paths and reports the exact omitted count.
func TestEmptySearchResultIgnoreHintLimit(t *testing.T) {
	paths := make([]string, 12)
	for i := range paths {
		paths[i] = fmt.Sprintf("/repo/%02d", i)
	}
	got := formatHintList("No matches. Not searched because of ignore rules: ", paths[:10], 12)
	want := "[No matches. Not searched because of ignore rules: /repo/00, /repo/01, /repo/02, /repo/03, /repo/04, /repo/05, /repo/06, /repo/07, /repo/08, /repo/09, … and 2 more]"
	if got != want {
		t.Fatalf("hint=%q want=%q", got, want)
	}
}
