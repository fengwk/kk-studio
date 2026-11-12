package coding

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// COD-F-002
func TestReadTextHeaderFormatsRangeAndNewlineMetadata(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sample.txt")
	if err := os.WriteFile(path, []byte("alpha\nbeta\ngamma\n"), 0600); err != nil {
		t.Fatal(err)
	}
	arguments, err := json.Marshal(map[string]any{"path": path})
	if err != nil {
		t.Fatal(err)
	}
	call := runtime.CapabilityCall{InvocationID: "read", Arguments: arguments}
	result, err := readCapability(context.Background(), call, runtime.CapabilityContext{})
	if err != nil || result.Error {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	want := "path: " + filepath.ToSlash(path) + "\nends_with_newline: yes\nrange: 1:1-3:5\n\n1|alpha\n2|beta\n3|gamma"
	if result.Contents[0].Text != want {
		t.Fatalf("text=%q want=%q", result.Contents[0].Text, want)
	}
}

// COD-T-021
func TestReadWindowArgumentsAreValidatedBeforeFilesystemAccess(t *testing.T) {
	path := filepath.Join(t.TempDir(), "missing.txt")
	arguments, err := json.Marshal(map[string]any{"path": path, "offset": 0})
	if err != nil {
		t.Fatal(err)
	}
	call := runtime.CapabilityCall{InvocationID: "invalid-window", Arguments: arguments}
	result, err := readCapability(context.Background(), call, runtime.CapabilityContext{})
	if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "offset must be a positive integer") || strings.Contains(result.Contents[0].Text, "no such file") {
		t.Fatalf("result=%#v err=%v", result, err)
	}
}

// COD-F-002 COD-T-246 COD-T-247: fs.read must not decode invalid bytes outside the requested first window.
func TestReadCapabilityUsesBoundedTextWindow(t *testing.T) {
	path := filepath.Join(t.TempDir(), "large.txt")
	data := append([]byte("first\n"), bytes.Repeat([]byte("later\n"), 100_000)...)
	data = append(data, 0xff)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	call := runtime.CapabilityCall{InvocationID: "bounded-read", Arguments: mustJSON(map[string]any{"path": path, "limit": 1})}
	result, err := readCapability(context.Background(), call, runtime.CapabilityContext{})
	if err != nil || result.Error {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	text := result.Contents[0].Text
	if !strings.Contains(text, "1|first") || !strings.Contains(text, "truncation_reason: line_limit") || strings.Contains(text, "ends_with_newline:") {
		t.Fatalf("unexpected first-window result %q", text)
	}
}

// COD-F-001: the maximum offset is accepted, larger offsets are rejected, and omitted limit uses 2000.
func TestReadCapabilityOffsetBoundsAndDefaultLimit(t *testing.T) {
	path := filepath.Join(t.TempDir(), "lines.txt")
	if err := os.WriteFile(path, []byte(strings.Repeat("line\n", MaxReadLimit+1)), 0o600); err != nil {
		t.Fatal(err)
	}
	maxOffset := runtime.CapabilityCall{InvocationID: "max-offset", Arguments: mustJSON(map[string]any{"path": path, "offset": 2147483647})}
	result, err := readCapability(context.Background(), maxOffset, runtime.CapabilityContext{})
	if err != nil || result.Error || !strings.Contains(result.Contents[0].Text, "range: empty") {
		t.Fatalf("max offset result=%#v err=%v", result, err)
	}
	tooLarge := runtime.CapabilityCall{InvocationID: "too-large-offset", Arguments: mustJSON(map[string]any{"path": path, "offset": 2147483648})}
	result, err = readCapability(context.Background(), tooLarge, runtime.CapabilityContext{})
	if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "offset must be a positive integer") {
		t.Fatalf("too-large offset result=%#v err=%v", result, err)
	}
	defaultLimit := runtime.CapabilityCall{InvocationID: "default-limit", Arguments: mustJSON(map[string]any{"path": path})}
	result, err = readCapability(context.Background(), defaultLimit, runtime.CapabilityContext{})
	if err != nil || result.Error {
		t.Fatalf("default limit result=%#v err=%v", result, err)
	}
	text := result.Contents[0].Text
	if !strings.Contains(text, "range: 1:1-2000:4") || !strings.Contains(text, "2000|line") || strings.Contains(text, "2001|line") || !strings.Contains(text, "truncation_reason: line_limit") {
		t.Fatalf("default limit output=%q", text)
	}
}

// COD-F-019: reading through a symlink resolves the reported path and LSP hook to the real file.
func TestReadCapabilityUsesResolvedExistingPath(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "target.txt")
	alias := filepath.Join(dir, "alias.txt")
	if err := os.WriteFile(target, []byte("content"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(target, alias); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	var lspPath string
	call := runtime.CapabilityCall{InvocationID: "resolved-read", Arguments: mustJSON(map[string]any{"path": alias})}
	result, err := readCapabilityWithLSPStatus(context.Background(), call, runtime.CapabilityContext{}, func(path string) (string, bool) {
		lspPath = path
		return "go", true
	})
	if err != nil || result.Error {
		t.Fatalf("read result=%#v err=%v", result, err)
	}
	resolved, err := filepath.EvalSymlinks(target)
	if err != nil {
		t.Fatal(err)
	}
	if lspPath != resolved || !strings.Contains(result.Contents[0].Text, "path: "+filepath.ToSlash(resolved)+"\n") {
		t.Fatalf("LSP path=%q result=%q want resolved %q", lspPath, result.Contents[0].Text, resolved)
	}
}

// COD-F-002: cancellation is honored before stat or opening the requested path.
func TestReadCapabilityChecksCancellationBeforeFilesystemAccess(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	call := runtime.CapabilityCall{InvocationID: "cancelled-read", Arguments: mustJSON(map[string]any{"path": filepath.Join(t.TempDir(), "missing.txt")})}
	result, err := readCapability(ctx, call, runtime.CapabilityContext{})
	if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "Operation cancelled.") {
		t.Fatalf("result=%#v err=%v", result, err)
	}
}
