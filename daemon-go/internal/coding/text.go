package coding

import (
	"bytes"
	"errors"
	"fmt"
	"strings"
	"unicode/utf16"
	"unicode/utf8"
)

const (
	DefaultReadLimit  = 2000
	MaxReadLimit      = 2000
	MaxReadCodePoints = 60000
)

type DecodedText struct {
	Text     string
	Charset  string
	BOMBytes int
}

type WindowOptions struct {
	Offset       int
	Limit        int
	ColumnOffset int
}

type TextWindow struct {
	Body             string
	StartLine        int
	StartColumn      int
	EndLine          int
	EndColumn        int
	Empty            bool
	EndsWithNewline  *bool
	Truncated        bool
	TruncationReason string
	NextLine         int
	NextColumn       int
}

// DecodeText strictly decodes UTF-8 or BOM-marked UTF-16 and rejects binary text.
func DecodeText(data []byte) (DecodedText, error) {
	charset, bom := charsetOf(data)
	body := data[bom:]
	if charset == "UTF-8" {
		if !utf8.Valid(body) || LooksBinary(body, false) {
			return DecodedText{}, errors.New("file appears to be binary")
		}
		return DecodedText{Text: string(body), Charset: charset, BOMBytes: bom}, nil
	}
	if len(body)%2 != 0 {
		return DecodedText{}, errors.New("file appears to be binary")
	}
	units := make([]uint16, len(body)/2)
	for i := range units {
		if charset == "UTF-16LE" {
			units[i] = uint16(body[2*i]) | uint16(body[2*i+1])<<8
		} else {
			units[i] = uint16(body[2*i])<<8 | uint16(body[2*i+1])
		}
	}
	for i, u := range units {
		if u >= 0xd800 && u <= 0xdbff {
			if i+1 >= len(units) || units[i+1] < 0xdc00 || units[i+1] > 0xdfff {
				return DecodedText{}, errors.New("file appears to be binary")
			}
		} else if u >= 0xdc00 && u <= 0xdfff && (i == 0 || units[i-1] < 0xd800 || units[i-1] > 0xdbff) {
			return DecodedText{}, errors.New("file appears to be binary")
		}
	}
	return DecodedText{Text: string(utf16.Decode(units)), Charset: charset, BOMBytes: bom}, nil
}

// EncodeText restores the charset and BOM detected by DecodeText.
func EncodeText(text, charset string, bomBytes int) ([]byte, error) {
	if !utf8.ValidString(text) {
		return nil, fmt.Errorf("text cannot be losslessly encoded in charset %s", charset)
	}
	var body []byte
	switch charset {
	case "UTF-8":
		body = []byte(text)
	case "UTF-16LE", "UTF-16BE":
		for _, u := range utf16.Encode([]rune(text)) {
			if charset == "UTF-16LE" {
				body = append(body, byte(u), byte(u>>8))
			} else {
				body = append(body, byte(u>>8), byte(u))
			}
		}
	default:
		return nil, fmt.Errorf("text cannot be losslessly encoded in charset %s", charset)
	}
	if bomBytes == 0 {
		return body, nil
	}
	switch charset {
	case "UTF-8":
		return append([]byte{0xef, 0xbb, 0xbf}, body...), nil
	case "UTF-16LE":
		return append([]byte{0xff, 0xfe}, body...), nil
	case "UTF-16BE":
		return append([]byte{0xfe, 0xff}, body...), nil
	default:
		return nil, fmt.Errorf("text cannot be losslessly encoded in charset %s", charset)
	}
}

// LooksBinary follows ripgrep's control-byte heuristic; UTF-16 is explicitly allowed.
func LooksBinary(data []byte, utf16Encoded bool) bool {
	if utf16Encoded {
		return false
	}
	for _, b := range data {
		if b == 0 || (b < 0x20 && b != '\t' && b != '\n' && b != '\r' && b != '\f' && b != 0x1b) {
			return true
		}
	}
	return false
}

