package coding

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

const (
	inlineOutputBytes    = 50 * 1024
	inlineOutputLines    = 2000
	previewHeadBytes     = 4 * 1024
	previewTailBytes     = 8 * 1024
	defaultCaptureBudget = 1 << 30
)

var (
	outputStoresMu sync.Mutex
	outputStores   = make(map[string]*TextOutputStore)
)

// TextOutputStoreForDataDir returns the shared temporary-output store for a daemon data directory.
func TextOutputStoreForDataDir(dataDir string) (*TextOutputStore, error) {
	if dataDir == "" {
		return nil, errors.New("daemon data directory is required for process output storage")
	}
	outputStoresMu.Lock()
	defer outputStoresMu.Unlock()
	if store := outputStores[dataDir]; store != nil {
		return store, nil
	}
	store, err := OpenTextOutputStore(dataDir)
	if err != nil {
		return nil, err
	}
	outputStores[dataDir] = store
	return store, nil
}

// TextOutputStore owns bounded process output files under dataDir/tmp/workspaces.
type TextOutputStore struct {
	root            string
	mu              sync.Mutex
	leases          map[string]int
	staging         map[string]string
	openStagingFile func(string) (*os.File, error)
}

// OpenTextOutputStore opens or creates the private temporary-output root.
func OpenTextOutputStore(dataDir string) (*TextOutputStore, error) {
	root := filepath.Join(dataDir, "tmp", "workspaces")
	if err := os.MkdirAll(root, 0o700); err != nil {
		return nil, err
	}
	info, err := os.Lstat(root)
	if err != nil {
		return nil, err
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.IsDir() {
		return nil, fmt.Errorf("temporary workspace root is not a real directory: %s", root)
	}
	if err := os.Chmod(root, 0o700); err != nil {
		return nil, err
	}
	return &TextOutputStore{root: root, leases: make(map[string]int), staging: make(map[string]string)}, nil
}

// Root returns the workspace root directory.
func (s *TextOutputStore) Root() string { return s.root }

// CreateStagingFile creates a private .part file and holds its workspace lease.
func (s *TextOutputStore) CreateStagingFile(callID string) (*os.File, string, error) {
	id, err := randomUUID()
	if err != nil {
		return nil, "", err
	}
	workspace := filepath.Join(s.root, id)
	if err = os.Mkdir(workspace, 0o700); err != nil {
		return nil, "", err
	}
	if err = os.WriteFile(filepath.Join(workspace, "created-at"), []byte(strconv.FormatInt(time.Now().Unix(), 10)), 0o600); err != nil {
		_ = os.RemoveAll(workspace)
		return nil, "", err
	}
	path := filepath.Join(workspace, sanitizeOutputName(callID)+"-"+id[:8]+".part")
	var file *os.File
	if s.openStagingFile != nil {
		file, err = s.openStagingFile(path)
	} else {
		file, err = os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_RDWR, 0o600)
	}
	if err != nil {
		_ = os.RemoveAll(workspace)
		return nil, "", err
	}
	s.mu.Lock()
	s.leases[id]++
	s.staging[path] = workspace
	s.mu.Unlock()
	return file, path, nil
}

// Publish atomically changes a staging file suffix from .part to .log.
func (s *TextOutputStore) Publish(path string) (string, error) {
	if !strings.HasSuffix(path, ".part") {
		return "", errors.New("staging file must end with .part")
	}
	target := strings.TrimSuffix(path, ".part") + ".log"
	if err := os.Rename(path, target); err != nil {
		return "", err
	}
	s.release(path)
	return target, nil
}

// DeleteStaging removes an unpublished file and its workspace.
func (s *TextOutputStore) DeleteStaging(path string) error {
	if path == "" {
		return nil
	}
	err := os.Remove(path)
	if errors.Is(err, os.ErrNotExist) {
		err = nil
	}
	s.mu.Lock()
	workspace := s.staging[path]
	delete(s.staging, path)
	if workspace != "" {
		s.releaseWorkspaceLocked(workspace)
	}
	s.mu.Unlock()
	if workspace != "" {
		_ = os.RemoveAll(workspace)
	}
	return err
}

// Acquire adds a workspace lease for resources inside this store; out-of-root paths are no-ops.
func (s *TextOutputStore) Acquire(path string) func() {
	abs, err := filepath.Abs(path)
	if err != nil {
		return func() {}
	}
	rel, err := filepath.Rel(s.root, abs)
	if err != nil || rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return func() {}
	}
	parts := strings.Split(rel, string(filepath.Separator))
	if len(parts) < 2 || !canonicalUUID(parts[0]) {
		return func() {}
	}
	s.mu.Lock()
	workspace := filepath.Join(s.root, parts[0])
	info, err := os.Lstat(workspace)
	if err != nil || info.Mode()&os.ModeSymlink != 0 || !info.IsDir() {
		s.mu.Unlock()
		return func() {}
	}
	if _, err = os.Stat(filepath.Join(workspace, "created-at")); err != nil {
		s.mu.Unlock()
		return func() {}
	}
	s.leases[parts[0]]++
	s.mu.Unlock()
	var once sync.Once
	return func() {
		once.Do(func() {
			s.mu.Lock()
			s.leases[parts[0]]--
			if s.leases[parts[0]] <= 0 {
				delete(s.leases, parts[0])
			}
			s.mu.Unlock()
		})
	}
}

