package textcodec

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// encodeAs encodes text with a declared encoding so a fixture holds real legacy bytes.
func encodeAs(t *testing.T, name, text string) []byte {
	t.Helper()
	data, err := Encode(text, Encoding{Name: name})
	if err != nil {
		t.Fatalf("Encode(%q, %s): %v", text, name, err)
	}
	return data
}

// COD-T-232, COD-T-233: every standard declaration position names the encoding of its file.
func TestDecodeReadsStandardDeclarations(t *testing.T) {
	for _, test := range []struct {
		name        string
		path        string
		data        []byte
		wantText    string
		wantName    string
		declaration string
	}{
		{
			name:        "xml",
			path:        "/repo/doc.xml",
			data:        append([]byte(`<?xml version="1.0" encoding="GBK"?>`), encodeAs(t, "GBK", "中文")...),
			wantText:    `<?xml version="1.0" encoding="GBK"?>` + "中文",
			wantName:    "GBK",
			declaration: declarationXML,
		},
		{
			name:        "css",
			path:        "/repo/site.css",
			data:        encodeAs(t, "windows-1252", `@charset "windows-1252";`+"\nbody { content: \"café\"; }\n"),
			wantText:    `@charset "windows-1252";` + "\nbody { content: \"café\"; }\n",
			wantName:    "windows-1252",
			declaration: declarationCSS,
		},
		{
			name:        "html-meta",
			path:        "/repo/page.html",
			data:        encodeAs(t, "Big5", `<html><head><meta charset="big5"><title>中文</title></head></html>`),
			wantText:    `<html><head><meta charset="big5"><title>中文</title></head></html>`,
			wantName:    "Big5",
			declaration: declarationHTML,
		},
		{
			name:        "html-http-equiv",
			path:        "/repo/page.htm",
			data:        encodeAs(t, "Big5", `<html><head><meta http-equiv="Content-Type" content="text/html; charset=big5"></head></html>`),
			wantText:    `<html><head><meta http-equiv="Content-Type" content="text/html; charset=big5"></head></html>`,
			wantName:    "Big5",
			declaration: declarationHTML,
		},
		{
			name:        "python-line-1",
			path:        "/repo/script.py",
			data:        encodeAs(t, "GBK", "# coding: gbk\nprint(\"中文\")\n"),
			wantText:    "# coding: gbk\nprint(\"中文\")\n",
			wantName:    "GBK",
			declaration: "coding comment on line 1",
		},
		{
			name:        "python-line-2",
			path:        "/repo/script.pyw",
			data:        encodeAs(t, "GBK", "# Copyright\n# -*- fileencoding: gbk -*-\n"),
			wantText:    "# Copyright\n# -*- fileencoding: gbk -*-\n",
			wantName:    "GBK",
			declaration: "coding comment on line 2",
		},
		{
			name:        "python-utf8-unix",
			path:        "/repo/tool.pyi",
			data:        []byte("# coding: utf-8-unix\n"),
			wantText:    "# coding: utf-8-unix\n",
			wantName:    "UTF-8",
			declaration: "coding comment on line 1",
		},
		{
			name:        "ruby-shebang",
			path:        "/repo/tool.rb",
			data:        encodeAs(t, "Shift_JIS", "#!/usr/bin/env ruby\n# encoding: shift_jis\nputs \"日本語\"\n"),
			wantText:    "#!/usr/bin/env ruby\n# encoding: shift_jis\nputs \"日本語\"\n",
			wantName:    "Shift_JIS",
			declaration: "coding comment on line 2",
		},
		{
			name:        "jsp-page-encoding",
			path:        "/repo/view.jsp",
			data:        encodeAs(t, "EUC-JP", `<jsp:root><%@ page pageEncoding="euc-jp" %>日本語</jsp:root>`),
			wantText:    `<jsp:root><%@ page pageEncoding="euc-jp" %>日本語</jsp:root>`,
			wantName:    "EUC-JP",
			declaration: declarationJSP,
		},
		{
			name:        "jsp-content-type",
			path:        "/repo/tag.jspf",
			data:        encodeAs(t, "EUC-JP", `<jsp:root><%@ page contentType="text/html;charset=euc-jp" %>日本語</jsp:root>`),
			wantText:    `<jsp:root><%@ page contentType="text/html;charset=euc-jp" %>日本語</jsp:root>`,
			wantName:    "EUC-JP",
			declaration: declarationJSP,
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			decoded, enc, err := Decode(test.path, test.data)
			if err != nil {
				t.Fatalf("Decode: %v", err)
			}
			if string(decoded) != test.wantText {
				t.Fatalf("text=%q want=%q", decoded, test.wantText)
			}
			if enc.Name != test.wantName || enc.Source != SourceDeclaration || enc.Declaration != test.declaration || enc.BOM {
				t.Fatalf("encoding=%#v", enc)
			}
		})
	}
}

