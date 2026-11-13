package textcodec

import (
	"bytes"
	"errors"
	"io"
	"strings"
	"testing"
)

// readRunes reads a Reader to its end and reports the trailing error.
func readRunes(t *testing.T, reader *Reader) (string, error) {
	t.Helper()
	var out strings.Builder
	for {
		value, size, err := reader.ReadRune()
		if err != nil {
			return out.String(), err
		}
		if size != len(string(value)) {
			t.Fatalf("size=%d for %q", size, value)
		}
		out.WriteRune(value)
	}
}

// readAllRunes drains a Reader that must end cleanly.
func readAllRunes(t *testing.T, reader *Reader) string {
	t.Helper()
	text, err := readRunes(t, reader)
	if !errors.Is(err, io.EOF) {
		t.Fatalf("ReadRune: %v", err)
	}
	return text
}

// COD-T-246: the streaming reader decodes UTF-8 and every BOM encoding to the same text.
func TestReaderDecodesStreams(t *testing.T) {
	text := "first\n中😀\nlast"
	for _, name := range bomEncodings {
		enc := Encoding{Name: name, BOM: true, Source: SourceBOM}
		reader, err := NewReader("/repo/doc.txt", bytes.NewReader(encodeFixture(t, text, enc)))
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if reader.Encoding() != enc {
			t.Fatalf("%s: encoding=%#v", name, reader.Encoding())
		}
		if got := readAllRunes(t, reader); got != text {
			t.Fatalf("%s: text=%q", name, got)
		}
	}
	plain, err := NewReader("/repo/doc.txt", strings.NewReader(text))
	if err != nil || plain.Encoding().Source != SourceUTF8 {
		t.Fatalf("encoding=%#v err=%v", plain.Encoding(), err)
	}
	if got := readAllRunes(t, plain); got != text {
		t.Fatalf("text=%q", got)
	}
}

// COD-T-228: the streaming reader applies the binary rule before serving any text.
func TestReaderRejectsBinaryBeforeReading(t *testing.T) {
	stream := append(bytes.Repeat([]byte{'a'}, binaryProbeBytes-10), 0, 'b')
	if _, err := NewReader("/repo/doc.txt", bytes.NewReader(stream)); !errors.Is(err, ErrBinary) {
		t.Fatalf("err=%v", err)
	}
	late := append(bytes.Repeat([]byte{'a'}, binaryProbeBytes+10), 0, 'b')
	reader, err := NewReader("/repo/doc.txt", bytes.NewReader(late))
	if err != nil {
		t.Fatalf("NUL after the probe: %v", err)
	}
	if got := readAllRunes(t, reader); len(got) != binaryProbeBytes+12 {
		t.Fatalf("text length=%d", len(got))
	}
	if _, err = NewReader("/repo/doc.txt", bytes.NewReader([]byte{0xff, 0xfe, 'a', 0, 0, 0})); !errors.Is(err, ErrBinary) {
		t.Fatalf("UTF-16 NUL character: err=%v", err)
	}
}

