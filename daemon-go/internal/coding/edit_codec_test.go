package coding

import (
	"encoding/json"
	"strings"
	"testing"
)

// An edit keeps the file's encoding and refuses changes that would re-encode it.
func TestEditKeepsDeclaredEncodingAndRefusesChanges(t *testing.T) {
	kept := writeFixture(t, "page.html", declaredBytes(t, "GBK", `<meta charset="gbk">旧`))
	result := invokeCoding(t, Provider{}, "fs.edit", editArgs(kept, replace("旧", "新")), nil)
	if result.Error {
		t.Fatalf("edit failed: %#v", result)
	}
	if written := mustRead(t, kept); string(written) != string(declaredBytes(t, "GBK", `<meta charset="gbk">新`)) {
		t.Fatalf("bytes=%x", written)
	}
	changed := writeFixture(t, "change.html", declaredBytes(t, "GBK", `<meta charset="gbk">旧`))
	result = invokeCoding(t, Provider{}, "fs.edit", editArgs(changed, replace("gbk", "big5")), nil)
	if !result.Error || !strings.Contains(result.Contents[0].Text, "would change the encoding of") || !strings.Contains(result.Contents[0].Text, "from GBK to Big5") {
		t.Fatalf("declaration change=%#v", result)
	}
	if written := mustRead(t, changed); string(written) != string(declaredBytes(t, "GBK", `<meta charset="gbk">旧`)) {
		t.Fatalf("refused edit rewrote the file: %x", written)
	}
	dropped := writeFixture(t, "drop.py", declaredBytes(t, "GBK", "# coding: gbk\nx = 1\n"))
	result = invokeCoding(t, Provider{}, "fs.edit", editArgs(dropped, replace("# coding: gbk\n", "")), nil)
	if !result.Error || !strings.Contains(result.Contents[0].Text, "from GBK to UTF-8") {
		t.Fatalf("dropped declaration=%#v", result)
	}
	unrepresentable := writeFixture(t, "snow.html", declaredBytes(t, "GBK", `<meta charset="gbk">旧`))
	result = invokeCoding(t, Provider{}, "fs.edit", editArgs(unrepresentable, replace("旧", "☃")), nil)
	if !result.Error || !strings.Contains(result.Contents[0].Text, "cannot be encoded in GBK") || !strings.Contains(result.Contents[0].Text, "U+2603") {
		t.Fatalf("unrepresentable=%#v", result)
	}
}

// An edit of a lossy file rewrites the U+FFFD view it showed and discloses the loss in the review;
// binary files and declarations the codec cannot use are still refused before any write.
func TestEditDisclosesLossyDecodeAndRefusesCodecFailures(t *testing.T) {
	lossy := writeFixture(t, "lossy.txt", []byte("bad: \xff\n"))
	preview := invokeCoding(t, Provider{}, "fs.edit.preview", editArgs(lossy, replace("\ufffd", "\ufffd!")), nil)
	if preview.Error {
		t.Fatalf("preview=%#v", preview)
	}
	var review EditReview
	if err := json.Unmarshal(preview.Contents[0].JSON, &review); err != nil {
		t.Fatal(err)
	}
	if review.LossyDecode == nil || review.LossyDecode.Encoding != "UTF-8" || review.LossyDecode.InvalidSequences != 1 {
		t.Fatalf("review=%#v", review)
	}
	result := invokeCoding(t, Provider{}, "fs.edit", editArgs(lossy, replace("\ufffd", "\ufffd!")), nil)
	if result.Error || !strings.Contains(result.Contents[0].Text, "Note: the original file contains 1 byte sequence that is not valid UTF-8, shown as U+FFFD (�); those still present were saved as U+FFFD, so their original bytes are lost.") {
		t.Fatalf("result=%#v", result)
	}
	if written := mustRead(t, lossy); string(written) != "bad: \xef\xbf\xbd!\n" {
		t.Fatalf("bytes=%x", written)
	}
	binary := writeFixture(t, "binary.dat", []byte{'a', 0, 'b'})
	for _, capability := range []string{"fs.edit", "fs.edit.preview"} {
		result = invokeCoding(t, Provider{}, capability, editArgs(binary, replace("a", "c")), nil)
		if !result.Error || !strings.Contains(result.Contents[0].Text, "is a binary file (it contains NUL bytes); edit only rewrites text files.") || !strings.Contains(result.Contents[0].Text, "The tool was not executed.") {
			t.Fatalf("%s binary=%#v", capability, result)
		}
	}
	if written := mustRead(t, binary); string(written) != "a\x00b" {
		t.Fatalf("refused edit rewrote the binary file: %x", written)
	}
	conflict := writeFixture(t, "conflict.html", []byte(`<meta charset="utf-16le">中文`))
	result = invokeCoding(t, Provider{}, "fs.edit", editArgs(conflict, replace("中文", "英文")), nil)
	if !result.Error || !strings.Contains(result.Contents[0].Text, "HTML meta charset") || !strings.Contains(string(result.Details), `"code":"CODEC"`) {
		t.Fatalf("conflict=%#v", result)
	}
}

// Edit keeps a BOM-marked file whose content declares its own UTF-16 family, and
// refuses a declaration of the other byte order.
func TestEditKeepsBOMWithFamilyDeclaration(t *testing.T) {
	content := `<?xml version="1.0" encoding="UTF-16"?><root>旧</root>`
	path := writeFixture(t, "doc.xml", mustEncode(t, content, "UTF-16LE", 2))
	if result := invokeCoding(t, Provider{}, "fs.edit", editArgs(path, replace("旧", "新")), nil); result.Error {
		t.Fatalf("edit failed: %#v", result)
	}
	want := mustEncode(t, strings.Replace(content, "旧", "新", 1), "UTF-16LE", 2)
	if written := mustRead(t, path); string(written) != string(want) {
		t.Fatalf("bytes=%x want=%x", written, want)
	}
	result := invokeCoding(t, Provider{}, "fs.edit", editArgs(path, replace(`encoding="UTF-16"`, `encoding="UTF-16BE"`)), nil)
	if !result.Error || !strings.Contains(result.Contents[0].Text, "would change the encoding of") || !strings.Contains(result.Contents[0].Text, "UTF-16BE") {
		t.Fatalf("other order=%#v", result)
	}
}
