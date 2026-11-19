//go:build windows

// Windows access control does not honor the permission bits used for the copied record,
// so the command must fail closed instead of publishing a copy it cannot restrict.
package main

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"testing"
)

// Export refuses to run on Windows, leaving no copy and reaching no record reader.
func TestIDERecoveryExportFailsClosedOnWindows(t *testing.T) {
	dataDir := t.TempDir()
	output := filepath.Join(t.TempDir(), "export.json")
	var stdout, stderr bytes.Buffer
	code := RunCLI(context.Background(), []string{"ide-recovery", "export", "123e4567-e89b-42d3-a456-426614174000", output, "--data-dir", dataDir}, Dependencies{}, &stdout, &stderr)
	if code != 1 || stdout.Len() != 0 || stderr.String() != "IDE recovery export failed: owner-only output permissions cannot be enforced on Windows\n" {
		t.Fatalf("code=%d stdout=%q stderr=%q", code, stdout.String(), stderr.String())
	}
	if _, err := os.Stat(output); !os.IsNotExist(err) {
		t.Fatalf("refused export left output behind: %v", err)
	}
}

// Listing and argument validation stay available so operators can inspect a host.
func TestIDERecoveryRemainingCommandsOnWindows(t *testing.T) {
	dataDir := t.TempDir()
	var stdout, stderr bytes.Buffer
	if code := RunCLI(context.Background(), []string{"ide-recovery", "list", "--data-dir", dataDir}, Dependencies{}, &stdout, &stderr); code != 0 || stderr.Len() != 0 {
		t.Fatalf("list code=%d stderr=%q", code, stderr.String())
	}
	stderr.Reset()
	if code := RunCLI(context.Background(), []string{"ide-recovery", "export", "view"}, Dependencies{}, &stdout, &stderr); code != 1 || stderr.String() != ideRecoveryUsage {
		t.Fatalf("usage code=%d stderr=%q", code, stderr.String())
	}
}
