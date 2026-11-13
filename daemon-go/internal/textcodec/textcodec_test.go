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

// COD-T-220…224: every byte order mark is decoded, and a UTF-32LE mark wins over UTF-16LE.
func TestDecodeReadsEveryByteOrderMark(t *testing.T) {
	text := "a中😀\n"
	for _, name := range bomEncodings {
		enc := Encoding{Name: name, BOM: true, Source: SourceBOM}
		data := encodeFixture(t, text, enc)
		decoded, got, err := Decode("/repo/bom.txt", data)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if got != enc {
			t.Fatalf("%s: encoding=%#v", name, got)
		}
		if string(decoded) != text {
			t.Fatalf("%s: text=%q", name, decoded)
		}
	}
	decoded, enc, err := Decode("/repo/order.txt", []byte{0xff, 0xfe, 0x00, 0x00, 'a', 0, 0, 0})
	if err != nil || enc.Name != "UTF-32LE" || string(decoded) != "a" {
		t.Fatalf("UTF-32LE priority: text=%q encoding=%#v err=%v", decoded, enc, err)
	}
}

// COD-T-220: a byte order mark that is not followed by a valid sequence is undecodable.
func TestDecodeRejectsUndecodableBytesAfterBOM(t *testing.T) {
	for _, test := range []struct {
		name   string
		data   []byte
		offset int
	}{
		{name: "utf8-bom", data: []byte{0xef, 0xbb, 0xbf, 0xff}, offset: 3},
		{name: "utf16le-odd", data: []byte{0xff, 0xfe, 'a'}, offset: 2},
		{name: "utf16le-low", data: []byte{0xff, 0xfe, 0x00, 0xdc}, offset: 2},
		{name: "utf16be-low", data: []byte{0xfe, 0xff, 0xdc, 0x00}, offset: 2},
		{name: "utf16le-truncated-pair", data: []byte{0xff, 0xfe, 0x00, 0xd8}, offset: 2},
		{name: "utf16le-unpaired-high", data: []byte{0xff, 0xfe, 0x00, 0xd8, 'a', 0}, offset: 2},
		{name: "utf16be-unpaired-high", data: []byte{0xfe, 0xff, 0xd8, 0x00, 0x00, 'a'}, offset: 2},
		{name: "utf16be-odd", data: []byte{0xfe, 0xff, 0x00}, offset: 2},
		{name: "utf32be-range", data: []byte{0x00, 0x00, 0xfe, 0xff, 0x00, 0x11, 0x00, 0x00}, offset: 4},
		{name: "utf32be-surrogate", data: []byte{0x00, 0x00, 0xfe, 0xff, 0x00, 0x00, 0xd8, 0x00}, offset: 4},
		{name: "utf32le-odd", data: []byte{0xff, 0xfe, 0x00, 0x00, 'a', 0, 0}, offset: 4},
	} {
		t.Run(test.name, func(t *testing.T) {
			var undecodable *UndecodableError
			if _, _, err := Decode("/repo/broken.txt", test.data); !errors.As(err, &undecodable) || undecodable.Offset != test.offset {
				t.Fatalf("err=%v want offset %d", err, test.offset)
			}
		})
	}
}

// COD-T-228…231: only NUL is binary, and the UTF-16/UTF-32 window counts decoded characters.
func TestDecodeBinaryRule(t *testing.T) {
	if _, _, err := Decode("/repo/controls.txt", []byte("a\tb\r\n\f\x1b\x01\x1f")); err != nil {
		t.Fatalf("control characters are text: %v", err)
	}
	inside := bytes.Repeat([]byte{'a'}, binaryProbeBytes)
	inside[binaryProbeBytes-1] = 0
	if _, _, err := Decode("/repo/inside.txt", inside); !errors.Is(err, ErrBinary) {
		t.Fatalf("NUL inside the window: err=%v", err)
	}
	outside := bytes.Repeat([]byte{'a'}, binaryProbeBytes+1)
	outside[binaryProbeBytes] = 0
	if _, _, err := Decode("/repo/outside.txt", outside); err != nil {
		t.Fatalf("NUL outside the window: err=%v", err)
	}
	wide := append([]byte{0xff, 0xfe}, bytes.Repeat([]byte{'a', 0}, binaryProbeBytes/2)...)
	if _, _, err := Decode("/repo/wide.txt", wide); err != nil {
		t.Fatalf("UTF-16 text with NUL bytes is not binary: %v", err)
	}
	if _, _, err := Decode("/repo/wide-nul.txt", append(wide, 0, 0)); err != nil {
		t.Fatalf("NUL character outside the window: %v", err)
	}
	if _, _, err := Decode("/repo/wide-zero.txt", []byte{0xff, 0xfe, 'a', 0, 0, 0}); !errors.Is(err, ErrBinary) {
		t.Fatalf("NUL character inside the window: err=%v", err)
	}
}

// COD-T-227: UTF-8 without a declaration is returned unchanged and aliases the input.
func TestDecodeAliasesUTF8Input(t *testing.T) {
	data := []byte("plain UTF-8\n")
	decoded, enc, err := Decode("/repo/note.txt", data)
	if err != nil || enc != (Encoding{Name: "UTF-8", Source: SourceUTF8}) {
		t.Fatalf("encoding=%#v err=%v", enc, err)
	}
	if &decoded[0] != &data[0] {
		t.Fatal("decoded UTF-8 text was copied")
	}
}

// COD-T-232: undeclared bytes that are not UTF-8 are reported as an unknown encoding.
func TestDecodeReportsUnknownEncoding(t *testing.T) {
	var unknown *UnknownEncodingError
	// "中文" in GBK holds bytes that are not valid UTF-8.
	if _, _, err := Decode("/repo/gbk.txt", []byte("\xd6\xd0\xce\xc4\n")); !errors.As(err, &unknown) || unknown.Path != "/repo/gbk.txt" {
		t.Fatalf("err=%v", err)
	}
}

// COD-T-242: an empty document and a second byte order mark are ordinary text.
func TestDecodeEmptyAndDoubleBOM(t *testing.T) {
	decoded, enc, err := Decode("/repo/empty.txt", nil)
	if err != nil || len(decoded) != 0 || enc.Name != "UTF-8" {
		t.Fatalf("empty: text=%q encoding=%#v err=%v", decoded, enc, err)
	}
	decoded, enc, err = Decode("/repo/double.txt", []byte{0xef, 0xbb, 0xbf, 0xef, 0xbb, 0xbf, 'a'})
	if err != nil || string(decoded) != "\ufeffa" || enc.Name != "UTF-8" || !enc.BOM {
		t.Fatalf("double BOM: text=%q encoding=%#v err=%v", decoded, enc, err)
	}
}

// COD-T-239: the lossy offset points at the first byte the declared encoding cannot reproduce,
// even after valid mixed ASCII and two-byte text.
func TestDecodeReportsLossyOffsetAfterValidLegacyText(t *testing.T) {
	declaration := []byte(`<?xml encoding="gbk"?>`)
	valid := encodeAs(t, "GBK", "abc中文def")
	data := append(append(append([]byte(nil), declaration...), valid...), 0x81, 0x20)
	want := len(declaration) + len(valid)
	var undecodable *UndecodableError
	if _, _, err := Decode("/repo/doc.xml", data); !errors.As(err, &undecodable) || undecodable.Offset != want {
		t.Fatalf("err=%v want offset %d", err, want)
	}
}
