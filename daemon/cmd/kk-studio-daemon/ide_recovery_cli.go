package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"syscall"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/ide"
	"github.com/fengwk/kk-studio/daemon/internal/native"
)

const (
	ideRecoveryCommand = "ide-recovery"
	// ideRecoveryQuota mirrors the IDE service default so the command validates persisted
	// records like the daemon without reading configuration or credentials.
	ideRecoveryQuota = int64(16 << 20)
	ideRecoveryUsage = "Usage: kk-studio-daemon ide-recovery list --data-dir <absolute-path>\n" +
		"       kk-studio-daemon ide-recovery export <viewId>[.prior] <absolute-json> --data-dir <absolute-path>\n" +
		"       kk-studio-daemon ide-recovery archive <viewId>[.prior] <new-absolute-output-json> --data-dir <absolute-path>\n"
	recoveryArchiveSupported = runtime.GOOS == "linux" || runtime.GOOS == "darwin"
)

// recoveryOwnerOnlySupported reports whether the exported copy can be restricted to the
// current user. Windows access control does not follow the permission bits used here, so
// export fails closed there instead of publishing a copy it cannot protect.
const recoveryOwnerOnlySupported = runtime.GOOS != "windows"

// ideRecoveryEntry is one backup summary; draft text and document paths never appear. A prior
// record carries `prior:true` so an operator can tell the two records of one view apart.
type ideRecoveryEntry struct {
	ViewID    string `json:"viewId"`
	Prior     bool   `json:"prior,omitempty"`
	CreatedAt string `json:"createdAt,omitempty"`
	Complete  *bool  `json:"complete,omitempty"`
	Problem   string `json:"problem,omitempty"`
	Bytes     *int64 `json:"bytes,omitempty"`
}

// runIDERecoveryCLI serves the private-host recovery command. It reads only persisted
// records under an explicit data directory and never loads configuration or credentials.
func runIDERecoveryCLI(args []string, stdout, stderr io.Writer) (bool, int) {
	if len(args) == 0 || args[0] != ideRecoveryCommand {
		return false, 0
	}
	command, positional, dataDir, ok := parseIDERecoveryArgs(args[1:])
	if !ok {
		fmt.Fprint(stderr, ideRecoveryUsage)
		return true, 1
	}
	directory, err := ide.CanonicalDirectory(dataDir)
	if err != nil {
		fmt.Fprintf(stderr, "IDE recovery data directory is unavailable: %s\n", safeError(err))
		return true, 1
	}
	cfg := config.DaemonConfig{DataDir: directory}
	output := ""
	switch command {
	case "list":
		output, err = listIDERecovery(cfg)
	case "export":
		output, err = copyIDERecovery(cfg, positional[0], positional[1], false)
	case "archive":
		output, err = archiveIDERecovery(cfg, positional[0], positional[1])
	}
	if err != nil {
		fmt.Fprintf(stderr, "IDE recovery %s failed: %s\n", command, safeError(err))
		return true, 1
	}
	fmt.Fprint(stdout, output)
	return true, 0
}

// parseIDERecoveryArgs accepts one subcommand, one --data-dir value and the subcommand's
// positional arguments in any order.
func parseIDERecoveryArgs(args []string) (string, []string, string, bool) {
	command, dataDir := "", ""
	positional := make([]string, 0, 2)
	for index := 0; index < len(args); index++ {
		switch {
		case args[index] == "--data-dir" && dataDir == "" && index+1 < len(args):
			index++
			dataDir = args[index]
		case command == "":
			command = args[index]
		default:
			positional = append(positional, args[index])
		}
	}
	switch command {
	case "list":
		return command, positional, dataDir, dataDir != "" && len(positional) == 0
	case "export", "archive":
		return command, positional, dataDir, dataDir != "" && len(positional) == 2
	}
	return "", nil, "", false
}

