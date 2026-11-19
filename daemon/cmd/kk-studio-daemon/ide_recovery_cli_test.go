//go:build !windows

// POSIX permission bits back the owner-only export guarantee, so these cases run only
// where that guarantee is enforceable.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/ide"
)

const (
	recoveryViewA = "123e4567-e89b-42d3-a456-426614174000"
	recoveryViewB = "223e4567-e89b-42d3-a456-426614174001"
	recoveryDraft = "synthetic unsaved draft line"
)

// seedRecovery writes synthetic records under a fresh data directory.
func seedRecovery(t *testing.T, backups ...ide.RecoveryBackup) string {
	t.Helper()
	dataDir := t.TempDir()
	cfg := config.DaemonConfig{DataDir: dataDir}
	if err := os.MkdirAll(ideRecoveryDirectory(cfg), 0o700); err != nil {
		t.Fatal(err)
	}
	for _, backup := range backups {
		backup.CreatedAt = "2025-02-03T04:05:06Z"
		if backup.Documents == nil {
			backup.Documents = []ide.RecoveryDocument{}
		}
		if backup.BindingEpoch == "" {
			backup.BindingEpoch = "7"
		}
		if backup.Directory == "" {
			backup.Directory = filepath.Join(dataDir, "work")
		}
		if _, err := ide.WriteRecoveryBackup(cfg, backup, ideRecoveryQuota); err != nil {
			t.Fatalf("seed recovery backup: %v", err)
		}
	}
	return dataDir
}

func seededBackup(viewID string, complete bool) ide.RecoveryBackup {
	return ide.RecoveryBackup{
		ViewID:    viewID,
		Complete:  complete,
		Documents: []ide.RecoveryDocument{{Path: "/workspace/draft.txt", Text: recoveryDraft}},
	}
}

func runRecovery(t *testing.T, args ...string) (int, string, string) {
	t.Helper()
	var stdout, stderr bytes.Buffer
	code := RunCLI(context.Background(), args, Dependencies{}, &stdout, &stderr)
	return code, stdout.String(), stderr.String()
}

// Listing reports only identity, time and completeness, ordered by view id.
func TestIDERecoveryListMetadata(t *testing.T) {
	dataDir := seedRecovery(t, seededBackup(recoveryViewB, false), seededBackup(recoveryViewA, true))
	code, stdout, stderr := runRecovery(t, "ide-recovery", "list", "--data-dir", dataDir)
	if code != 0 || stderr != "" {
		t.Fatalf("code=%d stderr=%q", code, stderr)
	}
	if strings.Contains(stdout, recoveryDraft) || strings.Contains(stdout, "draft.txt") {
		t.Fatalf("listing leaked draft content: %q", stdout)
	}
	var listing struct {
		Backups []ideRecoveryEntry `json:"backups"`
	}
	if err := json.Unmarshal([]byte(stdout), &listing); err != nil {
		t.Fatalf("listing is not JSON: %v in %q", err, stdout)
	}
	if len(listing.Backups) != 2 || listing.Backups[0].ViewID != recoveryViewA || listing.Backups[1].ViewID != recoveryViewB {
		t.Fatalf("listing = %#v", listing.Backups)
	}
	if got := listing.Backups[0]; got.Complete == nil || !*got.Complete || got.CreatedAt != "2025-02-03T04:05:06Z" {
		t.Fatalf("complete entry = %#v", got)
	}
	if got := listing.Backups[1]; got.Complete == nil || *got.Complete {
		t.Fatalf("incomplete entry = %#v", got)
	}
}

