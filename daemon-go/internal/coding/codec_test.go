package coding

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/coding/search"
	"github.com/fengwk/kk-studio/daemon/internal/runtime"
	"github.com/fengwk/kk-studio/daemon/internal/textcodec"
)

// declaredBytes encodes text with a declared legacy encoding to build a fixture.
func declaredBytes(t *testing.T, name, text string) []byte {
	t.Helper()
	data, err := textcodec.Encode(text, textcodec.Encoding{Name: name})
	if err != nil {
		t.Fatalf("encode %s: %v", name, err)
	}
	return data
}

// mustEncode encodes text with a named encoding and fails the test when the codec refuses.
func mustEncode(t *testing.T, text, charset string, bomBytes int) []byte {
	t.Helper()
	data, err := encodeFixture(text, charset, bomBytes)
	if err != nil {
		t.Fatalf("encode %s: %v", charset, err)
	}
	return data
}

// writeFixture writes data to a fresh file and returns its path.
func writeFixture(t *testing.T, name string, data []byte) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

// COD-T-260: read reports the encoding of a declared file and decodes its text.
func TestReadReportsEncodingHeader(t *testing.T) {
	path := writeFixture(t, "page.html", declaredBytes(t, "GBK", `<html><head><meta charset="gbk"></head><body>中文</body></html>`))
	result := invokeCoding(t, Provider{}, "fs.read", map[string]any{"path": path}, nil)
	if result.Error {
		t.Fatalf("result=%#v", result)
	}
	text := result.Contents[0].Text
	if !strings.Contains(text, "encoding: GBK\n") || !strings.Contains(text, "中文") {
		t.Fatalf("text=%q", text)
	}
	if strings.Contains(text, "encoding: UTF-8") {
		t.Fatalf("UTF-8 must not be reported: %q", text)
	}
	plain := writeFixture(t, "plain.txt", []byte("plain\n"))
	if text := invokeCoding(t, Provider{}, "fs.read", map[string]any{"path": plain}, nil).Contents[0].Text; strings.Contains(text, "encoding:") {
		t.Fatalf("plain text=%q", text)
	}
}

// COD-T-261: read explains undeclared, binary and conflicting files precisely.
func TestReadReportsCodecFailures(t *testing.T) {
	undecodable := invokeCoding(t, Provider{}, "fs.read", map[string]any{"path": writeFixture(t, "gbk.txt", []byte("\xd6\xd0\xce\xc4\n"))}, nil)
	if !undecodable.Error || !strings.Contains(undecodable.Contents[0].Text, "is not UTF-8 text and declares no encoding, so it was not read") || !strings.Contains(undecodable.Contents[0].Text, "iconv -f GBK") {
		t.Fatalf("undeclared=%#v", undecodable)
	}
	if !strings.Contains(string(undecodable.Details), `"code":"CODEC"`) {
		t.Fatalf("details=%s", undecodable.Details)
	}
	conflict := invokeCoding(t, Provider{}, "fs.read", map[string]any{"path": writeFixture(t, "page.html", []byte(`<html><head><meta charset="gb2312"></head><body>中文</body></html>`))}, nil)
	if !conflict.Error || !strings.Contains(conflict.Contents[0].Text, "HTML meta charset") || !strings.Contains(conflict.Contents[0].Text, "GBK") {
		t.Fatalf("conflict=%#v", conflict)
	}
	unsupported := invokeCoding(t, Provider{}, "fs.read", map[string]any{"path": writeFixture(t, "doc.xml", []byte(`<?xml encoding="klingon"?>x`))}, nil)
	if !unsupported.Error || !strings.Contains(unsupported.Contents[0].Text, "XML declaration") || !strings.Contains(unsupported.Contents[0].Text, "klingon") {
		t.Fatalf("unsupported=%#v", unsupported)
	}
}

