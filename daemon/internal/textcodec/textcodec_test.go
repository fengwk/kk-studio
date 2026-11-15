package textcodec

import (
	"bytes"
	"errors"
	"testing"
)

// bomEncodings lists the names the codec handles with a byte order mark or declaration.
var bomEncodings = []string{"UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE"}

// encodeFixture encodes text with enc and fails the test when the codec refuses.
func encodeFixture(t *testing.T, text string, enc Encoding) []byte {
	t.Helper()
	data, err := Encode(text, enc)
	if err != nil {
		t.Fatalf("Encode(%q, %s): %v", text, enc.Name, err)
	}
	return data
}

// Every byte order mark is decoded, and a UTF-32LE mark wins over UTF-16LE.
func TestDecodeReadsEveryByteOrderMark(t *testing.T) {
	text := "a中😀\n"
	for _, name := range bomEncodings {
		enc := Encoding{Name: name, BOM: true, Source: SourceBOM}
		data := encodeFixture(t, text, enc)
		decoded, got, replaced, err := Decode("/repo/bom.txt", data)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if got != enc || replaced != 0 {
			t.Fatalf("%s: encoding=%#v replaced=%d", name, got, replaced)
		}
		if string(decoded) != text {
			t.Fatalf("%s: text=%q", name, decoded)
		}
	}
	decoded, enc, replaced, err := Decode("/repo/order.txt", []byte{0xff, 0xfe, 0x00, 0x00, 'a', 0, 0, 0})
	if err != nil || enc.Name != "UTF-32LE" || string(decoded) != "a" || replaced != 0 {
		t.Fatalf("UTF-32LE priority: text=%q encoding=%#v replaced=%d err=%v", decoded, enc, replaced, err)
	}
}

// Ill-formed UTF-8 decodes to one U+FFFD per maximal subpart, exactly as browsers, Node and
// rg --encoding utf-8 do, and the count reports those replacements.
func TestDecodeReplacesIllFormedUTF8ByMaximalSubpart(t *testing.T) {
	for _, test := range []struct {
		name  string
		data  []byte
		want  string
		count int
	}{
		{name: "incomplete-but-valid-prefix", data: []byte{0xe4, 0xb8, 'A'}, want: "\uFFFDA", count: 1},
		{name: "surrogate", data: []byte{0xed, 0xa0, 0x80}, want: "\uFFFD\uFFFD\uFFFD", count: 3},
		{name: "overlong-lead", data: []byte{0xc0, 0xaf}, want: "\uFFFD\uFFFD", count: 2},
		{name: "overlong-three-byte", data: []byte{0xe0, 0x80, 0x80}, want: "\uFFFD\uFFFD\uFFFD", count: 3},
		{name: "above-unicode", data: []byte{0xf4, 0x90, 0x80, 0x80}, want: "\uFFFD\uFFFD\uFFFD\uFFFD", count: 4},
		{name: "overlong-four-byte", data: []byte{0xf0, 0x80, 0x80, 0x80}, want: "\uFFFD\uFFFD\uFFFD\uFFFD", count: 4},
		{name: "truncated-three-byte", data: []byte{0xe4, 0xb8}, want: "\uFFFD", count: 1},
		{name: "truncated-four-byte", data: []byte{0xf0, 0x9f}, want: "\uFFFD", count: 1},
		{name: "stray-continuation", data: []byte{0x80, 'a'}, want: "\uFFFDa", count: 1},
		{name: "impossible-byte", data: []byte{0xfe}, want: "\uFFFD", count: 1},
		{name: "literal-replacement-character", data: []byte("a\uFFFDb"), want: "a\uFFFDb", count: 0},
	} {
		t.Run(test.name, func(t *testing.T) {
			decoded, enc, replaced, err := Decode("/repo/broken.txt", test.data)
			if err != nil {
				t.Fatalf("Decode: %v", err)
			}
			if enc.Name != "UTF-8" || enc.Source != SourceUTF8 {
				t.Fatalf("encoding=%#v", enc)
			}
			if string(decoded) != test.want || replaced != test.count {
				t.Fatalf("text=%q replaced=%d want %q %d", decoded, replaced, test.want, test.count)
			}
		})
	}
}

