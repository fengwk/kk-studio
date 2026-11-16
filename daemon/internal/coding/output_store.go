package coding

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/uuid"
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
	id := uuid.New()
	workspace := filepath.Join(s.root, id)
	if err := os.Mkdir(workspace, 0o700); err != nil {
		return nil, "", err
	}
	if err := os.WriteFile(filepath.Join(workspace, "created-at"), []byte(strconv.FormatInt(time.Now().Unix(), 10)), 0o600); err != nil {
		_ = os.RemoveAll(workspace)
		return nil, "", err
	}
	path := filepath.Join(workspace, sanitizeOutputName(callID)+"-"+id[:8]+".part")
	var file *os.File
	var err error
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
	if len(parts) < 2 || !uuid.Canonical(parts[0]) {
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
		if !uuid.Canonical(entry.Name()) || !entry.IsDir() {
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