// COD-T-233: declaration-like text outside the standard positions is not a declaration.
func TestDecodeIgnoresDeclarationLookalikes(t *testing.T) {
	gbk := encodeAs(t, "GBK", "中文")
	for _, test := range []struct {
		name string
		path string
		data []byte
	}{
		{name: "xml-not-at-byte-0", path: "/repo/doc.xml", data: append([]byte(` <?xml version="1.0" encoding="GBK"?>`), gbk...)},
		{name: "html-meta-in-body", path: "/repo/page.html", data: encodeAs(t, "Big5", `<html><body><meta charset="big5">中文</body></html>`)},
		{name: "html-meta-after-head", path: "/repo/page.html", data: encodeAs(t, "Big5", `<html><head></head><meta charset="big5"><body>中文</body></html>`)},
		{name: "html-meta-in-comment", path: "/repo/page.html", data: encodeAs(t, "Big5", `<html><head><!-- <meta charset="big5"> --></head><body>中文</body></html>`)},
		{name: "html-meta-in-script", path: "/repo/page.html", data: encodeAs(t, "Big5", `<html><head><script>var s = '<meta charset="big5">';</script></head><body>中文</body></html>`)},
		{name: "html-meta-after-window", path: "/repo/page.html", data: encodeAs(t, "Big5", strings.Repeat("<!-- x -->", 120)+`<meta charset="big5">中文`)},
		{name: "html-meta-in-text-file", path: "/repo/page.txt", data: encodeAs(t, "Big5", `<html><head><meta charset="big5"></head>中文</html>`)},
		{name: "python-line-2-after-code", path: "/repo/script.py", data: encodeAs(t, "GBK", "x = \"中文\"\n# coding: gbk\n")},
		{name: "python-line-3", path: "/repo/script.py", data: encodeAs(t, "GBK", "# one\n# two\n# coding: gbk\n中文\n")},
		{name: "ruby-line-2-without-shebang", path: "/repo/tool.rb", data: encodeAs(t, "Shift_JIS", "# comment\n# encoding: shift_jis\n日本語\n")},
		{name: "jsp-after-window", path: "/repo/view.jsp", data: encodeAs(t, "EUC-JP", strings.Repeat("<!-- x -->", 120)+`<%@ page pageEncoding="euc-jp" %>日本語`)},
	} {
		t.Run(test.name, func(t *testing.T) {
			var unknown *UnknownEncodingError
			if _, _, err := Decode(test.path, test.data); !errors.As(err, &unknown) {
				t.Fatalf("err=%v want an unknown encoding", err)
			}
		})
	}
}

// COD-T-234: a coding comment in a file type that does not carry one stays ordinary text.
func TestDecodeIgnoresCodingCommentsOutsideTheirFileTypes(t *testing.T) {
	for _, name := range []string{"notes.md", "recipe.txt", "run.sh"} {
		data, err := os.ReadFile(filepath.Join("testdata", name))
		if err != nil {
			t.Fatal(err)
		}
		_, enc, err := Decode("/repo/"+name, data)
		if err != nil || enc.Source != SourceUTF8 {
			t.Fatalf("%s encoding=%#v err=%v", name, enc, err)
		}
	}
}

// COD-T-235: a byte order mark wins over any declaration in the file.
func TestDecodePrefersBOMOverDeclaration(t *testing.T) {
	data := append([]byte{0xef, 0xbb, 0xbf}, []byte(`<html><head><meta charset="gb2312"></head><body>中文</body></html>`)...)
	decoded, enc, err := Decode("/repo/page.html", data)
	if err != nil {
		t.Fatal(err)
	}
	if enc.Name != "UTF-8" || enc.Source != SourceBOM || !enc.BOM {
		t.Fatalf("encoding=%#v", enc)
	}
	if !strings.Contains(string(decoded), "中文") {
		t.Fatalf("text=%q", decoded)
	}
}