// listIDERecovery reports safe identity, time and completeness metadata per record, including
// the prior record kept for a view so both copies of one view can be compared.
func listIDERecovery(cfg config.DaemonConfig) (string, error) {
	entries, err := os.ReadDir(ideRecoveryDirectory(cfg))
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		return "", fmt.Errorf("recovery directory is unreadable: %s", recoveryFSCode(err))
	}
	listing := struct {
		Backups []ideRecoveryEntry `json:"backups"`
	}{Backups: []ideRecoveryEntry{}}
	for _, entry := range entries {
		name := entry.Name()
		viewID, canonical := strings.CutSuffix(name, ".json")
		prior := !canonical && strings.HasPrefix(name, ".") && strings.HasSuffix(name, ".prior")
		if prior {
			viewID = strings.TrimSuffix(strings.TrimPrefix(name, "."), ".prior")
		}
		if _, err := ide.ValidateViewID(viewID); err != nil || !(canonical || prior) {
			continue
		}
		summary := ideRecoveryEntry{ViewID: viewID, Prior: prior}
		if info, err := entry.Info(); err == nil && info.Mode().IsRegular() {
			size := info.Size()
			summary.Bytes = &size
		}
		backup, err := readIDERecovery(cfg, viewID, prior)
		switch {
		case err != nil:
			summary.Problem = err.Error()
		case backup == nil:
			continue
		default:
			if createdAt, ok := recoveryTimestamp(backup.CreatedAt); ok {
				summary.CreatedAt = createdAt
				summary.Complete = &backup.Complete
			} else {
				summary.Problem = "recovery backup timestamp is invalid"
			}
		}
		listing.Backups = append(listing.Backups, summary)
	}
	sort.Slice(listing.Backups, func(i, j int) bool {
		a, b := listing.Backups[i], listing.Backups[j]
		if a.ViewID == b.ViewID {
			return !a.Prior && b.Prior
		}
		return a.ViewID < b.ViewID
	})
	data, err := json.MarshalIndent(listing, "", "  ")
	if err != nil {
		return "", errors.New("recovery listing could not be encoded")
	}
	return string(data) + "\n", nil
}

// archiveIDERecovery holds the same lock as the daemon from record validation through removal.
func archiveIDERecovery(cfg config.DaemonConfig, viewArg, output string) (string, error) {
	if !recoveryArchiveSupported {
		return "", errors.New("archive is supported only on Linux/macOS")
	}
	lock, err := native.AcquireDirectoryLock(cfg.DataDir, native.DaemonLockFileName)
	if errors.Is(err, native.ErrDirectoryLockBusy) {
		return "", errors.New("data directory is in use; stop the daemon or wait for the other archive to finish")
	}
	if err != nil {
		return "", fmt.Errorf("data directory lock could not be acquired: %s", recoveryFSCode(err))
	}
	defer lock.Close()
	return copyIDERecovery(cfg, viewArg, output, true)
}

// copyIDERecovery validates and copies the selected record without overwriting the destination.
// Only the offline archive operation removes that record, and only after the copy is durable.
func copyIDERecovery(cfg config.DaemonConfig, viewArg, output string, archive bool) (string, error) {
	if !recoveryOwnerOnlySupported {
		return "", errors.New("owner-only output permissions cannot be enforced on Windows")
	}
	viewID, prior := strings.CutSuffix(viewArg, ".prior")
	viewID, err := ide.ValidateViewID(viewID)
	if err != nil {
		return "", err
	}
	if !filepath.IsAbs(output) {
		return "", errors.New("output path must be an absolute path")
	}
	destination := filepath.Clean(output)
	if insideRecovery(destination, cfg) {
		return "", errors.New("output path must be outside the IDE recovery directory")
	}
	backup, err := readIDERecovery(cfg, viewID, prior)
	if err != nil {
		return "", err
	}
	if backup == nil {
		if prior {
			return "", errors.New("recovery prior backup was not found")
		}
		return "", errors.New("recovery backup was not found")
	}
	if _, ok := recoveryTimestamp(backup.CreatedAt); !ok {
		return "", errors.New("recovery backup timestamp is invalid")
	}
	data, err := json.MarshalIndent(backup, "", "  ")
	if err != nil {
		return "", errors.New("recovery backup could not be encoded")
	}
	if err := writeOwnerOnlyFile(destination, append(data, '\n'), archive); err != nil {
		return "", err
	}
	verb := "Exported"
	if archive {
		verb = "Archived"
		name := viewID + ".json"
		if prior {
			name = "." + viewID + ".prior"
		}
		dir := ideRecoveryDirectory(cfg)
		// The external copy is committed now. No later failure may clean it up.
		if err := os.Remove(filepath.Join(dir, name)); err != nil {
			return "", fmt.Errorf("durable output kept; source record could not be removed: %s", recoveryFSCode(err))
		}
		if err := syncIDERecoveryDirectory(dir); err != nil {
			return "", fmt.Errorf("durable output kept; source removal could not be synced: %s", recoveryFSCode(err))
		}
	}
	status := "incomplete"
	if backup.Complete {
		status = "complete"
	}
	label := "backup"
	if prior {
		label = "prior backup"
	}
	return fmt.Sprintf("%s IDE recovery %s %s (%s) to %s\n", verb, label, viewID, status, destination), nil
}

