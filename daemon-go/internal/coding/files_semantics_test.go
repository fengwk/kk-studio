package coding

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// COD-F-003: write surfaces the exact charset and stable CODEC error for unencodable text.
// COD-F-003: write surfaces the exact charset and stable CODEC error for unencodable text.
func TestWriteEncodingFailureIncludesCharsetAndCodecCode(t *testing.T) {
	path := filepath.Join(t.TempDir(), "invalid.txt")
	_, err := Write(path, string([]byte{0xff}), nil)
	if err == nil || err.Error() != "The new text cannot be encoded in UTF-8: \"\ufffd\" (U+FFFD) on line 1." {
		t.Fatalf("write encoding error=%v", err)
	}
	result := toCapabilityErrorResult("invalid-write", err)
	if !result.Error || !strings.Contains(string(result.Details), `"code":"CODEC"`) {
		t.Fatalf("result=%#v", result)
	}
	if _, statErr := os.Stat(path); !os.IsNotExist(statErr) {
		t.Fatalf("encoding failure created target: stat error=%v", statErr)
	}
}

// COD-F-007, COD-T-027
func TestWriteRejectsInvalidTargetsAndCreatesNewFile(t *testing.T) {
	root := t.TempDir()
	if _, err := Write("relative.txt", "x", nil); err == nil || err.Error() != "path must be absolute" {
		t.Fatalf("relative path err=%v", err)
	}
	directory := filepath.Join(root, "directory")
	if err := os.Mkdir(directory, 0700); err != nil {
		t.Fatal(err)
	}
	if _, err := Write(directory, "x", nil); err == nil || !strings.Contains(err.Error(), "path is a directory") {
		t.Fatalf("directory write err=%v", err)
	}
	if _, err := PreviewWrite(filepath.Join(root, "missing", "file.txt"), "x"); err != nil {
		t.Fatalf("new nested preview err=%v", err)
	}
	if _, err := PreviewWrite(root, "x"); err == nil || !strings.Contains(err.Error(), "path is a directory") {
		t.Fatalf("directory preview err=%v", err)
	}
}

// COD-F-007
func TestWriteAndPreviewRejectSymlinkTargets(t *testing.T) {
	root := t.TempDir()
	target := filepath.Join(root, "target.txt")
	if err := os.WriteFile(target, []byte("target"), 0600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(root, "link.txt")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	for _, operation := range []struct {
		name string
		run  func() error
	}{
		{name: "write", run: func() error { _, err := Write(link, "changed", nil); return err }},
		{name: "preview", run: func() error { _, err := PreviewWrite(link, "changed"); return err }},
	} {
		t.Run(operation.name, func(t *testing.T) {
			if err := operation.run(); err == nil || !strings.Contains(err.Error(), "path is a symbolic link") {
				t.Fatalf("symlink operation err=%v", err)
			}
		})
	}
}