// A missing recovery directory and unreadable records stay non-fatal, path-free reports.
func TestIDERecoveryListDegradedRecords(t *testing.T) {
	code, stdout, stderr := runRecovery(t, "ide-recovery", "list", "--data-dir", t.TempDir())
	if code != 0 || stderr != "" || strings.TrimSpace(stdout) != "{\n  \"backups\": []\n}" {
		t.Fatalf("empty listing code=%d stdout=%q stderr=%q", code, stdout, stderr)
	}

	dataDir := seedRecovery(t, seededBackup(recoveryViewA, true))
	directory := ideRecoveryDirectory(config.DaemonConfig{DataDir: dataDir})
	writeFile(t, filepath.Join(directory, recoveryViewB+".json"), "{")
	writeFile(t, filepath.Join(directory, "unrelated.json"), "{}")
	writeFile(t, filepath.Join(directory, "notes.txt"), "ignore me")
	code, stdout, stderr = runRecovery(t, "ide-recovery", "list", "--data-dir", dataDir)
	if code != 0 || stderr != "" {
		t.Fatalf("code=%d stderr=%q", code, stderr)
	}
	var listing struct {
		Backups []ideRecoveryEntry `json:"backups"`
	}
	if err := json.Unmarshal([]byte(stdout), &listing); err != nil {
		t.Fatalf("listing is not JSON: %v in %q", err, stdout)
	}
	if len(listing.Backups) != 2 || listing.Backups[0].ViewID != recoveryViewA || listing.Backups[0].Problem != "" || listing.Backups[1].Problem != "recovery backup is corrupt" {
		t.Fatalf("listing = %#v", listing.Backups)
	}

	if err := os.RemoveAll(directory); err != nil {
		t.Fatal(err)
	}
	writeFile(t, directory, "not a directory")
	code, stdout, stderr = runRecovery(t, "ide-recovery", "list", "--data-dir", dataDir)
	if code != 1 || stdout != "" || stderr != "IDE recovery list failed: recovery directory is unreadable: ENOTDIR\n" {
		t.Fatalf("unreadable listing code=%d stdout=%q stderr=%q", code, stdout, stderr)
	}
}

// Export copies the validated record to a new owner-only file and leaves the original intact.
func TestIDERecoveryExportWritesOwnerOnlyCopy(t *testing.T) {
	dataDir := seedRecovery(t, seededBackup(recoveryViewA, true))
	original := readFile(t, filepath.Join(ideRecoveryDirectory(config.DaemonConfig{DataDir: dataDir}), recoveryViewA+".json"))
	output := filepath.Join(t.TempDir(), "export.json")
	code, stdout, stderr := runRecovery(t, "ide-recovery", "export", recoveryViewA, output, "--data-dir", dataDir)
	if code != 0 || stderr != "" {
		t.Fatalf("code=%d stderr=%q", code, stderr)
	}
	if want := "Exported IDE recovery backup " + recoveryViewA + " (complete) to " + output + "\n"; stdout != want {
		t.Fatalf("stdout = %q, want %q", stdout, want)
	}
	var backup ide.RecoveryBackup
	if err := json.Unmarshal(readFile(t, output), &backup); err != nil {
		t.Fatalf("exported copy is not a backup: %v", err)
	}
	if backup.ViewID != recoveryViewA || !backup.Complete || len(backup.Documents) != 1 || backup.Documents[0].Text != recoveryDraft {
		t.Fatalf("exported copy = %#v", backup)
	}
	info, err := os.Stat(output)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("exported mode = %04o, want 0600", info.Mode().Perm())
	}
	originalPath := filepath.Join(ideRecoveryDirectory(config.DaemonConfig{DataDir: dataDir}), recoveryViewA+".json")
	if !bytes.Equal(original, readFile(t, originalPath)) {
		t.Fatal("export modified the persisted original")
	}
}

// An incomplete record exports with explicit status rather than implied safety.
func TestIDERecoveryExportIncompleteStatus(t *testing.T) {
	dataDir := seedRecovery(t, seededBackup(recoveryViewA, false))
	code, stdout, stderr := runRecovery(t, "ide-recovery", "export", recoveryViewA, filepath.Join(t.TempDir(), "export.json"), "--data-dir", dataDir)
	if code != 0 || stderr != "" || !strings.Contains(stdout, "(incomplete)") {
		t.Fatalf("code=%d stdout=%q stderr=%q", code, stdout, stderr)
	}
}

