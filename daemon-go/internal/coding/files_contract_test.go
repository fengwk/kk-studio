package coding

import (
	"context"
	"os"
	"path/filepath"
	goruntime "runtime"
	"strings"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// COD-T-076: deadline failures remain distinct from caller cancellation and do not write files.
func TestFileCapabilitiesKeepTimeoutDistinctFromCancellation(t *testing.T) {
	path := filepath.Join(t.TempDir(), "before.txt")
	if err := os.WriteFile(path, []byte("before\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		name string
		args map[string]any
	}{
		{"fs.read", map[string]any{"path": path}},
		{"fs.write", map[string]any{"path": path, "content": "after\n"}},
		{"fs.edit", editArgs(path, replace("before", "after"))},
		{"fs.edit.preview", editArgs(path, replace("before", "after"))},
	}
	handlers := Provider{}.Capabilities()
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			ctx, cancel := context.WithDeadline(context.Background(), time.Now().Add(-time.Second))
			defer cancel()
			result, err := handlers[test.name](ctx, capabilityCall(t, test.name, test.args), runtime.CapabilityContext{})
			if err != nil || !result.Error || !strings.Contains(string(result.Details), `"code":"TIMEOUT"`) || !strings.Contains(result.Contents[0].Text, "Operation timed out") || strings.Contains(result.Contents[0].Text, "Operation cancelled") {
				t.Fatalf("result=%#v err=%v", result, err)
			}
			content, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			if string(content) != "before\n" {
				t.Fatalf("deadline changed file content to %q", content)
			}
		})
	}
}

