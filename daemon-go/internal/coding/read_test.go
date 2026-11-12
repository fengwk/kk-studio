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
