package textcodec

import (
	"bytes"
	"unicode/utf16"
	"unicode/utf8"

	"golang.org/x/text/transform"
)

// Decode returns the UTF-8 text of a whole file. When data is already UTF-8 the result
// aliases data; a byte order mark is removed by slicing.
func Decode(path string, data []byte) ([]byte, Encoding, error) {
	if enc, ok := bomEncoding(data); ok {
		return decodeWithBOM(enc, data)
	}
	if checkBinaryWindow(data, 0) {
		return nil, Encoding{}, ErrBinary
	}
	label, where := findDeclaration(path, data)
	if label == "" {
		if utf8.Valid(data) {
			return data, Encoding{Name: "UTF-8", Source: SourceUTF8}, nil
		}
		return nil, Encoding{}, &UnknownEncodingError{Path: path}
	}
	declared, err := resolveLabel(label, where, formatOf(path, where))
	if err != nil {
		return nil, Encoding{}, err
	}
	return decodeDeclared(data, declared)
}

// decodeWithBOM decodes a file whose encoding a byte order mark established.
func decodeWithBOM(enc Encoding, data []byte) ([]byte, Encoding, error) {
	prefix := bomPrefix(enc)
	if enc.Name == "UTF-8" {
		if checkBinaryWindow(data, len(prefix)) {
			return nil, enc, ErrBinary
		}
		body := data[len(prefix):]
		if !utf8.Valid(body) {
			return nil, enc, &UndecodableError{Encoding: enc, Offset: len(prefix) + firstInvalidOffset(body)}
		}
		return body, enc, nil
	}
	if binaryUnits(data[len(prefix):], wideWidth(enc.Name)) {
		return nil, enc, ErrBinary
	}
	text, err := decodeWide(enc, data)
	if err != nil {
		return nil, enc, err
	}
	return text, enc, nil
}

// decodeWide decodes a BOM-marked UTF-16 or UTF-32 file.
func decodeWide(enc Encoding, data []byte) ([]byte, error) {
	prefix := bomPrefix(enc)
	body := data[len(prefix):]
	width := wideWidth(enc.Name)
	bigEndian := enc.Name == "UTF-16BE" || enc.Name == "UTF-32BE"
	if len(body)%width != 0 {
		return nil, &UndecodableError{Encoding: enc, Offset: len(data) - len(body)%width}
	}
	text := make([]byte, 0, len(body))
	for offset := 0; offset < len(body); offset += width {
		value := readUnit(body[offset:], width, bigEndian)
		decoded, consumed, err := decodeUnit(enc, body, offset, width, value)
		if err != nil {
			return nil, err
		}
		text = utf8.AppendRune(text, decoded)
		offset += consumed
	}
	return text, nil
}

// decodeUnit decodes the code point at offset and reports how many further units it consumed, so
// a UTF-16 surrogate pair advances past its low half.
func decodeUnit(enc Encoding, body []byte, offset, width int, value uint32) (rune, int, error) {
	position := len(bomPrefix(enc)) + offset
	if width == 4 {
		if value > utf8.MaxRune || value >= 0xd800 && value <= 0xdfff {
			return 0, 0, &UndecodableError{Encoding: enc, Offset: position}
		}
		return rune(value), 0, nil
	}
	unit := uint16(value)
	if unit >= 0xdc00 && unit <= 0xdfff {
		return 0, 0, &UndecodableError{Encoding: enc, Offset: position}
	}
	if unit < 0xd800 || unit > 0xdbff {
		return rune(unit), 0, nil
	}
	if offset+width >= len(body) {
		return 0, 0, &UndecodableError{Encoding: enc, Offset: position}
	}
	bigEndian := enc.Name == "UTF-16BE" || enc.Name == "UTF-32BE"
	low := uint16(readUnit(body[offset+width:], width, bigEndian))
	if low < 0xdc00 || low > 0xdfff {
		return 0, 0, &UndecodableError{Encoding: enc, Offset: position}
	}
	return utf16.DecodeRune(rune(unit), rune(low)), width, nil
}

// readUnit reads one UTF-16 or UTF-32 unit.
func readUnit(body []byte, width int, bigEndian bool) uint32 {
	value := uint32(0)
	if bigEndian {
		for index := 0; index < width; index++ {
			value = value<<8 | uint32(body[index])
		}
		return value
	}
	for index := width - 1; index >= 0; index-- {
		value = value<<8 | uint32(body[index])
	}
	return value
}

// decodeDeclared decodes data with the encoding a declaration named.
func decodeDeclared(data []byte, declared resolved) ([]byte, Encoding, error) {
	switch {
	case declared.enc.Name == "UTF-8":
		if !utf8.Valid(data) {
			return nil, Encoding{}, &UndecodableError{Encoding: declared.enc, Offset: firstInvalidOffset(data)}
		}
		return data, declared.enc, nil
	case strictASCII(declared.enc.Name):
		if offset := firstNonASCII(data); offset >= 0 {
			return nil, Encoding{}, &UndecodableError{Encoding: declared.enc, Offset: offset}
		}
		return data, declared.enc, nil
	}
	// Valid UTF-8 under a legacy declaration is a conflict, not a legacy encoded file.
	if utf8.Valid(data) && hasHighByte(data) {
		return nil, Encoding{}, &DeclarationConflictError{Declared: declared.enc}
	}
	if len(data) > maxDeclaredSize {
		return nil, Encoding{}, &TooLargeError{Encoding: declared.enc, Size: int64(len(data))}
	}
	text, _, err := transform.Bytes(declared.xtext.NewDecoder(), data)
	reencoded, _, encodeErr := transform.Bytes(declared.xtext.NewEncoder(), text)
	if err == nil && encodeErr == nil && bytes.Equal(reencoded, data) {
		return text, declared.enc, nil
	}
	return nil, Encoding{}, &UndecodableError{Encoding: declared.enc, Offset: firstLossyOffset(data, reencoded)}
}

// firstLossyOffset reports where the re-encoded bytes stop matching the original, so the report
// points at the first byte the declared encoding cannot reproduce. transform.Bytes keeps the
// output written before an encoder error, so a partial result still identifies that byte.
func firstLossyOffset(data, reencoded []byte) int {
	limit := min(len(data), len(reencoded))
	for offset := 0; offset < limit; offset++ {
		if data[offset] != reencoded[offset] {
			return offset
		}
	}
	return limit
}
