package coding

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/fengwk/kk-studio/daemon/internal/textcodec"
)

const staleWriteReviewMessage = "File changed since review; nothing was written. Review a new change before approving it."

type ExpectedFile struct {
	Path   string
	SHA256 string
}

// LossyDecode discloses that the original file could not be decoded losslessly and that the saved
// text still shows U+FFFD, so the approval diff cannot show the bytes the save rewrites.
type LossyDecode struct {
	Encoding         string `json:"encoding"`
	InvalidSequences int    `json:"invalidSequences"`
}

type WriteReview struct {
	Path        string       `json:"path"`
	Diff        string       `json:"diff"`
	SHA256      *string      `json:"sha256"`
	LossyDecode *LossyDecode `json:"lossyDecode,omitempty"`
	NewFile     bool         `json:"-"`
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
	exists, err := rejectNonRegularTarget(resolved)
	if err != nil {
		return WriteReview{}, err
	}
	pending, err := planWrite(resolved, content, nil, exists)
	if err != nil {
		return WriteReview{}, err
	}
	return pending.review, nil
}

// rejectNonRegularTarget refuses a symbolic link or anything that is not a regular file, and
// reports whether the target exists.
func rejectNonRegularTarget(path string) (bool, error) {
	info, err := os.Lstat(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return false, nil
		}
		return false, err
	}
	return true, rejectNonRegularInfo(path, info)
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

// pendingWrite is one validated write: the review it shows, the bytes it commits and whether the
// approval has to disclose a lossy decode.
type pendingWrite struct {
	review WriteReview
	bytes  []byte
}

// planWrite validates content against the current file and prepares the review and bytes of a
// write. Nothing is modified here.
func planWrite(path, content string, expected *ExpectedFile, exists bool) (pendingWrite, error) {
	var original, decoded []byte
	var existing *textcodec.Encoding
	replacements := 0
	if exists {
		var err error
		if original, err = os.ReadFile(path); err != nil {
			return pendingWrite{}, err
		}
		if expected != nil && expected.SHA256 != sha256Hex(original) {
			return pendingWrite{}, rejectInput(staleWriteReviewMessage)
		}
		var enc textcodec.Encoding
		if decoded, enc, replacements, err = textcodec.Decode(path, original); err != nil {
			return pendingWrite{}, codecError(path, err, codecWrite)
		}
		existing = &enc
	}
	written, err := encodeWriteContent(path, existing, content)
	if err != nil {
		return pendingWrite{}, err
	}
	diff, err := numberedDiff(normalizeEOL(string(decoded)), normalizeEOL(written.text))
	if err != nil {
		return pendingWrite{}, unchangedFileError(err)
	}
	review := WriteReview{Path: path, Diff: diff, NewFile: !exists}
	if exists {
		hashText := sha256Hex(original)
		review.SHA256 = &hashText
		review.LossyDecode = lossyDecode(*existing, replacements, written.text)
	}
	if err := checkReviewBudget("write", review); err != nil {
		return pendingWrite{}, err
	}
	return pendingWrite{review: review, bytes: written.bytes}, nil
}

// checkReviewBudget refuses a review that cannot travel in one result frame, so the refusal happens
// before the target is touched.
func checkReviewBudget(tool string, review any) error {
	details := mustJSON(map[string]any{tool + "Review": review})
	if len(details) > maxReviewBytes {
		return rejectInput(fmt.Sprintf("Complete %s diff exceeds the review transport limit; split the change into several smaller %s calls.", tool, tool))
	}
	return nil
}

// Write creates or rewrites a regular text file in place. expected, when present, binds the write
// to the exact file snapshot shown in an approval preview.
func Write(path, content string, expected *ExpectedFile) (WriteReview, error) {
	resolved, err := resolveWritePath(path)
	if err != nil {
		return WriteReview{}, err
	}
	unlock := lockPath(resolved)
	defer unlock()
	exists, err := existingTarget(resolved, expected)
	if err != nil {
		return WriteReview{}, err
	}
	pending, err := planWrite(resolved, content, expected, exists)
	if err != nil {
		return WriteReview{}, err
	}
	if err = os.MkdirAll(filepath.Dir(resolved), 0o755); err != nil {
		return WriteReview{}, err
	}
	if err = writeInPlace(resolved, pending.bytes, exists); err != nil {
		code, message := classifyFileSystemError(err)
		return WriteReview{}, failRun(message, code)
	}
	return pending.review, nil
}

// existingTarget stats the write target and applies the rejections that do not need its content.
func existingTarget(path string, expected *ExpectedFile) (bool, error) {
	info, statErr := os.Lstat(path)
	exists := statErr == nil
	if statErr != nil && !errors.Is(statErr, os.ErrNotExist) {
		return false, statErr
	}
	if expected != nil && expected.Path != path {
		return false, rejectInput(staleWriteReviewMessage)
	}
	if expected != nil && !exists && expected.SHA256 != "" {
		return false, rejectInput(staleWriteReviewMessage)
	}
	if exists {
		if err := rejectNonRegularInfo(path, info); err != nil {
			return false, err
		}
	}
	return exists, nil
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

// writeInPlace writes data in place, so an existing file keeps its inode, owner, mode and hard
// links, and a new file is created exclusively with 0644 (the umask applies). The file is synced;
// a new file also syncs its parent directory. The write is not crash-atomic: a failed write leaves
// the truncated or partial file behind and is reported as such.
func writeInPlace(path string, data []byte, exists bool) error {
	return writeInPlaceWithSync(path, data, exists, func(file *os.File) error { return file.Sync() })
}

func writeInPlaceWithSync(path string, data []byte, exists bool, syncFile func(*os.File) error) error {
	flags := os.O_WRONLY | os.O_CREATE | os.O_EXCL
	if exists {
		flags = os.O_WRONLY | os.O_TRUNC
	}
	file, err := os.OpenFile(path, flags, 0o644)
	if err != nil {
		return err
	}
	if _, err = file.Write(data); err != nil {
		file.Close()
		return err
	}
	if err = syncFile(file); err != nil {
		file.Close()
		return err
	}
	if err = file.Close(); err != nil {
		return err
	}
	if exists {
		return nil
	}
	return syncParentDirectory(filepath.Dir(path))
}

// lossyDecode reports the honest approval disclosure: the original file decoded with replacements,
// the saved text still shows U+FFFD and it was encoded successfully.
func lossyDecode(existing textcodec.Encoding, replacements int, text string) *LossyDecode {
	if replacements == 0 || !strings.Contains(text, "\uFFFD") {
		return nil
	}
	return &LossyDecode{Encoding: existing.Name, InvalidSequences: replacements}
}

// lossyDecodeNote renders the model-facing line that discloses a lossy save.
func lossyDecodeNote(lossy *LossyDecode) string {
	sequences := fmt.Sprintf("%d byte sequences that are", lossy.InvalidSequences)
	if lossy.InvalidSequences == 1 {
		sequences = "1 byte sequence that is"
	}
	return fmt.Sprintf("Note: the original file contains %s not valid %s, shown as U+FFFD (�); those still present were saved as U+FFFD, so their original bytes are lost.",
		sequences, lossy.Encoding)
}