// Rejected exports never create, truncate or modify the destination.
func TestIDERecoveryExportRefusesUnsafeDestination(t *testing.T) {
	dataDir := seedRecovery(t, seededBackup(recoveryViewA, true))
	directory := ideRecoveryDirectory(config.DaemonConfig{DataDir: dataDir})
	existing := filepath.Join(t.TempDir(), "existing.json")
	writeFile(t, existing, "keep me\n")
	blocked := filepath.Join(t.TempDir(), "existing-directory")
	if err := os.Mkdir(blocked, 0o700); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(t.TempDir(), "recovery-link")
	if err := os.Symlink(directory, link); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	for _, tc := range []struct {
		name string
		args []string
		want string
	}{
		{name: "existing file", args: []string{"export", recoveryViewA, existing}, want: "output file already exists"},
		{name: "existing directory", args: []string{"export", recoveryViewA, blocked}, want: "output file already exists"},
		{name: "inside recovery", args: []string{"export", recoveryViewA, filepath.Join(directory, recoveryViewB+".json")}, want: "output path must be outside the IDE recovery directory"},
		{name: "recovery directory", args: []string{"export", recoveryViewA, directory}, want: "output path must be outside the IDE recovery directory"},
		{name: "symlinked recovery", args: []string{"export", recoveryViewA, filepath.Join(link, recoveryViewB+".json")}, want: "output path must be outside the IDE recovery directory"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			args := append([]string{"ide-recovery"}, tc.args...)
			code, stdout, stderr := runRecovery(t, append(args, "--data-dir", dataDir)...)
			if code != 1 || stdout != "" || stderr != "IDE recovery export failed: "+tc.want+"\n" {
				t.Fatalf("code=%d stdout=%q stderr=%q, want %q", code, stdout, stderr, tc.want)
			}
		})
	}
	if got := string(readFile(t, existing)); got != "keep me\n" {
		t.Fatalf("existing file was rewritten: %q", got)
	}
	entries, err := os.ReadDir(blocked)
	if err != nil || len(entries) != 0 {
		t.Fatalf("directory destination was modified: %v %v", entries, err)
	}
}

// Records that are not plain files are refused before the shared reader opens them.
func TestIDERecoveryRefusesNonRegularRecords(t *testing.T) {
	dataDir := seedRecovery(t)
	directory := ideRecoveryDirectory(config.DaemonConfig{DataDir: dataDir})
	if err := os.Mkdir(filepath.Join(directory, recoveryViewA+".json"), 0o700); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(t.TempDir(), "outside.json")
	writeFile(t, target, `{"viewId":"`+recoveryViewB+`"}`)
	if err := os.Symlink(target, filepath.Join(directory, recoveryViewB+".json")); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	code, stdout, stderr := runRecovery(t, "ide-recovery", "list", "--data-dir", dataDir)
	if code != 0 || stderr != "" || strings.Count(stdout, "recovery backup is not a regular file") != 2 {
		t.Fatalf("code=%d stdout=%q stderr=%q", code, stdout, stderr)
	}
	output := filepath.Join(t.TempDir(), "export.json")
	code, stdout, stderr = runRecovery(t, "ide-recovery", "export", recoveryViewB, output, "--data-dir", dataDir)
	if code != 1 || stdout != "" || stderr != "IDE recovery export failed: recovery backup is not a regular file\n" {
		t.Fatalf("symlink export code=%d stdout=%q stderr=%q", code, stdout, stderr)
	}
	if _, err := os.Stat(output); !os.IsNotExist(err) {
		t.Fatalf("rejected export left output behind: %v", err)
	}
}