// COD-T-236: labels resolve through the index of their format.
func TestDecodeLabelResolution(t *testing.T) {
	for _, test := range []struct {
		name     string
		path     string
		data     []byte
		wantName string
		wantText string
	}{
		{name: "gb2312-is-gbk", path: "/repo/page.html", data: encodeAs(t, "GBK", `<meta charset="gb2312">中文`), wantName: "GBK", wantText: `<meta charset="gb2312">中文`},
		{name: "utf8-alias", path: "/repo/doc.xml", data: []byte(`<?xml encoding="utf8"?>` + "中文"), wantName: "UTF-8", wantText: `<?xml encoding="utf8"?>` + "中文"},
		{name: "ascii-strict", path: "/repo/doc.xml", data: []byte(`<?xml encoding="us-ascii"?>plain`), wantName: "US-ASCII", wantText: `<?xml encoding="us-ascii"?>plain`},
		{name: "latin-1-in-python", path: "/repo/script.py", data: encodeAs(t, "ISO-8859-1", "# coding: latin-1\nname = \"café\"\n"), wantName: "ISO-8859-1", wantText: "# coding: latin-1\nname = \"café\"\n"},
		{name: "html-iso-8859-1-is-windows-1252", path: "/repo/page.html", data: encodeAs(t, "windows-1252", `<meta charset="iso-8859-1">€`), wantName: "windows-1252", wantText: `<meta charset="iso-8859-1">€`},
	} {
		t.Run(test.name, func(t *testing.T) {
			decoded, enc, err := Decode(test.path, test.data)
			if err != nil {
				t.Fatalf("Decode: %v", err)
			}
			if enc.Name != test.wantName || string(decoded) != test.wantText {
				t.Fatalf("encoding=%#v text=%q", enc, decoded)
			}
		})
	}
}

// COD-T-237: unknown labels, UTF-16/32 labels without a byte order mark and conflicting
// declarations each report their own typed error.
func TestDecodeRejectsUnusableDeclarations(t *testing.T) {
	var unsupported *UnsupportedLabelError
	if _, _, err := Decode("/repo/doc.xml", []byte(`<?xml encoding="klingon"?>x`)); !errors.As(err, &unsupported) || unsupported.Label != "klingon" || unsupported.Declaration != declarationXML {
		t.Fatalf("unsupported label err=%v", err)
	}
	var conflict *DeclarationConflictError
	if _, _, err := Decode("/repo/page.html", []byte(`<meta charset="utf-16le">x`)); !errors.As(err, &conflict) || conflict.Declared.Name != "UTF-16LE" {
		t.Fatalf("UTF-16 label err=%v", err)
	}
	conflict = nil
	if _, _, err := Decode("/repo/page.html", []byte(`<meta charset="gb2312">中文`)); !errors.As(err, &conflict) || conflict.Declared.Name != "GBK" || conflict.Declared.Declaration != declarationHTML {
		t.Fatalf("conflict err=%v", err)
	}
	var undecodable *UndecodableError
	if _, _, err := Decode("/repo/doc.xml", []byte(`<?xml encoding="utf-8"?>`+"\xff")); !errors.As(err, &undecodable) || undecodable.Offset != len(`<?xml encoding="utf-8"?>`) {
		t.Fatalf("invalid UTF-8 err=%v", err)
	}
	undecodable = nil
	if _, _, err := Decode("/repo/doc.xml", []byte(`<?xml encoding="ascii"?>`+"\x80")); !errors.As(err, &undecodable) || undecodable.Offset != len(`<?xml encoding="ascii"?>`) {
		t.Fatalf("non-ASCII under an ASCII declaration err=%v", err)
	}
}

// COD-T-238: a legacy declaration over ASCII-only bytes stays lossless.
func TestDecodeAcceptsASCIIOnlyLegacyFile(t *testing.T) {
	decoded, enc, err := Decode("/repo/page.html", []byte(`<meta charset="gbk">plain`))
	if err != nil || enc.Name != "GBK" || string(decoded) != `<meta charset="gbk">plain` {
		t.Fatalf("text=%q encoding=%#v err=%v", decoded, enc, err)
	}
}