// ReadTextWindow projects a validated read window and never returns more than the
// requested line or Unicode code-point budget.
func ReadTextWindow(text string, options WindowOptions) (TextWindow, error) {
	if options.Offset < 1 || options.Offset > 2147483647 {
		return TextWindow{}, errors.New("offset must be a positive integer")
	}
	if options.Limit == 0 {
		options.Limit = DefaultReadLimit
	}
	if options.Limit < 1 || options.Limit > MaxReadLimit {
		return TextWindow{}, errors.New("limit must be a positive integer no greater than 2000")
	}
	if options.ColumnOffset < 0 {
		return TextWindow{}, errors.New("column_offset must be a positive integer")
	}
	if options.ColumnOffset == 0 {
		options.ColumnOffset = 1
	}
	text = stringsReplaceCRLF(text)
	lines := splitLines(text)
	if options.Offset > len(lines) {
		ends := strings.HasSuffix(text, "\n")
		return TextWindow{Empty: true, EndsWithNewline: &ends}, nil
	}
	start := options.Offset - 1
	end := min(len(lines), start+options.Limit)
	selected := append([]string(nil), lines[start:end]...)
	firstRunes := []rune(selected[0])
	columnIndex := options.ColumnOffset - 1
	if columnIndex >= len(firstRunes) && len(firstRunes) > 0 || columnIndex > 0 && len(firstRunes) == 0 {
		return TextWindow{}, fmt.Errorf("column_offset %d is out of range: line %d has %d columns", options.ColumnOffset, options.Offset, len(firstRunes))
	}
	selected[0] = string(firstRunes[columnIndex:])
	truncated, reason := false, ""
	count := 0
	for i, line := range selected {
		runes := []rune(line)
		if count+len(runes) > MaxReadCodePoints {
			selected[i] = string(runes[:MaxReadCodePoints-count])
			selected = selected[:i+1]
			truncated, reason = true, "character_limit"
			break
		}
		count += len(runes)
	}
	if !truncated && count >= MaxReadCodePoints && end < len(lines) {
		truncated, reason = true, "character_limit"
	}
	if !truncated && end < len(lines) {
		truncated, reason = true, "line_limit"
	}
	startColumn := 1
	if start == 0 {
		startColumn = options.ColumnOffset
	}
	lastColumn := 1
	if len(selected) > 0 {
		lastStartColumn := 1
		if start+len(selected)-1 == start {
			lastStartColumn = options.ColumnOffset
		}
		lastColumn = max(1, lastStartColumn-1+len([]rune(selected[len(selected)-1])))
	}
	window := TextWindow{Body: numberedLines(selected, start+1), StartLine: start + 1, StartColumn: startColumn, EndLine: start + len(selected), EndColumn: lastColumn, Truncated: truncated, TruncationReason: reason}
	if !truncated {
		ends := strings.HasSuffix(text, "\n")
		window.EndsWithNewline = &ends
	}
	if truncated {
		if reason == "character_limit" {
			window.NextLine = window.EndLine
			window.NextColumn = window.EndColumn + 1
		} else {
			window.NextLine = window.EndLine + 1
			window.NextColumn = 1
		}
	}
	return window, nil
}

func stringsReplaceCRLF(s string) string {
	return strings.ReplaceAll(strings.ReplaceAll(s, "\r\n", "\n"), "\r", "\n")
}

func splitLines(s string) []string {
	if s == "" {
		return nil
	}
	parts := bytes.Split([]byte(s), []byte("\n"))
	if len(parts) > 1 && len(parts[len(parts)-1]) == 0 {
		parts = parts[:len(parts)-1]
	}
	lines := make([]string, len(parts))
	for i := range parts {
		lines[i] = string(parts[i])
	}
	return lines
}

func numberedLines(lines []string, start int) string {
	width := len(fmt.Sprint(start + len(lines) - 1))
	var out strings.Builder
	for i, line := range lines {
		if i > 0 {
			out.WriteByte('\n')
		}
		fmt.Fprintf(&out, "%*d|%s", width, start+i, line)
	}
	return out.String()
}
