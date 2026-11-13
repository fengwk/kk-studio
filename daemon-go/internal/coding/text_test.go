package coding

import (
	"bytes"
	"errors"
	"strings"
	"testing"
	"unicode/utf8"
)

// COD-T-222
func TestTextUTF16RoundTrip(t *testing.T) {
	for _, tc := range []struct {
		charset string
		bom     []byte
	}{{"UTF-16LE", []byte{0xff, 0xfe}}, {"UTF-16BE", []byte{0xfe, 0xff}}} {
		encoded, err := encodeFixture("hi 🌍\n", tc.charset, len(tc.bom))
		if err != nil {
			t.Fatal(err)
		}
		encoded = append(tc.bom, encoded[len(tc.bom):]...)
		text, decoded, err := decodeFixture(encoded)
		if err != nil || text != "hi 🌍\n" || !decoded.BOM {
			t.Fatalf("%s: %q %#v %v", tc.charset, text, decoded, err)
		}
	}
}

// COD-T-220
func TestTextRejectsNULAndMalformedUTF8(t *testing.T) {
	for _, data := range [][]byte{{'a', 0, 'b'}, {0xff}} {
		if _, _, err := decodeFixture(data); err == nil {
			t.Fatalf("expected rejection of %x, got %v", data, err)
		}
	}
}

// COD-T-246
func TestReadWindowStopsAtLineLimit(t *testing.T) {
	text := strings.Repeat("line\n", 220) + string([]byte{0xff})
	window, err := ReadTextWindow(text, WindowOptions{Offset: 1, Limit: 200})
	if err != nil || !window.Truncated || window.TruncationReason != "line_limit" || window.EndsWithNewline != nil || !strings.Contains(window.Body, "200|line") {
		t.Fatalf("unexpected bounded window %#v %v", window, err)
	}
}

// COD-T-246 COD-T-247: a bounded reader proves the first window neither consumes the file nor decodes invalid bytes beyond it.
func TestReadTextWindowReaderStopsAfterFirstWindowAndOnlyReportsEOFMetadataAtEOF(t *testing.T) {
	data := append([]byte("first\n"), 0xff)
	data = append(data, bytes.Repeat([]byte("later line\n"), 100_000)...)
	reader := &countingReader{reader: bytes.NewReader(data)}
	window, err := ReadTextWindowReader(reader, WindowOptions{Offset: 1, Limit: 1})
	if err != nil {
		t.Fatal(err)
	}
	if window.Body != "1|first" || !window.Truncated || window.TruncationReason != "line_limit" || window.EndsWithNewline != nil {
		t.Fatalf("window=%#v", window)
	}
	if reader.readBytes >= int64(len(data)) || reader.readBytes > 8192 {
		t.Fatalf("bounded read consumed %d of %d bytes", reader.readBytes, len(data))
	}

	atEOF, err := ReadTextWindowReader(strings.NewReader("one\n"), WindowOptions{Offset: 1, Limit: 1})
	if err != nil || atEOF.Truncated || atEOF.EndsWithNewline == nil || !*atEOF.EndsWithNewline {
		t.Fatalf("EOF window=%#v err=%v", atEOF, err)
	}
}

type countingReader struct {
	reader    *bytes.Reader
	readBytes int64
}

func (r *countingReader) Read(p []byte) (int, error) {
	n, err := r.reader.Read(p)
	r.readBytes += int64(n)
	return n, err
}

