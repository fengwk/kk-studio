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

// The streaming reader decodes UTF-8 and every BOM encoding to the same text.
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

// The streaming reader applies the binary rule before serving any text.
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

// oneByteReader hands out a single byte per Read, so every sequence is split at its boundary.
type oneByteReader struct {
	data   []byte
	offset int
}

func (r *oneByteReader) Read(buffer []byte) (int, error) {
	if r.offset >= len(r.data) {
		return 0, io.EOF
	}
	if len(buffer) > 1 {
		buffer = buffer[:1]
	}
	buffer[0] = r.data[r.offset]
	r.offset++
	return 1, nil
}

// Streaming and whole-file decoding replace exactly the same sequences, whatever the read sizes.
func TestReaderReplacesExactlyLikeDecode(t *testing.T) {
	sequences := [][]byte{
		{0xe4, 0xb8, 'A'},
		{0xed, 0xa0, 0x80},
		{0xc0, 0xaf},
		{0xe0, 0x80, 0x80},
		{0xf4, 0x90, 0x80, 0x80},
		{0xf0, 0x9f},
		{0x80, 'a'},
		[]byte("ok\n\xd6\xd0"),
		{0xff, 0xfe, 0x00, 0xd8, 'a', 0},
		{0xff, 0xfe, 0x00, 0xd8, 'a'},
		{0xff, 0xfe, 0x00, 0xdc},
		{0xff, 0xfe, 'a'},
		{0xfe, 0xff, 0xd8, 0x00, 0x00, 'a'},
		{0x00, 0x00, 0xfe, 0xff, 0x00, 0x11, 0x00, 0x00},
		append([]byte(`<?xml encoding="ascii"?>`), 0x80, 0xff),
		append([]byte(`<?xml encoding="gbk"?>`), 0x81, 0x20),
	}
	for _, data := range sequences {
		want, _, _, err := Decode("/repo/doc.txt", data)
		if err != nil {
			t.Fatalf("%x: Decode: %v", data, err)
		}
		for name, source := range map[string]io.Reader{
			"buffered": bytes.NewReader(data),
			"one-byte": &oneByteReader{data: data},
		} {
			reader, err := NewReader("/repo/doc.txt", source)
			if err != nil {
				t.Fatalf("%x %s: NewReader: %v", data, name, err)
			}
			if got := readAllRunes(t, reader); got != string(want) {
				t.Fatalf("%x %s: text=%q want=%q", data, name, got, want)
			}
		}
	}
	if len(readAllRunes(t, mustReader(t, "/repo/doc.txt", []byte{0xff, 0xfe}))) != 0 {
		t.Fatal("an empty UTF-16 document is not empty")
	}
}

// Both entry points ignore a coding comment beyond the shared probe window.
func TestDecodeAndReaderShareDeclarationWindow(t *testing.T) {
	const path = "/repo/script.py"
	prefix := "#" + strings.Repeat("x", binaryProbeBytes) + "\n# coding: latin-1\n"
	data := []byte(prefix + "print('caf\xe9')\n")
	reader := mustReader(t, path, data)
	text, encoding, replacements, err := Decode(path, data)
	if err != nil {
		t.Fatal(err)
	}
	if want := reader.Encoding(); encoding != want || encoding.Name != "UTF-8" {
		t.Fatalf("Decode encoding=%+v, NewReader encoding=%+v; want both UTF-8", encoding, want)
	}
	if streamed := readAllRunes(t, reader); string(text) != streamed || streamed != prefix+"print('caf\uFFFD')\n" || replacements != 1 {
		t.Fatal("both decoders should replace the single invalid UTF-8 byte")
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

// A declared legacy encoding is decoded as a whole file before the first rune, and its undecodable
// bytes are served as the decoder's U+FFFD output.
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
	// Valid UTF-8 under a legacy declaration is that encoding's text, not a conflict.
	utf8UnderGBK, err := NewReader("/repo/page.html", strings.NewReader(`<meta charset="gb2312">中文`))
	if err != nil || utf8UnderGBK.Encoding().Name != "GBK" {
		t.Fatalf("legacy declaration encoding=%#v err=%v", utf8UnderGBK.Encoding(), err)
	}
	lossy, err := NewReader("/repo/doc.xml", bytes.NewReader(append([]byte(`<?xml encoding="gbk"?>`), 0x81, 0x20)))
	if err != nil {
		t.Fatal(err)
	}
	if got := readAllRunes(t, lossy); got != `<?xml encoding="gbk"?>`+"\uFFFD " {
		t.Fatalf("lossy text=%q", got)
	}
	var unsupported *UnsupportedLabelError
	if _, err = NewReader("/repo/doc.xml", strings.NewReader(`<?xml encoding="klingon"?>x`)); !errors.As(err, &unsupported) {
		t.Fatalf("label err=%v", err)
	}
	var conflict *DeclarationConflictError
	if _, err = NewReader("/repo/doc.xml", strings.NewReader(`<?xml encoding="utf-16"?><r/>`)); !errors.As(err, &conflict) {
		t.Fatalf("conflict err=%v", err)
	}
}

// A reader that fails underneath the codec passes its own error through.
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