// Sweep removes expired, registered workspaces that are not leased.
func (s *TextOutputStore) Sweep(ttl time.Duration) (int, error) {
	if ttl <= 0 {
		return 0, errors.New("ttl must be positive")
	}
	entries, err := os.ReadDir(s.root)
	if errors.Is(err, os.ErrNotExist) {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}
	cutoff := time.Now().Add(-ttl).Unix()
	removed := 0
	for _, entry := range entries {
		if !canonicalUUID(entry.Name()) || !entry.IsDir() {
			continue
		}
		s.mu.Lock()
		workspace := filepath.Join(s.root, entry.Name())
		info, statErr := os.Lstat(workspace)
		if statErr != nil || info.Mode()&os.ModeSymlink != 0 || !info.IsDir() {
			s.mu.Unlock()
			continue
		}
		created, readErr := os.ReadFile(filepath.Join(workspace, "created-at"))
		if readErr != nil {
			s.mu.Unlock()
			continue
		}
		createdAt, parseErr := strconv.ParseInt(strings.TrimSpace(string(created)), 10, 64)
		if parseErr != nil || createdAt > cutoff || s.leases[entry.Name()] > 0 {
			s.mu.Unlock()
			continue
		}
		err = os.RemoveAll(workspace)
		s.mu.Unlock()
		if err != nil {
			return removed, err
		}
		removed++
	}
	return removed, nil
}

// ListFilesWithSuffix lists direct workspace files matching suffix, ignoring malformed entries.
func (s *TextOutputStore) ListFilesWithSuffix(suffix string) ([]string, error) {
	entries, err := os.ReadDir(s.root)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var paths []string
	for _, entry := range entries {
		if !canonicalUUID(entry.Name()) || !entry.IsDir() {
			continue
		}
		workspace := filepath.Join(s.root, entry.Name())
		info, statErr := os.Lstat(workspace)
		if statErr != nil || info.Mode()&os.ModeSymlink != 0 || !info.IsDir() {
			continue
		}
		files, readErr := os.ReadDir(workspace)
		if readErr != nil {
			continue
		}
		for _, file := range files {
			if strings.HasSuffix(file.Name(), suffix) {
				paths = append(paths, filepath.Join(workspace, file.Name()))
			}
		}
	}
	return paths, nil
}

func (s *TextOutputStore) release(path string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	workspace := s.staging[path]
	delete(s.staging, path)
	if workspace != "" {
		s.releaseWorkspaceLocked(workspace)
	}
}
func (s *TextOutputStore) releaseWorkspaceLocked(workspace string) {
	id := filepath.Base(workspace)
	s.leases[id]--
	if s.leases[id] <= 0 {
		delete(s.leases, id)
	}
}

func randomUUID() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	h := hex.EncodeToString(b)
	return h[:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:], nil
}
func canonicalUUID(value string) bool {
	if len(value) != 36 {
		return false
	}
	for i, c := range value {
		if i == 8 || i == 13 || i == 18 || i == 23 {
			if c != '-' {
				return false
			}
			continue
		}
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
			return false
		}
	}
	return true
}
func sanitizeOutputName(value string) string {
	var b strings.Builder
	for _, r := range value {
		if b.Len() >= 64 {
			break
		}
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || strings.ContainsRune("-_.", r) {
			b.WriteRune(r)
		} else {
			b.WriteByte('_')
		}
	}
	if b.Len() == 0 {
		return "output"
	}
	return b.String()
}

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
	if s.truncated || s.failed {
		return
	}
	if !s.spilled {
		if s.total <= inlineOutputBytes && s.totalLines() <= inlineOutputLines {
			s.memory = append(s.memory, data...)
			return
		}
		s.spilled = true
		file, path, err := s.store.CreateStagingFile(s.callID)
		if err != nil {
			s.failed = true
			s.memory = nil
			return
		}
		s.file, s.staging = file, path
		if err = s.writeCaptured(s.memory); err != nil {
			s.fail()
			s.memory = nil
			return
		}
		s.memory = nil
	}
	_ = s.writeCaptured(data)
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

// TotalBytes reports all bytes observed, including bytes beyond the capture budget.
func (s *OutputSpool) TotalBytes() int64 { s.mu.Lock(); defer s.mu.Unlock(); return s.total }

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
	published := ""
	if s.file != nil && !s.failed {
		if err := s.file.Sync(); err != nil {
			s.fail()
		} else if err = s.file.Close(); err != nil {
			s.file = nil
			s.fail()
		} else {
			s.file = nil
			target, publishErr := s.store.Publish(s.staging)
			if publishErr == nil {
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
		if text != "" && !strings.HasSuffix(text, "\n") {
			text += "\n"
		}
		text += fmt.Sprintf("[... %d bytes omitted here; the middle of the output is not shown ...]\n", omitted)
	}
	text += strings.ToValidUTF8(string(s.tail[tailStart:tailEnd]), "�")
	if text != "" && !strings.HasSuffix(text, "\n") {
		text += "\n"
	}
	text += "\n"
	if published == "" {
		footer := fmt.Sprintf("[Full output (%d bytes, %d lines) could not be saved to local storage; only this bounded preview is available.", s.total, s.totalLines())
		if s.truncated {
			footer += fmt.Sprintf(" Capture stopped at the local %d-byte daemon budget; the command itself ran to completion and its exit code is meaningful.", s.budget)
		}
		return text + footer + "]"
	}
	footer := fmt.Sprintf("[Full output: %d bytes, %d lines. Saved to: %s\nUse read with offset/limit to page through the file, or grep to search it.]", s.total, s.totalLines(), published)
	if s.truncated {
		footer += fmt.Sprintf(" Capture stopped at the local %d-byte daemon budget; the command itself ran to completion and its exit code is meaningful.", s.budget)
	}
	return text + footer
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
