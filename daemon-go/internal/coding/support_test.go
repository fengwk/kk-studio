package coding

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// COD-F-014, COD-T-203
func TestToErrorResultClassifiesStableFailures(t *testing.T) {
	for _, test := range []struct {
		name string
		err  error
		code string
		text string
	}{
		{name: "input", err: rejectInput("  bad arguments  "), code: "INVALID_ARGUMENT", text: "bad arguments. The tool was not executed. Correct the arguments to match the tool schema, then call the tool again."},
		{name: "run", err: failRun("operation failed!", "FAILED"), code: "FAILED", text: "operation failed! Whether the tool took effect cannot be confirmed. Verify whether the intended effect already took place before deciding what to do next."},
		{name: "service", err: failService("service unavailable"), code: "SERVICE_FAILURE", text: "service unavailable. Whether the tool took effect cannot be confirmed. Verify whether the intended effect already took place before deciding what to do next."},
		{name: "cancelled", err: context.Canceled, code: "CANCELLED", text: "Operation cancelled. Whether the tool took effect cannot be confirmed. Check whether the operation already took effect before deciding what to do next."},
		{name: "timeout", err: context.DeadlineExceeded, code: "TIMEOUT", text: "Operation timed out. Whether the tool took effect cannot be confirmed. Check whether the operation already took effect before deciding what to do next."},
		{name: "unknown", err: errors.New("private secret"), code: "INTERNAL", text: "The capability failed while running and the underlying error is not repeated to avoid leaking details. Whether the tool took effect cannot be confirmed. Verify whether the intended effect already took place before deciding what to do next."},
	} {
		t.Run(test.name, func(t *testing.T) {
			result := errorResult("call", test.err)
			var details map[string]string
			if err := json.Unmarshal(result.Details, &details); err != nil {
				t.Fatal(err)
			}
			if !result.Error || details["code"] != test.code || result.Contents[0].Text != test.text {
				t.Fatalf("result=%#v expected code=%s text=%q", result, test.code, test.text)
			}
			if test.name == "unknown" && strings.Contains(result.Contents[0].Text, "private secret") {
				t.Fatal("unknown error leaked its message")
			}
		})
	}
}

// COD-F-014, COD-T-206
func TestFileSystemErrorsUseStableCodeAndSafeMessage(t *testing.T) {
	for _, test := range []struct {
		name string
		err  error
		code string
		text string
	}{
		{name: "permission", err: &os.PathError{Op: "open", Path: "secret", Err: syscall.EACCES}, code: "PERMISSION_DENIED", text: "permission denied while accessing the path"},
		{name: "operation-not-permitted", err: &os.PathError{Op: "open", Path: "secret", Err: syscall.EPERM}, code: "PERMISSION_DENIED", text: "permission denied while accessing the path"},
		{name: "not-found", err: &os.PathError{Op: "open", Path: "secret", Err: syscall.ENOENT}, code: "NOT_FOUND", text: "path does not exist"},
		{name: "directory", err: &os.PathError{Op: "open", Path: "secret", Err: syscall.EISDIR}, code: "INVALID_ARGUMENT", text: "path is a directory"},
		{name: "not-directory", err: &os.PathError{Op: "open", Path: "secret", Err: syscall.ENOTDIR}, code: "INVALID_ARGUMENT", text: "path is not a directory"},
		{name: "exists", err: &os.PathError{Op: "open", Path: "secret", Err: syscall.EEXIST}, code: "ALREADY_EXISTS", text: "path already exists"},
		{name: "no-space", err: &os.PathError{Op: "open", Path: "secret", Err: syscall.ENOSPC}, code: "NO_SPACE", text: "no space left on the device"},
		{name: "io", err: &os.PathError{Op: "open", Path: "secret", Err: fmt.Errorf("unknown")}, code: "IO", text: "file system operation failed"},
	} {
		t.Run(test.name, func(t *testing.T) {
			result := errorResult("call", test.err)
			var details map[string]string
			if err := json.Unmarshal(result.Details, &details); err != nil {
				t.Fatal(err)
			}
			if details["code"] != test.code || !strings.Contains(result.Contents[0].Text, test.text) || strings.Contains(result.Contents[0].Text, "secret") {
				t.Fatalf("result=%#v", result)
			}
		})
	}
}

// COD-F-014, COD-T-205
func TestGuidePunctuation(t *testing.T) {
	for _, test := range []struct {
		what, fact, next, want string
	}{
		{"boom", "FAILED", "retry", "boom. The tool ran but failed; side effects may have occurred. retry."},
		{"boom!", "NOT_EXECUTED", "retry?", "boom! The tool was not executed. retry?"},
	} {
		if got := guideText(test.what, test.fact, test.next); got != test.want {
			t.Errorf("guideText(%q, %q, %q)=%q want=%q", test.what, test.fact, test.next, got, test.want)
		}
	}
	if got := errorSentence("\u00a0 already complete? \u00a0"); got != "already complete?" {
		t.Fatalf("sentence=%q", got)
	}
	if got := errorSentence(" "); got != "" {
		t.Fatalf("empty sentence=%q", got)
	}
}

// COD-F-014
func TestClassifySearchErrorMapsMalformedInputAndUnexpectedFailures(t *testing.T) {
	for _, test := range []struct {
		name string
		err  error
		code string
		text string
	}{
		{name: "regex", err: fmt.Errorf("invalid regex: missing ]"), code: "INVALID_ARGUMENT", text: "Invalid regex: missing ]. Use literal=true for exact text, or escape regex metacharacters."},
		{name: "glob", err: fmt.Errorf("invalid glob: malformed range"), code: "INVALID_ARGUMENT", text: "Invalid glob: malformed range"},
		{name: "unexpected", err: fmt.Errorf("unexpected scanner failure"), code: "FAILED", text: "ripgrep failed while searching; results are incomplete"},
	} {
		t.Run(test.name, func(t *testing.T) {
			result := errorResult("call", classifySearchError(test.err))
			var details map[string]string
			if err := json.Unmarshal(result.Details, &details); err != nil {
				t.Fatal(err)
			}
			if details["code"] != test.code || !strings.Contains(result.Contents[0].Text, test.text) {
				t.Fatalf("result=%#v", result)
			}
		})
	}
}

