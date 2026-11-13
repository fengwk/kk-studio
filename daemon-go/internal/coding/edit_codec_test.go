package coding

import (
	"strings"
	"testing"
)

// COD-T-270: an edit keeps the file's encoding and refuses changes that would re-encode it.
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

// COD-T-271: an edit refuses files the shared codec cannot decode, in both edit and preview.
func TestEditRefusesUndecodableFiles(t *testing.T) {
	undeclared := writeFixture(t, "gbk.txt", []byte("\xd6\xd0\xce\xc4\n"))
	result := invokeCoding(t, Provider{}, "fs.edit", editArgs(undeclared, replace("\xd6\xd0", "\xd6\xd1")), nil)
	if !result.Error || !strings.Contains(result.Contents[0].Text, "is not UTF-8 text and declares no encoding") || !strings.Contains(result.Contents[0].Text, "it was not edited") {
		t.Fatalf("undeclared=%#v", result)
	}
	if !strings.Contains(string(result.Details), `"code":"CODEC"`) {
		t.Fatalf("details=%s", result.Details)
	}
	binary := writeFixture(t, "binary.dat", []byte{'a', 0, 'b'})
	for _, capability := range []string{"fs.edit", "fs.edit.preview"} {
		result = invokeCoding(t, Provider{}, capability, editArgs(binary, replace("a", "c")), nil)
		if !result.Error || !strings.Contains(result.Contents[0].Text, "is a binary file (it contains NUL bytes); edit only rewrites text files.") {
			t.Fatalf("%s binary=%#v", capability, result)
		}
	}
	conflict := writeFixture(t, "conflict.html", []byte(`<meta charset="gb2312">中文`))
	result = invokeCoding(t, Provider{}, "fs.edit", editArgs(conflict, replace("中文", "英文")), nil)
	if !result.Error || !strings.Contains(result.Contents[0].Text, "HTML meta charset") {
		t.Fatalf("conflict=%#v", result)
	}
}

// COD-T-273: edit keeps a BOM-marked file whose content declares its own UTF-16 family, and
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
