package textcodec

import (
	"unicode/utf16"
	"unicode/utf8"

	"golang.org/x/text/encoding"
	"golang.org/x/text/transform"
)

// Encode encodes text for enc, restoring its byte order mark; it never substitutes characters.
func Encode(text string, enc Encoding) ([]byte, error) {
	body, err := encodeBody(text, enc)
	if err != nil {
		return nil, err
	}
	prefix := bomPrefix(enc)
	if len(prefix) == 0 {
		return body, nil
	}
	out := make([]byte, 0, len(prefix)+len(body))
	out = append(out, prefix...)
	return append(out, body...), nil
}

// encodeBody encodes text without its byte order mark.
func encodeBody(text string, enc Encoding) ([]byte, error) {
	switch {
	case enc.Name == "UTF-8":
		if offset := firstInvalidStringOffset(text); offset >= 0 {
			return nil, &UnrepresentableError{Encoding: enc, Char: utf8.RuneError, Line: lineAt(text, offset)}
		}
		return []byte(text), nil
	case strictASCII(enc.Name):
		for offset, value := range text {
			if value >= utf8.RuneSelf {
				return nil, &UnrepresentableError{Encoding: enc, Char: value, Line: lineAt(text, offset)}
			}
		}
		return []byte(text), nil
	case isWideName(enc.Name):
		return encodeWide(text, enc)
	}
	xtext, err := encodingByName(enc.Name)
	if err != nil || xtext == nil {
		return nil, &UnsupportedLabelError{Label: enc.Name, Declaration: enc.Declaration}
	}
	return encodeLegacy(text, enc, xtext)
}

// encodeLegacy encodes text with a legacy encoding and verifies that the bytes reproduce it:
// some encoders map several characters to the same bytes, which would silently change the text.
func encodeLegacy(text string, enc Encoding, xtext encoding.Encoding) ([]byte, error) {
	encoded, source, err := transform.Bytes(xtext.NewEncoder(), []byte(text))
	if err != nil {
		return nil, unrepresentableAt(text, source, enc)
	}
	if err := checkEncoded(text, encoded, xtext, enc); err != nil {
		return nil, err
	}
	return encoded, nil
}

// checkEncoded reports the first character of text the encoded bytes do not reproduce.
func checkEncoded(text string, encoded []byte, xtext encoding.Encoding, enc Encoding) error {
	reproduced, _, decodeErr := transform.Bytes(xtext.NewDecoder(), encoded)
	if decodeErr == nil && string(reproduced) == text {
		return nil
	}
	for offset, value := range text {
		decoded, size := utf8.DecodeRune(reproduced)
		if size == 0 || decoded != value {
			return &UnrepresentableError{Encoding: enc, Char: value, Line: lineAt(text, offset)}
		}
		reproduced = reproduced[size:]
	}
	return &UnrepresentableError{Encoding: enc, Char: utf8.RuneError, Line: lineAt(text, len(text))}
}

// encodeWide encodes text as UTF-16 or UTF-32.
func encodeWide(text string, enc Encoding) ([]byte, error) {
	width := wideWidth(enc.Name)
	bigEndian := enc.Name == "UTF-16BE" || enc.Name == "UTF-32BE"
	out := make([]byte, 0, len(text)*width)
	for offset := 0; offset < len(text); {
		value, size := utf8.DecodeRuneInString(text[offset:])
		if value == utf8.RuneError && size <= 1 {
			return nil, &UnrepresentableError{Encoding: enc, Char: utf8.RuneError, Line: lineAt(text, offset)}
		}
		if width == 2 && value > 0xffff {
			high, low := utf16.EncodeRune(value)
			out = appendUnit(out, uint32(high), width, bigEndian)
			out = appendUnit(out, uint32(low), width, bigEndian)
		} else {
			out = appendUnit(out, uint32(value), width, bigEndian)
		}
		offset += size
	}
	return out, nil
}

// appendUnit appends one unit of width bytes in the requested byte order.
func appendUnit(out []byte, value uint32, width int, bigEndian bool) []byte {
	if bigEndian {
		for shift := width - 1; shift >= 0; shift-- {
			out = append(out, byte(value>>(8*uint(shift))))
		}
		return out
	}
	for shift := 0; shift < width; shift++ {
		out = append(out, byte(value>>(8*uint(shift))))
	}
	return out
}

// unrepresentableAt reports the character that stopped a legacy encoder at source offset.
func unrepresentableAt(text string, offset int, enc Encoding) error {
	value, _ := utf8.DecodeRuneInString(text[min(max(offset, 0), len(text)):])
	return &UnrepresentableError{Encoding: enc, Char: value, Line: lineAt(text, offset)}
}

// firstInvalidStringOffset reports the offset of the first byte that is not part of a valid
// UTF-8 sequence, or -1 when the whole string is valid.
func firstInvalidStringOffset(text string) int {
	for offset := 0; offset < len(text); {
		value, size := utf8.DecodeRuneInString(text[offset:])
		if value == utf8.RuneError && size <= 1 {
			return offset
		}
		offset += size
	}
	return -1
}
