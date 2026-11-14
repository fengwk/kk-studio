package coding

import (
	"errors"
	"fmt"
	"io"
	"strings"
	"sync"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

const (
	inlineOutputBytes    = 50 * 1024
	inlineOutputLines    = 2000
	previewHeadBytes     = 4 * 1024
	previewTailBytes     = 8 * 1024
	defaultCaptureBudget = 1 << 30
)

type outputFile interface {
	io.Writer
	Sync() error
	Close() error
}

// OutputSpool bounds retained process output, spills complete captures and keeps a bounded preview.
type OutputSpool struct {
	mu                                   sync.Mutex
	store                                *TextOutputStore
	callID                               string
	budget                               int64
	memory                               []byte
	file                                 outputFile
	staging                              string
	captured, total, lineBreaks          int64
	last                                 byte
	hasBytes, spilled, truncated, failed bool
	head, tail                           []byte
}

// NewOutputSpool creates a process output collector with protocol limits.
func NewOutputSpool(store *TextOutputStore, callID string, captureBudget int64) (*OutputSpool, error) {
	if store == nil {
		return nil, errors.New("output store is required")
	}
	if captureBudget <= 0 {
		return nil, errors.New("capture budget must be positive")
	}
	return &OutputSpool{store: store, callID: callID, budget: captureBudget}, nil
}

// Write captures output while keeping bounded head and tail previews.
func (s *OutputSpool) Write(data []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(data) == 0 {
		return
	}
	s.observe(data)
	if s.truncated || s.failed {
		return
	}
	if !s.spilled {
		if s.total <= inlineOutputBytes && s.totalLines() <= inlineOutputLines {
			s.memory = append(s.memory, data...)
			return
		}
		if !s.spill() {
			return
		}
	}
	_ = s.writeCaptured(data)
}

// observe counts the bytes and line breaks of data and extends the bounded previews.
func (s *OutputSpool) observe(data []byte) {
	for _, b := range data {
		if b == '\r' {
			s.lineBreaks++
		} else if b == '\n' && s.last != '\r' {
			s.lineBreaks++
		}
		s.last = b
	}
	s.total += int64(len(data))
	s.hasBytes = true
	s.head = appendBoundedHead(s.head, data, previewHeadBytes)
	s.tail = appendBoundedTail(s.tail, data, previewTailBytes)
}

// spill moves the captured output into a staging file and reports whether capture continues.
func (s *OutputSpool) spill() bool {
	s.spilled = true
	file, path, err := s.store.CreateStagingFile(s.callID)
	if err != nil {
		s.failed = true
		s.memory = nil
		return false
	}
	s.file, s.staging = file, path
	if err = s.writeCaptured(s.memory); err != nil {
		s.fail()
		s.memory = nil
		return false
	}
	s.memory = nil
	return true
}

func (s *OutputSpool) totalLines() int64 {
	if !s.hasBytes {
		return 0
	}
	if s.last == '\n' || s.last == '\r' {
		return s.lineBreaks
	}
	return s.lineBreaks + 1
}

// IsCaptureTruncated reports whether capture stopped at the configured byte budget.
func (s *OutputSpool) IsCaptureTruncated() bool { s.mu.Lock(); defer s.mu.Unlock(); return s.truncated }

// CaptureTruncatedTailPreview returns the bounded valid-UTF-8 tail used for a live snapshot.
func (s *OutputSpool) CaptureTruncatedTailPreview() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	start := utf8Prefix(s.tail)
	end := alignUTF8(s.tail, len(s.tail))
	return strings.ToValidUTF8(string(s.tail[start:end]), "�")
}

// Finish finalizes the captured output and returns an inline result or bounded preview.
func (s *OutputSpool) Finish(failed bool, note string) runtime.CapabilityResult {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.spilled {
		text := strings.ToValidUTF8(string(s.memory), "�")
		if note != "" {
			if text != "" && !strings.HasSuffix(text, "\n") {
				text += "\n"
			}
			text += note
		}
		return runtime.CapabilityResult{CallID: s.callID, Error: failed, Contents: []runtime.ResultContent{{Type: "text", Text: text, Size: s.total}}}
	}
	published := s.publish()
	text := s.preview(published)
	if note != "" {
		text += "\n" + note
	}
	textOutput := map[string]any{"totalBytes": s.total, "totalLines": s.totalLines(), "capturedBytes": s.captured, "captureTruncated": s.truncated, "captureFailed": s.failed || published == ""}
	if published != "" {
		textOutput["path"] = published
		textOutput["readHint"] = "read pages the file with offset/limit; grep searches it"
	}
	return runtime.CapabilityResult{CallID: s.callID, Error: failed, Details: mustJSON(map[string]any{"textOutput": textOutput}), Contents: []runtime.ResultContent{{Type: "text", Text: text, Size: s.total}}}
}

// publish syncs and closes the staging file and renames it to its final name, or fails the
// capture. It reports the published path, or "" when the output stays unpublished.
func (s *OutputSpool) publish() string {
	published := ""
	if s.file != nil && !s.failed {
		if err := s.file.Sync(); err != nil {
			s.fail()
		} else if err = s.file.Close(); err != nil {
			s.file = nil
			s.fail()
		} else {
			s.file = nil
			if target, err := s.store.Publish(s.staging); err == nil {
				published = target
				s.staging = ""
			} else {
				s.fail()
			}
		}
	}
	if published == "" {
		s.fail()
	}
	return published
}