// COD-T-080: offsets beyond a non-empty file return an empty range.
func TestReadPastEOFReturnsEmptyRange(t *testing.T) {
	path := filepath.Join(t.TempDir(), "lines.txt")
	if err := os.WriteFile(path, []byte("one\ntwo\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	result, err := readCapability(context.Background(), capabilityCall(t, "past-eof", map[string]any{"path": path, "offset": 5}), runtime.CapabilityContext{})
	if err != nil || result.Error || !strings.Contains(result.Contents[0].Text, "range: empty") {
		t.Fatalf("result=%#v err=%v", result, err)
	}
}

// COD-T-081: UTF-16LE BOM detection folds CRLF into line records and reports the final newline state.
func TestReadUTF16LECRLFWindow(t *testing.T) {
	path := filepath.Join(t.TempDir(), "utf16.txt")
	data, err := EncodeText("中\r\n文", "UTF-16LE", 2)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	result, err := readCapability(context.Background(), capabilityCall(t, "utf16-window", map[string]string{"path": path}), runtime.CapabilityContext{})
	if err != nil || result.Error {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	for _, expected := range []string{"ends_with_newline: no", "1|中", "2|文"} {
		if !strings.Contains(result.Contents[0].Text, expected) {
			t.Fatalf("read result missing %q: %q", expected, result.Contents[0].Text)
		}
	}
}

// COD-T-083 is asserted by TestReadImageUsesRequestedTierAndReportsDimensions.

// COD-T-084: recognized media returns the sniffed type, basename, and original bytes.
func TestReadMediaReportsTypeNameAndOriginalBytes(t *testing.T) {
	cases := []struct {
		name      string
		data      []byte
		mediaType string
	}{
		{"sample.mp4", []byte{0, 0, 0, 12, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'}, "video/mp4"},
		{"sample.mov", []byte{0, 0, 0, 12, 'f', 't', 'y', 'p', 'q', 't', ' ', ' '}, "video/quicktime"},
		{"sample.wav", []byte{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'}, "audio/wav"},
		{"sample-id3.mp3", []byte{'I', 'D', '3', 4, 0}, "audio/mpeg"},
		{"sample-frame.mp3", []byte{0xff, 0xfb, 0x90, 0x64}, "audio/mpeg"},
		{"sample.pdf", []byte("%PDF-1.7\n"), "application/pdf"},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), test.name)
			if err := os.WriteFile(path, test.data, 0o600); err != nil {
				t.Fatal(err)
			}
			call := runtime.CapabilityCall{InvocationID: test.name, Arguments: mustJSON(map[string]any{"path": path})}
			result, err := Provider{}.Capabilities()["fs.read"](context.Background(), call, runtime.CapabilityContext{})
			if err != nil || result.Error || len(result.Contents) != 1 {
				t.Fatalf("result=%#v err=%v", result, err)
			}
			content := result.Contents[0]
			if content.Type != "binary" || content.MediaType != test.mediaType || content.Name == nil || *content.Name != test.name || string(content.Bytes) != string(test.data) {
				t.Fatalf("content=%#v want media=%q name=%q bytes=%x", content, test.mediaType, test.name, test.data)
			}
		})
	}
}

// COD-T-085: a media-looking extension does not make ordinary text binary.
func TestReadTextIgnoresMediaExtension(t *testing.T) {
	path := filepath.Join(t.TempDir(), "movie.mp4")
	const source = "plain text, not a movie\n"
	if err := os.WriteFile(path, []byte(source), 0o600); err != nil {
		t.Fatal(err)
	}
	result, err := readCapability(context.Background(), capabilityCall(t, "text-movie", map[string]string{"path": path}), runtime.CapabilityContext{})
	if err != nil || result.Error || !strings.Contains(result.Contents[0].Text, strings.TrimSuffix(source, "\n")) {
		t.Fatalf("result=%#v err=%v", result, err)
	}
}

// COD-T-089: fs.write retains the UTF-8 BOM when overwriting an existing file.
func TestWriteRetainsUTF8BOM(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bom.txt")
	before, err := EncodeText("old", "UTF-8", 3)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, before, 0o600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.write", map[string]any{"path": path, "content": "new"}, nil)
	if result.Error {
		t.Fatalf("write failed: %#v", result)
	}
	written, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(written) != "\xef\xbb\xbfnew" {
		t.Fatalf("written bytes=%x", written)
	}
}

// COD-T-091: writes reject dangling symlinks instead of creating their targets.
func TestWriteRejectsDanglingSymlink(t *testing.T) {
	if goruntime.GOOS == "windows" {
		t.Skip("creating symlinks requires platform privileges")
	}
	root := t.TempDir()
	link := filepath.Join(root, "dangling.txt")
	target := filepath.Join(root, "missing.txt")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	result := invokeCoding(t, Provider{}, "fs.write", map[string]any{"path": link, "content": "new"}, nil)
	if !result.Error || !strings.Contains(result.Contents[0].Text, "symbolic link") {
		t.Fatalf("result=%#v", result)
	}
	if _, err := os.Stat(target); !os.IsNotExist(err) {
		t.Fatalf("dangling symlink target stat error=%v", err)
	}
}

// COD-T-092: a single edit preserves CRLF throughout the file.
func TestEditUniqueMatchPreservesCRLF(t *testing.T) {
	path := filepath.Join(t.TempDir(), "crlf.txt")
	if err := os.WriteFile(path, []byte("one\r\nold\r\nthree\r\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.edit", editArgs(path, replace("old", "TWO")), nil)
	if result.Error || !strings.Contains(result.Contents[0].Text, "Replacements: 1") {
		t.Fatalf("result=%#v", result)
	}
	written, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(written) != "one\r\nTWO\r\nthree\r\n" {
		t.Fatalf("written=%q", written)
	}
}

// COD-T-094: replace_all reports every replacement and writes the complete result.
func TestEditReplaceAllReplacesEveryMatch(t *testing.T) {
	path := filepath.Join(t.TempDir(), "replace-all.txt")
	if err := os.WriteFile(path, []byte("old old\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.edit", editArgs(path, replaceAll("old", "new")), nil)
	if result.Error || !strings.Contains(result.Contents[0].Text, "Replacements: 2") {
		t.Fatalf("result=%#v", result)
	}
	written, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(written) != "new new\n" {
		t.Fatalf("written=%q", written)
	}
}

// COD-T-098: multiline edits preserve mixed EOLs and files without a final newline.
func TestEditMixedLineEndingsAndNoFinalNewline(t *testing.T) {
	path := filepath.Join(t.TempDir(), "mixed.txt")
	if err := os.WriteFile(path, []byte("line1\r\nline2\nline3\r\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.edit", editArgs(path, replace("line2\n", "sub1\nsub2\nsub3\n")), nil)
	if result.Error {
		t.Fatalf("multiline edit failed: %#v", result)
	}
	written, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(written) != "line1\r\nsub1\nsub2\nsub3\nline3\r\n" {
		t.Fatalf("mixed EOL result=%q", written)
	}
	noEnding := filepath.Join(t.TempDir(), "no-ending.txt")
	if err := os.WriteFile(noEnding, []byte("hello"), 0o600); err != nil {
		t.Fatal(err)
	}
	result = invokeCoding(t, Provider{}, "fs.edit", editArgs(noEnding, replace("o", "p")), nil)
	if result.Error {
		t.Fatalf("no-ending edit failed: %#v", result)
	}
	written, err = os.ReadFile(noEnding)
	if err != nil {
		t.Fatal(err)
	}
	if string(written) != "hellp" {
		t.Fatalf("no-ending result=%q", written)
	}
}