// COD-T-053 COD-T-054: window reads must honor UTF-16 BOMs and reject malformed units inside the window.
func TestReadTextWindowReaderHandlesUTF16AndRejectsMalformedUnits(t *testing.T) {
	for _, charset := range []string{"UTF-16LE", "UTF-16BE"} {
		encoded, err := encodeFixture("first\nsecond", charset, 2)
		if err != nil {
			t.Fatal(err)
		}
		window, err := ReadTextWindowReader(bytes.NewReader(encoded), WindowOptions{Offset: 1, Limit: 1})
		if err != nil || window.Body != "1|first" || !window.Truncated || window.EndsWithNewline != nil {
			t.Fatalf("%s window=%#v err=%v", charset, window, err)
		}
	}
	malformed := []byte{0xff, 0xfe, 0x00, 0xd8}
	if _, err := ReadTextWindowReader(bytes.NewReader(malformed), WindowOptions{Offset: 1, Limit: 1}); err == nil || !strings.Contains(err.Error(), "not valid UTF-16") {
		t.Fatalf("expected malformed UTF-16 error, got %v", err)
	}
}

// COD-F-002 COD-T-246: the character budget counts Unicode code points and stops before later invalid bytes.
func TestReadTextWindowReaderStopsAtCodePointBudget(t *testing.T) {
	data := append([]byte(strings.Repeat("🌍", MaxReadCodePoints)), 0xff)
	window, err := ReadTextWindowReader(bytes.NewReader(data), WindowOptions{Offset: 1, Limit: 1})
	if err != nil {
		t.Fatal(err)
	}
	if !window.Truncated || window.TruncationReason != "character_limit" || window.EndLine != 1 || window.EndColumn != MaxReadCodePoints || window.NextLine != 1 || window.NextColumn != MaxReadCodePoints+1 {
		t.Fatalf("window=%#v", window)
	}
	if got := utf8.RuneCountInString(strings.SplitN(window.Body, "|", 2)[1]); got != MaxReadCodePoints {
		t.Fatalf("captured %d code points", got)
	}
}

// COD-T-021 COD-T-053 COD-T-054: reader preflight, BOM, malformed encoding, and empty-range behavior remain strict.
func TestReadTextWindowReaderValidatesRangesAndEncoding(t *testing.T) {
	for _, tc := range []struct {
		name    string
		data    []byte
		options WindowOptions
		wantErr string
	}{
		{"offset", []byte("x"), WindowOptions{Offset: 0}, "offset must be a positive integer"},
		{"limit", []byte("x"), WindowOptions{Offset: 1, Limit: MaxReadLimit + 1}, "limit must be a positive integer no greater than 2000"},
		{"column", []byte("x\n"), WindowOptions{Offset: 1, Limit: 1, ColumnOffset: 2}, "column_offset 2 is out of range: line 1 has 1 columns"},
		{"column exact contract", []byte("a\n"), WindowOptions{Offset: 1, Limit: 1, ColumnOffset: 5}, "column_offset 5 is out of range: line 1 has 1 columns"},
		{"utf8", []byte{0xff}, WindowOptions{Offset: 1, Limit: 1}, "file is not UTF-8 text and declares no encoding"},
		{"utf16 odd", []byte{0xff, 0xfe, 'x'}, WindowOptions{Offset: 1, Limit: 1}, "the bytes are not valid UTF-16LE; the first byte that cannot be reproduced is at offset 2"},
		{"utf16 low surrogate", []byte{0xff, 0xfe, 0x00, 0xdc}, WindowOptions{Offset: 1, Limit: 1}, "the bytes are not valid UTF-16LE; the first byte that cannot be reproduced is at offset 2"},
		{"negative column", []byte("x"), WindowOptions{Offset: 1, Limit: 1, ColumnOffset: -1}, "column_offset must be a positive integer"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := ReadTextWindowReader(bytes.NewReader(tc.data), tc.options)
			if err == nil || err.Error() != tc.wantErr {
				t.Fatalf("err=%v want=%q", err, tc.wantErr)
			}
		})
	}

	withBOM := append([]byte{0xef, 0xbb, 0xbf}, []byte("x")...)
	window, err := ReadTextWindowReader(bytes.NewReader(withBOM), WindowOptions{Offset: 1, Limit: 1})
	if err != nil || window.Body != "1|x" || window.EndsWithNewline == nil || *window.EndsWithNewline {
		t.Fatalf("BOM window=%#v err=%v", window, err)
	}
	empty, err := ReadTextWindowReader(strings.NewReader("x\n"), WindowOptions{Offset: 2, Limit: 1})
	if err != nil || !empty.Empty || empty.EndsWithNewline == nil || !*empty.EndsWithNewline {
		t.Fatalf("empty window=%#v err=%v", empty, err)
	}
	defaultWindow, err := ReadTextWindowReader(strings.NewReader("x"), WindowOptions{Offset: 1})
	if err != nil || defaultWindow.Body != "1|x" {
		t.Fatalf("default window=%#v err=%v", defaultWindow, err)
	}
	validUTF16, err := encodeFixture("x", "UTF-16LE", 2)
	if err != nil {
		t.Fatal(err)
	}
	utf16Window, err := ReadTextWindowReader(bytes.NewReader(validUTF16), WindowOptions{Offset: 1, Limit: 1})
	if err != nil || utf16Window.Body != "1|x" {
		t.Fatalf("UTF-16 EOF window=%#v err=%v", utf16Window, err)
	}
	if _, err := ReadTextWindowReader(errorReader{}, WindowOptions{Offset: 1, Limit: 1}); err == nil || err.Error() != "read failed" {
		t.Fatalf("expected source read failure, got %v", err)
	}
}

