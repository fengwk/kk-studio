package lsp

import (
	"encoding/binary"
	"errors"
	"fmt"
	"strings"
	"unicode/utf16"
	"unicode/utf8"
)

const probeBytes = 8192

// DecodedText is a strictly decoded source file and its original encoding metadata.
type DecodedText struct {
	Text      string
	Charset   string
	BOMLength int
}

// DecodeTextFile decodes supported Unicode text and rejects binary or malformed input.
func DecodeTextFile(data []byte) (DecodedText, error) {
	var text, charset string
	bom := 0
	switch {
	case len(data) >= 3 && string(data[:3]) == "\xef\xbb\xbf":
		charset, bom = "utf-8", 3
		if !utf8.Valid(data[3:]) {
			return DecodedText{}, errors.New("file appears to be binary: invalid text encoding")
		}
		text = string(data[3:])
	case len(data) >= 2 && data[0] == 0xff && data[1] == 0xfe:
		charset, bom = "utf-16le", 2
		decoded, err := decodeUTF16(data[2:], binary.LittleEndian)
		if err != nil {
			return DecodedText{}, errors.New("file appears to be binary: invalid text encoding")
		}
		text = decoded
	case len(data) >= 2 && data[0] == 0xfe && data[1] == 0xff:
		charset, bom = "utf-16be", 2
		decoded, err := decodeUTF16(data[2:], binary.BigEndian)
		if err != nil {
			return DecodedText{}, errors.New("file appears to be binary: invalid text encoding")
		}
		text = decoded
	default:
		charset = "utf-8"
		limit := len(data)
		if limit > probeBytes {
			limit = probeBytes
		}
		for _, b := range data[:limit] {
			if b == 0 {
				return DecodedText{}, errors.New("file appears to be binary: contains NUL character")
			}
			if b < 0x09 || (b > 0x0d && b < 0x20 && b != 0x1b) {
				return DecodedText{}, errors.New("file appears to be binary")
			}
		}
		if !utf8.Valid(data) {
			return DecodedText{}, errors.New("file appears to be binary: invalid text encoding")
		}
		text = string(data)
	}
	text = strings.TrimPrefix(text, "\ufeff")
	for _, r := range text {
		if r == 0 {
			return DecodedText{}, errors.New("file appears to be binary: contains NUL character")
		}
	}
	return DecodedText{Text: text, Charset: charset, BOMLength: bom}, nil
}

func decodeUTF16(data []byte, order binary.ByteOrder) (string, error) {
	if len(data)%2 != 0 {
		return "", fmt.Errorf("odd UTF-16 byte length")
	}
	units := make([]uint16, len(data)/2)
	for i := range units {
		units[i] = order.Uint16(data[i*2:])
	}
	for i := 0; i < len(units); i++ {
		if units[i] >= 0xdc00 && units[i] <= 0xdfff {
			return "", fmt.Errorf("unpaired UTF-16 surrogate")
		}
		if units[i] >= 0xd800 && units[i] <= 0xdbff {
			if i+1 == len(units) || units[i+1] < 0xdc00 || units[i+1] > 0xdfff {
				return "", fmt.Errorf("unpaired UTF-16 surrogate")
			}
			i++
		}
	}
	return string(utf16.Decode(units)), nil
}
