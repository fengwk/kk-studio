package coding

import (
	"bytes"
	"strings"
	"testing"
)

// COD-T-222
func TestTextUTF16RoundTrip(t *testing.T) {
	for _, tc := range []struct {
		charset string
		bom     []byte
	}{{"UTF-16LE", []byte{0xff, 0xfe}}, {"UTF-16BE", []byte{0xfe, 0xff}}} {
		encoded, err := EncodeText("hi 🌍\n", tc.charset, len(tc.bom))
		if err != nil {
			t.Fatal(err)
		}
		encoded = append(tc.bom, encoded[len(tc.bom):]...)
		decoded, err := DecodeText(encoded)
		if err != nil || decoded.Text != "hi 🌍\n" {
			t.Fatalf("%s: %#v %v", tc.charset, decoded, err)
		}
	}
}

// COD-T-220
func TestTextRejectsNULAndMalformedUTF8(t *testing.T) {
	for _, data := range [][]byte{{'a', 0, 'b'}, {0xff}} {
		if _, err := DecodeText(data); err == nil || err.Error() != "file appears to be binary" {
			t.Fatalf("expected binary rejection, got %v", err)
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
}

// COD-T-117
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
