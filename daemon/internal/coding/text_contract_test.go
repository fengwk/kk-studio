package coding

import (
	"bytes"
	"errors"
	"strconv"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/textcodec"
)

// Decoding keeps UTF-8 code points and UTF-16 surrogate pairs intact across tiny reads.
func TestReadTextWindowReaderPreservesMultibyteCharactersAcrossChunks(t *testing.T) {
	utf16Data, err := encodeFixture("a中😀\n", "UTF-16LE", 2)
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name string
		data []byte
		want string
	}{
		{name: "utf8", data: []byte("a中😀\n"), want: "1|a中😀"},
		{name: "utf16le", data: utf16Data, want: "1|a中😀"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			window, err := ReadTextWindowReader(&singleByteReader{reader: bytes.NewReader(tc.data)}, WindowOptions{Offset: 1, Limit: 1})
			if err != nil || window.Body != tc.want || window.EndsWithNewline == nil || !*window.EndsWithNewline {
				t.Fatalf("window=%#v err=%v", window, err)
			}
		})
	}
}

type singleByteReader struct{ reader *bytes.Reader }

func (r *singleByteReader) Read(buffer []byte) (int, error) {
	if len(buffer) > 1 {
		buffer = buffer[:1]
	}
	return r.reader.Read(buffer)
}

// Number only the projected lines and apply column offset to the first line.
func TestReadTextWindowProjectionAndColumnOffset(t *testing.T) {
	lines := make([]string, 10)
	for i := range lines {
		lines[i] = "line " + strconv.Itoa(i+1)
	}
	window, err := readTextWindow(strings.Join(lines, "\n")+"\n", WindowOptions{Offset: 9, Limit: 2})
	if err != nil || window.Body != " 9|line 9\n10|line 10" {
		t.Fatalf("window=%#v err=%v", window, err)
	}

	columnWindow, err := readTextWindow("abc\ndef\n", WindowOptions{Offset: 1, Limit: 2, ColumnOffset: 2})
	if err != nil || columnWindow.Body != "1|bc\n2|def" {
		t.Fatalf("column window=%#v err=%v", columnWindow, err)
	}
}

// CRLF is one separator and is not retained in line content.
func TestReadTextWindowCRLFFoldsSeparator(t *testing.T) {
	window, err := readTextWindow("a\r\nb", WindowOptions{Offset: 1, Limit: 2})
	if err != nil || window.Body != "1|a\n2|b" || window.EndsWithNewline == nil || *window.EndsWithNewline {
		t.Fatalf("window=%#v err=%v", window, err)
	}
}

// A full line window stops before later data, including malformed bytes.
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

// The last requested row still reports EOF newline metadata when the window ends at EOF.
func TestReadTextWindowEOFMetadataAtRequestedOffset(t *testing.T) {
	window, err := readTextWindow("a\nb\n", WindowOptions{Offset: 2, Limit: DefaultReadLimit})
	if err != nil || window.Truncated || window.EndsWithNewline == nil || !*window.EndsWithNewline {
		t.Fatalf("window=%#v err=%v", window, err)
	}
}

// Malformed UTF-8 is shown as U+FFFD by the whole-file decode and by the read window, at every
// buffer split, and the maximal subpart rule decides how many replacements it takes.
func TestTextCodecReplacesMalformedUTF8Sequences(t *testing.T) {
	for _, test := range []struct {
		data []byte
		want string
	}{
		{[]byte{0x80}, "\uFFFD"},
		{[]byte{0xe4, 0x41}, "\uFFFDA"},
		{[]byte{0xe4}, "\uFFFD"},
		{[]byte{0xc0, 0x81}, "\uFFFD\uFFFD"},
		{[]byte{0xe0, 0x81, 0x81}, "\uFFFD\uFFFD\uFFFD"},
		{[]byte{0xf0, 0x8f, 0xbf, 0xbf}, "\uFFFD\uFFFD\uFFFD\uFFFD"},
		{[]byte{0xed, 0xa0, 0x80}, "\uFFFD\uFFFD\uFFFD"},
		{[]byte{0xf4, 0x90, 0x80, 0x80}, "\uFFFD\uFFFD\uFFFD\uFFFD"},
	} {
		text, _, err := decodeFixture(test.data)
		if err != nil || text != test.want {
			t.Fatalf("Decode(%x)=%q err=%v want %q", test.data, text, err, test.want)
		}
		window, err := ReadTextWindowReader(bytes.NewReader(test.data), WindowOptions{Offset: 1, Limit: 1})
		if err != nil || window.Body != "1|"+test.want || window.Truncated {
			t.Fatalf("window(%x)=%#v err=%v", test.data, window, err)
		}
		for split := 1; split < len(test.data); split++ {
			boundary := append(bytes.Repeat([]byte("a"), 8192-split), test.data...)
			window, err := ReadTextWindowReader(bytes.NewReader(boundary), WindowOptions{Offset: 1, Limit: 1})
			if err != nil || !strings.HasSuffix(window.Body, test.want) {
				t.Fatalf("window(%x) split at %d=%#v err=%v", test.data, split, window, err)
			}
		}
	}
	if _, err := encodeFixture(string([]byte{0xed, 0xa0, 0x80}), "UTF-8", 0); err == nil {
		t.Fatal("Encode accepted a surrogate-encoded invalid UTF-8 string")
	}
}

// Decoding removes the BOM from text while retaining charset and BOM byte count.
func TestDecodeReportsUTF8BOM(t *testing.T) {
	text, decoded, err := decodeFixture([]byte{0xef, 0xbb, 0xbf, 'a'})
	if err != nil || text != "a" || decoded.Name != "UTF-8" || !decoded.BOM {
		t.Fatalf("text=%q encoding=%#v err=%v", text, decoded, err)
	}
}

// Legal UTF-8 boundary code points decode without normalization.
func TestTextCodecAcceptsUTF8BoundaryCodePoints(t *testing.T) {
	want := string([]rune{0x7f, 0x80, 0x7ff, 0x800, 0xd7ff, 0xe000, 0xffff, 0x10000, 0x10ffff})
	got, _, err := decodeFixture([]byte(want))
	if err != nil || got != want {
		t.Fatalf("decoded=%q want=%q err=%v", got, want, err)
	}
}

// Only NUL is binary, the probe window is 8000 bytes, and control characters
// are text.
func TestBinaryProbeBoundaryAndControlCharacters(t *testing.T) {
	if _, _, _, err := textcodec.Decode("/repo/controls.txt", []byte("hello\tworld\r\n\f\x1b\x01\x1f")); err != nil {
		t.Fatalf("control characters were classified as binary: %v", err)
	}
	inside := bytes.Repeat([]byte{'a'}, 8000)
	inside[7999] = 0
	if _, _, _, err := textcodec.Decode("/repo/nul.txt", inside); !errors.Is(err, textcodec.ErrBinary) {
		t.Fatalf("NUL within the probe window: err=%v", err)
	}
	outside := bytes.Repeat([]byte{'a'}, 8001)
	outside[8000] = 0
	if _, _, _, err := textcodec.Decode("/repo/late-nul.txt", outside); err != nil {
		t.Fatalf("NUL outside the probe window: err=%v", err)
	}
	if _, _, _, err := textcodec.Decode("/repo/utf16.txt", []byte{0xff, 0xfe, 'a', 0, 'b', 0}); err != nil {
		t.Fatalf("UTF-16 data was classified as binary: %v", err)
	}
}
