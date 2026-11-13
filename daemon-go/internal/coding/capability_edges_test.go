package coding

import (
	"bytes"
	"context"
	"encoding/json"
	"image"
	"image/png"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// COD-F-002, COD-F-014
func TestReadRejectsInvalidWindowsBeforeOpeningFiles(t *testing.T) {
	for _, test := range []struct {
		name string
		args map[string]any
		want string
	}{
		{name: "missing-path", args: map[string]any{}, want: "path must be a non-empty string"},
		{name: "relative-path", args: map[string]any{"path": "relative"}, want: "absolute path"},
		{name: "zero-limit", args: map[string]any{"path": filepath.Join(t.TempDir(), "missing"), "limit": 0}, want: "limit must be a positive integer"},
		{name: "bad-column", args: map[string]any{"path": filepath.Join(t.TempDir(), "missing"), "column_offset": 0}, want: "column_offset must be a positive integer"},
	} {
		t.Run(test.name, func(t *testing.T) {
			result := invokeCoding(t, Provider{}, "fs.read", test.args, nil)
			if !result.Error || !strings.Contains(result.Contents[0].Text, test.want) || strings.Contains(result.Contents[0].Text, "path does not exist") {
				t.Fatalf("result=%#v", result)
			}
		})
	}
}

// COD-F-002, COD-F-004
func TestReadDirectoryAndBinaryFile(t *testing.T) {
	root := t.TempDir()
	for _, name := range []string{"z.txt", "a.txt"} {
		if err := os.WriteFile(filepath.Join(root, name), []byte(name), 0600); err != nil {
			t.Fatal(err)
		}
	}
	directory := invokeCoding(t, Provider{}, "fs.read", map[string]any{"path": root}, nil)
	wantDirectory := "path: " + filepath.ToSlash(resolvedTestPath(t, root)) + "\nkind: directory\n\na.txt\nz.txt"
	if directory.Error || directory.Contents[0].Text != wantDirectory {
		t.Fatalf("directory=%#v want=%q", directory, wantDirectory)
	}
	binaryPath := filepath.Join(root, "binary.dat")
	if err := os.WriteFile(binaryPath, []byte{0, 1, 2, 3}, 0600); err != nil {
		t.Fatal(err)
	}
	binary := invokeCoding(t, Provider{}, "fs.read", map[string]any{"path": binaryPath}, nil)
	if !binary.Error || !strings.Contains(binary.Contents[0].Text, "file appears to be binary") {
		t.Fatalf("binary=%#v", binary)
	}
}

// COD-F-007, COD-F-014
func TestWriteRequiresStringContent(t *testing.T) {
	for _, value := range []any{nil, 1, false} {
		result := invokeCoding(t, Provider{}, "fs.write", map[string]any{"path": filepath.Join(t.TempDir(), "write.txt"), "content": value}, nil)
		if !result.Error || !strings.Contains(result.Contents[0].Text, "content must be a string") {
			t.Fatalf("content=%v result=%#v", value, result)
		}
	}
	result := invokeCoding(t, Provider{}, "fs.write.preview", map[string]any{"path": filepath.Join(t.TempDir(), "write.txt")}, nil)
	if !result.Error || !strings.Contains(result.Contents[0].Text, "content must be a string") {
		t.Fatalf("missing content result=%#v", result)
	}
}

// COD-F-008, COD-F-014
func TestEditRequiresTypedStrings(t *testing.T) {
	path := filepath.Join(t.TempDir(), "edit.txt")
	if err := os.WriteFile(path, []byte("before"), 0600); err != nil {
		t.Fatal(err)
	}
	for _, args := range []map[string]any{
		{"path": path, "old_string": nil, "new_string": "after"},
		{"path": path, "old_string": "before", "new_string": nil},
		{"path": path, "old_string": "before"},
	} {
		result := invokeCoding(t, Provider{}, "fs.edit", args, nil)
		if !result.Error || !strings.Contains(result.Contents[0].Text, "must be a string") {
			t.Fatalf("args=%v result=%#v", args, result)
		}
	}
}

// COD-F-014
func TestImageTierRejectsUnknownValue(t *testing.T) {
	path := filepath.Join(t.TempDir(), "small.png")
	var encoded bytes.Buffer
	if err := png.Encode(&encoded, image.NewNRGBA(image.Rect(0, 0, 1, 1))); err != nil {
		t.Fatal(err)
	}
	data := encoded.Bytes()
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	arguments, err := json.Marshal(map[string]string{"path": path})
	if err != nil {
		t.Fatal(err)
	}
	call := runtime.CapabilityCall{InvocationID: "bad-tier", Arguments: arguments, ImageInputTier: runtime.ImageInputTier("P1440")}
	result, err := readCapability(context.Background(), call, runtime.CapabilityContext{})
	if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "invalid image input tier") {
		t.Fatalf("result=%#v err=%v", result, err)
	}
}