// COD-T-262: write keeps the encoding of the existing file or adopts the declared one.
func TestWriteKeepsOrAdoptsDeclaredEncoding(t *testing.T) {
	converted := writeFixture(t, "page.html", declaredBytes(t, "GBK", `<meta charset="gbk">旧`))
	updated := `<meta charset="gbk">新`
	if _, err := Write(converted, updated, nil); err != nil {
		t.Fatalf("write: %v", err)
	}
	written, err := os.ReadFile(converted)
	if err != nil {
		t.Fatal(err)
	}
	if string(written) != string(declaredBytes(t, "GBK", updated)) {
		t.Fatalf("bytes=%x", written)
	}
	withBOM := writeFixture(t, "bom.txt", append([]byte{0xef, 0xbb, 0xbf}, []byte("old\n")...))
	if _, err = Write(withBOM, "new\n", nil); err != nil {
		t.Fatalf("BOM write: %v", err)
	}
	if written, err = os.ReadFile(withBOM); err != nil || string(written) != "\xef\xbb\xbfnew\n" {
		t.Fatalf("BOM bytes=%x err=%v", written, err)
	}
	created := filepath.Join(t.TempDir(), "page.html")
	if _, err = Write(created, `<meta charset="gbk">中文`, nil); err != nil {
		t.Fatalf("create: %v", err)
	}
	if written, err = os.ReadFile(created); err != nil || string(written) != string(declaredBytes(t, "GBK", `<meta charset="gbk">中文`)) {
		t.Fatalf("created bytes=%x err=%v", written, err)
	}
}

// COD-T-263: write refuses changes that would silently drop or contradict an encoding.
func TestWriteRefusesEncodingChanges(t *testing.T) {
	declared := writeFixture(t, "page.html", declaredBytes(t, "GBK", `<meta charset="gbk">旧`))
	if _, err := Write(declared, "no declaration", nil); err == nil || !strings.Contains(err.Error(), "removes the GBK declaration") {
		t.Fatalf("dropped declaration err=%v", err)
	}
	declaredUTF8 := writeFixture(t, "declared.html", []byte(`<meta charset="utf-8">old`))
	if _, err := Write(declaredUTF8, "plain", nil); err != nil || !strings.Contains(string(mustRead(t, declaredUTF8)), "plain") {
		t.Fatalf("dropped UTF-8 declaration err=%v", err)
	}
	withBOM := writeFixture(t, "bom.html", append([]byte{0xef, 0xbb, 0xbf}, []byte("old\n")...))
	if _, err := Write(withBOM, `<meta charset="gbk">new`, nil); err == nil || !strings.Contains(err.Error(), "byte order mark") {
		t.Fatalf("BOM conflict err=%v", err)
	}
	gbk := writeFixture(t, "page.html", declaredBytes(t, "GBK", `<meta charset="gbk">旧`))
	if _, err := Write(gbk, `<meta charset="gbk">☃`, nil); err == nil || !strings.Contains(err.Error(), "cannot be encoded in GBK") || !strings.Contains(err.Error(), "U+2603") {
		t.Fatalf("unrepresentable err=%v", err)
	}
	binary := writeFixture(t, "binary.dat", []byte{'a', 0, 'b'})
	if _, err := Write(binary, "text", nil); err == nil || !strings.Contains(err.Error(), "is a binary file") || !strings.Contains(err.Error(), "write only rewrites text files") {
		t.Fatalf("binary err=%v", err)
	}
	if _, err := PreviewWrite(binary, "text"); err == nil || !strings.Contains(err.Error(), "is a binary file") {
		t.Fatalf("preview err=%v", err)
	}
	if _, err := PreviewWrite(filepath.Join(t.TempDir(), "page.html"), `<meta charset="klingon">x`); err == nil || !strings.Contains(err.Error(), "klingon") {
		t.Fatalf("preview label err=%v", err)
	}
}

func mustRead(t *testing.T, path string) []byte {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return data
}