// COD-F-014
func TestCodedErrorResultKeepsModelVisibleMessageVerbatim(t *testing.T) {
	message := "Tool output is 1.000001 MiB; do not rewrite this contract."
	result := codedErrorResult("limited", "FAILED", message)
	if !result.Error || len(result.Contents) != 1 || result.Contents[0].Text != message {
		t.Fatalf("result=%#v", result)
	}
	var details map[string]string
	if err := json.Unmarshal(result.Details, &details); err != nil || details["code"] != "FAILED" {
		t.Fatalf("details=%v err=%v", details, err)
	}
}

// COD-T-204: unexpected errors must not expose their private message.
func TestUnknownError(t *testing.T) {
	result := errorResult("call", errors.New("secret-path"))
	want := "The capability failed while running and the underlying error is not repeated to avoid leaking details. Whether the tool took effect cannot be confirmed. Verify whether the intended effect already took place before deciding what to do next."
	var details map[string]string
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	if !result.Error || details["code"] != "INTERNAL" || result.Contents[0].Text != want || strings.Contains(result.Contents[0].Text, "secret-path") {
		t.Fatalf("result=%#v", result)
	}
}

// COD-T-208: lexical Windows paths remain platform-independent while display paths use slashes.
func TestPathHelpers(t *testing.T) {
	for _, test := range []struct {
		path string
		want bool
	}{
		{"/a", true},
		{"C:/a", true},
		{`\\srv\share`, true},
		{"rel", false},
	} {
		if got := isAbsolutePath(test.path); got != test.want {
			t.Errorf("isAbsolutePath(%q)=%v want=%v", test.path, got, test.want)
		}
	}
	for _, test := range []struct {
		path, want string
	}{
		{"a/./b/../c", "a/c"},
		{"/a/b/../c", "/a/c"},
		{`C:\a`, `C:\a`},
		{"C:/a", `C:\a`},
		{`C:\a\.\b\..\c`, `C:\a\c`},
		{"C:/a/./b/../c", `C:\a\c`},
		{"C:/..", `C:\`},
		{`C:\a\..\..`, `C:\`},
		{`\\server\share\a\..\b`, `\\server\share\b`},
		{"/..", "/"},
		{"a/../../b", "../b"},
		{"", "."},
		{".", "."},
		{`rel\a\..\b`, `rel\b`},
	} {
		if got := normalizePath(test.path); got != test.want {
			t.Errorf("normalizePath(%q)=%q want=%q", test.path, got, test.want)
		}
	}
	if got := toPosix(`a\b`); got != "a/b" {
		t.Fatalf("toPosix=%q", got)
	}
	rawAbsolute := "/raw/./x"
	target := "/real"
	if got := displayPath(&target, &rawAbsolute); got != "/raw/x" {
		t.Errorf("displayPath absolute=%q", got)
	}
	rawRelative := "rel"
	if got := displayPath(&target, &rawRelative); got != "/real" {
		t.Errorf("displayPath relative=%q", got)
	}
	if got := displayPath(nil, nil); got != "<missing>" {
		t.Errorf("displayPath missing=%q", got)
	}
}

// COD-T-209: existing paths resolve to their canonical absolute filesystem paths.
func TestResolveExisting(t *testing.T) {
	file := filepath.Join(t.TempDir(), "a.txt")
	if err := os.WriteFile(file, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	want, err := filepath.EvalSymlinks(file)
	if err != nil {
		t.Fatal(err)
	}
	if got, err := resolveExistingPath(file); err != nil || got != want {
		t.Fatalf("resolved=%q err=%v want=%q", got, err, want)
	}
	if _, err := resolveExistingPath("rel"); err == nil || !strings.Contains(err.Error(), "absolute path") {
		t.Fatalf("relative path error=%v", err)
	}
	if _, err := resolveExistingPath(filepath.Join(filepath.Dir(file), "missing")); err == nil || !strings.Contains(err.Error(), "does not exist") {
		t.Fatalf("missing path error=%v", err)
	}
}

// COD-T-210: writable paths may contain multiple missing suffix components.
func TestResolveWritable(t *testing.T) {
	root := t.TempDir()
	target := filepath.Join(root, "new", "deep", "f.txt")
	got, err := resolveWritablePath(target)
	if err != nil || got != target {
		t.Fatalf("resolved=%q err=%v want=%q", got, err, target)
	}
}

// COD-T-211: filesystem predicates distinguish existing files, links, and missing paths.
func TestPathPredicates(t *testing.T) {
	root := t.TempDir()
	file := filepath.Join(root, "a.txt")
	if err := os.WriteFile(file, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	missing := filepath.Join(root, "missing")
	if !pathExists(file) || pathExists(missing) || isSymbolicLink(file) || isSymbolicLink(missing) {
		t.Fatal("filesystem predicates returned an incorrect file/missing result")
	}
	link := filepath.Join(root, "link")
	if err := os.Symlink(file, link); err != nil {
		t.Skipf("symlink creation unavailable: %v", err)
	}
	if !pathExists(link) || !isSymbolicLink(link) {
		t.Fatal("symlink predicates returned false for an existing link")
	}
}

var _ runtime.CapabilityExecutor = readCapability