// A declared US-ASCII file replaces every byte above ASCII, and the count is the number of bytes.
func TestDecodeReplacesNonASCIIBytesInDeclaredASCIIText(t *testing.T) {
	declaration := `<?xml encoding="us-ascii"?>`
	decoded, enc, replaced, err := Decode("/repo/doc.xml", append([]byte(declaration), 0x80, 'a', 0xff))
	if err != nil {
		t.Fatalf("Decode: %v", err)
	}
	if enc.Name != "US-ASCII" || string(decoded) != declaration+"\uFFFDa\uFFFD" || replaced != 2 {
		t.Fatalf("encoding=%#v text=%q replaced=%d", enc, decoded, replaced)
	}
}

// BOM-marked UTF-16 and UTF-32 replace unpaired surrogates, out-of-range values and a truncated
// final unit, and an unpaired high surrogate does not swallow the following unit.
func TestDecodeReplacesIllFormedWideUnits(t *testing.T) {
	for _, test := range []struct {
		name  string
		data  []byte
		want  string
		count int
	}{
		{name: "utf16le-low-surrogate", data: []byte{0xff, 0xfe, 0x00, 0xdc}, want: "\uFFFD", count: 1},
		{name: "utf16be-low-surrogate", data: []byte{0xfe, 0xff, 0xdc, 0x00}, want: "\uFFFD", count: 1},
		{name: "utf16le-unpaired-high-at-eof", data: []byte{0xff, 0xfe, 0x00, 0xd8}, want: "\uFFFD", count: 1},
		{name: "utf16le-unpaired-high-before-text", data: []byte{0xff, 0xfe, 0x00, 0xd8, 'a', 0}, want: "\uFFFDa", count: 1},
		{name: "utf16be-unpaired-high-before-text", data: []byte{0xfe, 0xff, 0xd8, 0x00, 0x00, 'a'}, want: "\uFFFDa", count: 1},
		{name: "utf16le-high-then-truncated-unit", data: []byte{0xff, 0xfe, 0x00, 0xd8, 'a'}, want: "\uFFFD\uFFFD", count: 2},
		{name: "utf16le-truncated-unit", data: []byte{0xff, 0xfe, 'a'}, want: "\uFFFD", count: 1},
		{name: "utf16be-truncated-unit", data: []byte{0xfe, 0xff, 0x00}, want: "\uFFFD", count: 1},
		{name: "utf16le-pair", data: []byte{0xff, 0xfe, 0x3d, 0xd8, 0x00, 0xde}, want: "😀", count: 0},
		{name: "utf32be-out-of-range", data: []byte{0x00, 0x00, 0xfe, 0xff, 0x00, 0x11, 0x00, 0x00}, want: "\uFFFD", count: 1},
		{name: "utf32be-surrogate", data: []byte{0x00, 0x00, 0xfe, 0xff, 0x00, 0x00, 0xd8, 0x00}, want: "\uFFFD", count: 1},
		{name: "utf32le-truncated-unit", data: []byte{0xff, 0xfe, 0x00, 0x00, 'a', 0, 0}, want: "\uFFFD", count: 1},
		{name: "utf32be-valid", data: []byte{0x00, 0x00, 0xfe, 0xff, 0x00, 0x01, 0xf6, 0x00}, want: "😀", count: 0},
	} {
		t.Run(test.name, func(t *testing.T) {
			decoded, _, replaced, err := Decode("/repo/wide.txt", test.data)
			if err != nil {
				t.Fatalf("Decode: %v", err)
			}
			if string(decoded) != test.want || replaced != test.count {
				t.Fatalf("text=%q replaced=%d want %q %d", decoded, replaced, test.want, test.count)
			}
		})
	}
}

// Only NUL is binary, and the UTF-16/UTF-32 window counts decoded characters.
func TestDecodeBinaryRule(t *testing.T) {
	if _, _, _, err := Decode("/repo/controls.txt", []byte("a\tb\r\n\f\x1b\x01\x1f")); err != nil {
		t.Fatalf("control characters are text: %v", err)
	}
	inside := bytes.Repeat([]byte{'a'}, binaryProbeBytes)
	inside[binaryProbeBytes-1] = 0
	if _, _, _, err := Decode("/repo/inside.txt", inside); !errors.Is(err, ErrBinary) {
		t.Fatalf("NUL inside the window: err=%v", err)
	}
	outside := bytes.Repeat([]byte{'a'}, binaryProbeBytes+1)
	outside[binaryProbeBytes] = 0
	if _, _, _, err := Decode("/repo/outside.txt", outside); err != nil {
		t.Fatalf("NUL outside the window: %v", err)
	}
	wide := append([]byte{0xff, 0xfe}, bytes.Repeat([]byte{'a', 0}, binaryProbeBytes/2)...)
	if _, _, _, err := Decode("/repo/wide.txt", wide); err != nil {
		t.Fatalf("UTF-16 text with NUL bytes is not binary: %v", err)
	}
	if _, _, _, err := Decode("/repo/wide-nul.txt", append(wide, 0, 0)); err != nil {
		t.Fatalf("NUL character outside the window: %v", err)
	}
	if _, _, _, err := Decode("/repo/wide-zero.txt", []byte{0xff, 0xfe, 'a', 0, 0, 0}); !errors.Is(err, ErrBinary) {
		t.Fatalf("NUL character inside the window: err=%v", err)
	}
}

