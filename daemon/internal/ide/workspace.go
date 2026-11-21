package ide

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"unicode/utf8"

	"github.com/fengwk/kk-studio/daemon/internal/config"
)

const longMax = uint64(1<<63 - 1)

// ValidateViewID accepts only canonical lowercase UUID strings.
func ValidateViewID(value string) (string, error) {
	if len(value) != 36 || value[8] != '-' || value[13] != '-' || value[18] != '-' || value[23] != '-' {
		return "", NewError("viewId must be a canonical lowercase UUID", "UNAVAILABLE")
	}
	for i, c := range value {
		if i == 8 || i == 13 || i == 18 || i == 23 {
			continue
		}
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return "", NewError("viewId must be a canonical lowercase UUID", "UNAVAILABLE")
		}
	}
	return value, nil
}

// ValidateBindingEpoch validates a positive, canonical signed-long decimal epoch.
func ValidateBindingEpoch(value string) (string, error) {
	if value == "" || value[0] == '0' {
		return "", NewError("bindingEpoch must be a positive decimal string", "UNAVAILABLE")
	}
	for _, c := range value {
		if c < '0' || c > '9' {
			return "", NewError("bindingEpoch must be a positive decimal string", "UNAVAILABLE")
		}
	}
	var number uint64
	for _, c := range value {
		digit := uint64(c - '0')
		if number > (longMax-digit)/10 {
			return "", NewError("bindingEpoch is out of range", "UNAVAILABLE")
		}
		number = number*10 + digit
	}
	return value, nil
}

// nativeDirectoryPath converts the VS Code URI path a Windows Studio client reports into a native
// path. URI paths are not percent-decoded: a percent sign is an actual file name character. Other
// platforms already report native absolute paths, and Windows paths are never re-encoded.
func nativeDirectoryPath(input string) string {
	if runtime.GOOS != "windows" {
		return input
	}
	return windowsPathFromURIPath(input)
}

