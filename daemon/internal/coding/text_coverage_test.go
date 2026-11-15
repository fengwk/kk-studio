package coding

import (
	"strings"
	"testing"
)

// readTextWindow projects an in-memory document through the same reader fs.read uses.
func readTextWindow(text string, options WindowOptions) (TextWindow, error) {
	return ReadTextWindowReader(strings.NewReader(text), options)
}

// Malformed wide units decode to U+FFFD; an encoding the codec does not know or cannot write is
// still refused by Encode.
func TestTextCodecReplacesMalformedWideUnitsAndRejectsUnsupportedCharsets(t *testing.T) {
	for _, data := range [][]byte{{0xff, 0xfe, 0x41}, {0xff, 0xfe, 0x00, 0xdc}, {0xfe, 0xff, 0xdc, 0x00}} {
		if text, _, err := decodeFixture(data); err != nil || text != "\uFFFD" {
			t.Fatalf("malformed UTF-16 %x text=%q error=%v", data, text, err)
		}
	}
	for _, test := range []struct {
		text    string
		charset string
		bom     int
	}{
		{string([]byte{0xff}), "UTF-8", 0},
		{"text", "UNKNOWN", 0},
		{"text", "UNKNOWN", 1},
	} {
		if _, err := encodeFixture(test.text, test.charset, test.bom); err == nil || !strings.Contains(err.Error(), "cannot be encoded in") && !strings.Contains(err.Error(), "unsupported encoding") {
			t.Fatalf("charset=%s bom=%d error=%v", test.charset, test.bom, err)
		}
	}
}

func TestEncodeRestoresEachSupportedBOM(t *testing.T) {
	for _, test := range []struct {
		charset string
		bom     int
		prefix  []byte
	}{
		{"UTF-8", 3, []byte{0xef, 0xbb, 0xbf}},
		{"UTF-16LE", 2, []byte{0xff, 0xfe}},
		{"UTF-16BE", 2, []byte{0xfe, 0xff}},
	} {
		data, err := encodeFixture("A", test.charset, test.bom)
		if err != nil || !strings.HasPrefix(string(data), string(test.prefix)) {
			t.Fatalf("charset=%s data=%x err=%v", test.charset, data, err)
		}
	}
}

func TestReadTextWindowCoversValidationOffsetsAndTruncation(t *testing.T) {
	for _, options := range []WindowOptions{
		{Offset: 0, Limit: 1, ColumnOffset: 1},
		{Offset: 1, Limit: MaxReadLimit + 1, ColumnOffset: 1},
		{Offset: 1, Limit: 1, ColumnOffset: -1},
	} {
		if _, err := readTextWindow("text", options); err == nil {
			t.Fatalf("invalid options accepted: %#v", options)
		}
	}
	if _, err := readTextWindow("abc", WindowOptions{Offset: 1, Limit: 1, ColumnOffset: 5}); err == nil {
		t.Fatal("out-of-range column accepted")
	}
	empty, err := readTextWindow("line", WindowOptions{Offset: 2, Limit: 1, ColumnOffset: 1})
	if err != nil || !empty.Empty || empty.EndsWithNewline == nil || *empty.EndsWithNewline {
		t.Fatalf("past-EOF window=%#v err=%v", empty, err)
	}
	lineLimited, err := readTextWindow("a\nb\nc", WindowOptions{Offset: 1, Limit: 1, ColumnOffset: 1})
	if err != nil || !lineLimited.Truncated || lineLimited.TruncationReason != "line_limit" || lineLimited.NextLine != 2 {
		t.Fatalf("line-limited window=%#v err=%v", lineLimited, err)
	}
	charLimited, err := readTextWindow(strings.Repeat("界", MaxReadCodePoints+1), WindowOptions{Offset: 1, Limit: 1, ColumnOffset: 1})
	if err != nil || !charLimited.Truncated || charLimited.TruncationReason != "character_limit" || charLimited.EndColumn != MaxReadCodePoints {
		t.Fatalf("character-limited window=%#v err=%v", charLimited, err)
	}
}
