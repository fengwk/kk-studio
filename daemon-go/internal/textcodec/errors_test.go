package textcodec

import (
	"errors"
	"strings"
	"testing"
)

// COD-T-250: every typed failure renders a message that names its cause.
func TestErrorMessages(t *testing.T) {
	declared := declaredEncoding("GBK", declarationXML)
	for _, test := range []struct {
		name string
		err  error
		want []string
	}{
		{name: "binary", err: ErrBinary, want: []string{"binary"}},
		{name: "unknown", err: &UnknownEncodingError{Path: "/repo/gbk.txt"}, want: []string{"/repo/gbk.txt", "not UTF-8"}},
		{name: "unsupported", err: &UnsupportedLabelError{Label: "klingon", Declaration: declarationHTML}, want: []string{"HTML meta charset", "klingon"}},
		{name: "unsupported-without-declaration", err: &UnsupportedLabelError{Label: "klingon"}, want: []string{"unknown encoding", "klingon"}},
		{name: "conflict", err: &DeclarationConflictError{Declared: declared}, want: []string{declarationXML, "GBK", "byte order mark"}},
		{name: "undecodable", err: &UndecodableError{Encoding: declared, Offset: 42}, want: []string{"GBK", declarationXML, "offset 42"}},
		{name: "undecodable-without-declaration", err: &UndecodableError{Encoding: Encoding{Name: "UTF-16LE"}, Offset: 0}, want: []string{"UTF-16LE", "offset 0"}},
		{name: "unrepresentable", err: &UnrepresentableError{Encoding: Encoding{Name: "GBK"}, Char: '☃', Line: 7}, want: []string{"☃", "U+2603", "line 7", "GBK"}},
		{name: "too-large", err: &TooLargeError{Encoding: declared, Size: 1 << 30}, want: []string{"1073741824", "GBK", "67108864"}},
	} {
		t.Run(test.name, func(t *testing.T) {
			message := test.err.Error()
			for _, part := range test.want {
				if !strings.Contains(message, part) {
					t.Fatalf("message %q does not contain %q", message, part)
				}
			}
			if !IsCodecError(test.err) {
				t.Fatalf("%T is not reported as a codec failure", test.err)
			}
		})
	}
	if IsCodecError(errors.New("read failed")) {
		t.Fatal("a foreign error is reported as a codec failure")
	}
}

// COD-T-251: label helpers map every UTF-16/UTF-32 label spelling to a canonical name.
func TestLabelHelpers(t *testing.T) {
	for label, want := range map[string]string{
		"utf-16":   "UTF-16",
		"utf16":    "UTF-16",
		"utf-32":   "UTF-32",
		"utf32":    "UTF-32",
		"utf-16le": "UTF-16LE",
		"utf16le":  "UTF-16LE",
		"utf-16be": "UTF-16BE",
		"utf16be":  "UTF-16BE",
		"utf-32le": "UTF-32LE",
		"utf32le":  "UTF-32LE",
		"utf-32be": "UTF-32BE",
		"utf32be":  "UTF-32BE",
	} {
		if got := unicodeFamilyName(label); got != want {
			t.Fatalf("unicodeFamilyName(%q)=%q want %q", label, got, want)
		}
		if !isUTF16Or32Label(strings.ToUpper(label)) {
			t.Fatalf("%q is not reported as a UTF-16/UTF-32 label", label)
		}
	}
	for _, label := range []string{"UTF-16", "utf-32be"} {
		var conflict *DeclarationConflictError
		if _, err := resolveLabel(label, declarationXML, formatIANA); !errors.As(err, &conflict) {
			t.Fatalf("resolveLabel(%q) err=%v", label, err)
		}
	}
	if isUTF16Or32Label("GBK") {
		t.Fatal("GBK is reported as a UTF-16/UTF-32 label")
	}
	if wideWidth("UTF-8") != 1 || wideWidth("UTF-16LE") != 2 || wideWidth("UTF-32BE") != 4 || isWideName("UTF-8") {
		t.Fatal("wide encoding helpers disagree")
	}
}

// COD-T-252: CPython's get_normal_name decides the Python and Ruby labels.
func TestNormalizeCPythonName(t *testing.T) {
	for label, want := range map[string]string{
		"utf-8":            "utf-8",
		"utf-8-sig":        "utf-8",
		"utf_8":            "utf-8",
		"latin-1":          "iso-8859-1",
		"latin-1-unix":     "iso-8859-1",
		"iso-latin-1":      "iso-8859-1",
		"iso-8859-1":       "iso-8859-1",
		"iso-8859-1-unix":  "iso-8859-1",
		"iso-8859-1x":      "iso-8859-1x",
		"latin-1-and-more": "iso-8859-1",
		"gbk":              "gbk",
	} {
		if got := normalizeCPythonName(strings.ToLower(label)); got != want {
			t.Fatalf("normalizeCPythonName(%q)=%q want %q", label, got, want)
		}
	}
}

// COD-T-253: byte and line helpers used by the binary rule and by error offsets.
func TestByteAndLineHelpers(t *testing.T) {
	if firstInvalidOffset([]byte("plain")) != len("plain") {
		t.Fatal("valid UTF-8 reports an invalid offset")
	}
	if offset := firstInvalidOffset([]byte("a\xff")); offset != 1 {
		t.Fatalf("offset=%d", offset)
	}
	if !checkBinaryWindow([]byte{0}, 0) || checkBinaryWindow(nil, 0) || checkBinaryWindow([]byte{'a'}, 1) {
		t.Fatal("binary window helper disagrees")
	}
	if !binaryUnits([]byte{0, 0}, 2) || binaryUnits([]byte{0, 1}, 2) || binaryUnits([]byte{1}, 2) || binaryUnits([]byte{0, 0, 0}, 4) {
		t.Fatal("wide binary helper disagrees")
	}
	if !hasHighByte([]byte("é")) || hasHighByte([]byte("plain")) {
		t.Fatal("hasHighByte disagrees")
	}
	if firstNonASCII([]byte("é")) != 0 || firstNonASCII([]byte("plain")) != -1 {
		t.Fatal("firstNonASCII disagrees")
	}
	for text, want := range map[string]int{
		"a":          1,
		"a\nb":       2,
		"a\r\nb":     2,
		"a\rb":       2,
		"a\n\nb":     3,
		"a\r\n\r\nb": 3,
	} {
		if got := lineAt(text, len(text)); got != want {
			t.Fatalf("lineAt(%q)=%d want %d", text, got, want)
		}
	}
	if lineAt("a\nb", 0) != 1 {
		t.Fatal("lineAt at offset 0 is not the first line")
	}
}
