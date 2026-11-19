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
	"strings"
	"syscall"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/ide"
)

const (
	ideRecoveryCommand = "ide-recovery"
	// ideRecoveryQuota mirrors the IDE service default so the command validates persisted
	// records like the daemon without reading configuration or credentials.
	ideRecoveryQuota = int64(16 << 20)
	ideRecoveryUsage = "Usage: kk-studio-daemon ide-recovery list --data-dir <absolute-path>\n" +
		"       kk-studio-daemon ide-recovery export <viewId> <absolute-json> --data-dir <absolute-path>\n"
)

// recoveryOwnerOnlySupported reports whether the exported copy can be restricted to the
// current user. Windows access control does not follow the permission bits used here, so
// export fails closed there instead of publishing a copy it cannot protect.
const recoveryOwnerOnlySupported = runtime.GOOS != "windows"

// ideRecoveryEntry is one backup summary; draft text and document paths never appear.
type ideRecoveryEntry struct {
	ViewID    string `json:"viewId"`
	CreatedAt string `json:"createdAt,omitempty"`
	Complete  *bool  `json:"complete,omitempty"`
	Problem   string `json:"problem,omitempty"`
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
	if command == "list" {
		output, err = listIDERecovery(cfg)
	} else {
		output, err = exportIDERecovery(cfg, positional[0], positional[1])
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
	case "export":
		return command, positional, dataDir, dataDir != "" && len(positional) == 2
	}
	return "", nil, "", false
}

// listIDERecovery reports safe identity, time and completeness metadata per record.
func listIDERecovery(cfg config.DaemonConfig) (string, error) {
	entries, err := os.ReadDir(ideRecoveryDirectory(cfg))
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		return "", fmt.Errorf("recovery directory is unreadable: %s", recoveryFSCode(err))
	}
	listing := struct {
		Backups []ideRecoveryEntry `json:"backups"`
	}{Backups: []ideRecoveryEntry{}}
	for _, entry := range entries {
		viewID, ok := strings.CutSuffix(entry.Name(), ".json")
		if !ok {
			continue
		}
		if _, err := ide.ValidateViewID(viewID); err != nil {
			continue
		}
		backup, err := readIDERecovery(cfg, viewID)
		switch {
		case err != nil:
			listing.Backups = append(listing.Backups, ideRecoveryEntry{ViewID: viewID, Problem: err.Error()})
		case backup != nil:
			createdAt, ok := recoveryTimestamp(backup.CreatedAt)
			if !ok {
				listing.Backups = append(listing.Backups, ideRecoveryEntry{ViewID: viewID, Problem: "recovery backup timestamp is invalid"})
				continue
			}
			complete := backup.Complete
			listing.Backups = append(listing.Backups, ideRecoveryEntry{ViewID: viewID, CreatedAt: createdAt, Complete: &complete})
		}
	}
	data, err := json.MarshalIndent(listing, "", "  ")
	if err != nil {
		return "", errors.New("recovery listing could not be encoded")
	}
	return string(data) + "\n", nil
}

// exportIDERecovery copies one validated record to a new owner-only file; the persisted
// record is never modified and an existing destination is never overwritten.
func exportIDERecovery(cfg config.DaemonConfig, viewID, output string) (string, error) {
	if !recoveryOwnerOnlySupported {
		return "", errors.New("owner-only output permissions cannot be enforced on Windows")
	}
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
	backup, err := readIDERecovery(cfg, viewID)
	if err != nil {
		return "", err
	}
	if backup == nil {
		return "", errors.New("recovery backup was not found")
	}
	if _, ok := recoveryTimestamp(backup.CreatedAt); !ok {
		return "", errors.New("recovery backup timestamp is invalid")
	}
	data, err := json.MarshalIndent(backup, "", "  ")
	if err != nil {
		return "", errors.New("recovery backup could not be encoded")
	}
	if err := writeOwnerOnlyFile(destination, append(data, '\n')); err != nil {
		return "", err
	}
	status := "incomplete"
	if backup.Complete {
		status = "complete"
	}
	return fmt.Sprintf("Exported IDE recovery backup %s (%s) to %s\n", viewID, status, destination), nil
}

func ideRecoveryDirectory(cfg config.DaemonConfig) string {
	return filepath.Join(ide.IDEPathsFor(cfg).IDEDir, "recovery")
}

// readIDERecovery rejects symlinks, FIFOs and directories before the shared reader opens
// the record, so listing and export never follow a link or block on a pipe. Any other stat
// failure falls through to the reader's safe diagnostic.
func readIDERecovery(cfg config.DaemonConfig, viewID string) (*ide.RecoveryBackup, error) {
	info, err := os.Lstat(filepath.Join(ideRecoveryDirectory(cfg), viewID+".json"))
	if err == nil {
		if !info.Mode().IsRegular() {
			return nil, errors.New("recovery backup is not a regular file")
		}
		if info.Size() > ideRecoveryQuota {
			return nil, errors.New("recovery backup exceeds the configured quota")
		}
	}
	return ide.ReadRecoveryBackup(cfg, viewID, ideRecoveryQuota)
}

// insideRecovery resolves an existing destination parent so a symlinked directory cannot
// redirect the copy into the recovery store.
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
	target, dirErr := filepath.EvalSymlinks(dir)
	return parentErr == nil && dirErr == nil && isWithin(filepath.Join(parent, filepath.Base(path)), target)
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
func writeOwnerOnlyFile(path string, data []byte) error {
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		if errors.Is(err, fs.ErrExist) {
			return errors.New("output file already exists")
		}
		return fmt.Errorf("output file could not be created: %s", recoveryFSCode(err))
	}
	_, err = file.Write(data)
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		_ = os.Remove(path)
		return fmt.Errorf("output file could not be written: %s", recoveryFSCode(err))
	}
	return nil
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
