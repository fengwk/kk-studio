package coding

import (
	"strings"
	"testing"
)

// COD-F-003
func TestTextCodecRejectsMalformedEncodingAndUnsupportedCharsets(t *testing.T) {
	for _, data := range [][]byte{{0xff, 0xfe, 0x41}, {0xff, 0xfe, 0x00, 0xdc}, {0xfe, 0xff, 0xdc, 0x00}} {
		if _, _, err := decodeFixture(data); err == nil || !strings.Contains(err.Error(), "not valid UTF-16") {
			t.Fatalf("malformed UTF-16 %x error=%v", data, err)
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
		if _, err := encodeFixture(test.text, test.charset, test.bom); err == nil || !strings.Contains(err.Error(), "cannot be encoded in") && !strings.Contains(err.Error(), "unknown encoding") {
			t.Fatalf("charset=%s bom=%d error=%v", test.charset, test.bom, err)
		}
	}
}

// COD-F-003
func TestEncodeTextRestoresEachSupportedBOM(t *testing.T) {
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

// COD-F-001
func TestReadTextWindowCoversValidationOffsetsAndTruncation(t *testing.T) {
	for _, options := range []WindowOptions{
		{Offset: 0, Limit: 1, ColumnOffset: 1},
		{Offset: 1, Limit: MaxReadLimit + 1, ColumnOffset: 1},
		{Offset: 1, Limit: 1, ColumnOffset: -1},
	} {
		if _, err := ReadTextWindow("text", options); err == nil {
			t.Fatalf("invalid options accepted: %#v", options)
		}
	}
	if _, err := ReadTextWindow("abc", WindowOptions{Offset: 1, Limit: 1, ColumnOffset: 5}); err == nil {
		t.Fatal("out-of-range column accepted")
	}
	empty, err := ReadTextWindow("line", WindowOptions{Offset: 2, Limit: 1, ColumnOffset: 1})
	if err != nil || !empty.Empty || empty.EndsWithNewline == nil || *empty.EndsWithNewline {
		t.Fatalf("past-EOF window=%#v err=%v", empty, err)
	}
	lineLimited, err := ReadTextWindow("a\nb\nc", WindowOptions{Offset: 1, Limit: 1, ColumnOffset: 1})
	if err != nil || !lineLimited.Truncated || lineLimited.TruncationReason != "line_limit" || lineLimited.NextLine != 2 {
		t.Fatalf("line-limited window=%#v err=%v", lineLimited, err)
	}
	charLimited, err := ReadTextWindow(strings.Repeat("界", MaxReadCodePoints+1), WindowOptions{Offset: 1, Limit: 1, ColumnOffset: 1})
	if err != nil || !charLimited.Truncated || charLimited.TruncationReason != "character_limit" || charLimited.EndColumn != MaxReadCodePoints {
		t.Fatalf("character-limited window=%#v err=%v", charLimited, err)
	}
}