// Close removes unpublished staging files and releases their leases.
func (s *OutputSpool) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.file != nil {
		_ = s.file.Close()
		s.file = nil
	}
	if s.staging != "" {
		err := s.store.DeleteStaging(s.staging)
		s.staging = ""
		return err
	}
	return nil
}

func (s *OutputSpool) writeCaptured(data []byte) error {
	remaining := s.budget - s.captured
	if remaining <= 0 {
		s.truncated = true
		return nil
	}
	accepted := len(data)
	if int64(accepted) > remaining {
		accepted = int(remaining)
	}
	if s.file == nil {
		return errors.New("output staging file is not open")
	}
	if err := writeAll(s.file, data[:accepted]); err != nil {
		s.failed = true
		_ = s.file.Close()
		s.file = nil
		return err
	}
	s.captured += int64(accepted)
	if accepted < len(data) {
		s.truncated = true
	}
	return nil
}

func (s *OutputSpool) fail() {
	s.failed = true
	if s.file != nil {
		_ = s.file.Close()
		s.file = nil
	}
	if s.staging != "" {
		_ = s.store.DeleteStaging(s.staging)
		s.staging = ""
	}
}

// preview renders the bounded head and tail around one omission notice and the storage footer.
func (s *OutputSpool) preview(published string) string {
	headEnd := alignUTF8(s.head, len(s.head))
	tailStart := utf8Prefix(s.tail)
	tailEnd := alignUTF8(s.tail, len(s.tail))
	tailAbsolute := s.total - int64(len(s.tail)) + int64(tailStart)
	skip := int64(0)
	if int64(headEnd) > tailAbsolute {
		skip = int64(headEnd) - tailAbsolute
	}
	if skip > int64(tailEnd-tailStart) {
		skip = int64(tailEnd - tailStart)
	}
	if skip >= int64(tailEnd-tailStart) {
		tailStart = 0
		tailEnd = 0
	} else {
		tailStart += int(skip)
	}
	omitted := tailAbsolute + skip - int64(headEnd)
	text := strings.ToValidUTF8(string(s.head[:headEnd]), "�")
	if omitted > 0 {
		text = endWithLineBreak(text)
		text += fmt.Sprintf("[... %d bytes omitted here; the middle of the output is not shown ...]\n", omitted)
	}
	text += strings.ToValidUTF8(string(s.tail[tailStart:tailEnd]), "�")
	if text != "" && !strings.HasSuffix(text, "\n") {
		text += "\n"
	}
	text += "\n"
	if published == "" {
		footer := fmt.Sprintf("[Full output (%d bytes, %d lines) could not be saved to local storage; only this bounded preview is available.", s.total, s.totalLines())
		return text + s.withBudgetStop(footer) + "]"
	}
	footer := fmt.Sprintf("[Full output: %d bytes, %d lines. Saved to: %s\nUse read with offset/limit to page through the file, or grep to search it.]", s.total, s.totalLines(), published)
	return text + s.withBudgetStop(footer)
}

// endWithLineBreak appends a line break unless text is empty or already ends with one.
func endWithLineBreak(text string) string {
	if text != "" && !strings.HasSuffix(text, "\n") {
		return text + "\n"
	}
	return text
}

// withBudgetStop reports the local capture budget when capture stopped at it.
func (s *OutputSpool) withBudgetStop(footer string) string {
	if s.truncated {
		footer += fmt.Sprintf(" Capture stopped at the local %d-byte daemon budget; the command itself ran to completion and its exit code is meaningful.", s.budget)
	}
	return footer
}

func appendBoundedHead(head, data []byte, capacity int) []byte {
	if len(head) >= capacity {
		return head
	}
	n := min(capacity-len(head), len(data))
	return append(head, data[:n]...)
}

func appendBoundedTail(tail, data []byte, capacity int) []byte {
	if len(data) >= capacity {
		return append(tail[:0], data[len(data)-capacity:]...)
	}
	excess := len(tail) + len(data) - capacity
	if excess > 0 {
		tail = append(tail[:0], tail[excess:]...)
	}
	return append(tail, data...)
}

func alignUTF8(data []byte, end int) int {
	end = min(end, len(data))
	if end == 0 {
		return 0
	}
	index := end - 1
	continuations := 0
	for index >= 0 && data[index]&0xc0 == 0x80 {
		continuations++
		index--
	}
	if index < 0 {
		return max(0, end-3)
	}
	lead := data[index]
	expected := 1
	switch {
	case lead&0xe0 == 0xc0:
		expected = 2
	case lead&0xf0 == 0xe0:
		expected = 3
	case lead&0xf8 == 0xf0:
		expected = 4
	}
	available := continuations + 1
	if expected > 1 && available < expected {
		return index
	}
	return end
}

func utf8Prefix(data []byte) int {
	index := 0
	for index < len(data) && data[index]&0xc0 == 0x80 {
		index++
	}
	return index
}

func writeAll(w io.Writer, data []byte) error {
	for len(data) > 0 {
		n, err := w.Write(data)
		if n > 0 {
			data = data[n:]
		}
		if err != nil {
			return err
		}
		if n == 0 {
			return errors.New("file write made no progress")
		}
	}
	return nil
}
