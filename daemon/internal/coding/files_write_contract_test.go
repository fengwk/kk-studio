package coding

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	goruntime "runtime"
	"strings"
	"testing"
)

// writeReviewJSON decodes the review an approval carries.
func writeReviewJSON(t *testing.T, data []byte) WriteReview {
	t.Helper()
	var review WriteReview
	if err := json.Unmarshal(data, &review); err != nil {
		t.Fatal(err)
	}
	return review
}

// An in-place write keeps the inode, the mode and every hard link of the file.
func TestWriteCommitsInPlace(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "linked.txt")
	if err := os.WriteFile(path, []byte("alpha\n"), 0o640); err != nil {
		t.Fatal(err)
	}
	alias := filepath.Join(dir, "alias.txt")
	if err := os.Link(path, alias); err != nil {
		t.Skipf("hard links unavailable: %v", err)
	}
	before, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = Write(path, "beta\n", nil); err != nil {
		t.Fatalf("write: %v", err)
	}
	after, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if !os.SameFile(before, after) {
		t.Fatal("the in-place write replaced the file instead of rewriting it")
	}
	if after.Mode().Perm() != before.Mode().Perm() {
		t.Fatalf("mode changed from %#o to %#o", before.Mode().Perm(), after.Mode().Perm())
	}
	linked, err := os.ReadFile(alias)
	if err != nil || string(linked) != "beta\n" {
		t.Fatalf("hard link contents=%q err=%v", linked, err)
	}
}

