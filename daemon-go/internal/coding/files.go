package coding

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"unicode/utf16"
	"unicode/utf8"
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
	linkInfo, linkErr := os.Lstat(resolved)
	if linkErr == nil && linkInfo.Mode()&os.ModeSymlink != 0 {
		return WriteReview{}, rejectInput(fmt.Sprintf("path is a symbolic link: %s", resolved))
	}
	if linkErr != nil && !errors.Is(linkErr, os.ErrNotExist) {
		return WriteReview{}, linkErr
	}
	if linkErr == nil && !linkInfo.Mode().IsRegular() {
		if linkInfo.IsDir() {
			return WriteReview{}, rejectInput(fmt.Sprintf("path is a directory: %s", resolved))
		}
		return WriteReview{}, rejectInput(fmt.Sprintf("path is not a regular file: %s", resolved))
	}
	current, err := os.ReadFile(resolved)
	if errors.Is(err, os.ErrNotExist) {
		if isSymbolicLink(resolved) {
			return WriteReview{}, rejectInput(fmt.Sprintf("path is a symbolic link: %s", resolved))
		}
		diff, err := numberedDiff("", normalizeEOL(content))
		if err != nil {
			return WriteReview{}, err
		}
		return WriteReview{Path: resolved, Diff: diff, NewFile: true, Content: content}, nil
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
	decoded, err := decodeFileText(current)
	if err != nil {
		return WriteReview{}, err
	}
	hash := sha256.Sum256(current)
	diff, err := numberedDiff(normalizeEOL(decoded), normalizeEOL(content))
	if err != nil {
		return WriteReview{}, err
	}
	hashText := hex.EncodeToString(hash[:])
	return WriteReview{Path: resolved, SHA256: &hashText, Diff: diff}, nil
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
	if expected != nil && expected.Path != resolved {
		return WriteReview{}, rejectInput(staleWriteReviewMessage)
	}
	if expected != nil && !exists && expected.SHA256 != "" {
		return WriteReview{}, rejectInput(staleWriteReviewMessage)
	}
	if exists && info.Mode()&os.ModeSymlink != 0 {
		return WriteReview{}, rejectInput(fmt.Sprintf("path is a symbolic link: %s", resolved))
	}
	if exists && !info.Mode().IsRegular() {
		if info.IsDir() {
			return WriteReview{}, rejectInput(fmt.Sprintf("path is a directory: %s", resolved))
		}
		return WriteReview{}, rejectInput(fmt.Sprintf("path is not a regular file: %s", resolved))
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
				return WriteReview{}, rejectInput(staleWriteReviewMessage)
			}
		}
		charset, bom = charsetOf(original)
		decoded, decodeErr := decodeFileText(original)
		if decodeErr != nil {
			return WriteReview{}, decodeErr
		}
		hash := sha256.Sum256(original)
		diff, diffErr := numberedDiff(normalizeEOL(decoded), normalizeEOL(content))
		if diffErr != nil {
			return WriteReview{}, diffErr
		}
		hashText := hex.EncodeToString(hash[:])
		review = WriteReview{Path: resolved, SHA256: &hashText, Diff: diff}
	} else {
		diff, diffErr := numberedDiff("", normalizeEOL(content))
		if diffErr != nil {
			return WriteReview{}, diffErr
		}
		review = WriteReview{Path: resolved, Diff: diff, NewFile: true, Content: content}
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
		code, message := classifyFileSystemError(err)
		return WriteReview{}, failRun(message, code)
	}
	return review, nil
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
			return "", failRun("file appears to be binary", "CODEC")
		}
		return string(data), nil
	}
	if len(data)%2 != 0 {
		return "", failRun("file appears to be binary", "CODEC")
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
				return "", failRun("file appears to be binary", "CODEC")
			}
		} else if unit >= 0xdc00 && unit <= 0xdfff && (i == 0 || units[i-1] < 0xd800 || units[i-1] > 0xdbff) {
			return "", failRun("file appears to be binary", "CODEC")
		}
	}
	return string(utf16.Decode(units)), nil
}

func encodeFileText(text, charset string, bom int) ([]byte, error) {
	if !utf8.ValidString(text) {
		return nil, failRun("text cannot be losslessly encoded in charset "+charset, "CODEC")
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
		return nil, failRun("text cannot be losslessly encoded in charset "+charset, "CODEC")
	}
	if bom == 0 {
		return out, nil
	}
	prefix := map[string][]byte{"UTF-8": {0xef, 0xbb, 0xbf}, "UTF-16LE": {0xff, 0xfe}, "UTF-16BE": {0xfe, 0xff}}[charset]
	return append(prefix, out...), nil
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
	return syncParentDirectory(filepath.Dir(path))
}
