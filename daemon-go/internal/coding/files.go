package coding

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"

	"github.com/fengwk/kk-studio/daemon/internal/textcodec"
)

const staleWriteReviewMessage = "File changed since review; nothing was written. Review a new change before approving it."

type ExpectedFile struct {
	Path   string
	SHA256 string
}

type WriteReview struct {
	Path    string  `json:"path"`
	Diff    string  `json:"diff"`
	SHA256  *string `json:"sha256"`
	NewFile bool    `json:"-"`
	Content string  `json:"-"`
}

var pathLocks sync.Map

// sha256Hex returns the lower-case hex SHA-256 of data.
func sha256Hex(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

func lockPath(path string) func() {
	lock, _ := pathLocks.LoadOrStore(path, &sync.Mutex{})
	mutex := lock.(*sync.Mutex)
	mutex.Lock()
	return mutex.Unlock
}

// PreviewWrite computes an approval preview without modifying the target.
func PreviewWrite(path, content string) (WriteReview, error) {
	resolved, err := resolveWritePath(path)
	if err != nil {
		return WriteReview{}, err
	}
	unlock := lockPath(resolved)
	defer unlock()
	if err := rejectNonRegularTarget(resolved); err != nil {
		return WriteReview{}, err
	}
	current, err := os.ReadFile(resolved)
	if errors.Is(err, os.ErrNotExist) {
		return previewNewFile(resolved, content)
	}
	if err != nil {
		return WriteReview{}, err
	}
	info, err := os.Stat(resolved)
	if err != nil {
		return WriteReview{}, err
	}
	if !info.Mode().IsRegular() {
		return WriteReview{}, rejectInput(fmt.Sprintf("path is not a regular file: %s", resolved))
	}
	decoded, existing, err := textcodec.Decode(resolved, current)
	if err != nil {
		return WriteReview{}, codecError(resolved, err, codecWrite)
	}
	if _, err = encodeWriteContent(resolved, &existing, content); err != nil {
		return WriteReview{}, err
	}
	diff, err := numberedDiff(normalizeEOL(string(decoded)), normalizeEOL(content))
	if err != nil {
		return WriteReview{}, err
	}
	hashText := sha256Hex(current)
	return WriteReview{Path: resolved, SHA256: &hashText, Diff: diff}, nil
}

// rejectNonRegularTarget refuses a symbolic link or anything that is not a regular file. A path
// that does not exist yet is not an error here; the caller decides how to create it.
func rejectNonRegularTarget(path string) error {
	info, err := os.Lstat(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return err
	}
	return rejectNonRegularInfo(path, info)
}

// rejectNonRegularInfo refuses an existing target that write and preview cannot replace.
func rejectNonRegularInfo(path string, info os.FileInfo) error {
	if info.Mode()&os.ModeSymlink != 0 {
		return rejectInput(fmt.Sprintf("path is a symbolic link: %s", path))
	}
	if info.IsDir() {
		return rejectInput(fmt.Sprintf("path is a directory: %s", path))
	}
	if !info.Mode().IsRegular() {
		return rejectInput(fmt.Sprintf("path is not a regular file: %s", path))
	}
	return nil
}

// previewNewFile renders the preview of content that would create path.
func previewNewFile(path, content string) (WriteReview, error) {
	if isSymbolicLink(path) {
		return WriteReview{}, rejectInput(fmt.Sprintf("path is a symbolic link: %s", path))
	}
	if _, err := encodeWriteContent(path, nil, content); err != nil {
		return WriteReview{}, err
	}
	diff, err := numberedDiff("", normalizeEOL(content))
	if err != nil {
		return WriteReview{}, err
	}
	return WriteReview{Path: path, Diff: diff, NewFile: true, Content: content}, nil
}

// Write creates or atomically replaces a regular text file. expected, when present,
// binds the write to the exact file snapshot shown in an approval preview.
func Write(path, content string, expected *ExpectedFile) (WriteReview, error) {
	resolved, err := resolveWritePath(path)
	if err != nil {
		return WriteReview{}, err
	}
	unlock := lockPath(resolved)
	defer unlock()
	info, exists, err := existingTarget(resolved, expected)
	if err != nil {
		return WriteReview{}, err
	}
	review, existing, err := writeReview(resolved, content, expected, exists)
	if err != nil {
		return WriteReview{}, err
	}
	encoded, err := encodeWriteContent(resolved, existing, content)
	if err != nil {
		return WriteReview{}, err
	}
	if err = os.MkdirAll(filepath.Dir(resolved), 0o755); err != nil {
		return WriteReview{}, err
	}
	if err = atomicWrite(resolved, encoded, writeMode(info, exists)); err != nil {
		code, message := classifyFileSystemError(err)
		return WriteReview{}, failRun(message, code)
	}
	return review, nil
}

// existingTarget stats the write target and applies the rejections that do not need its content.
func existingTarget(path string, expected *ExpectedFile) (os.FileInfo, bool, error) {
	info, statErr := os.Lstat(path)
	exists := statErr == nil
	if statErr != nil && !errors.Is(statErr, os.ErrNotExist) {
		return nil, false, statErr
	}
	if expected != nil && expected.Path != path {
		return nil, false, rejectInput(staleWriteReviewMessage)
	}
	if expected != nil && !exists && expected.SHA256 != "" {
		return nil, false, rejectInput(staleWriteReviewMessage)
	}
	if exists {
		if err := rejectNonRegularInfo(path, info); err != nil {
			return nil, false, err
		}
	}
	return info, exists, nil
}

// writeReview builds the approval review of the pending write and reports the encoding the file
// must keep.
func writeReview(path, content string, expected *ExpectedFile, exists bool) (WriteReview, *textcodec.Encoding, error) {
	if !exists {
		diff, err := numberedDiff("", normalizeEOL(content))
		if err != nil {
			return WriteReview{}, nil, err
		}
		return WriteReview{Path: path, Diff: diff, NewFile: true, Content: content}, nil, nil
	}
	original, err := os.ReadFile(path)
	if err != nil {
		return WriteReview{}, nil, err
	}
	if expected != nil && expected.SHA256 != sha256Hex(original) {
		return WriteReview{}, nil, rejectInput(staleWriteReviewMessage)
	}
	decoded, existing, decodeErr := textcodec.Decode(path, original)
	if decodeErr != nil {
		return WriteReview{}, nil, codecError(path, decodeErr, codecWrite)
	}
	diff, err := numberedDiff(normalizeEOL(string(decoded)), normalizeEOL(content))
	if err != nil {
		return WriteReview{}, nil, err
	}
	hashText := sha256Hex(original)
	return WriteReview{Path: path, SHA256: &hashText, Diff: diff}, &existing, nil
}

// writeMode keeps the permissions of a replaced file and removes nothing from a new one.
// info is nil when the target does not exist.
func writeMode(info os.FileInfo, exists bool) os.FileMode {
	if exists && info != nil {
		return info.Mode().Perm()
	}
	return 0o644
}

func resolveWritePath(path string) (string, error) {
	if !filepath.IsAbs(path) {
		return "", rejectInput("path must be absolute")
	}
	abs, err := filepath.Abs(filepath.Clean(path))
	if err != nil {
		return "", err
	}
	parent, err := filepath.EvalSymlinks(filepath.Dir(abs))
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			return "", err
		}
		parent, err = resolveExistingParent(filepath.Dir(abs))
		if err != nil {
			return "", err
		}
	}
	return filepath.Join(parent, filepath.Base(abs)), nil
}

