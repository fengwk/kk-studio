package search

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"os"
	"regexp"
	"regexp/syntax"
	"sync"
	"unicode/utf8"
)

var fileBuffers = sync.Pool{New: func() any { buffer := make([]byte, 0, 256*1024); return &buffer }}
var foldedBuffers = sync.Pool{New: func() any { buffer := make([]byte, 0, 256*1024); return &buffer }}

// readPooled performs one open and reuses the per-worker whole-file buffer.
func readPooled(path string) ([]byte, func(), error) {
	data, release, _, err := readPooledContext(context.Background(), path)
	return data, release, err
}

func readPooledContext(ctx context.Context, path string) ([]byte, func(), bool, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, func() {}, false, err
	}
	return readPooledReader(ctx, f, f.Close)
}

func readPooledReader(ctx context.Context, reader io.Reader, closeReader func() error) ([]byte, func(), bool, error) {
	buf := (*fileBuffers.Get().(*[]byte))[:0]
	released := false
	release := func() {
		if !released {
			releaseBuffer(buf)
			released = true
		}
	}
	for {
		if contextErr := ctx.Err(); contextErr != nil {
			closeErr := closeReader()
			if contextErr == context.DeadlineExceeded {
				return buf, release, true, closeErr
			}
			release()
			return nil, func() {}, false, contextErr
		}
		if len(buf) == cap(buf) {
			next := cap(buf) * 2
			if next < 64*1024 {
				next = 64 * 1024
			}
			grown := make([]byte, len(buf), next)
			copy(grown, buf)
			buf = grown
		}
		n, readErr := reader.Read(buf[len(buf):cap(buf)])
		buf = buf[:len(buf)+n]
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			_ = closeReader()
			release()
			return nil, func() {}, false, readErr
		}
		if n == 0 {
			break
		}
	}
	if err := closeReader(); err != nil {
		release()
		return nil, func() {}, false, err
	}
	return buf, release, false, nil
}

func releaseBuffer(buf []byte) {
	if cap(buf) <= 4*1024*1024 {
		fileBuffers.Put(&buf)
	}
}

func grepFile(data []byte, path, pattern string, literal, ignoreCase bool, re *regexp.Regexp, required []byte) []string {
	if len(data) == 0 {
		return nil
	}
	lines := &lineCollector{data: data, path: path, number: 1, lastOutput: -1}
	lines.end = lines.lineEnd(0)
	if literal && !(ignoreCase && !ascii(pattern)) {
		needle := []byte(pattern)
		if ignoreCase {
			scanASCIIInsensitive(data, needle, lines.add)
		} else {
			scanLiteral(data, needle, lines.add)
		}
		return lines.out
	}
	if len(required) != 0 {
		lastAttempt := -1
		for from := 0; from+len(required) <= len(data); {
			i := bytes.Index(data[from:], required)
			if i < 0 {
				break
			}
			pos := from + i
			start, end, _ := lines.bounds(pos)
			if start != lastAttempt {
				text := data[start:end]
				text = bytes.TrimSuffix(text, []byte{'\r'})
				if re.Match(text) {
					lines.add(pos)
				}
				lastAttempt = start
			}
			from = pos + len(required)
		}
		return lines.out
	}
	// Whole-buffer fallback avoids invoking RE2 once per line when no required
	// literal can safely screen candidates.
	normalized := stripCRLF(data)
	lines.data = normalized
	lines.end = lines.lineEnd(0)
	for _, loc := range re.FindAllIndex(normalized, -1) {
		lines.add(loc[0])
	}
	return lines.out
}

func scanLiteral(data, needle []byte, emit func(int)) {
	if len(needle) == 0 {
		return
	}
	for from := 0; from+len(needle) <= len(data); {
		i := bytes.Index(data[from:], needle)
		if i < 0 {
			return
		}
		pos := from + i
		emit(pos)
		from = pos + len(needle)
	}
}

