package coding

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

func capabilityCall(t *testing.T, id string, args any) runtime.CapabilityCall {
	t.Helper()
	data, err := json.Marshal(args)
	if err != nil {
		t.Fatal(err)
	}
	return runtime.CapabilityCall{InvocationID: id, Arguments: data}
}

// COD-N-001
func TestFormatMiBUsesEnoughPrecisionToDistinguishLimit(t *testing.T) {
	for _, test := range []struct {
		size, limit int64
		want        string
	}{
		{1 << 20, 1 << 20, "1.0"},
		{(1 << 20) + 1, 1 << 20, "1.000001"},
		{1 << 19, 1 << 20, "0.5"},
	} {
		if got := formatMiB(test.size, test.limit); got != test.want {
			t.Fatalf("formatMiB(%d,%d)=%q want=%q", test.size, test.limit, got, test.want)
		}
	}
}

// COD-F-014
func TestProcessCapabilityRejectsMalformedArguments(t *testing.T) {
	result, err := processCapability(context.Background(), runtime.CapabilityCall{InvocationID: "invalid-json", Arguments: []byte("{")}, runtime.CapabilityContext{})
	if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "invalid arguments") {
		t.Fatalf("result=%#v err=%v", result, err)
	}
}

// COD-F-001
func TestReadCapabilityListsDirectoryAndRejectsMissingPath(t *testing.T) {
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "a-dir"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "z-file"), []byte("x"), 0600); err != nil {
		t.Fatal(err)
	}
	listed, err := readCapability(context.Background(), capabilityCall(t, "list", map[string]string{"path": root}), runtime.CapabilityContext{})
	wantListing := "path: " + filepath.ToSlash(root) + "\nkind: directory\n\na-dir/\nz-file"
	if err != nil || listed.Error || listed.Contents[0].Text != wantListing {
		t.Fatalf("directory listing=%#v err=%v want=%q", listed, err, wantListing)
	}
	missing, err := readCapability(context.Background(), capabilityCall(t, "missing", map[string]string{"path": filepath.Join(root, "missing")}), runtime.CapabilityContext{})
	if err != nil || !missing.Error || !strings.Contains(string(missing.Details), `"code":"NOT_FOUND"`) {
		t.Fatalf("missing result=%#v err=%v", missing, err)
	}
}

// COD-F-002
func TestFormatTextWindowCoversOptionalHeadersAndTruncation(t *testing.T) {
	ended := true
	got := formatTextWindow("dir/file.txt", TextWindow{Body: "1|one", StartLine: 1, StartColumn: 1, EndLine: 1, EndColumn: 4, EndsWithNewline: &ended, Truncated: true, TruncationReason: "line_limit", NextLine: 2, NextColumn: 1})
	for _, want := range []string{"path: dir/file.txt", "ends_with_newline: yes", "range: 1:1-1:4", "truncation_reason: line_limit", "next: 2:1", "[TRUNCATED: More file content remains."} {
		if !strings.Contains(got, want) {
			t.Fatalf("formatted window missing %q: %q", want, got)
		}
	}
	got = formatTextWindow("empty", TextWindow{Empty: true, Truncated: true, NextLine: 3, NextColumn: 2})
	if !strings.Contains(got, "range: empty") || !strings.Contains(got, "Next position: line 3, column 2") {
		t.Fatalf("empty truncated window=%q", got)
	}
}

// COD-F-010
func TestSearchCapabilitiesRejectLimitsAndReturnEmptyMessages(t *testing.T) {
	root := t.TempDir()
	for _, executor := range []struct {
		name string
		call func(context.Context, runtime.CapabilityCall, runtime.CapabilityContext) (runtime.CapabilityResult, error)
		args map[string]any
		want string
	}{
		{"find-empty", findCapability, map[string]any{"path": root, "pattern": "*.missing"}, "No files found matching pattern"},
		{"grep-empty", grepCapability, map[string]any{"path": root, "pattern": "missing", "literal": true}, "No matches found"},
	} {
		result, err := executor.call(context.Background(), capabilityCall(t, executor.name, executor.args), runtime.CapabilityContext{})
		if err != nil || result.Error || result.Contents[0].Text != executor.want {
			t.Fatalf("%s result=%#v err=%v", executor.name, result, err)
		}
	}
	for _, executor := range []struct {
		name string
		call func(context.Context, runtime.CapabilityCall, runtime.CapabilityContext) (runtime.CapabilityResult, error)
	}{
		{"find", findCapability},
		{"grep", grepCapability},
	} {
		result, err := executor.call(context.Background(), capabilityCall(t, executor.name, map[string]any{"path": root, "pattern": "x", "limit": maxSearchLimit + 1}), runtime.CapabilityContext{})
		if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "limit must be between 1 and 100000") {
			t.Fatalf("%s result=%#v err=%v", executor.name, result, err)
		}
	}
}

