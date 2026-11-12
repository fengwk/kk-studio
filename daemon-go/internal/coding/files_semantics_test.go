package coding

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// COD-F-003, COD-T-222
func TestFileTextCodecPreservesBOMAndRejectsMalformedUTF16(t *testing.T) {
	for _, test := range []struct {
		name string
		data []byte
		want string
	}{
		{name: "utf8-bom", data: []byte{0xef, 0xbb, 0xbf, 'h', 'i'}, want: "hi"},
		{name: "utf16le-bom", data: []byte{0xff, 0xfe, 'h', 0, 'i', 0}, want: "hi"},
		{name: "utf16be-bom", data: []byte{0xfe, 0xff, 0, 'h', 0, 'i'}, want: "hi"},
	} {
		t.Run(test.name, func(t *testing.T) {
			got, err := decodeFileText(test.data)
			if err != nil || got != test.want {
				t.Fatalf("text=%q err=%v", got, err)
			}
		})
	}
	for _, malformed := range [][]byte{{0xff, 0xfe, 'a'}, {0xff, 0xfe, 0x00, 0xd8}, {0xfe, 0xff, 0xdc, 0x00}, {0xff, 0xfe, 0, 0, 0, 0xd8, 0, 0}} {
		if _, err := decodeFileText(malformed); err == nil || err.Error() != "file appears to be binary" {
			t.Fatalf("expected malformed UTF16 rejection for %x, got %v", malformed, err)
		}
	}
}

// COD-F-003
func TestFileTextEncodeRetainsCharsetAndRejectsInvalidInput(t *testing.T) {
	for _, test := range []struct {
		charset string
		bom     []byte
		want    []byte
	}{
		{charset: "UTF-8", bom: []byte{0xef, 0xbb, 0xbf}, want: []byte{0xef, 0xbb, 0xbf, 'x'}},
		{charset: "UTF-16LE", bom: []byte{0xff, 0xfe}, want: []byte{0xff, 0xfe, 'x', 0}},
		{charset: "UTF-16BE", bom: []byte{0xfe, 0xff}, want: []byte{0xfe, 0xff, 0, 'x'}},
	} {
		got, err := encodeFileText("x", test.charset, len(test.bom))
		if err != nil || !bytes.Equal(got, test.want) {
			t.Fatalf("charset=%s bytes=%x err=%v", test.charset, got, err)
		}
	}
	if _, err := encodeFileText("x", "unknown", 0); err == nil || !strings.Contains(err.Error(), "losslessly encoded") {
		t.Fatalf("unsupported charset err=%v", err)
	}
}

// COD-F-003: write surfaces the exact charset and stable CODEC error for unencodable text.
func TestWriteEncodingFailureIncludesCharsetAndCodecCode(t *testing.T) {
	path := filepath.Join(t.TempDir(), "invalid.txt")
	_, err := Write(path, string([]byte{0xff}), nil)
	if err == nil || err.Error() != "text cannot be losslessly encoded in charset UTF-8" {
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
