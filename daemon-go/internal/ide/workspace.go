package ide

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
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

// ValidateExpectedBindingEpoch validates a canonical non-negative signed-long epoch.
func ValidateExpectedBindingEpoch(value string) (string, error) {
	if value == "0" {
		return value, nil
	}
	return ValidateBindingEpoch(value)
}

// HostHome returns HOME or USERPROFILE and refuses to guess when neither is set.
func HostHome(env map[string]string) (string, error) {
	if home := env["HOME"]; strings.TrimSpace(home) != "" {
		return home, nil
	}
	if home := env["USERPROFILE"]; strings.TrimSpace(home) != "" {
		return home, nil
	}
	return "", NewError("host HOME is not set", "UNAVAILABLE")
}

// CanonicalDirectory resolves symlinks and requires an existing directory.
func CanonicalDirectory(input string) (string, error) {
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

// DirectoryEntry is one real direct child directory.
type DirectoryEntry struct {
	Name string `json:"name"`
	Path string `json:"path"`
}

// DirectoryListing is bounded for safe inclusion in a control response.
type DirectoryListing struct {
	Path       string           `json:"path"`
	ParentPath string           `json:"parentPath,omitempty"`
	Children   []DirectoryEntry `json:"children"`
	Truncated  bool             `json:"truncated"`
}

// ListDirectories lists direct real directories in canonical name order, within protocol limits.
func ListDirectories(input string) (DirectoryListing, error) {
	path, err := CanonicalDirectory(input)
	if err != nil {
		return DirectoryListing{}, err
	}
	if len([]rune(path)) > 4096 || hasControl(path) {
		return DirectoryListing{}, NewError("directory path is invalid", "UNAVAILABLE")
	}
	listing := DirectoryListing{Path: path, Children: []DirectoryEntry{}}
	parent := filepath.Dir(path)
	if parent != path {
		listing.ParentPath = parent
	}
	entries, err := os.ReadDir(path)
	if err != nil {
		return DirectoryListing{}, NewError("directory cannot be listed", "UNAVAILABLE")
	}
	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		name := entry.Name()
		if strings.TrimSpace(name) == "" || hasControl(name) || len([]rune(filepath.Join(path, name))) > 4096 {
			listing.Truncated = true
			continue
		}
		var truncated bool
		names, truncated = insertDirectoryName(names, name)
		listing.Truncated = listing.Truncated || truncated
	}
	encoded, _ := json.Marshal(listing)
	budget := len(encoded)
	for _, name := range names {
		entry := DirectoryEntry{Name: name, Path: filepath.Join(path, name)}
		encoded, _ = json.Marshal(entry)
		if budget+len(encoded)+1 > 48*1024 {
			listing.Truncated = true
			break
		}
		budget += len(encoded) + 1
		listing.Children = append(listing.Children, entry)
	}
	return listing, nil
}

func insertDirectoryName(names []string, name string) ([]string, bool) {
	index := sort.SearchStrings(names, name)
	if index >= 1000 {
		return names, true
	}
	names = append(names, "")
	copy(names[index+1:], names[index:])
	names[index] = name
	if len(names) > 1000 {
		return names[:1000], true
	}
	return names, false
}

// WorkspaceFileContent returns the single-folder workspace JSON written for a view.
func WorkspaceFileContent(directory string) string {
	var output strings.Builder
	encoder := json.NewEncoder(&output)
	encoder.SetEscapeHTML(false)
	encoder.SetIndent("", "  ")
	_ = encoder.Encode(struct {
		Folders []struct {
			Path string `json:"path"`
		} `json:"folders"`
	}{Folders: []struct {
		Path string `json:"path"`
	}{{Path: directory}}})
	return output.String()
}

// RecoveryDocument contains one unsaved ordinary text buffer.
type RecoveryDocument struct {
	Path string `json:"path"`
	Text string `json:"text"`
}

// RecoveryBackup is the bounded persistent recovery payload for one managed view.
type RecoveryBackup struct {
	ViewID       string             `json:"viewId"`
	BindingEpoch string             `json:"bindingEpoch"`
	Directory    string             `json:"directory"`
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

// WriteRecoveryBackup stores a private, per-view recovery record within aggregate quota.
func WriteRecoveryBackup(config config.DaemonConfig, backup RecoveryBackup, quota int64) (int64, error) {
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
	entries, err := os.ReadDir(dir)
	if err != nil {
		return 0, &RecoveryQuotaError{message: "recovery quota could not be measured: UNKNOWN"}
	}
	var used int64
	for _, entry := range entries {
		if !strings.HasSuffix(entry.Name(), ".json") {
			continue
		}
		info, statErr := os.Stat(filepath.Join(dir, entry.Name()))
		if statErr != nil {
			return 0, &RecoveryQuotaError{message: "recovery quota could not be measured: UNKNOWN"}
		}
		if info.Mode().IsRegular() {
			used += info.Size()
			if entry.Name() == filepath.Base(path) {
				used -= info.Size()
			}
		}
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
	if err == nil {
		err = os.Rename(tmpName, path)
	}
	if err != nil {
		return 0, &RecoveryQuotaError{message: "recovery backup is unavailable"}
	}
	return int64(len(data)), nil
}

// ReadRecoveryBackup reads and validates the record, returning nil when it does not exist.
func ReadRecoveryBackup(config config.DaemonConfig, viewID string, quota int64) (*RecoveryBackup, error) {
	if quota <= 0 {
		return nil, &RecoveryQuotaError{message: "recovery quota is invalid"}
	}
	path, err := recoveryPath(config, viewID)
	if err != nil {
		return nil, err
	}
	data, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
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
	if _, err := ValidateBindingEpoch(backup.BindingEpoch); err != nil || !filepath.IsAbs(backup.Directory) || backup.CreatedAt == "" || backup.Documents == nil || len(backup.Documents) > 256 {
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