func scanASCIIInsensitive(data, needle []byte, emit func(int)) {
	if len(needle) == 0 {
		return
	}
	folded := *foldedBuffers.Get().(*[]byte)
	if cap(folded) < len(data) {
		folded = make([]byte, len(data))
	} else {
		folded = folded[:len(data)]
	}
	for i, b := range data {
		folded[i] = asciiFold(b)
	}
	var fixed [256]byte
	var foldNeedle []byte
	if len(needle) <= len(fixed) {
		foldNeedle = fixed[:len(needle)]
	} else {
		foldNeedle = make([]byte, len(needle))
	}
	for i, b := range needle {
		foldNeedle[i] = asciiFold(b)
	}
	for from := 0; from+len(needle) <= len(folded); {
		i := bytes.Index(folded[from:], foldNeedle)
		if i < 0 {
			break
		}
		pos := from + i
		emit(pos)
		from = pos + len(needle)
	}
	if cap(folded) <= 4*1024*1024 {
		foldedBuffers.Put(&folded)
	}
}

func asciiFold(b byte) byte {
	if b >= 'A' && b <= 'Z' {
		return b + ('a' - 'A')
	}
	return b
}
func ascii(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] >= 0x80 {
			return false
		}
	}
	return true
}

func requiredLiteralPattern(pattern string) []byte {
	ast, err := syntax.Parse(pattern, syntax.Perl)
	if err != nil {
		return nil
	}
	return requiredLiteral(ast)
}

// crossLinePattern mirrors Grep's compiled flags when deciding whether the pattern can span lines.
func crossLinePattern(pattern string, literal, ignoreCase bool) bool {
	if literal {
		return bytes.Contains([]byte(pattern), []byte{'\n'})
	}
	body := pattern
	flags := "(?m)"
	if ignoreCase {
		flags = "(?im)"
	}
	ast, err := syntax.Parse(flags+"(?:"+body+")", syntax.Perl)
	return err == nil && hasCrossLineOperator(ast)
}

func hasCrossLineOperator(ast *syntax.Regexp) bool {
	if ast.Op == syntax.OpAnyChar {
		return true
	}
	if ast.Op == syntax.OpLiteral {
		for _, r := range ast.Rune {
			if r == '\n' {
				return true
			}
		}
	}
	for _, sub := range ast.Sub {
		if hasCrossLineOperator(sub) {
			return true
		}
	}
	return false
}

func grepMultilineFile(data []byte, path string, re *regexp.Regexp, maxMatches int) ([]string, int) {
	if len(data) == 0 {
		return nil, 0
	}
	normalized := stripCRLF(data)
	locations := re.FindAllIndex(normalized, -1)
	lines := &lineCollector{data: normalized, path: path, number: 1, lastOutput: -1}
	lines.end = lines.lineEnd(0)
	seen := make(map[int]struct{})
	for matchIndex, loc := range locations {
		if maxMatches < 0 || maxMatches > 0 && matchIndex >= maxMatches {
			break
		}
		if loc[0] == loc[1] {
			continue
		}
		start := loc[0]
		end := loc[1] - 1
		for pos := start; pos <= end; {
			lineStart, _, _ := lines.bounds(pos)
			if _, ok := seen[lineStart]; !ok {
				lines.add(pos)
				seen[lineStart] = struct{}{}
			}
			lineTerminator := lines.lineEnd(pos)
			if lineTerminator > end {
				break
			}
			pos = lineTerminator + 1
		}
	}
	return lines.out, len(locations)
}

