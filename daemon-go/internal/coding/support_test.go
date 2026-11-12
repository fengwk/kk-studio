package coding

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"syscall"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// COD-F-014, COD-T-200
func TestToErrorResultClassifiesStableFailures(t *testing.T) {
	for _, test := range []struct {
		name string
		err  error
		code string
		text string
	}{
		{name: "input", err: rejectInput("  bad arguments  "), code: "INVALID_ARGUMENT", text: "bad arguments. The tool was not executed. Correct the arguments to match the tool schema, then call the tool again."},
		{name: "run", err: failRun("operation failed!", "FAILED"), code: "FAILED", text: "operation failed! Whether the tool took effect cannot be confirmed. Verify whether the intended effect already took place before deciding what to do next."},
		{name: "service", err: failService("service unavailable"), code: "SERVICE_FAILURE", text: "service unavailable. Whether the tool took effect cannot be confirmed."},
		{name: "cancelled", err: context.Canceled, code: "CANCELLED", text: "Operation cancelled. Whether the tool took effect cannot be confirmed."},
		{name: "timeout", err: context.DeadlineExceeded, code: "TIMEOUT", text: "Operation timed out. Whether the tool took effect cannot be confirmed."},
		{name: "unknown", err: errors.New("private secret"), code: "INTERNAL", text: "underlying error is not repeated"},
	} {
		t.Run(test.name, func(t *testing.T) {
			result := errorResult("call", test.err)
			var details map[string]string
			if err := json.Unmarshal(result.Details, &details); err != nil {
				t.Fatal(err)
			}
			if !result.Error || details["code"] != test.code || !strings.Contains(result.Contents[0].Text, test.text) {
				t.Fatalf("result=%#v expected code=%s text=%q", result, test.code, test.text)
			}
			if test.name == "unknown" && strings.Contains(result.Contents[0].Text, "private secret") {
				t.Fatal("unknown error leaked its message")
			}
		})
	}
}

// COD-F-014
func TestFileSystemErrorsUseStableCodeAndSafeMessage(t *testing.T) {
	for _, test := range []struct {
		name string
		err  error
		code string
		text string
	}{
		{name: "permission", err: &os.PathError{Op: "open", Path: "secret", Err: syscall.EACCES}, code: "PERMISSION_DENIED", text: "permission denied while accessing the path"},
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

// COD-F-014
func TestGuideSentencePunctuationAndUnicodeTrim(t *testing.T) {
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

var _ runtime.CapabilityExecutor = readCapability