// windowsPathFromURIPath maps the two URI path shapes VS Code reports for Windows: `/C:/folder`
// for a drive path and `//server/share` for a UNC path. Unmatched shapes are returned unchanged.
func windowsPathFromURIPath(input string) string {
	if rest, ok := strings.CutPrefix(input, "//"); ok {
		return `\\` + strings.ReplaceAll(rest, "/", `\`)
	}
	if len(input) >= 3 && input[0] == '/' && input[2] == ':' && isASCIILetter(input[1]) && (len(input) == 3 || input[3] == '/') {
		return strings.ReplaceAll(input[1:], "/", `\`)
	}
	return input
}

func isASCIILetter(value byte) bool {
	return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z'
}

// CanonicalDirectory resolves symlinks and requires an existing directory.
func CanonicalDirectory(input string) (string, error) {
	input = nativeDirectoryPath(input)
	if !filepath.IsAbs(input) {
		return "", NewError("directory must be an absolute path", "UNAVAILABLE")
	}
	resolved, err := filepath.EvalSymlinks(input)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) || errors.Is(err, fs.ErrInvalid) {
			return "", NewError("directory does not exist", "UNAVAILABLE")
		}
		return "", NewError("directory is not accessible", "UNAVAILABLE")
	}
	info, err := os.Stat(resolved)
	if err != nil {
		return "", NewError("directory is not accessible", "UNAVAILABLE")
	}
	if !info.IsDir() {
		return "", NewError("directory is not a directory", "UNAVAILABLE")
	}
	return resolved, nil
}

// workspaceFolder is one entry of the managed workspace folder list.
type workspaceFolder struct {
	Path string `json:"path"`
}

// WorkspaceFileContent returns the managed workspace JSON written for a view. A view created
// without a directory keeps an empty folder list instead of inventing a folder.
func WorkspaceFileContent(directory string) string {
	folders := []workspaceFolder{}
	if directory != "" {
		folders = append(folders, workspaceFolder{Path: directory})
	}
	var output strings.Builder
	encoder := json.NewEncoder(&output)
	encoder.SetEscapeHTML(false)
	encoder.SetIndent("", "  ")
	_ = encoder.Encode(struct {
		Folders []workspaceFolder `json:"folders"`
	}{Folders: folders})
	return output.String()
}

// RecoveryDocument contains one unsaved ordinary text buffer.
type RecoveryDocument struct {
	Path string `json:"path"`
	Text string `json:"text"`
}

// RecoveryBackup is the bounded persistent recovery payload for one managed view. A view created
// without a directory has no directory, while every captured document still has an absolute path.
type RecoveryBackup struct {
	ViewID       string             `json:"viewId"`
	BindingEpoch string             `json:"bindingEpoch"`
	Directory    string             `json:"directory,omitempty"`
	CreatedAt    string             `json:"createdAt"`
	Complete     bool               `json:"complete"`
	Documents    []RecoveryDocument `json:"documents"`
}

// RecoveryQuotaError indicates a corrupt recovery record or exhausted quota.
type RecoveryQuotaError struct{ message string }

func (e *RecoveryQuotaError) Error() string { return e.message }

func recoveryDirectory(config config.DaemonConfig) string {
	return filepath.Join(config.DataDir, "ide", "recovery")
}

func recoveryPath(config config.DaemonConfig, viewID string) (string, error) {
	if _, err := ValidateViewID(viewID); err != nil {
		return "", &RecoveryQuotaError{message: "recovery backup is corrupt"}
	}
	return filepath.Join(recoveryDirectory(config), viewID+".json"), nil
}

// recoveryPriorPath names the previous record kept until its replacement is durable.
func recoveryPriorPath(path string) string {
	return filepath.Join(filepath.Dir(path), "."+strings.TrimSuffix(filepath.Base(path), ".json")+".prior")
}

// recoveryDirectorySyncSupported reports whether a directory entry can be flushed to stable storage.
// Windows rejects FlushFileBuffers on a directory handle and needs an explicit ACL for the
// owner-only guarantees this store relies on, so it cannot promise a durable record.
var recoveryDirectorySyncSupported = runtime.GOOS == "linux" || runtime.GOOS == "darwin"

// syncRecoveryDirectory flushes one directory entry so a rename or link inside it survives a crash.
func syncRecoveryDirectory(path string) error {
	dir, err := os.Open(path)
	if err != nil {
		return err
	}
	syncErr := dir.Sync()
	if closeErr := dir.Close(); syncErr == nil {
		syncErr = closeErr
	}
	return syncErr
}

// WriteRecoveryBackup stores a private, per-view recovery record within aggregate quota.
func WriteRecoveryBackup(config config.DaemonConfig, backup RecoveryBackup, quota int64) (int64, error) {
	return writeRecoveryBackup(config, backup, quota, nil)
}

// writeDurableRecoveryBackup flushes the record and its directory entries. If publication
// fails after rename, the previous record is restored or retained as an exportable prior.
func writeDurableRecoveryBackup(config config.DaemonConfig, backup RecoveryBackup, quota int64) (int64, error) {
	if !recoveryDirectorySyncSupported {
		return 0, &RecoveryQuotaError{message: "recovery backup durability is not supported on this platform"}
	}
	return writeRecoveryBackup(config, backup, quota, syncRecoveryDirectory)
}

// writeRecoveryBackup validates and publishes one record. A nil syncDir publishes best-effort, which
// is enough while the writing process keeps running.
func writeRecoveryBackup(config config.DaemonConfig, backup RecoveryBackup, quota int64, syncDir func(string) error) (int64, error) {
	if quota <= 0 {
		return 0, &RecoveryQuotaError{message: "recovery quota is invalid"}
	}
	if err := validateBackup(backup); err != nil {
		return 0, err
	}
	path, err := recoveryPath(config, backup.ViewID)
	if err != nil {
		return 0, err
	}
	data, err := json.Marshal(backup)
	if err != nil {
		return 0, &RecoveryQuotaError{message: "recovery backup is corrupt"}
	}
	data = append(data, '\n')
	if int64(len(data)) > quota {
		return 0, &RecoveryQuotaError{message: "recovery backup exceeds the configured quota"}
	}
	dir := recoveryDirectory(config)
	if err = os.MkdirAll(dir, 0o700); err != nil {
		return 0, &RecoveryQuotaError{message: "recovery directory is unavailable"}
	}
	if err = os.Chmod(dir, 0o700); err != nil {
		return 0, &RecoveryQuotaError{message: "recovery directory is unavailable"}
	}
	if !backup.Complete {
		previous, err := ReadRecoveryBackup(config, backup.ViewID, quota)
		if err != nil {
			return 0, err
		}
		if previous != nil && previous.Complete {
			return 0, nil // A best-effort snapshot must not replace the last complete copy.
		}
	}
	if syncDir == nil {
		// Force-close may proceed, but a best-effort write cannot reconcile a rollback link.
		if _, err := os.Lstat(recoveryPriorPath(path)); !errors.Is(err, fs.ErrNotExist) {
			return 0, &RecoveryQuotaError{message: "recovery prior record requires reconciliation"}
		}
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return 0, &RecoveryQuotaError{message: "recovery quota could not be measured: UNKNOWN"}
	}
	var used int64
	for _, entry := range entries {
		name := entry.Name()
		priorID, prior := strings.CutSuffix(strings.TrimPrefix(name, "."), ".prior")
		prior = prior && strings.HasPrefix(name, ".")
		if !strings.HasSuffix(name, ".json") && !prior {
			continue
		}
		info, statErr := os.Stat(filepath.Join(dir, name))
		if statErr != nil {
			return 0, &RecoveryQuotaError{message: "recovery quota could not be measured: UNKNOWN"}
		}
		if !info.Mode().IsRegular() || name == filepath.Base(path) {
			continue
		}
		if prior {
			canonical, err := os.Stat(filepath.Join(dir, priorID+".json"))
			if err != nil && !errors.Is(err, fs.ErrNotExist) {
				return 0, &RecoveryQuotaError{message: "recovery quota could not be measured: UNKNOWN"}
			}
			if os.SameFile(info, canonical) {
				continue // The rollback link and the canonical record share the same data.
			}
		}
		used += info.Size()
	}
	if used+int64(len(data)) > quota {
		return 0, &RecoveryQuotaError{message: "recovery data quota is exhausted"}
	}
	tmp, err := os.CreateTemp(dir, ".recovery-*")
	if err != nil {
		return 0, &RecoveryQuotaError{message: "recovery backup is unavailable"}
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)
	if err = tmp.Chmod(0o600); err == nil {
		_, err = tmp.Write(data)
	}
	if err == nil {
		err = tmp.Sync()
	}
	closeErr := tmp.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		return 0, &RecoveryQuotaError{message: "recovery backup is unavailable"}
	}
	if syncDir == nil {
		if err := os.Rename(tmpName, path); err != nil {
			return 0, &RecoveryQuotaError{message: "recovery backup is unavailable"}
		}
		return int64(len(data)), nil
	}
	// The chain from the data directory down to the record is fixed and always exists afterwards, so
	// it is flushed in full: a concurrent writer may have created part of it, which makes an
	// existence diff miss the entry that still needs flushing.
	chain := []string{filepath.Dir(filepath.Dir(dir)), filepath.Dir(dir), dir}
	if err := publishDurableRecoveryRecord(chain, path, tmpName, syncDir); err != nil {
		return 0, err
	}
	return int64(len(data)), nil
}

// publishDurableRecoveryRecord publishes the already synced temporary file and reports success only
// once the record and every directory entry it depends on are flushed.
func publishDurableRecoveryRecord(dirs []string, path, tmpName string, syncDir func(string) error) error {
	prior := recoveryPriorPath(path)
	priorInfo, err := os.Lstat(prior)
	pathInfo, pathErr := os.Lstat(path)
	if err == nil && os.SameFile(priorInfo, pathInfo) {
		// Both names still refer to the same record; the extra link is redundant.
		if err := os.Remove(prior); err != nil {
			return &RecoveryQuotaError{message: "recovery backup is unavailable"}
		}
		err = fs.ErrNotExist
	}
	if err == nil {
		// A crash or failed rollback can leave two different records. Never guess which one
		// may be discarded; only restore the prior when the canonical record is missing.
		if !priorInfo.Mode().IsRegular() || !errors.Is(pathErr, fs.ErrNotExist) {
			return &RecoveryQuotaError{message: "recovery backup is unavailable"}
		}
		if renameErr := os.Rename(prior, path); renameErr != nil {
			return &RecoveryQuotaError{message: "recovery backup is unavailable"}
		}
	} else if !errors.Is(err, fs.ErrNotExist) {
		return &RecoveryQuotaError{message: "recovery backup is unavailable"}
	} else if pathErr != nil && !errors.Is(pathErr, fs.ErrNotExist) {
		return &RecoveryQuotaError{message: "recovery backup is unavailable"}
	}
	previous := pathErr == nil || err == nil
	if previous {
		if err := os.Link(path, prior); err != nil {
			return &RecoveryQuotaError{message: "recovery backup is unavailable"}
		}
		if err := syncDir(filepath.Dir(path)); err != nil {
			_ = os.Remove(prior)
			return &RecoveryQuotaError{message: "recovery backup could not be made durable"}
		}
	}
	if err := os.Rename(tmpName, path); err != nil {
		return &RecoveryQuotaError{message: "recovery backup is unavailable"}
	}
	// Top-down, so each directory entry is durable before the contents of the next are flushed.
	for _, dir := range dirs {
		if err := syncDir(dir); err != nil {
			rollbackRecoveryRecord(path, prior, previous)
			_ = syncDir(filepath.Dir(path))
			return &RecoveryQuotaError{message: "recovery backup could not be made durable"}
		}
	}
	// The replacement is durable; failure to clean up the rollback link cannot invalidate it.
	if previous && os.Remove(prior) == nil {
		_ = syncDir(filepath.Dir(path))
	}
	return nil
}

// rollbackRecoveryRecord restores the previous record, or removes the record it just published when
// there was none. A previous record that cannot be renamed back is left at its prior link, which is
// then its only copy.
func rollbackRecoveryRecord(path, prior string, previous bool) {
	if !previous {
		_ = os.Remove(path)
		return
	}
	_ = os.Rename(prior, path)
}

// ReadRecoveryBackup reads and validates the record, returning nil when it does not exist.
func ReadRecoveryBackup(config config.DaemonConfig, viewID string, quota int64) (*RecoveryBackup, error) {
	return readRecoveryBackup(config, viewID, quota, false)
}

// ReadPriorRecoveryBackup reads a retained rollback record without modifying either copy.
func ReadPriorRecoveryBackup(config config.DaemonConfig, viewID string, quota int64) (*RecoveryBackup, error) {
	return readRecoveryBackup(config, viewID, quota, true)
}

func readRecoveryBackup(config config.DaemonConfig, viewID string, quota int64, prior bool) (*RecoveryBackup, error) {
	if quota <= 0 {
		return nil, &RecoveryQuotaError{message: "recovery quota is invalid"}
	}
	path, err := recoveryPath(config, viewID)
	if err != nil {
		return nil, err
	}
	if prior {
		path = recoveryPriorPath(path)
	}
	if info, err := os.Lstat(path); err == nil {
		if !info.Mode().IsRegular() {
			return nil, &RecoveryQuotaError{message: "recovery backup is not a regular file"}
		}
		if info.Size() > quota {
			return nil, &RecoveryQuotaError{message: "recovery backup exceeds the configured quota"}
		}
	}
	data, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		dirInfo, dirErr := os.Stat(recoveryDirectory(config))
		if errors.Is(dirErr, fs.ErrNotExist) {
			return nil, nil
		}
		if dirErr != nil {
			return nil, &RecoveryQuotaError{message: fmt.Sprintf("recovery backup is unreadable: %s", fsCode(dirErr))}
		}
		if !dirInfo.IsDir() {
			return nil, &RecoveryQuotaError{message: "recovery backup is unreadable: ENOTDIR"}
		}
		return nil, nil
	}
	if err != nil {
		return nil, &RecoveryQuotaError{message: fmt.Sprintf("recovery backup is unreadable: %s", fsCode(err))}
	}
	if int64(len(data)) > quota {
		return nil, &RecoveryQuotaError{message: "recovery backup exceeds the configured quota"}
	}
	var backup RecoveryBackup
	if err := json.Unmarshal(data, &backup); err != nil {
		return nil, &RecoveryQuotaError{message: "recovery backup is corrupt"}
	}
	if err := validateBackup(backup); err != nil {
		return nil, err
	}
	if backup.ViewID != viewID {
		return nil, &RecoveryQuotaError{message: "recovery backup is corrupt"}
	}
	var textBytes int64
	for _, document := range backup.Documents {
		textBytes += int64(len(document.Text))
	}
	if textBytes > quota {
		return nil, &RecoveryQuotaError{message: "recovery backup exceeds the configured quota"}
	}
	return &backup, nil
}

func validateBackup(backup RecoveryBackup) error {
	if _, err := ValidateViewID(backup.ViewID); err != nil {
		return &RecoveryQuotaError{message: "recovery backup is corrupt"}
	}
	if _, err := ValidateBindingEpoch(backup.BindingEpoch); err != nil || backup.Directory != "" && !filepath.IsAbs(backup.Directory) || backup.CreatedAt == "" || backup.Documents == nil || len(backup.Documents) > 256 {
		return &RecoveryQuotaError{message: "recovery backup is corrupt"}
	}
	for _, document := range backup.Documents {
		if !filepath.IsAbs(document.Path) || len([]rune(document.Path)) > 4096 || hasControl(document.Path) || !utf8.ValidString(document.Text) {
			return &RecoveryQuotaError{message: "recovery backup is corrupt"}
		}
	}
	return nil
}

func hasControl(value string) bool {
	for _, r := range value {
		if r < 0x20 || r == 0x7f {
			return true
		}
	}
	return false
}

func fsCode(err error) string {
	if errors.Is(err, fs.ErrNotExist) {
		return "ENOENT"
	}
	if errors.Is(err, fs.ErrPermission) {
		return "EACCES"
	}
	var errno syscall.Errno
	if errors.As(err, &errno) {
		switch errno {
		case syscall.EACCES:
			return "EACCES"
		case syscall.ENOENT:
			return "ENOENT"
		case syscall.ENOTDIR:
			return "ENOTDIR"
		case syscall.EPERM:
			return "EPERM"
		}
	}
	return "UNKNOWN"
}
