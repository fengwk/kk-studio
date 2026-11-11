package coding

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"unicode/utf16"
	"unicode/utf8"
)

const staleWriteReviewMessage = "File changed since edit review; nothing was written. Review a new edit before approving it."

type ExpectedFile struct {
	Path   string
	SHA256 string
}

type WriteReview struct {
	Path    string `json:"path"`
	Diff    string `json:"diff,omitempty"`
	SHA256  string `json:"sha256,omitempty"`
	NewFile bool   `json:"newFile,omitempty"`
	Content string `json:"content,omitempty"`
}

var pathLocks sync.Map

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
	current, err := os.ReadFile(resolved)
	if errors.Is(err, os.ErrNotExist) {
		if isSymlink(resolved) {
			return WriteReview{}, fmt.Errorf("path is a symbolic link: %s", resolved)
		}
		return WriteReview{Path: resolved, NewFile: true, Content: content}, nil
	}
	if err != nil {
		return WriteReview{}, err
	}
	info, err := os.Stat(resolved)
	if err != nil {
		return WriteReview{}, err
	}
	if !info.Mode().IsRegular() {
		return WriteReview{}, fmt.Errorf("path is not a regular file: %s", resolved)
	}
	decoded, err := decodeFileText(current)
	if err != nil {
		return WriteReview{}, err
	}
	hash := sha256.Sum256(current)
	return WriteReview{Path: resolved, SHA256: hex.EncodeToString(hash[:]), Diff: numberedDiff(decoded, content)}, nil
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
	info, statErr := os.Lstat(resolved)
	exists := statErr == nil
	if statErr != nil && !errors.Is(statErr, os.ErrNotExist) {
		return WriteReview{}, statErr
	}
	if exists && info.Mode()&os.ModeSymlink != 0 {
		return WriteReview{}, fmt.Errorf("path is a symbolic link: %s", resolved)
	}
	if exists && !info.Mode().IsRegular() {
		if info.IsDir() {
			return WriteReview{}, fmt.Errorf("path is a directory: %s", resolved)
		}
		return WriteReview{}, fmt.Errorf("path is not a regular file: %s", resolved)
	}
	var original []byte
	var review WriteReview
	charset, bom := "UTF-8", 0
	if exists {
		original, err = os.ReadFile(resolved)
		if err != nil {
			return WriteReview{}, err
		}
		if expected != nil {
			hash := sha256.Sum256(original)
			if expected.Path != resolved || expected.SHA256 != hex.EncodeToString(hash[:]) {
				//lint:ignore ST1005 The message is a model-visible contract and retains its prescribed capitalization.
				return WriteReview{}, errors.New(staleWriteReviewMessage)
			}
		}
		charset, bom = charsetOf(original)
		decoded, decodeErr := decodeFileText(original)
		if decodeErr != nil {
			return WriteReview{}, decodeErr
		}
		hash := sha256.Sum256(original)
		review = WriteReview{Path: resolved, SHA256: hex.EncodeToString(hash[:]), Diff: numberedDiff(decoded, content)}
	} else {
		review = WriteReview{Path: resolved, NewFile: true, Content: content}
	}
	encoded, err := encodeFileText(content, charset, bom)
	if err != nil {
		return WriteReview{}, err
	}
	if err = os.MkdirAll(filepath.Dir(resolved), 0o755); err != nil {
		return WriteReview{}, err
	}
	mode := os.FileMode(0o644)
	if exists {
		mode = info.Mode().Perm()
	}
	if err = atomicWrite(resolved, encoded, mode); err != nil {
		return WriteReview{}, err
	}
	return review, nil
}

