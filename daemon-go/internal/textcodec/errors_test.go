package textcodec

import (
	"errors"
	"strings"
	"testing"
	"unicode/utf8"
)

// Every typed failure renders a message that names its cause.
func TestErrorMessages(t *testing.T) {
	declared := declaredEncoding("GBK", declarationXML)
	for _, test := range []struct {
		name string
		err  error
		want []string
	}{
		{name: "binary", err: ErrBinary, want: []string{"binary"}},
		{name: "unsupported", err: &UnsupportedLabelError{Label: "klingon", Declaration: declarationHTML}, want: []string{"HTML meta charset", "klingon", "unsupported encoding"}},
		{name: "unsupported-without-declaration", err: &UnsupportedLabelError{Label: "klingon"}, want: []string{"unsupported encoding", "klingon"}},
		{name: "conflict", err: &DeclarationConflictError{Declared: declared}, want: []string{declarationXML, "\"GBK\"", "byte order mark"}},
		{name: "conflict-raw-label", err: &DeclarationConflictError{Declared: declaredEncoding("UTF-16LE", declarationHTML), Label: "Utf-16le"}, want: []string{declarationHTML, "\"Utf-16le\"", "which is UTF-16LE"}},
		{name: "unrepresentable", err: &UnrepresentableError{Encoding: Encoding{Name: "GBK"}, Char: '☃', Line: 7}, want: []string{"The resulting text cannot be encoded in GBK", `"☃"`, "U+2603", "line 7"}},
		{name: "unrepresentable-replacement", err: &UnrepresentableError{Encoding: Encoding{Name: "GBK"}, Char: utf8.RuneError, Line: 3}, want: []string{"U+FFFD", "replace it with text that GBK can encode"}},
		{name: "unrepresentable-without-replacement", err: &UnrepresentableError{Encoding: Encoding{Name: "GBK"}, Char: '☃', Line: 7}, want: nil},
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
	if strings.Contains((&UnrepresentableError{Encoding: Encoding{Name: "GBK"}, Char: '☃', Line: 7}).Error(), "U+FFFD (�) marks bytes") {
		t.Fatal("the U+FFFD hint is added for a character that is not a replacement")
	}
	if IsCodecError(errors.New("read failed")) {
		t.Fatal("a foreign error is reported as a codec failure")
	}
}

// Label helpers map every UTF-16/UTF-32 label spelling to a canonical name.
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
		resolved, err := resolveLabel(label, declarationXML, formatIANA)
		if err != nil || !resolved.bomRequired {
			t.Fatalf("resolveLabel(%q)=%#v err=%v", label, resolved, err)
		}
	}
	if isUTF16Or32Label("GBK") {
		t.Fatal("GBK is reported as a UTF-16/UTF-32 label")
	}
	if wideWidth("UTF-8") != 1 || wideWidth("UTF-16LE") != 2 || wideWidth("UTF-32BE") != 4 || isWideName("UTF-8") {
		t.Fatal("wide encoding helpers disagree")
	}
}

// CPython's get_normal_name decides the Python and Ruby labels.
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

// A declaration is found in raw ASCII bytes, so an encoding that does not write ASCII as ASCII
// cannot be declared.
func TestAsciiCompatibility(t *testing.T) {
	for label, want := range map[string]bool{"gbk": true, "big5": true, "windows-1252": true, "ibm037": false} {
		xtext, err := lookupEncoding(label, formatIANA)
		if err != nil || xtext == nil {
			t.Fatalf("%s: lookup error %v", label, err)
		}
		if got := asciiCompatible(xtext); got != want {
			t.Fatalf("asciiCompatible(%s)=%v want %v", label, got, want)
		}
	}
}

// Byte and line helpers used by the binary rule, the replacement count and error lines.
func TestByteAndLineHelpers(t *testing.T) {
	if countReplacements([]byte("a\uFFFDb")) != 1 || countReplacements([]byte("plain")) != 0 {
		t.Fatal("countReplacements disagrees")
	}
	if !checkBinaryWindow([]byte{0}, 0) || checkBinaryWindow(nil, 0) || checkBinaryWindow([]byte{'a'}, 1) {
		t.Fatal("binary window helper disagrees")
	}
	if !binaryUnits([]byte{0, 0}, 2) || binaryUnits([]byte{0, 1}, 2) || binaryUnits([]byte{1}, 2) || binaryUnits([]byte{0, 0, 0}, 4) {
		t.Fatal("wide binary helper disagrees")
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