// UTF-8 without a declaration is returned unchanged and aliases the input.
func TestDecodeAliasesUTF8Input(t *testing.T) {
	data := []byte("plain UTF-8\n")
	decoded, enc, replaced, err := Decode("/repo/note.txt", data)
	if err != nil || enc != (Encoding{Name: "UTF-8", Source: SourceUTF8}) || replaced != 0 {
		t.Fatalf("encoding=%#v replaced=%d err=%v", enc, replaced, err)
	}
	if &decoded[0] != &data[0] {
		t.Fatal("decoded UTF-8 text was copied")
	}
}

// Undeclared bytes that are not UTF-8 are shown, not rejected: the file reads with U+FFFD.
func TestDecodeReplacesUndeclaredNonUTF8Bytes(t *testing.T) {
	// "中文" in GBK holds bytes that are not valid UTF-8.
	decoded, enc, replaced, err := Decode("/repo/gbk.txt", []byte("\xd6\xd0\xce\xc4\n"))
	if err != nil {
		t.Fatalf("Decode: %v", err)
	}
	if enc.Name != "UTF-8" || enc.Source != SourceUTF8 || string(decoded) != "\uFFFD\uFFFD\uFFFD\uFFFD\n" || replaced != 4 {
		t.Fatalf("encoding=%#v text=%q replaced=%d", enc, decoded, replaced)
	}
}

// An empty document and a second byte order mark are ordinary UTF-8 text.
func TestDecodeEmptyAndDoubleBOM(t *testing.T) {
	decoded, enc, replaced, err := Decode("/repo/empty.txt", nil)
	if err != nil || len(decoded) != 0 || enc.Name != "UTF-8" || replaced != 0 {
		t.Fatalf("empty: text=%q encoding=%#v replaced=%d err=%v", decoded, enc, replaced, err)
	}
	decoded, enc, replaced, err = Decode("/repo/double.txt", []byte{0xef, 0xbb, 0xbf, 0xef, 0xbb, 0xbf, 'a'})
	if err != nil || string(decoded) != "\ufeffa" || enc.Name != "UTF-8" || !enc.BOM || replaced != 0 {
		t.Fatalf("double BOM: text=%q encoding=%#v replaced=%d err=%v", decoded, enc, replaced, err)
	}
}

// A declared legacy encoding decodes its replacement output instead of refusing bytes it cannot
// reproduce, and a byte sequence that is also valid UTF-8 stays that encoding's text.
func TestDecodeReplacesLegacyDecoderOutput(t *testing.T) {
	declaration := []byte(`<?xml encoding="gbk"?>`)
	decoded, enc, replaced, err := Decode("/repo/doc.xml", append(append([]byte(nil), declaration...), 0x81, 0x20))
	if err != nil {
		t.Fatalf("Decode: %v", err)
	}
	if enc.Name != "GBK" || enc.Source != SourceDeclaration || string(decoded) != string(declaration)+"\uFFFD " || replaced != 1 {
		t.Fatalf("encoding=%#v text=%q replaced=%d", enc, decoded, replaced)
	}
	// 茅 is C3 A9 in GBK, which is also valid UTF-8 (é); the declaration stays authoritative.
	mao := []byte{0xc3, 0xa9}
	decoded, enc, replaced, err = Decode("/repo/doc.xml", append([]byte(`<?xml version="1.0" encoding="GBK"?>`), mao...))
	if err != nil {
		t.Fatalf("GBK 茅: %v", err)
	}
	if enc.Name != "GBK" || string(decoded[len(decoded)-len("茅"):]) != "茅" || replaced != 0 {
		t.Fatalf("GBK 茅: encoding=%#v text=%q replaced=%d", enc, decoded, replaced)
	}
}
