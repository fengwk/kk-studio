package textcodec

import "bytes"

// Byte order marks, longest first so that UTF-32LE is never mistaken for UTF-16LE.
var (
	bomUTF32LE = []byte{0xff, 0xfe, 0x00, 0x00}
	bomUTF32BE = []byte{0x00, 0x00, 0xfe, 0xff}
	bomUTF8    = []byte{0xef, 0xbb, 0xbf}
	bomUTF16LE = []byte{0xff, 0xfe}
	bomUTF16BE = []byte{0xfe, 0xff}
)

// bomEncoding returns the encoding a byte order mark establishes, if any.
func bomEncoding(data []byte) (Encoding, bool) {
	switch {
	case bytes.HasPrefix(data, bomUTF32LE):
		return Encoding{Name: "UTF-32LE", BOM: true, Source: SourceBOM}, true
	case bytes.HasPrefix(data, bomUTF32BE):
		return Encoding{Name: "UTF-32BE", BOM: true, Source: SourceBOM}, true
	case bytes.HasPrefix(data, bomUTF8):
		return Encoding{Name: "UTF-8", BOM: true, Source: SourceBOM}, true
	case bytes.HasPrefix(data, bomUTF16LE):
		return Encoding{Name: "UTF-16LE", BOM: true, Source: SourceBOM}, true
	case bytes.HasPrefix(data, bomUTF16BE):
		return Encoding{Name: "UTF-16BE", BOM: true, Source: SourceBOM}, true
	}
	return Encoding{}, false
}

// bomPrefix returns the byte order mark of enc, if it is a BOM-marked encoding.
func bomPrefix(enc Encoding) []byte {
	if !enc.BOM {
		return nil
	}
	switch enc.Name {
	case "UTF-8":
		return bomUTF8
	case "UTF-16LE":
		return bomUTF16LE
	case "UTF-16BE":
		return bomUTF16BE
	case "UTF-32LE":
		return bomUTF32LE
	case "UTF-32BE":
		return bomUTF32BE
	}
	return nil
}

// wideWidth reports the width in bytes of one unit of a UTF-16 or UTF-32 encoding.
func wideWidth(name string) int {
	switch name {
	case "UTF-16LE", "UTF-16BE":
		return 2
	case "UTF-32LE", "UTF-32BE":
		return 4
	}
	return 1
}

// isWideName reports whether name is a UTF-16 or UTF-32 encoding.
func isWideName(name string) bool {
	switch name {
	case "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE":
		return true
	}
	return false
}

// isUTF16Or32Label reports whether a declared label selects a UTF-16/UTF-32 encoding,
// which is refused without a byte order mark.
func isUTF16Or32Label(name string) bool {
	switch name {
	case "UTF-16", "UTF-16LE", "UTF-16BE", "UTF-32", "UTF-32LE", "UTF-32BE",
		"UTF16", "UTF16LE", "UTF16BE", "UTF32", "UTF32LE", "UTF32BE":
		return true
	}
	return false
}
