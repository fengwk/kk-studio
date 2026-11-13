package textcodec

import (
	"bytes"
	"unicode/utf16"
	"unicode/utf8"

	"golang.org/x/text/encoding"
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
		position := len(prefix) + offset
		value := readUnit(body[offset:], width, bigEndian)
		if width == 4 {
			if value > utf8.MaxRune || value >= 0xd800 && value <= 0xdfff {
				return nil, &UndecodableError{Encoding: enc, Offset: position}
			}
			text = utf8.AppendRune(text, rune(value))
			continue
		}
		unit := uint16(value)
		if unit >= 0xdc00 && unit <= 0xdfff {
			return nil, &UndecodableError{Encoding: enc, Offset: position}
		}
		if unit < 0xd800 || unit > 0xdbff {
			text = utf8.AppendRune(text, rune(unit))
			continue
		}
		if offset+width >= len(body) {
			return nil, &UndecodableError{Encoding: enc, Offset: position}
		}
		low := uint16(readUnit(body[offset+width:], width, bigEndian))
		if low < 0xdc00 || low > 0xdfff {
			return nil, &UndecodableError{Encoding: enc, Offset: position}
		}
		text = utf8.AppendRune(text, utf16.DecodeRune(rune(unit), rune(low)))
		offset += width
	}
	return text, nil
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
	if err == nil {
		reencoded, _, encodeErr := transform.Bytes(declared.xtext.NewEncoder(), text)
		if encodeErr == nil && bytes.Equal(reencoded, data) {
			return text, declared.enc, nil
		}
	}
	return nil, Encoding{}, &UndecodableError{Encoding: declared.enc, Offset: firstLossyOffset(data, declared.xtext)}
}

// firstLossyOffset returns the smallest offset at which decoding and re-encoding data stops
// reproducing the original bytes, so a report points at the first byte that differs.
func firstLossyOffset(data []byte, xtext encoding.Encoding) int {
	low, high := 0, len(data)
	for high-low > 1 {
		middle := low + (high-low)/2
		if roundTrips(data[:middle], xtext) {
			low = middle
		} else {
			high = middle
		}
	}
	return max(high-1, 0)
}

// roundTrips reports whether a prefix of a file decodes and re-encodes to itself.
func roundTrips(data []byte, xtext encoding.Encoding) bool {
	text, _, err := transform.Bytes(xtext.NewDecoder(), data)
	if err != nil {
		return false
	}
	reencoded, _, err := transform.Bytes(xtext.NewEncoder(), text)
	return err == nil && bytes.Equal(reencoded, data)
}
