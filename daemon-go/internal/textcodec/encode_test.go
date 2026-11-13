package textcodec

import (
	"errors"
	"testing"

	"golang.org/x/text/encoding"
	"golang.org/x/text/transform"
)

// COD-T-243: Encode restores every byte order mark and never substitutes characters.
func TestEncodeRestoresBOM(t *testing.T) {
	for _, name := range bomEncodings {
		enc := Encoding{Name: name, BOM: true, Source: SourceBOM}
		data, err := Encode("a\n", enc)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if want := bomPrefix(enc); len(want) == 0 || !equalPrefix(data, want) {
			t.Fatalf("%s: bytes=%x", name, data)
		}
		decoded, got, err := Decode("/repo/doc.txt", data)
		if err != nil || got != enc || string(decoded) != "a\n" {
			t.Fatalf("%s: encoding=%#v text=%q err=%v", name, got, decoded, err)
		}
	}
}

func equalPrefix(data, prefix []byte) bool {
	if len(data) < len(prefix) {
		return false
	}
	for index := range prefix {
		if data[index] != prefix[index] {
			return false
		}
	}
	return true
}

// COD-T-244: unencodable text reports the character and its line.
func TestEncodeReportsUnrepresentableCharacter(t *testing.T) {
	for _, test := range []struct {
		name string
		text string
		enc  Encoding
		char rune
		line int
	}{
		{name: "gbk", text: "one\r\ntwo ☃\n", enc: Encoding{Name: "GBK"}, char: '☃', line: 2},
		{name: "ascii", text: "plain é\n", enc: Encoding{Name: "US-ASCII"}, char: 'é', line: 1},
		{name: "windows-1252", text: "中文", enc: Encoding{Name: "windows-1252"}, char: '中', line: 1},
	} {
		t.Run(test.name, func(t *testing.T) {
			var unrepresentable *UnrepresentableError
			if _, err := Encode(test.text, test.enc); !errors.As(err, &unrepresentable) || unrepresentable.Char != test.char || unrepresentable.Line != test.line {
				t.Fatalf("err=%v want %q on line %d", err, test.char, test.line)
			}
		})
	}
}

// COD-T-244: invalid UTF-8 in the text is unrepresentable in every target encoding.
func TestEncodeRejectsInvalidUTF8(t *testing.T) {
	invalid := string([]byte{'a', '\n', 0xed, 0xa0, 0x80})
	var unrepresentable *UnrepresentableError
	for _, name := range []string{"UTF-8", "UTF-16LE", "UTF-32BE", "US-ASCII", "GBK"} {
		if _, err := Encode(invalid, Encoding{Name: name}); !errors.As(err, &unrepresentable) || unrepresentable.Line != 2 {
			t.Fatalf("%s: err=%v", name, err)
		}
	}
}

// COD-T-244: an unknown encoding name is refused instead of guessed.
func TestEncodeRejectsUnknownEncoding(t *testing.T) {
	var unsupported *UnsupportedLabelError
	if _, err := Encode("x", Encoding{Name: "klingon"}); !errors.As(err, &unsupported) || unsupported.Label != "klingon" {
		t.Fatalf("err=%v", err)
	}
}

// COD-T-243: UTF-16 and UTF-32 encode surrogate pairs and wide characters exactly.
func TestEncodeWideEncodings(t *testing.T) {
	for _, test := range []struct {
		name string
		want []byte
	}{
		{name: "UTF-16LE", want: []byte{'a', 0, 0x3d, 0xd8, 0x00, 0xde}},
		{name: "UTF-16BE", want: []byte{0, 'a', 0xd8, 0x3d, 0xde, 0x00}},
		{name: "UTF-32LE", want: []byte{'a', 0, 0, 0, 0x00, 0xf6, 0x01, 0x00}},
		{name: "UTF-32BE", want: []byte{0, 0, 0, 'a', 0x00, 0x01, 0xf6, 0x00}},
	} {
		data, err := Encode("a😀", Encoding{Name: test.name})
		if err != nil {
			t.Fatalf("%s: %v", test.name, err)
		}
		if string(data) != string(test.want) {
			t.Fatalf("%s: bytes=%x want=%x", test.name, data, test.want)
		}
	}
}

// COD-T-244: U+0080 is outside US-ASCII, so ASCII encoding rejects it instead of emitting bytes.
func TestEncodeASCIIRejectsU0080(t *testing.T) {
	var unrepresentable *UnrepresentableError
	if _, err := Encode("\u0080", Encoding{Name: "US-ASCII"}); !errors.As(err, &unrepresentable) || unrepresentable.Char != 0x80 {
		t.Fatalf("U+0080 err=%v", err)
	}
	if data, err := Encode("\u007f", Encoding{Name: "US-ASCII"}); err != nil || string(data) != "\u007f" {
		t.Fatalf("U+007F bytes=%x err=%v", data, err)
	}
}

// COD-T-244: a legacy encoder that maps several characters to one byte must be refused instead
// of silently changing the text.
func TestEncodeRejectsSubstitutingLegacyEncoder(t *testing.T) {
	var unrepresentable *UnrepresentableError
	if _, err := encodeLegacy("AB", Encoding{Name: "merging"}, mergingEncoding{}); !errors.As(err, &unrepresentable) || unrepresentable.Char != 'B' || unrepresentable.Line != 1 {
		t.Fatalf("substituting encoder err=%v", err)
	}
	if data, err := encodeLegacy("A", Encoding{Name: "merging"}, mergingEncoding{}); err != nil || string(data) != "\xab" {
		t.Fatalf("round-tripping encoder bytes=%x err=%v", data, err)
	}
}

// mergingEncoding is a fake legacy encoding whose encoder maps both 'A' and 'B' to 0xAB while
// its decoder maps 0xAB back to 'A'.
type mergingEncoding struct{}

func (mergingEncoding) NewDecoder() *encoding.Decoder {
	return &encoding.Decoder{Transformer: mergingTransformer{}}
}

func (mergingEncoding) NewEncoder() *encoding.Encoder {
	return &encoding.Encoder{Transformer: mergingTransformer{}}
}

type mergingTransformer struct{}

func (mergingTransformer) Reset() {}

func (mergingTransformer) Transform(dst, src []byte, _ bool) (int, int, error) {
	consumed := 0
	written := 0
	for consumed < len(src) {
		if written >= len(dst) {
			return written, consumed, transform.ErrShortDst
		}
		switch src[consumed] {
		case 'A', 'B':
			dst[written] = 0xab
		case 0xab:
			dst[written] = 'A'
		default:
			return written, consumed, errors.New("fake encoding: unsupported byte")
		}
		written++
		consumed++
	}
	return written, consumed, nil
}
