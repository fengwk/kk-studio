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
)

var fileBuffers = sync.Pool{New: func() any { buffer := make([]byte, 0, 256*1024); return &buffer }}
var foldedBuffers = sync.Pool{New: func() any { buffer := make([]byte, 0, 256*1024); return &buffer }}

// readPooled performs one open and reuses the per-worker whole-file buffer.
func readPooled(path string) ([]byte, func(), error) {
	data, release, _, err := readPooledContext(context.Background(), path)
	return data, release, err
}

func readPooledContext(ctx context.Context, path string) ([]byte, func(), bool, error) {
	buf := (*fileBuffers.Get().(*[]byte))[:0]
	f, err := os.Open(path)
	if err != nil {
		fileBuffers.Put(&buf)
		return nil, func() {}, false, err
	}
	released := false
	release := func() {
		if !released {
			releaseBuffer(buf)
			released = true
		}
	}
	for {
		if contextErr := ctx.Err(); contextErr != nil {
			closeErr := f.Close()
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
		n, readErr := f.Read(buf[len(buf):cap(buf)])
		buf = buf[:len(buf)+n]
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			_ = f.Close()
			release()
			return nil, func() {}, false, readErr
		}
		if n == 0 {
			break
		}
	}
	if err := f.Close(); err != nil {
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
	if len(data) == 0 || binary(data) {
		return nil
	}
	lines := &lineCollector{data: data, path: path, end: lineEnd(data, 0), number: 1, lastOutput: -1}
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
	lines.end = lineEnd(normalized, 0)
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
	out                            []string
}

func (c *lineCollector) bounds(pos int) (int, int, bool) {
	if pos < 0 || pos > len(c.data) || (pos == len(c.data) && c.data[len(c.data)-1] == '\n') {
		return c.start, c.end, false
	}
	for pos > c.end && c.end < len(c.data) {
		c.start = c.end + 1
		c.number++
		c.end = lineEnd(c.data, c.start)
	}
	return c.start, c.end, c.start <= pos && pos <= c.end
}
func (c *lineCollector) add(pos int) {
	start, end, ok := c.bounds(pos)
	if !ok || start == c.lastOutput {
		return
	}
	text := bytes.TrimSuffix(c.data[start:end], []byte{'\r'})
	c.out = append(c.out, fmt.Sprintf("%s:%d:%s", c.path, c.number, string(text)))
	c.lastOutput = start
}
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