type errorReader struct{}

func (errorReader) Read([]byte) (int, error) { return 0, errors.New("read failed") }

// COD-T-246: an additional blank line after a full character budget is still a truncated window.
func TestReadTextWindowReaderCharacterBudgetTakesPrecedenceAtLineBoundary(t *testing.T) {
	text := strings.Repeat("x", MaxReadCodePoints) + "\n\n"
	window, err := ReadTextWindowReader(strings.NewReader(text), WindowOptions{Offset: 1, Limit: 1})
	if err != nil || !window.Truncated || window.TruncationReason != "character_limit" || window.NextLine != 2 || window.NextColumn != 1 {
		t.Fatalf("window=%#v err=%v", window, err)
	}
}

// COD-T-022
func TestReadWindowColumnOffsetUsesOneBasedColumnsAndCRSeparators(t *testing.T) {
	window, err := ReadTextWindow("abcdef\rnext\r\n", WindowOptions{Offset: 1, Limit: 1, ColumnOffset: 3})
	if err != nil {
		t.Fatal(err)
	}
	if window.Body != "1|cdef" || window.StartColumn != 3 || window.EndColumn != 6 || window.NextLine != 2 || window.NextColumn != 1 || !window.Truncated {
		t.Fatalf("window=%#v", window)
	}
}

// COD-F-002
func TestReadWindowEmptyRangeForEmptyFile(t *testing.T) {
	window, err := ReadTextWindow("", WindowOptions{Offset: 1, Limit: 10})
	if err != nil || !window.Empty || window.Body != "" || window.EndsWithNewline == nil || *window.EndsWithNewline {
		t.Fatalf("window=%#v err=%v", window, err)
	}
	if formatted := formatTextWindow("empty.txt", window); !strings.Contains(formatted, "range: empty") {
		t.Fatalf("formatted empty window=%q", formatted)
	}
}

func TestMediaSniffIgnoresExtensionAndDetectsMagic(t *testing.T) {
	if got := DetectMediaType([]byte("%PDF-anything")); got != "application/pdf" {
		t.Fatalf("got %q", got)
	}
	if got := DetectMediaType([]byte("hello world")); got != "" {
		t.Fatalf("unexpected media type %q", got)
	}
	if got := DetectImageType([]byte{0xff, 0xd8, 0xff, 0}); got != "image/jpeg" {
		t.Fatalf("got %q", got)
	}
	if bytes.Equal([]byte(DetectImageType(nil)), []byte("image/png")) {
		t.Fatal("empty input recognized as image")
	}
}