// A writable file in a read-only directory can be rewritten in place, by write and by edit.
func TestWriteAndEditSucceedInReadOnlyDirectory(t *testing.T) {
	if goruntime.GOOS == "windows" {
		t.Skip("POSIX directory permissions are required")
	}
	dir := filepath.Join(t.TempDir(), "ro")
	if err := os.Mkdir(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "file.txt")
	if err := os.WriteFile(path, []byte("alpha\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(dir, 0o555); err != nil {
		t.Fatal(err)
	}
	defer os.Chmod(dir, 0o755)
	if _, err := Write(path, "beta\n", nil); err != nil {
		t.Fatalf("write: %v", err)
	}
	if data, err := os.ReadFile(path); err != nil || string(data) != "beta\n" {
		t.Fatalf("contents=%q err=%v", data, err)
	}
	result := invokeCoding(t, Provider{}, "fs.edit", editArgs(path, replace("beta", "gamma")), nil)
	if result.Error {
		t.Fatalf("edit failed: %#v", result)
	}
	if data, err := os.ReadFile(path); err != nil || string(data) != "gamma\n" {
		t.Fatalf("edited contents=%q err=%v", data, err)
	}
}

// Creating a new file never replaces a path that exists.
func TestWriteCreatesNewFileExclusively(t *testing.T) {
	path := filepath.Join(t.TempDir(), "existing.txt")
	if err := os.WriteFile(path, []byte("keep"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := writeInPlace(path, []byte("clobber"), false); !errors.Is(err, os.ErrExist) {
		t.Fatalf("exclusive create error=%v", err)
	}
	if data, err := os.ReadFile(path); err != nil || string(data) != "keep" {
		t.Fatalf("contents=%q err=%v", data, err)
	}
}

// A leading U+FEFF asks write for a byte order mark: a UTF-16 or UTF-32 declaration writes the
// requested order, a legacy declaration is refused, and the request itself is never content.
func TestWriteBOMRequest(t *testing.T) {
	dir := t.TempDir()
	utf16Path := filepath.Join(dir, "utf16.xml")
	if _, err := Write(utf16Path, "\uFEFF<?xml version=\"1.0\" encoding=\"UTF-16\"?><r>中</r>", nil); err != nil {
		t.Fatalf("UTF-16 request: %v", err)
	}
	if data := mustRead(t, utf16Path); !bytes.HasPrefix(data, []byte{0xff, 0xfe}) {
		t.Fatalf("UTF-16 bytes=%x", data)
	}
	read := invokeCoding(t, Provider{}, "fs.read", map[string]any{"path": utf16Path}, nil)
	if read.Error || !strings.Contains(read.Contents[0].Text, "encoding: UTF-16LE\n") || !strings.Contains(read.Contents[0].Text, "中") {
		t.Fatalf("read=%#v", read)
	}
	bePath := filepath.Join(dir, "utf16be.xml")
	if _, err := Write(bePath, "\uFEFF<?xml version=\"1.0\" encoding=\"UTF-16BE\"?><r/>", nil); err != nil {
		t.Fatalf("UTF-16BE request: %v", err)
	}
	if data := mustRead(t, bePath); !bytes.HasPrefix(data, []byte{0xfe, 0xff}) {
		t.Fatalf("UTF-16BE bytes=%x", data)
	}
	if read := invokeCoding(t, Provider{}, "fs.read", map[string]any{"path": bePath}, nil); read.Error || !strings.Contains(read.Contents[0].Text, "encoding: UTF-16BE\n") || !strings.Contains(read.Contents[0].Text, "<r/>") {
		t.Fatalf("UTF-16BE read=%#v", read)
	}
	utf32Path := filepath.Join(dir, "utf32.xml")
	if _, err := Write(utf32Path, "\uFEFF<?xml encoding=\"UTF-32\"?><r/>", nil); err != nil {
		t.Fatalf("UTF-32 request: %v", err)
	}
	if data := mustRead(t, utf32Path); !bytes.HasPrefix(data, []byte{0xff, 0xfe, 0, 0}) {
		t.Fatalf("UTF-32 bytes=%x", data)
	}
	if read := invokeCoding(t, Provider{}, "fs.read", map[string]any{"path": utf32Path}, nil); read.Error || !strings.Contains(read.Contents[0].Text, "encoding: UTF-32LE\n") || !strings.Contains(read.Contents[0].Text, "<r/>") {
		t.Fatalf("UTF-32 read=%#v", read)
	}
	utf8Path := filepath.Join(dir, "utf8.txt")
	if _, err := Write(utf8Path, "\uFEFFplain", nil); err != nil {
		t.Fatalf("UTF-8 request: %v", err)
	}
	if data := mustRead(t, utf8Path); string(data) != "\xef\xbb\xbfplain" {
		t.Fatalf("UTF-8 bytes=%x", data)
	}
	// Without the request a UTF-16 declaration cannot be backed and nothing is created.
	plain := filepath.Join(dir, "plain.xml")
	if _, err := Write(plain, `<?xml version="1.0" encoding="UTF-16LE"?><r/>`, nil); err == nil || !strings.Contains(err.Error(), "Start the content with U+FEFF to write it with a byte order mark") {
		t.Fatalf("missing request err=%v", err)
	}
	if _, err := os.Stat(plain); !os.IsNotExist(err) {
		t.Fatalf("refused write created the file: %v", err)
	}
	// A legacy encoding has no byte order mark.
	gbk := filepath.Join(dir, "gbk.xml")
	if _, err := Write(gbk, "\uFEFF<?xml encoding=\"GBK\"?><r/>", nil); err == nil || !strings.Contains(err.Error(), "has no byte order mark") {
		t.Fatalf("legacy request err=%v", err)
	}
	if _, err := os.Stat(gbk); !os.IsNotExist(err) {
		t.Fatalf("refused write created the file: %v", err)
	}
}

// An existing BOM file keeps its encoding and never gains a second byte order mark.
func TestWriteBOMRequestOnExistingBOMFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bom.txt")
	if err := os.WriteFile(path, []byte("\xef\xbb\xbfold\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Write(path, "\uFEFFnew\n", nil); err != nil {
		t.Fatal(err)
	}
	if data := mustRead(t, path); string(data) != "\xef\xbb\xbfnew\n" {
		t.Fatalf("bytes=%x", data)
	}
}

// Preview and commit disclose a lossy decode: the approval cannot show the bytes the save rewrites.
func TestWriteDisclosesLossyDecode(t *testing.T) {
	path := filepath.Join(t.TempDir(), "lossy.txt")
	if err := os.WriteFile(path, []byte("bad: \xff\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	preview := invokeCoding(t, Provider{}, "fs.write.preview", map[string]any{"path": path, "content": "bad: \ufffd!\n"}, nil)
	if preview.Error {
		t.Fatalf("preview=%#v", preview)
	}
	pending := writeReviewJSON(t, preview.Contents[0].JSON)
	if pending.LossyDecode == nil || pending.LossyDecode.Encoding != "UTF-8" || pending.LossyDecode.InvalidSequences != 1 {
		t.Fatalf("preview review=%#v", pending)
	}
	result := invokeCoding(t, Provider{}, "fs.write", map[string]any{"path": path, "content": "bad: \ufffd!\n"}, nil)
	if result.Error {
		t.Fatalf("write=%#v", result)
	}
	note := "Note: the original file contains 1 byte sequence that is not valid UTF-8, shown as U+FFFD (�); those still present were saved as U+FFFD, so their original bytes are lost."
	if !strings.Contains(result.Contents[0].Text, note) {
		t.Fatalf("text=%q", result.Contents[0].Text)
	}
	var details struct {
		WriteReview WriteReview `json:"writeReview"`
	}
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	if details.WriteReview.LossyDecode == nil || details.WriteReview.LossyDecode.InvalidSequences != 1 {
		t.Fatalf("details=%s", result.Details)
	}
	if data := mustRead(t, path); string(data) != "bad: \xef\xbf\xbd!\n" {
		t.Fatalf("bytes=%x", data)
	}
}

// Content identical to the decoded file changes nothing, so the write is refused before it runs.
// A lossy file makes this reachable: its decoded text round-trips to different bytes.
func TestWriteRejectsContentIdenticalToDecodedFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "lossy.txt")
	if err := os.WriteFile(path, []byte("bad: \xff\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	before := mustRead(t, path)
	result := invokeCoding(t, Provider{}, "fs.write", map[string]any{"path": path, "content": "bad: \ufffd\n"}, nil)
	if !result.Error || !strings.Contains(result.Contents[0].Text, "identical to the current file") || !strings.Contains(result.Contents[0].Text, "nothing was written") {
		t.Fatalf("write=%#v", result)
	}
	if !strings.Contains(result.Contents[0].Text, "The tool was not executed.") || !strings.Contains(string(result.Details), `"code":"INVALID_ARGUMENT"`) || strings.Contains(result.Contents[0].Text, "cannot be confirmed") {
		t.Fatalf("write result=%#v", result)
	}
	if after := mustRead(t, path); string(after) != string(before) {
		t.Fatalf("refused write changed the file: %x", after)
	}
	preview := invokeCoding(t, Provider{}, "fs.write.preview", map[string]any{"path": path, "content": "bad: \ufffd\n"}, nil)
	if !preview.Error || !strings.Contains(string(preview.Details), `"code":"INVALID_ARGUMENT"`) {
		t.Fatalf("preview=%#v", preview)
	}
	clean := filepath.Join(filepath.Dir(path), "clean.txt")
	if err := os.WriteFile(clean, []byte("same\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	result = invokeCoding(t, Provider{}, "fs.write", map[string]any{"path": clean, "content": "same\n"}, nil)
	if !result.Error || !strings.Contains(result.Contents[0].Text, "The tool was not executed.") {
		t.Fatalf("clean=%#v", result)
	}
}

// A new file never carries the disclosure, and a lossless write never carries it either.
func TestWriteOmitsLossyDecodeWithoutLoss(t *testing.T) {
	dir := t.TempDir()
	created := invokeCoding(t, Provider{}, "fs.write.preview", map[string]any{"path": filepath.Join(dir, "new.txt"), "content": "bad: \ufffd\n"}, nil)
	if created.Error || strings.Contains(string(created.Contents[0].JSON), "lossyDecode") {
		t.Fatalf("new file review=%s", created.Contents[0].JSON)
	}
	path := filepath.Join(dir, "clean.txt")
	if err := os.WriteFile(path, []byte("clean\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	clean := invokeCoding(t, Provider{}, "fs.write.preview", map[string]any{"path": path, "content": "changed\n"}, nil)
	if clean.Error || strings.Contains(string(clean.Contents[0].JSON), "lossyDecode") {
		t.Fatalf("lossless review=%s", clean.Contents[0].JSON)
	}
}

// A GBK file whose characters are also valid UTF-8 keeps working across write, read, write
// and edit.
func TestWriteGBKTextThatIsValidUTF8RoundTrips(t *testing.T) {
	path := filepath.Join(t.TempDir(), "a.xml")
	first := "<?xml version=\"1.0\" encoding=\"GBK\"?>\n<name>茅</name>\n"
	if _, err := Write(path, first, nil); err != nil {
		t.Fatalf("write: %v", err)
	}
	if data := mustRead(t, path); !bytes.Equal(data, declaredBytes(t, "GBK", first)) {
		t.Fatalf("bytes=%x want=%x", data, declaredBytes(t, "GBK", first))
	}
	read := invokeCoding(t, Provider{}, "fs.read", map[string]any{"path": path}, nil)
	if read.Error || !strings.Contains(read.Contents[0].Text, "茅") || !strings.Contains(read.Contents[0].Text, "encoding: GBK\n") {
		t.Fatalf("read=%#v", read)
	}
	second := "<?xml version=\"1.0\" encoding=\"GBK\"?>\n<name>茅2</name>\n"
	if _, err := Write(path, second, nil); err != nil {
		t.Fatalf("second write: %v", err)
	}
	if data := mustRead(t, path); !bytes.Equal(data, declaredBytes(t, "GBK", second)) {
		t.Fatalf("bytes=%x want=%x", data, declaredBytes(t, "GBK", second))
	}
	if result := invokeCoding(t, Provider{}, "fs.edit", editArgs(path, replace("茅2", "茅3")), nil); result.Error {
		t.Fatalf("edit=%#v", result)
	}
	third := "<?xml version=\"1.0\" encoding=\"GBK\"?>\n<name>茅3</name>\n"
	if data := mustRead(t, path); !bytes.Equal(data, declaredBytes(t, "GBK", third)) {
		t.Fatalf("bytes=%x want=%x", data, declaredBytes(t, "GBK", third))
	}
}