// Argument and record validation fail closed with the fixed usage block or a safe message.
func TestIDERecoveryValidation(t *testing.T) {
	dataDir := seedRecovery(t, seededBackup(recoveryViewA, true))
	dataFile := filepath.Join(t.TempDir(), "not-a-directory")
	writeFile(t, dataFile, "x")
	invalidTimeDir := seedRecovery(t)
	writeFile(t, filepath.Join(ideRecoveryDirectory(config.DaemonConfig{DataDir: invalidTimeDir}), recoveryViewB+".json"), `{"viewId":"`+recoveryViewB+`","bindingEpoch":"1","directory":"/workspace","createdAt":"not-a-time","complete":true,"documents":[]}`)
	corruptDir := seedRecovery(t)
	writeFile(t, filepath.Join(ideRecoveryDirectory(config.DaemonConfig{DataDir: corruptDir}), recoveryViewB+".json"), "{")

	for _, tc := range []struct {
		name string
		args []string
		want string
	}{
		{name: "unknown subcommand", args: []string{"restore", "--data-dir", dataDir}, want: ideRecoveryUsage},
		{name: "missing subcommand", args: []string{"--data-dir", dataDir}, want: ideRecoveryUsage},
		{name: "missing data dir", args: []string{"list"}, want: ideRecoveryUsage},
		{name: "repeated data dir", args: []string{"list", "--data-dir", dataDir, "--data-dir", dataDir}, want: ideRecoveryUsage},
		{name: "list positional", args: []string{"list", recoveryViewA, "--data-dir", dataDir}, want: ideRecoveryUsage},
		{name: "export arity", args: []string{"export", recoveryViewA, "--data-dir", dataDir}, want: ideRecoveryUsage},
		{name: "relative data dir", args: []string{"list", "--data-dir", "relative"}, want: "IDE recovery data directory is unavailable: directory must be an absolute path\n"},
		{name: "missing data dir path", args: []string{"list", "--data-dir", filepath.Join(dataDir, "absent")}, want: "IDE recovery data directory is unavailable: directory does not exist\n"},
		{name: "data dir is a file", args: []string{"list", "--data-dir", dataFile}, want: "IDE recovery data directory is unavailable: directory is not a directory\n"},
		{name: "invalid view id", args: []string{"export", "not-a-view", filepath.Join(t.TempDir(), "o.json"), "--data-dir", dataDir}, want: "IDE recovery export failed: viewId must be a canonical lowercase UUID\n"},
		{name: "relative output", args: []string{"export", recoveryViewA, "out.json", "--data-dir", dataDir}, want: "IDE recovery export failed: output path must be an absolute path\n"},
		{name: "unknown view", args: []string{"export", recoveryViewB, filepath.Join(t.TempDir(), "o.json"), "--data-dir", dataDir}, want: "IDE recovery export failed: recovery backup was not found\n"},
		{name: "missing output parent", args: []string{"export", recoveryViewA, filepath.Join(t.TempDir(), "absent", "o.json"), "--data-dir", dataDir}, want: "IDE recovery export failed: output file could not be created: ENOENT\n"},
		{name: "corrupt record", args: []string{"export", recoveryViewB, filepath.Join(t.TempDir(), "o.json"), "--data-dir", corruptDir}, want: "IDE recovery export failed: recovery backup is corrupt\n"},
		{name: "invalid timestamp", args: []string{"export", recoveryViewB, filepath.Join(t.TempDir(), "o.json"), "--data-dir", invalidTimeDir}, want: "IDE recovery export failed: recovery backup timestamp is invalid\n"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			code, stdout, stderr := runRecovery(t, append([]string{"ide-recovery"}, tc.args...)...)
			if code != 1 || stdout != "" || stderr != tc.want {
				t.Fatalf("code=%d stdout=%q stderr=%q, want stderr %q", code, stdout, stderr, tc.want)
			}
		})
	}

	code, stdout, stderr := runRecovery(t, "ide-recovery", "list", "--data-dir", invalidTimeDir)
	if code != 0 || stderr != "" || strings.Contains(stdout, "not-a-time") || !strings.Contains(stdout, `"problem": "recovery backup timestamp is invalid"`) {
		t.Fatalf("invalid timestamp listing code=%d stdout=%q stderr=%q", code, stdout, stderr)
	}
}

