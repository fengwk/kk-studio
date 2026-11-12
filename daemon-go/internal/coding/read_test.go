package coding

import (
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
	if err := os.WriteFile(path, []byte("a\nb\n"), 0600); err != nil {
		t.Fatal(err)
	}
	arguments, err := json.Marshal(map[string]any{"path": path, "offset": 1, "limit": 2})
	if err != nil {
		t.Fatal(err)
	}
	call := runtime.CapabilityCall{InvocationID: "read", Arguments: arguments}
	result, err := readCapability(context.Background(), call, runtime.CapabilityContext{})
	if err != nil || result.Error {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	want := "path: " + filepath.ToSlash(path) + "\nends_with_newline: yes\nrange: 1:1-2:1\n\n1|a\n2|b"
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