// COD-T-239: decoding a declared encoding must reproduce the original bytes.
func TestDecodeRejectsLossyLegacyBytes(t *testing.T) {
	declaration := []byte(`<?xml encoding="gbk"?>`)
	data := append(append([]byte(nil), declaration...), 0x81, 0x20)
	var undecodable *UndecodableError
	if _, _, err := Decode("/repo/doc.xml", data); !errors.As(err, &undecodable) || undecodable.Offset != len(declaration) {
		t.Fatalf("err=%v want offset %d", err, len(declaration))
	}
}

// COD-T-240: a whole-file decode of a declared legacy encoding is bounded.
func TestDecodeRejectsOversizedDeclaredFile(t *testing.T) {
	data := append([]byte(`<?xml encoding="gbk"?>`), bytes.Repeat([]byte{'a'}, maxDeclaredSize)...)
	var tooLarge *TooLargeError
	if _, _, err := Decode("/repo/doc.xml", data); !errors.As(err, &tooLarge) || tooLarge.Encoding.Name != "GBK" || tooLarge.Size != int64(len(data)) {
		t.Fatalf("err=%v", err)
	}
}

// COD-T-241: declared legacy files decode and re-encode to exactly the original bytes.
func TestLegacyRoundTripIsExact(t *testing.T) {
	for _, test := range []struct {
		name string
		text string
	}{
		{name: "GBK", text: "plain 中文 €\nsecond line\n"},
		{name: "Big5", text: "plain 中文\nsecond line\n"},
		{name: "Shift_JIS", text: "plain 日本語\nsecond line\n"},
		{name: "windows-1252", text: "plain café €\nsecond line\n"},
		{name: "ISO-8859-1", text: "plain café\nsecond line\n"},
	} {
		name, text := test.name, test.text
		data := encodeAs(t, name, text)
		decoded, enc, err := Decode("/repo/data.xml", append([]byte(`<?xml encoding="`+strings.ToLower(name)+`"?>`), data...))
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		reencoded, err := Encode(string(decoded), enc)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		want := append([]byte(`<?xml encoding="`+strings.ToLower(name)+`"?>`), data...)
		if !bytes.Equal(reencoded, want) {
			t.Fatalf("%s: bytes=%x want=%x", name, reencoded, want)
		}
	}
}

// COD-T-242: Declared reports the declaration of new content for write and edit.
func TestDeclaredReportsContentDeclaration(t *testing.T) {
	for _, test := range []struct {
		name     string
		path     string
		text     string
		found    bool
		wantName string
	}{
		{name: "html", path: "/repo/page.html", text: `<html><head><meta charset="gbk"></head></html>`, found: true, wantName: "GBK"},
		{name: "xml", path: "/repo/doc.xml", text: `<?xml version="1.0" encoding="iso-8859-1"?>`, found: true, wantName: "ISO-8859-1"},
		{name: "python", path: "/repo/script.py", text: "# coding: gbk\n", found: true, wantName: "GBK"},
		{name: "none", path: "/repo/note.txt", text: "plain text\n"},
		{name: "lookalike", path: "/repo/page.html", text: `<body><meta charset="gbk"></body>`},
	} {
		t.Run(test.name, func(t *testing.T) {
			enc, found, err := Declared(test.path, []byte(test.text))
			if err != nil {
				t.Fatalf("Declared: %v", err)
			}
			if found != test.found || enc.Name != test.wantName || found && enc.Source != SourceDeclaration {
				t.Fatalf("found=%v encoding=%#v", found, enc)
			}
		})
	}
	var unsupported *UnsupportedLabelError
	if _, _, err := Declared("/repo/page.html", []byte(`<meta charset="klingon">`)); !errors.As(err, &unsupported) {
		t.Fatalf("err=%v", err)
	}
	var conflict *DeclarationConflictError
	if _, _, err := Declared("/repo/page.html", []byte(`<meta charset="utf-16be">`)); !errors.As(err, &conflict) {
		t.Fatalf("err=%v", err)
	}
}