// requiredLiteral returns a guaranteed literal fragment from an RE2 syntax tree.
func requiredLiteral(r *syntax.Regexp) []byte {
	switch r.Op {
	case syntax.OpLiteral:
		if r.Flags&syntax.FoldCase != 0 {
			return nil
		}
		return []byte(string(r.Rune))
	case syntax.OpCapture:
		return requiredLiteral(r.Sub[0])
	case syntax.OpConcat:
		var best []byte
		for _, sub := range r.Sub {
			if b := requiredLiteral(sub); len(b) > len(best) {
				best = b
			}
		}
		return best
	case syntax.OpPlus:
		return requiredLiteral(r.Sub[0])
	case syntax.OpRepeat:
		if r.Min > 0 {
			return requiredLiteral(r.Sub[0])
		}
	case syntax.OpAlternate:
		if len(r.Sub) == 0 {
			return nil
		}
		common := requiredLiteral(r.Sub[0])
		for _, sub := range r.Sub[1:] {
			other := requiredLiteral(sub)
			n := 0
			for n < len(common) && n < len(other) && common[n] == other[n] {
				n++
			}
			common = common[:n]
		}
		return common
	}
	return nil
}

type lineCollector struct {
	data                           []byte
	path                           string
	start, end, number, lastOutput int
	crCursor                       int
	crLoaded                       bool
	out                            []string
}

// lineEnd reports where the line that starts at start ends. Like read, CRLF, CR and LF are all
// line breaks, so a lone CR ends a line. The next carriage return is cached because the
// collector only moves forward, which keeps the search to one pass over the scanned text.
func (c *lineCollector) lineEnd(start int) int {
	end := lineEnd(c.data, start)
	if cr := c.nextCarriageReturn(start); cr < end {
		return cr
	}
	return end
}

// nextCarriageReturn reports the first carriage return at or after from, or len(data).
func (c *lineCollector) nextCarriageReturn(from int) int {
	if c.crLoaded && c.crCursor >= from {
		return c.crCursor
	}
	c.crLoaded = true
	c.crCursor = len(c.data)
	if i := bytes.IndexByte(c.data[from:], '\r'); i >= 0 {
		c.crCursor = from + i
	}
	return c.crCursor
}

func (c *lineCollector) bounds(pos int) (int, int, bool) {
	if pos < 0 || pos > len(c.data) || (pos == len(c.data) && c.data[len(c.data)-1] == '\n') {
		return c.start, c.end, false
	}
	for pos > c.end && c.end < len(c.data) {
		c.start = c.end + 1
		if c.start < len(c.data) && c.data[c.end] == '\r' && c.data[c.start] == '\n' {
			c.start++
		}
		c.number++
		c.end = c.lineEnd(c.start)
	}
	return c.start, c.end, c.start <= pos && pos <= c.end
}
func (c *lineCollector) add(pos int) {
	start, end, ok := c.bounds(pos)
	if !ok || start == c.lastOutput {
		return
	}
	line := bytes.TrimSuffix(c.data[start:end], []byte{'\r'})
	text := string(line)
	if len(line) > 440 {
		matchOffset := min(max(0, pos-start), len(line))
		text = centeredExcerpt(text, utf8.RuneCount(line[:matchOffset]))
	}
	c.out = append(c.out, fmt.Sprintf("%s:%d:%s", c.path, c.number, text))
	c.lastOutput = start
}

func centeredExcerpt(line string, matchStart int) string {
	points := []rune(line)
	if len(points) <= 440 {
		return line
	}
	start := max(0, matchStart-220)
	end := min(len(points), start+440)
	if end == len(points) {
		start = max(0, end-440)
	}
	text := string(points[start:end])
	if start > 0 {
		text = "... " + text
	}
	if end < len(points) {
		text += " ... (line truncated to 500 chars)"
	}
	return text
}

// lineEnd reports where the line that starts at start ends when only line feeds count.
func lineEnd(data []byte, start int) int {
	if start >= len(data) {
		return len(data)
	}
	if i := bytes.IndexByte(data[start:], '\n'); i >= 0 {
		return start + i
	}
	return len(data)
}
func stripCRLF(data []byte) []byte {
	w := 0
	for r := 0; r < len(data); r++ {
		if data[r] == '\r' && r+1 < len(data) && data[r+1] == '\n' {
			continue
		}
		data[w] = data[r]
		w++
	}
	return data[:w]
}