func resolveExistingParent(path string) (string, error) {
	missing := []string{}
	for {
		resolved, err := filepath.EvalSymlinks(path)
		if err == nil {
			for i := len(missing) - 1; i >= 0; i-- {
				resolved = filepath.Join(resolved, missing[i])
			}
			return resolved, nil
		}
		if !errors.Is(err, os.ErrNotExist) {
			return "", err
		}
		parent := filepath.Dir(path)
		if parent == path {
			return "", err
		}
		missing = append(missing, filepath.Base(path))
		path = parent
	}
}

func atomicWrite(path string, data []byte, mode os.FileMode) error {
	return atomicWriteWithSync(path, data, mode, func(file *os.File) error { return file.Sync() })
}

func atomicWriteWithSync(path string, data []byte, mode os.FileMode, syncFile func(*os.File) error) error {
	file, err := os.CreateTemp(filepath.Dir(path), ".kk-studio-*.part")
	if err != nil {
		return err
	}
	temp := file.Name()
	defer os.Remove(temp)
	if err = file.Chmod(mode); err == nil {
		_, err = file.Write(data)
	}
	if err == nil {
		err = syncFile(file)
	}
	closeErr := file.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err = os.Rename(temp, path); err != nil {
		return err
	}
	return syncParentDirectory(filepath.Dir(path))
}
