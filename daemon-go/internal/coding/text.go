package coding

import (
	"errors"
	"fmt"
	"io"
	"strings"
	"unicode/utf8"

	"github.com/fengwk/kk-studio/daemon/internal/textcodec"
)

const (
	DefaultReadLimit  = 2000
	MaxReadLimit      = 2000
	MaxReadCodePoints = 60000
)

type WindowOptions struct {
	Path         string
	Offset       int
	Limit        int
	ColumnOffset int
}

type TextWindow struct {
	Body             string
	Encoding         string
	LSPStatus        string
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
	reader, err := textcodec.NewReader(options.Path, source)
	if err != nil {
		return TextWindow{}, codecError(options.Path, err, codecRead)
	}
	scanner := textWindowScanner{options: options, line: 1, endLine: options.Offset + options.Limit - 1}
	finish := func(eof bool) (TextWindow, error) {
		window, finishErr := scanner.finish(eof)
		if finishErr != nil {
			return TextWindow{}, finishErr
		}
		window.Encoding = reader.Encoding().Name
		return window, nil
	}
	for {
		value, _, readErr := reader.ReadRune()
		if readErr != nil {
			if !errors.Is(readErr, io.EOF) {
				if scanner.line > scanner.endLine {
					scanner.stopAtLineLimit()
					return finish(false)
				}
				if scanner.captured == MaxReadCodePoints {
					scanner.stopAtCharacterLimit()
					return finish(false)
				}
				if !isCodecFailure(readErr) {
					return TextWindow{}, readErr
				}
				return TextWindow{}, codecError(options.Path, readErr, codecRead)
			}
			return finish(true)
		}
		stop, scanErr := scanner.push(value)
		if scanErr != nil {
			return TextWindow{}, scanErr
		}
		if stop {
			return finish(false)
		}
	}
}

type textWindowScanner struct {
	options          WindowOptions
	line             int
	endLine          int
	column           int
	lines            []string
	current          strings.Builder
	captured         int
	lastLineCut      bool
	endedWithNewline bool
	truncated        bool
	reason           string
	skipLF           bool
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
	s.endedWithNewline = false
	s.column++
	if s.line < s.options.Offset || s.line == s.options.Offset && s.column < s.options.ColumnOffset {
		return false, nil
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
		s.lines = append(s.lines, s.current.String())
	}
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
		s.lines = append(s.lines, s.current.String())
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