func ideRecoveryDirectory(cfg config.DaemonConfig) string {
	return filepath.Join(ide.IDEPathsFor(cfg).IDEDir, "recovery")
}

// readIDERecovery delegates to the shared reader, which rejects symlinks, FIFOs, directories and
// oversized records before reading them, so listing and export never follow a link or block on a
// pipe. The prior record is the previous copy kept until its replacement was durable.
func readIDERecovery(cfg config.DaemonConfig, viewID string, prior bool) (*ide.RecoveryBackup, error) {
	if prior {
		return ide.ReadPriorRecoveryBackup(cfg, viewID, ideRecoveryQuota)
	}
	return ide.ReadRecoveryBackup(cfg, viewID, ideRecoveryQuota)
}

// insideRecovery checks both the destination itself and its resolved ancestors so an
// alias of the recovery directory cannot redirect the copy into the recovery store.
func insideRecovery(path string, cfg config.DaemonConfig) bool {
	dir := ideRecoveryDirectory(cfg)
	isWithin := func(path, dir string) bool {
		relative, err := filepath.Rel(dir, path)
		return err == nil && relative != ".." && !strings.HasPrefix(relative, ".."+string(filepath.Separator))
	}
	if isWithin(path, dir) {
		return true
	}
	parent, parentErr := filepath.EvalSymlinks(filepath.Dir(path))
	target, dirErr := os.Stat(dir)
	if parentErr != nil || dirErr != nil {
		return false
	}
	// Compare directory identity as well as lexical paths, including on case-insensitive macOS.
	if info, err := os.Stat(path); err == nil && os.SameFile(info, target) {
		return true
	}
	for {
		info, err := os.Stat(parent)
		if err == nil && os.SameFile(info, target) {
			return true
		}
		next := filepath.Dir(parent)
		if next == parent {
			return false
		}
		parent = next
	}
}

// recoveryTimestamp returns the record time in canonical UTC form, or false when the value
// is not a parseable timestamp; the shared reader only checks that it is present.
func recoveryTimestamp(value string) (string, bool) {
	parsed, err := time.Parse(time.RFC3339Nano, value)
	if err != nil {
		return "", false
	}
	return parsed.UTC().Format(time.RFC3339Nano), true
}

// writeOwnerOnlyFile creates the copy exclusively with owner-only permissions and removes
// a partial file instead of leaving a truncated backup behind.
func writeOwnerOnlyFile(path string, data []byte, durable bool) error {
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		if errors.Is(err, fs.ErrExist) {
			return errors.New("output file already exists")
		}
		return fmt.Errorf("output file could not be created: %s", recoveryFSCode(err))
	}
	if err = file.Chmod(0o600); err == nil {
		_, err = file.Write(data)
	}
	if err == nil && durable {
		err = file.Sync()
	}
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		_ = os.Remove(path)
		return fmt.Errorf("output file could not be written: %s", recoveryFSCode(err))
	}
	if durable {
		if err := syncIDERecoveryDirectory(filepath.Dir(path)); err != nil {
			_ = os.Remove(path)
			return fmt.Errorf("output directory could not be synced: %s", recoveryFSCode(err))
		}
	}
	return nil
}

func syncIDERecoveryDirectory(path string) error {
	dir, err := os.Open(path)
	if err != nil {
		return err
	}
	return errors.Join(dir.Sync(), dir.Close())
}

// recoveryFSCode keeps filesystem diagnostics to a stable, path-free errno token.
func recoveryFSCode(err error) string {
	var code syscall.Errno
	if errors.As(err, &code) {
		switch code {
		case syscall.EACCES, syscall.EPERM:
			return "EACCES"
		case syscall.ENOENT:
			return "ENOENT"
		case syscall.ENOTDIR:
			return "ENOTDIR"
		}
	}
	return "UNKNOWN"
}