// COD-T-247: a stream that stops being decodable reports a typed error.
func TestReaderReportsUndecodableStreams(t *testing.T) {
	var unknown *UnknownEncodingError
	reader, err := NewReader("/repo/doc.txt", bytes.NewReader([]byte("ok\n\xd6\xd0")))
	if err != nil {
		t.Fatal(err)
	}
	if got, readErr := readRunes(t, reader); got != "ok\n" || !errors.As(readErr, &unknown) {
		t.Fatalf("text=%q err=%v", got, readErr)
	}
	var undecodable *UndecodableError
	declared := []byte("# coding: utf-8\nok\n\xff")
	if reader, err = NewReader("/repo/doc.py", bytes.NewReader(declared)); err != nil {
		t.Fatal(err)
	}
	for {
		if _, _, err = reader.ReadRune(); err != nil {
			break
		}
	}
	if !errors.As(err, &undecodable) || undecodable.Offset != len(declared)-1 {
		t.Fatalf("err=%v", err)
	}
	ascii := []byte(`<?xml encoding="us-ascii"?>` + "\x80")
	if reader, err = NewReader("/repo/doc.xml", bytes.NewReader(ascii)); err != nil {
		t.Fatal(err)
	}
	if got, readErr := readRunes(t, reader); got != `<?xml encoding="us-ascii"?>` || !errors.As(readErr, &undecodable) {
		t.Fatalf("ASCII text=%q err=%v", got, readErr)
	}
	wide := []byte{0xff, 0xfe, 'a', 0, 0x00, 0xdc}
	if reader, err = NewReader("/repo/doc.txt", bytes.NewReader(wide)); err != nil {
		t.Fatal(err)
	}
	if got, readErr := readRunes(t, reader); got != "a" || !errors.As(readErr, &undecodable) || undecodable.Offset != 4 {
		t.Fatalf("UTF-16 text=%q err=%v", got, readErr)
	}
	odd := []byte{0xfe, 0xff, 0x00, 'a', 0x00}
	if reader, err = NewReader("/repo/doc.txt", bytes.NewReader(odd)); err != nil {
		t.Fatal(err)
	}
	if got, readErr := readRunes(t, reader); got != "a" || !errors.As(readErr, &undecodable) || undecodable.Offset != 4 {
		t.Fatalf("UTF-16BE text=%q err=%v", got, readErr)
	}
	if len(readAllRunes(t, mustReader(t, "/repo/doc.txt", []byte{0xff, 0xfe}))) != 0 {
		t.Fatal("an empty UTF-16 document is not empty")
	}
}

func mustReader(t *testing.T, path string, data []byte) *Reader {
	t.Helper()
	reader, err := NewReader(path, bytes.NewReader(data))
	if err != nil {
		t.Fatalf("NewReader: %v", err)
	}
	return reader
}

// COD-T-248: a declared legacy encoding is decoded as a whole file before the first rune.
func TestReaderDecodesDeclaredLegacyFile(t *testing.T) {
	data := encodeAs(t, "GBK", "# coding: gbk\nprint(\"中文\")\n")
	reader, err := NewReader("/repo/script.py", bytes.NewReader(data))
	if err != nil {
		t.Fatalf("NewReader: %v", err)
	}
	if reader.Encoding().Name != "GBK" || reader.Encoding().Declaration != "coding comment on line 1" {
		t.Fatalf("encoding=%#v", reader.Encoding())
	}
	if got := readAllRunes(t, reader); got != "# coding: gbk\nprint(\"中文\")\n" {
		t.Fatalf("text=%q", got)
	}
	var conflict *DeclarationConflictError
	if _, err = NewReader("/repo/page.html", strings.NewReader(`<meta charset="gb2312">中文`)); !errors.As(err, &conflict) {
		t.Fatalf("conflict err=%v", err)
	}
	var undecodable *UndecodableError
	if _, err = NewReader("/repo/doc.xml", bytes.NewReader(append([]byte(`<?xml encoding="gbk"?>`), 0x81, 0x20))); !errors.As(err, &undecodable) {
		t.Fatalf("lossy err=%v", err)
	}
	var unsupported *UnsupportedLabelError
	if _, err = NewReader("/repo/doc.xml", strings.NewReader(`<?xml encoding="klingon"?>x`)); !errors.As(err, &unsupported) {
		t.Fatalf("label err=%v", err)
	}
}

// COD-T-249: a reader that fails underneath the codec passes its own error through.
func TestReaderPassesThroughReadErrors(t *testing.T) {
	reader, err := NewReader("/repo/doc.txt", io.MultiReader(strings.NewReader("a"), errorReader{}))
	if err != nil {
		t.Fatalf("NewReader: %v", err)
	}
	var readError readFailure
	for {
		if _, _, err = reader.ReadRune(); err != nil {
			break
		}
	}
	if !errors.As(err, &readError) {
		t.Fatalf("err=%v", err)
	}
}

type readFailure struct{}

func (readFailure) Error() string { return "read failed" }

type errorReader struct{}

func (errorReader) Read([]byte) (int, error) { return 0, readFailure{} }