// COD-T-264: grep searches decoded text and lists files it could not decode.
func TestGrepDecodesAndListsUndecodableFiles(t *testing.T) {
	root := t.TempDir()
	gbk := filepath.Join(root, "page.html")
	if err := os.WriteFile(gbk, declaredBytes(t, "GBK", `<meta charset="gbk">中文target`), 0o600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.grep", map[string]any{"path": root, "pattern": "target", "literal": true}, nil)
	if result.Error {
		t.Fatalf("result=%#v", result)
	}
	if text := result.Contents[0].Text; !strings.Contains(text, "page.html:1:") || !strings.Contains(text, "中文target") {
		t.Fatalf("text=%q", text)
	}
	if err := os.WriteFile(filepath.Join(root, "gbk.txt"), []byte("x\xd6\xd0target"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "binary.dat"), []byte{'x', 0, 'y'}, 0o600); err != nil {
		t.Fatal(err)
	}
	result = invokeCoding(t, Provider{}, "fs.grep", map[string]any{"path": root, "pattern": "target", "literal": true}, nil)
	if text := result.Contents[0].Text; !strings.Contains(text, "[Not searched because the text encoding could not be determined: "+filepath.ToSlash(filepath.Join(root, "gbk.txt"))+"]") {
		t.Fatalf("text=%q", text)
	}
	if strings.Contains(result.Contents[0].Text, "binary.dat") {
		t.Fatalf("binary file was listed: %q", result.Contents[0].Text)
	}
	direct := invokeCoding(t, Provider{}, "fs.grep", map[string]any{"path": filepath.Join(root, "gbk.txt"), "pattern": "target", "literal": true}, nil)
	if !direct.Error || !strings.Contains(direct.Contents[0].Text, "so it was not searched") || !strings.Contains(string(direct.Details), `"code":"CODEC"`) {
		t.Fatalf("direct=%#v", direct)
	}
	if _, err := search.GrepContext(context.Background(), filepath.Join(root, "gbk.txt"), "target", true, false, "", 10); err == nil || !strings.Contains(err.Error(), "not UTF-8 text") {
		t.Fatalf("search err=%v", err)
	}
	paths := make([]string, 12)
	for index := range paths {
		paths[index] = fmt.Sprintf("/repo/%02d", index)
	}
	summary := formatSearchResult(runtime.CapabilityCall{InvocationID: "undecodable"}, t.TempDir(), search.SearchOutcome{Undecodable: paths, UndecodableCount: len(paths)}, 10, "No matches found", "matches")
	if text := summary.Contents[0].Text; !strings.HasPrefix(text, "No matches found\n[Not searched because the text encoding could not be determined: /repo/00") || !strings.Contains(text, "… and 2 more") {
		t.Fatalf("text=%q", text)
	}
}

// decodeFixture decodes fixture bytes with the shared codec and reports the text.
func decodeFixture(data []byte) (string, textcodec.Encoding, error) {
	text, encoding, err := textcodec.Decode("", data)
	return string(text), encoding, err
}

// encodeFixture encodes text with a named encoding, restoring a BOM when bomBytes is positive.
func encodeFixture(text, charset string, bomBytes int) ([]byte, error) {
	return textcodec.Encode(text, textcodec.Encoding{Name: charset, BOM: bomBytes > 0})
}

// COD-T-272: write accepts a BOM-marked file whose content declares its own UTF-16 family and
// keeps its byte order mark and bytes; another order and a new file without a mark are refused.
func TestWriteKeepsBOMWithFamilyDeclaration(t *testing.T) {
	content := `<?xml version="1.0" encoding="UTF-16"?><root>中文</root>`
	path := writeFixture(t, "doc.xml", mustEncode(t, content+"<old/>", "UTF-16LE", 2))
	if _, err := Write(path, content, nil); err != nil {
		t.Fatalf("family declaration: %v", err)
	}
	if written := mustRead(t, path); string(written) != string(mustEncode(t, content, "UTF-16LE", 2)) {
		t.Fatalf("bytes=%x", written)
	}
	updated := content + "<next/>"
	if _, err := Write(path, updated, nil); err != nil {
		t.Fatalf("update: %v", err)
	}
	if written := mustRead(t, path); string(written) != string(mustEncode(t, updated, "UTF-16LE", 2)) {
		t.Fatalf("updated bytes=%x", written)
	}
	otherOrder := `<?xml version="1.0" encoding="UTF-16BE"?><root/>`
	if _, err := Write(path, otherOrder, nil); err == nil || !strings.Contains(err.Error(), "byte order mark") || !strings.Contains(err.Error(), "UTF-16BE") {
		t.Fatalf("other order err=%v", err)
	}
	created := filepath.Join(t.TempDir(), "new.xml")
	_, err := Write(created, `<?xml version="1.0" encoding="UTF-16"?><root/>`, nil)
	if err == nil || !strings.Contains(err.Error(), "byte order mark") || !strings.Contains(err.Error(), created) {
		t.Fatalf("new file err=%v", err)
	}
}