// COD-F-007, COD-F-014
func TestWriteCapabilityCreatesOverwritesAndRejectsDirectories(t *testing.T) {
	path := filepath.Join(t.TempDir(), "new.txt")
	changed := 0
	result, err := writeCapability(context.Background(), capabilityCall(t, "create", map[string]string{"path": path, "content": "first"}), runtime.CapabilityContext{FileChanged: func(string) { changed++ }})
	if err != nil || result.Error || !strings.Contains(result.Contents[0].Text, "Created ") || changed != 1 {
		t.Fatalf("create=%#v changed=%d err=%v", result, changed, err)
	}
	created, err := os.ReadFile(path)
	if err != nil || string(created) != "first" {
		t.Fatalf("created file=%q err=%v", created, err)
	}
	preview, err := PreviewWrite(path, "second")
	if err != nil {
		t.Fatal(err)
	}
	call := capabilityCall(t, "overwrite", map[string]string{"path": path, "content": "second"})
	call.ExpectedFile = &runtime.ExpectedFile{Path: preview.Path, SHA256: preview.SHA256}
	result, err = writeCapability(context.Background(), call, runtime.CapabilityContext{FileChanged: func(string) { changed++ }})
	if err != nil || result.Error || !strings.Contains(result.Contents[0].Text, "Overwrote ") || changed != 2 {
		t.Fatalf("overwrite=%#v changed=%d err=%v", result, changed, err)
	}
	result, err = writeCapability(context.Background(), capabilityCall(t, "directory", map[string]string{"path": filepath.Dir(path), "content": "x"}), runtime.CapabilityContext{})
	if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "path is a directory") {
		t.Fatalf("directory write=%#v err=%v", result, err)
	}
}

// COD-F-008
func TestPreviewWriteCapabilityReturnsJSONWithoutWriting(t *testing.T) {
	path := filepath.Join(t.TempDir(), "new.txt")
	result, err := previewWriteCapability(context.Background(), capabilityCall(t, "preview", map[string]string{"path": path, "content": "new content\n"}), runtime.CapabilityContext{})
	if err != nil || result.Error || result.Contents[0].Type != "json" || string(result.Details) != "{}" {
		t.Fatalf("preview=%#v err=%v", result, err)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("preview wrote target: %v", err)
	}
}

// COD-F-017
func TestExecuteProcessRejectsPreflightFailures(t *testing.T) {
	workdir := t.TempDir()
	for _, test := range []struct {
		name       string
		ctx        context.Context
		command    string
		workdir    string
		capability runtime.CapabilityContext
		want       string
	}{
		{name: "empty-command", ctx: context.Background(), command: " \t", workdir: workdir, capability: runtime.CapabilityContext{}, want: "command must be a non-empty string"},
		{name: "relative-workdir", ctx: context.Background(), command: "true", workdir: "relative", capability: runtime.CapabilityContext{}, want: "workdir must be absolute"},
		{name: "missing-supervisor", ctx: context.Background(), command: "true", workdir: workdir, capability: runtime.CapabilityContext{}, want: "process supervisor is unavailable"},
		{name: "missing-output-directory", ctx: context.Background(), command: "true", workdir: workdir, capability: runtime.CapabilityContext{Processes: fakeSupervisor{}}, want: "temporary output storage is unavailable"},
	} {
		t.Run(test.name, func(t *testing.T) {
			call := runtime.CapabilityCall{InvocationID: test.name}
			result, err := ExecuteProcess(test.ctx, call, test.capability, test.command, test.workdir)
			if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, test.want) {
				t.Fatalf("result=%#v err=%v", result, err)
			}
		})
	}
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	confirmed := 0
	call := runtime.CapabilityCall{InvocationID: "cancel-before-start", TimeoutMillis: 50}
	capability := runtime.CapabilityContext{ConfirmExecutionExit: func() { confirmed++ }}
	result, err := ExecuteProcess(cancelled, call, capability, "true", workdir)
	if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "Operation cancelled") || confirmed != 1 {
		t.Fatalf("cancelled result=%#v confirmed=%d err=%v", result, confirmed, err)
	}
	deadline, cancelDeadline := context.WithDeadline(context.Background(), time.Unix(1, 0))
	defer cancelDeadline()
	result, err = ExecuteProcess(deadline, call, capability, "true", workdir)
	if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "Command timed out after 50 ms") || confirmed != 2 {
		t.Fatalf("deadline result=%#v confirmed=%d err=%v", result, confirmed, err)
	}
}

// COD-F-015
func TestOutputNameAndUUIDValidationEdges(t *testing.T) {
	if got := sanitizeOutputName(""); got != "output" {
		t.Fatalf("empty name=%q", got)
	}
	if got := sanitizeOutputName(strings.Repeat("a", 100)); len(got) != 64 {
		t.Fatalf("name length=%d name=%q", len(got), got)
	}
	if !canonicalUUID("11111111-1111-4111-8111-111111111111") || canonicalUUID("11111111-1111-4111-8111-11111111111x") {
		t.Fatal("UUID validation mismatch")
	}
}
