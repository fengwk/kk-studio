package coding

import (
	"bytes"
	"strconv"
	"strings"
	"testing"
)

// COD-T-212, COD-T-213: number only the projected lines and apply column offset to the first line.
func TestReadTextWindowProjectionAndColumnOffset(t *testing.T) {
	lines := make([]string, 10)
	for i := range lines {
		lines[i] = "line " + strconv.Itoa(i+1)
	}
	window, err := ReadTextWindow(strings.Join(lines, "\n")+"\n", WindowOptions{Offset: 9, Limit: 2})
	if err != nil || window.Body != " 9|line 9\n10|line 10" {
		t.Fatalf("window=%#v err=%v", window, err)
	}

	columnWindow, err := ReadTextWindow("abc\ndef\n", WindowOptions{Offset: 1, Limit: 2, ColumnOffset: 2})
	if err != nil || columnWindow.Body != "1|bc\n2|def" {
		t.Fatalf("column window=%#v err=%v", columnWindow, err)
	}
}

// COD-T-216: CRLF is one separator and is not retained in line content.
func TestReadTextWindowCRLFFoldsSeparator(t *testing.T) {
	window, err := ReadTextWindow("a\r\nb", WindowOptions{Offset: 1, Limit: 2})
	if err != nil || window.Body != "1|a\n2|b" || window.EndsWithNewline == nil || *window.EndsWithNewline {
		t.Fatalf("window=%#v err=%v", window, err)
	}
}

// COD-T-246, COD-T-247: a full line window stops before later data, including malformed bytes.
func TestReadTextWindowStopsAtRequestedWindow(t *testing.T) {
	var content strings.Builder
	for i := 1; i <= 2500; i++ {
		content.WriteString("line ")
		content.WriteString(strconv.Itoa(i))
		content.WriteByte('\n')
	}
	data := append([]byte(content.String()), 0xff)
	data = append(data, bytes.Repeat([]byte("a"), 100_000)...)
	reader := &countingReader{reader: bytes.NewReader(data)}
	window, err := ReadTextWindowReader(reader, WindowOptions{Offset: 1, Limit: 200})
	if err != nil {
		t.Fatal(err)
	}
	if window.TruncationReason != "line_limit" || window.EndsWithNewline != nil || !strings.HasPrefix(window.Body, "  1|line 1") || !strings.Contains(window.Body, "200|line 200") {
		t.Fatalf("window=%#v", window)
	}
	if reader.readBytes > 8192 {
		t.Fatalf("bounded window read %d bytes", reader.readBytes)
	}

	boundary := make([]byte, 0)
	for i := 1; i <= 200; i++ {
		boundary = append(boundary, []byte("line ")...)
		boundary = append(boundary, []byte(strconv.Itoa(i))...)
		boundary = append(boundary, '\n')
	}
	boundary = append(boundary, 0xff)
	last, err := ReadTextWindowReader(bytes.NewReader(boundary), WindowOptions{Offset: 1, Limit: 200})
	if err != nil || last.TruncationReason != "line_limit" || last.EndsWithNewline != nil {
		t.Fatalf("boundary window=%#v err=%v", last, err)
	}
}

// COD-T-245: the last requested row still reports EOF newline metadata when the window ends at EOF.
func TestReadTextWindowEOFMetadataAtRequestedOffset(t *testing.T) {
	window, err := ReadTextWindow("a\nb\n", WindowOptions{Offset: 2, Limit: DefaultReadLimit})
	if err != nil || window.Truncated || window.EndsWithNewline == nil || !*window.EndsWithNewline {
		t.Fatalf("window=%#v err=%v", window, err)
	}
}

// Go receives UTF-8 bytes, so unpaired UTF-16 surrogate expectations are represented by their invalid UTF-8 encoding.
// COD-T-219, COD-T-224, COD-T-226: UTF-8 rejects malformed encodings at all buffer splits and cannot encode surrogate bytes.
func TestTextCodecRejectsMalformedUTF8Sequences(t *testing.T) {
	sequences := [][]byte{
		{0xc0, 0x81}, {0xe0, 0x81, 0x81}, {0xf0, 0x8f, 0xbf, 0xbf},
		{0xed, 0xa0, 0x80}, {0xf4, 0x90, 0x80, 0x80},
	}
	for _, data := range sequences {
		if _, err := DecodeText(data); err == nil {
			t.Errorf("DecodeText accepted malformed UTF-8 %x", data)
		}
		if _, err := ReadTextWindowReader(bytes.NewReader(data), WindowOptions{Offset: 1, Limit: 1}); err == nil {
			t.Errorf("ReadTextWindowReader accepted malformed UTF-8 %x", data)
		}
		for split := 1; split < len(data); split++ {
			boundary := append(bytes.Repeat([]byte("a"), 8192-split), data...)
			if _, err := ReadTextWindowReader(bytes.NewReader(boundary), WindowOptions{Offset: 1, Limit: 1}); err == nil {
				t.Errorf("ReadTextWindowReader accepted malformed UTF-8 %x split at %d", data, split)
			}
		}
	}
	if _, err := EncodeText(string([]byte{0xed, 0xa0, 0x80}), "UTF-8", 0); err == nil {
		t.Fatal("EncodeText accepted a surrogate-encoded invalid UTF-8 string")
	}
}

// COD-T-221: decoding removes the BOM from text while retaining charset and BOM byte count.
func TestDecodeTextReportsUTF8BOM(t *testing.T) {
	decoded, err := DecodeText([]byte{0xef, 0xbb, 0xbf, 'a'})
	if err != nil || decoded.Text != "a" || decoded.Charset != "UTF-8" || decoded.BOMBytes != 3 {
		t.Fatalf("decoded=%#v err=%v", decoded, err)
	}
}

// COD-T-225: legal UTF-8 boundary code points decode without normalization.
func TestTextCodecAcceptsUTF8BoundaryCodePoints(t *testing.T) {
	want := string([]rune{0x7f, 0x80, 0x7ff, 0x800, 0xd7ff, 0xe000, 0xffff, 0x10000, 0x10ffff})
	got, err := DecodeText([]byte(want))
	if err != nil || got.Text != want {
		t.Fatalf("decoded=%q want=%q err=%v", got.Text, want, err)
	}
}

// COD-T-228…231: binary controls, the 8192-byte probe boundary, and UTF-16 exemption.
func TestBinaryProbeBoundaryAndUTF16Allowance(t *testing.T) {
	allowed := []byte("hello\tworld\r\n\f\x1b")
	if LooksBinary(allowed, false) {
		t.Fatal("allowed text controls were classified as binary")
	}
	for _, b := range []byte{0x00, 0x01, 0x08, 0x0e, 0x1f} {
		if !LooksBinary([]byte{b}, false) {
			t.Errorf("control byte %02x was not classified as binary", b)
		}
	}
	inside := bytes.Repeat([]byte{'a'}, 8192)
	inside[8191] = 0
	if !LooksBinary(inside, false) {
		t.Fatal("control byte within the probe window was missed")
	}
	outside := bytes.Repeat([]byte{'a'}, 8193)
	outside[8192] = 0
	if LooksBinary(outside, false) {
		t.Fatal("control byte outside the probe window was scanned")
	}
	if LooksBinary([]byte{0, 0x61, 0, 0x62}, true) {
		t.Fatal("UTF-16 data was classified as binary")
	}
}
