package coding

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	goruntime "runtime"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// COD-T-023: an available language-support hook emits the LSP status header before the body.
func TestReadLSPHeader(t *testing.T) {
	path := filepath.Join(t.TempDir(), "main.go")
	if err := os.WriteFile(path, []byte("package main\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	resolvedPath := resolvedTestPath(t, path)
	provider := Provider{LSPStatus: func(file string) (string, bool) {
		if file != resolvedPath {
			t.Fatalf("LSP hook path=%q want=%q", file, resolvedPath)
		}
		return "go", true
	}}
	result, err := provider.Capabilities()["fs.read"](context.Background(), capabilityCall(t, "lsp", map[string]string{"path": path}), runtime.CapabilityContext{})
	if err != nil || result.Error {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	text := result.Contents[0].Text
	status := strings.Index(text, "lsp: supported (go)")
	body := strings.Index(text, "1|package main")
	if status < 0 || body < 0 || status > body {
		t.Fatalf("LSP status/header order missing: %q", text)
	}
}

// COD-T-024: unavailable support and null languages omit the LSP status header.
func TestReadNoLSPHeader(t *testing.T) {
	path := filepath.Join(t.TempDir(), "file")
	if err := os.WriteFile(path, []byte("content\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	for name, status := range map[string]func(string) (string, bool){
		"no-hook":          nil,
		"unavailable":      func(string) (string, bool) { return "go", false },
		"missing-language": func(string) (string, bool) { return "", true },
	} {
		t.Run(name, func(t *testing.T) {
			provider := Provider{LSPStatus: status}
			result, err := provider.Capabilities()["fs.read"](context.Background(), capabilityCall(t, name, map[string]string{"path": path}), runtime.CapabilityContext{})
			if err != nil || result.Error || strings.Contains(result.Contents[0].Text, "lsp:") {
				t.Fatalf("result=%#v err=%v", result, err)
			}
		})
	}
}

// COD-T-021: limit values above 2000 are rejected before opening even a missing path.
func TestReadRejectsOversizeLimitBeforeFilesystemAccess(t *testing.T) {
	missing := filepath.Join(t.TempDir(), "missing.txt")
	result, err := readCapability(context.Background(), capabilityCall(t, "oversize-limit", map[string]any{"path": missing, "limit": 2001}), runtime.CapabilityContext{})
	if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "limit must be a positive integer no greater than 2000") || strings.Contains(result.Contents[0].Text, "path does not exist") {
		t.Fatalf("result=%#v err=%v", result, err)
	}
}

// COD-T-022: column_offset projects the first displayed line and its range header.
func TestReadColumnOffsetProjection(t *testing.T) {
	path := filepath.Join(t.TempDir(), "columns.txt")
	if err := os.WriteFile(path, []byte("abcdef"), 0o600); err != nil {
		t.Fatal(err)
	}
	result, err := readCapability(context.Background(), capabilityCall(t, "column-offset", map[string]any{"path": path, "column_offset": 3}), runtime.CapabilityContext{})
	if err != nil || result.Error || !strings.Contains(result.Contents[0].Text, "range: 1:3-1:6") || !strings.Contains(result.Contents[0].Text, "1|cdef") {
		t.Fatalf("result=%#v err=%v", result, err)
	}
}

// COD-T-026: directory reads return the requested page and a stable continuation hint.
func TestDirectoryPagination(t *testing.T) {
	root := t.TempDir()
	listing := filepath.Join(root, "listing")
	if err := os.Mkdir(listing, 0o700); err != nil {
		t.Fatal(err)
	}
	for index := 1; index <= 5; index++ {
		if err := os.WriteFile(filepath.Join(listing, fmt.Sprintf("f%d.txt", index)), nil, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	page, err := readCapability(context.Background(), capabilityCall(t, "page", map[string]any{"path": listing, "offset": 2, "limit": 2}), runtime.CapabilityContext{})
	want := fmt.Sprintf("path: %s\nkind: directory\n\nf2.txt\nf3.txt\n\n[Showing entries 2-3 of 5. Re-run read with offset=4 to continue.]", filepath.ToSlash(resolvedTestPath(t, listing)))
	if err != nil || page.Error || page.Contents[0].Text != want {
		t.Fatalf("page=%#v err=%v want=%q", page, err, want)
	}
	beyond, err := readCapability(context.Background(), capabilityCall(t, "beyond", map[string]any{"path": listing, "offset": 10}), runtime.CapabilityContext{})
	want = fmt.Sprintf("path: %s\nkind: directory\n\n[Showing 0 entries of 5.]", filepath.ToSlash(resolvedTestPath(t, listing)))
	if err != nil || beyond.Error || beyond.Contents[0].Text != want {
		t.Fatalf("beyond=%#v err=%v want=%q", beyond, err, want)
	}
}

// COD-T-028: special files are rejected before opening their device stream.
func TestReadRejectsNonRegularFile(t *testing.T) {
	if goruntime.GOOS == "windows" {
		t.Skip("/dev/null is a POSIX character device")
	}
	path := "/dev/null"
	if _, err := os.Stat(path); err != nil {
		t.Skipf("character device unavailable: %v", err)
	}
	result, err := readCapability(context.Background(), capabilityCall(t, "device", map[string]string{"path": path}), runtime.CapabilityContext{})
	if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "path is not a regular file") || !strings.Contains(string(result.Details), `"code":"INVALID_ARGUMENT"`) {
		t.Fatalf("result=%#v err=%v", result, err)
	}
}

// COD-T-030: replacing a read-only file preserves its original permission bits.
func TestWritePreservesReadOnlyFilePermissions(t *testing.T) {
	if goruntime.GOOS == "windows" {
		t.Skip("POSIX permission bits are required")
	}
	path := filepath.Join(t.TempDir(), "readonly.txt")
	if err := os.WriteFile(path, []byte("before\n"), 0o444); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(path, 0o444); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.write", map[string]any{"path": path, "content": "after\n"}, nil)
	if result.Error {
		t.Fatalf("write failed: %#v", result)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o444 || string(content) != "after\n" {
		t.Fatalf("mode=%#o content=%q", info.Mode().Perm(), content)
	}
}

// COD-T-033: editing a UTF-16LE file retains its BOM and charset.
func TestEditUTF16LEPreservesBOM(t *testing.T) {
	path := filepath.Join(t.TempDir(), "utf16.txt")
	original, err := EncodeText("a\nb\n", "UTF-16LE", 2)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, original, 0o600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.edit", map[string]any{"path": path, "old_string": "b", "new_string": "c"}, nil)
	if result.Error {
		t.Fatalf("edit failed: %#v", result)
	}
	written, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	decoded, err := DecodeText(written)
	if err != nil {
		t.Fatal(err)
	}
	if decoded.Charset != "UTF-16LE" || decoded.BOMBytes != 2 || decoded.Text != "a\nc\n" || written[0] != 0xff || written[1] != 0xfe {
		t.Fatalf("decoded=%#v bytes=%x", decoded, written)
	}
}

// COD-T-034: edit review details hold the diff while the text result contains only the success message.
func TestEditReviewOutputSeparatesDiff(t *testing.T) {
	path := filepath.Join(t.TempDir(), "review.txt")
	if err := os.WriteFile(path, []byte("before\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.edit", map[string]any{"path": path, "old_string": "before", "new_string": "after"}, nil)
	if result.Error || result.Contents[0].Text != "Edited successfully.\nReplacements: 1" {
		t.Fatalf("result=%#v", result)
	}
	var details struct {
		EditReview struct {
			Path string `json:"path"`
			Diff string `json:"diff"`
		} `json:"editReview"`
	}
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	resolvedPath, err := filepath.EvalSymlinks(path)
	if err != nil {
		t.Fatal(err)
	}
	if details.EditReview.Path != resolvedPath || details.EditReview.Diff == "" || strings.Contains(result.Contents[0].Text, details.EditReview.Diff) {
		t.Fatalf("body=%q review=%#v", result.Contents[0].Text, details.EditReview)
	}
}

// COD-T-040: directly specified binary files fail with the binary-file reason, not a subprocess error.
func TestGrepDirectBinaryReportsBinaryReason(t *testing.T) {
	path := filepath.Join(t.TempDir(), "binary.bin")
	if err := os.WriteFile(path, []byte{'x', 0, 'y'}, 0o600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.grep", map[string]any{"path": path, "pattern": "x", "literal": true}, nil)
	if !result.Error || !strings.Contains(result.Contents[0].Text, "file appears to be binary") || strings.Contains(result.Contents[0].Text, "ripgrep") || !strings.Contains(string(result.Details), `"code":"CODEC"`) {
		t.Fatalf("result=%#v", result)
	}
}

// COD-T-042: grep glob patterns with a path separator are relative to the search root.
func TestGrepGlobWithSubdirectory(t *testing.T) {
	root := t.TempDir()
	sub := filepath.Join(root, "sub")
	if err := os.Mkdir(sub, 0o700); err != nil {
		t.Fatal(err)
	}
	for path, text := range map[string]string{
		filepath.Join(sub, "keep.ts"):   "needle\n",
		filepath.Join(root, "other.ts"): "needle\n",
	} {
		if err := os.WriteFile(path, []byte(text), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	result := invokeCoding(t, Provider{}, "fs.grep", map[string]any{"path": root, "pattern": "needle", "literal": true, "glob": "sub/*.ts"}, nil)
	if result.Error || result.Contents[0].Text != "sub/keep.ts:1:needle" {
		t.Fatalf("result=%#v", result)
	}
}

// COD-T-049: a final note is appended without clearing the failed result state.
func TestOutputFinishRetainsFailedState(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	spool, err := NewOutputSpool(store, "failed", defaultCaptureBudget)
	if err != nil {
		t.Fatal(err)
	}
	spool.Write([]byte("partial output"))
	result := spool.Finish(true, "[Command failed.]")
	if !result.Error || result.Contents[0].Text != "partial output\n[Command failed.]" {
		t.Fatalf("result=%#v", result)
	}
	if err := spool.Close(); err != nil {
		t.Fatal(err)
	}
}

// COD-T-051: publishing a path with a non-staging suffix returns the stable error.
func TestPublishRejectsNonStagingSuffix(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Publish(filepath.Join(store.Root(), "output.log")); err == nil || err.Error() != "staging file must end with .part" {
		t.Fatalf("publish error=%v", err)
	}
}