// POSIX permission failures stay path-free and never leave a partial copy.
func TestIDERecoveryPermissionAndCleanup(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root bypasses directory permissions")
	}
	dataDir := seedRecovery(t, seededBackup(recoveryViewA, true))
	blocked := filepath.Join(t.TempDir(), "blocked")
	if err := os.Mkdir(blocked, 0o500); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chmod(blocked, 0o700) })
	code, stdout, stderr := runRecovery(t, "ide-recovery", "export", recoveryViewA, filepath.Join(blocked, "out.json"), "--data-dir", dataDir)
	if code != 1 || stdout != "" || stderr != "IDE recovery export failed: output file could not be created: EACCES\n" {
		t.Fatalf("blocked destination code=%d stdout=%q stderr=%q", code, stdout, stderr)
	}
	unreadable := filepath.Join(ideRecoveryDirectory(config.DaemonConfig{DataDir: dataDir}), recoveryViewB+".json")
	writeFile(t, unreadable, `{"viewId":"`+recoveryViewB+`"}`)
	if err := os.Chmod(unreadable, 0o000); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chmod(unreadable, 0o600) })
	output := filepath.Join(t.TempDir(), "out.json")
	code, stdout, stderr = runRecovery(t, "ide-recovery", "export", recoveryViewB, output, "--data-dir", dataDir)
	if code != 1 || stdout != "" || stderr != "IDE recovery export failed: recovery backup is unreadable: EACCES\n" {
		t.Fatalf("unreadable record code=%d stdout=%q stderr=%q", code, stdout, stderr)
	}
	if _, err := os.Stat(output); !os.IsNotExist(err) {
		t.Fatalf("failed export left output behind: %v", err)
	}
	code, stdout, stderr = runRecovery(t, "ide-recovery", "list", "--data-dir", dataDir)
	if code != 0 || stderr != "" || !strings.Contains(stdout, "recovery backup is unreadable: EACCES") {
		t.Fatalf("degraded listing code=%d stdout=%q stderr=%q", code, stdout, stderr)
	}
}

// The command needs no daemon configuration and is advertised by help.
func TestIDERecoveryIsLocalAndAdvertised(t *testing.T) {
	code, stdout, stderr := runRecovery(t, "--help")
	if code != 0 || stderr != "" || !strings.Contains(stdout, "ide-recovery list|export") {
		t.Fatalf("help code=%d stdout=%q stderr=%q", code, stdout, stderr)
	}
	dataDir := seedRecovery(t, seededBackup(recoveryViewA, true))
	if _, err := os.Stat(filepath.Join(dataDir, "daemon.json")); !os.IsNotExist(err) {
		t.Fatalf("fixture unexpectedly has daemon configuration: %v", err)
	}
	if code, _, stderr = runRecovery(t, "ide-recovery", "list", "--data-dir", dataDir); code != 0 || stderr != "" {
		t.Fatalf("recovery required configuration: code=%d stderr=%q", code, stderr)
	}
}

// Other daemon arguments are not claimed by the recovery command.
func TestIDERecoveryLeavesOtherCommandsAlone(t *testing.T) {
	for _, args := range [][]string{nil, {"--version"}, {"--config", "/x"}} {
		if handled, _ := runIDERecoveryCLI(args, &bytes.Buffer{}, &bytes.Buffer{}); handled {
			t.Fatalf("%q was claimed", args)
		}
	}
}

// writeFile writes a synthetic fixture file, creating parents as needed.
func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
}

func readFile(t *testing.T, path string) []byte {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return data
}