func resolveWritePath(path string) (string, error) {
	if !filepath.IsAbs(path) {
		return "", errors.New("path must be absolute")
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

func isSymlink(path string) bool {
	info, err := os.Lstat(path)
	return err == nil && info.Mode()&os.ModeSymlink != 0
}

func charsetOf(data []byte) (string, int) {
	switch {
	case bytes.HasPrefix(data, []byte{0xef, 0xbb, 0xbf}):
		return "UTF-8", 3
	case bytes.HasPrefix(data, []byte{0xff, 0xfe}):
		return "UTF-16LE", 2
	case bytes.HasPrefix(data, []byte{0xfe, 0xff}):
		return "UTF-16BE", 2
	default:
		return "UTF-8", 0
	}
}

func decodeFileText(data []byte) (string, error) {
	charset, bom := charsetOf(data)
	data = data[bom:]
	if charset == "UTF-8" {
		if !utf8.Valid(data) || bytes.IndexByte(data, 0) >= 0 {
			return "", errors.New("file appears to be binary")
		}
		return string(data), nil
	}
	if len(data)%2 != 0 {
		return "", errors.New("file appears to be binary")
	}
	units := make([]uint16, len(data)/2)
	for i := range units {
		if charset == "UTF-16LE" {
			units[i] = uint16(data[2*i]) | uint16(data[2*i+1])<<8
		} else {
			units[i] = uint16(data[2*i])<<8 | uint16(data[2*i+1])
		}
	}
	for i, unit := range units {
		if unit >= 0xd800 && unit <= 0xdbff {
			if i+1 == len(units) || units[i+1] < 0xdc00 || units[i+1] > 0xdfff {
				return "", errors.New("file appears to be binary")
			}
		} else if unit >= 0xdc00 && unit <= 0xdfff && (i == 0 || units[i-1] < 0xd800 || units[i-1] > 0xdbff) {
			return "", errors.New("file appears to be binary")
		}
	}
	return string(utf16.Decode(units)), nil
}

func encodeFileText(text, charset string, bom int) ([]byte, error) {
	if !utf8.ValidString(text) {
		return nil, errors.New("text cannot be losslessly encoded in charset " + charset)
	}
	var out []byte
	switch charset {
	case "UTF-8":
		out = append([]byte(nil), text...)
	case "UTF-16LE", "UTF-16BE":
		for _, unit := range utf16.Encode([]rune(text)) {
			if charset == "UTF-16LE" {
				out = append(out, byte(unit), byte(unit>>8))
			} else {
				out = append(out, byte(unit>>8), byte(unit))
			}
		}
	default:
		return nil, errors.New("text cannot be losslessly encoded in charset " + charset)
	}
	if bom == 0 {
		return out, nil
	}
	prefix := map[string][]byte{"UTF-8": {0xef, 0xbb, 0xbf}, "UTF-16LE": {0xff, 0xfe}, "UTF-16BE": {0xfe, 0xff}}[charset]
	return append(prefix, out...), nil
}

func numberedDiff(oldText, newText string) string {
	oldLines := strings.Split(strings.TrimSuffix(oldText, "\n"), "\n")
	newLines := strings.Split(strings.TrimSuffix(newText, "\n"), "\n")
	prefix := 0
	for prefix < len(oldLines) && prefix < len(newLines) && oldLines[prefix] == newLines[prefix] {
		prefix++
	}
	suffix := 0
	for suffix < len(oldLines)-prefix && suffix < len(newLines)-prefix && oldLines[len(oldLines)-1-suffix] == newLines[len(newLines)-1-suffix] {
		suffix++
	}
	start := max(0, prefix-4)
	endOld := min(len(oldLines), len(oldLines)-suffix+4)
	endNew := min(len(newLines), len(newLines)-suffix+4)
	var b strings.Builder
	for i := start; i < prefix; i++ {
		fmt.Fprintf(&b, "  %d|%s\n", i+1, oldLines[i])
	}
	for i := prefix; i < len(oldLines)-suffix; i++ {
		fmt.Fprintf(&b, "- %d|%s\n", i+1, oldLines[i])
	}
	for i := prefix; i < len(newLines)-suffix; i++ {
		fmt.Fprintf(&b, "+ %d|%s\n", i+1, newLines[i])
	}
	for i := len(oldLines) - suffix; i < endOld; i++ {
		fmt.Fprintf(&b, "  %d|%s\n", i+1, oldLines[i])
	}
	_ = endNew
	return strings.TrimSuffix(b.String(), "\n")
}

func atomicWrite(path string, data []byte, mode os.FileMode) error {
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
		err = file.Sync()
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
	dir, err := os.Open(filepath.Dir(path))
	if err == nil {
		err = dir.Sync()
		_ = dir.Close()
	}
	return err
}
