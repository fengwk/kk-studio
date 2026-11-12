package coding

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"io"
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
			return DecodedText{}, failRun("file appears to be binary", "CODEC")
		}
		return DecodedText{Text: string(body), Charset: charset, BOMBytes: bom}, nil
	}
	if len(body)%2 != 0 {
		return DecodedText{}, failRun("file appears to be binary", "CODEC")
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
				return DecodedText{}, failRun("file appears to be binary", "CODEC")
			}
		} else if u >= 0xdc00 && u <= 0xdfff && (i == 0 || units[i-1] < 0xd800 || units[i-1] > 0xdbff) {
			return DecodedText{}, failRun("file appears to be binary", "CODEC")
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

// ReadTextWindow projects a string through the same bounded reader used by fs.read.
func ReadTextWindow(text string, options WindowOptions) (TextWindow, error) {
	return ReadTextWindowReader(strings.NewReader(text), options)
}

// ReadTextWindowReader decodes and scans only the requested window plus the small
// lookahead needed to distinguish truncation from EOF.
func ReadTextWindowReader(source io.Reader, options WindowOptions) (TextWindow, error) {
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
	reader := bufio.NewReaderSize(source, 8192)
	charset, err := detectReaderCharset(reader)
	if err != nil {
		return TextWindow{}, err
	}
	scanner := textWindowScanner{options: options, line: 1, endLine: options.Offset + options.Limit - 1, rejectBinaryControls: charset == "UTF-8"}
	for {
		value, eof, readErr := readTextRune(reader, charset)
		if readErr != nil {
			if scanner.line > scanner.endLine {
				scanner.stopAtLineLimit()
				return scanner.finish(false)
			}
			if scanner.captured == MaxReadCodePoints {
				scanner.stopAtCharacterLimit()
				return scanner.finish(false)
			}
			return TextWindow{}, readErr
		}
		if eof {
			return scanner.finish(true)
		}
		stop, scanErr := scanner.push(value)
		if scanErr != nil {
			return TextWindow{}, scanErr
		}
		if stop {
			return scanner.finish(false)
		}
	}
}

type textWindowScanner struct {
	options              WindowOptions
	line                 int
	endLine              int
	column               int
	lines                []string
	current              strings.Builder
	captured             int
	lastLineCut          bool
	endedWithNewline     bool
	truncated            bool
	reason               string
	skipLF               bool
	rejectBinaryControls bool
}

func (s *textWindowScanner) push(value rune) (bool, error) {
	if s.skipLF {
		s.skipLF = false
		if value == '\n' {
			return false, nil
		}
	}
	if value == '\r' || value == '\n' {
		if s.line > s.endLine {
			s.stopAtLineLimit()
			return true, nil
		}
		if err := s.finishLine(); err != nil {
			return false, err
		}
		s.line++
		s.column = 0
		s.current.Reset()
		s.endedWithNewline = true
		s.skipLF = value == '\r'
		return false, nil
	}
	if s.line > s.endLine {
		s.stopAtLineLimit()
		return true, nil
	}
	if s.captured == MaxReadCodePoints {
		s.stopAtCharacterLimit()
		return true, nil
	}
	if value == 0 {
		return false, failRun("file appears to be binary", "CODEC")
	}
	s.endedWithNewline = false
	s.column++
	if s.line < s.options.Offset || s.line == s.options.Offset && s.column < s.options.ColumnOffset {
		return false, nil
	}
	if s.rejectBinaryControls && value < 0x20 && value != '\t' && value != '\n' && value != '\r' && value != '\f' && value != 0x1b {
		return false, failRun("file appears to be binary", "CODEC")
	}
	if len(s.lines) == s.options.Limit {
		s.stopAtLineLimit()
		return true, nil
	}
	s.current.WriteRune(value)
	s.captured++
	return false, nil
}

func (s *textWindowScanner) finishLine() error {
	if s.line == s.options.Offset && (s.column == 0 && s.options.ColumnOffset > 1 || s.column > 0 && s.column < s.options.ColumnOffset) {
		return fmt.Errorf("column_offset %d is out of range: line %d has %d columns", s.options.ColumnOffset, s.line, s.column)
	}
	if s.line >= s.options.Offset && s.line <= s.endLine && len(s.lines) < s.options.Limit {
		return s.finishLineContent()
	}
	return nil
}

func (s *textWindowScanner) finishLineContent() error {
	s.lines = append(s.lines, s.current.String())
	return nil
}

func (s *textWindowScanner) stopAtLineLimit() {
	s.truncated = true
	s.reason = "line_limit"
	if s.captured == MaxReadCodePoints {
		s.reason = "character_limit"
	}
}

func (s *textWindowScanner) stopAtCharacterLimit() {
	s.truncated = true
	s.reason = "character_limit"
	lastLine := s.options.Offset + len(s.lines)
	if s.line == lastLine && s.current.Len() > 0 {
		s.lastLineCut = true
		_ = s.finishLineContent()
	}
}

func (s *textWindowScanner) finish(eof bool) (TextWindow, error) {
	if eof && s.column > 0 && s.line >= s.options.Offset && s.line <= s.endLine && len(s.lines) < s.options.Limit {
		if err := s.finishLine(); err != nil {
			return TextWindow{}, err
		}
	}
	if len(s.lines) == 0 {
		if !eof {
			return TextWindow{}, errors.New("bounded text scan stopped before collecting a window")
		}
		return TextWindow{Empty: true, EndsWithNewline: &s.endedWithNewline}, nil
	}
	endLine := s.options.Offset + len(s.lines) - 1
	startColumn := s.options.ColumnOffset
	lastStartColumn := 1
	if endLine == s.options.Offset {
		lastStartColumn = s.options.ColumnOffset
	}
	endColumn := max(1, lastStartColumn-1+utf8.RuneCountInString(s.lines[len(s.lines)-1]))
	window := TextWindow{Body: numberedLines(s.lines, s.options.Offset), StartLine: s.options.Offset, StartColumn: startColumn, EndLine: endLine, EndColumn: endColumn, Truncated: s.truncated, TruncationReason: s.reason}
	if s.truncated {
		if s.lastLineCut {
			window.NextLine = endLine
			window.NextColumn = endColumn + 1
		} else {
			window.NextLine = endLine + 1
			window.NextColumn = 1
		}
	} else if eof {
		window.EndsWithNewline = &s.endedWithNewline
	}
	return window, nil
}

func detectReaderCharset(reader *bufio.Reader) (string, error) {
	prefix, _ := reader.Peek(3)
	if len(prefix) >= 3 && bytes.Equal(prefix[:3], []byte{0xef, 0xbb, 0xbf}) {
		_, err := reader.Discard(3)
		return "UTF-8", err
	}
	if len(prefix) >= 2 && bytes.Equal(prefix[:2], []byte{0xff, 0xfe}) {
		_, err := reader.Discard(2)
		return "UTF-16LE", err
	}
	if len(prefix) >= 2 && bytes.Equal(prefix[:2], []byte{0xfe, 0xff}) {
		_, err := reader.Discard(2)
		return "UTF-16BE", err
	}
	return "UTF-8", nil
}

func readTextRune(reader *bufio.Reader, charset string) (rune, bool, error) {
	if charset == "UTF-8" {
		value, size, err := reader.ReadRune()
		if err == io.EOF {
			return 0, true, nil
		}
		if err != nil {
			return 0, false, err
		}
		if value == utf8.RuneError && size == 1 {
			return 0, false, failRun("file appears to be binary", "CODEC")
		}
		return value, false, nil
	}
	readUnit := func() (uint16, bool, error) {
		var pair [2]byte
		n, err := io.ReadFull(reader, pair[:])
		if err == io.EOF && n == 0 {
			return 0, true, nil
		}
		if err != nil {
			return 0, false, failRun("file appears to be binary", "CODEC")
		}
		if charset == "UTF-16LE" {
			return uint16(pair[0]) | uint16(pair[1])<<8, false, nil
		}
		return uint16(pair[0])<<8 | uint16(pair[1]), false, nil
	}
	first, eof, err := readUnit()
	if err != nil || eof {
		return 0, eof, err
	}
	if first >= 0xdc00 && first <= 0xdfff {
		return 0, false, failRun("file appears to be binary", "CODEC")
	}
	if first < 0xd800 || first > 0xdbff {
		return rune(first), false, nil
	}
	second, eof, err := readUnit()
	if err != nil || eof || second < 0xdc00 || second > 0xdfff {
		return 0, false, failRun("file appears to be binary", "CODEC")
	}
	return utf16.DecodeRune(rune(first), rune(second)), false, nil
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
