//go:build windows

package native

import (
	"errors"
	"os"
	"testing"

	"golang.org/x/sys/windows"
)

// TestWindowsSupervisor_CleanupFailedStartReopensAndClosesNamedJob verifies recoverable Job cleanup removes stale state.
func TestWindowsSupervisor_CleanupFailedStartReopensAndClosesNamedJob(t *testing.T) {
	dir := stateTestDir(t)
	name := jobNameFor(dir)
	original, err := createWindowsJob(name)
	if err != nil {
		t.Fatal(err)
	}
	defer windows.CloseHandle(original)
	if err := publishText(dir, scopeFile, "1234"); err != nil {
		t.Fatal(err)
	}
	process := &windowsProcess{
		supervisor: &windowsSupervisor{processes: make(map[int]*windowsProcess)},
		pid:        123,
		stateDir:   dir,
		jobName:    name,
	}
	cause := errors.New("injected failed start")
	if err := process.cleanupFailedStart(cause); !errors.Is(err, cause) {
		t.Fatalf("failed-start cleanup = %v, want cause %v", err, cause)
	}
	if _, err := os.Stat(dir); !os.IsNotExist(err) {
		t.Fatalf("state directory after proven cleanup = %v", err)
	}
}

// TestWindowsSupervisor_CleanupFailedStartPreservesStateWhenJobCannotBeReopened verifies missing Job evidence is retained.
func TestWindowsSupervisor_CleanupFailedStartPreservesStateWhenJobCannotBeReopened(t *testing.T) {
	dir := stateTestDir(t)
	if err := publishText(dir, scopeFile, "1234"); err != nil {
		t.Fatal(err)
	}
	process := &windowsProcess{
		supervisor: &windowsSupervisor{processes: make(map[int]*windowsProcess)},
		pid:        123,
		stateDir:   dir,
		jobName:    jobNameFor(dir),
	}
	cause := errors.New("injected failed start")
	err := process.cleanupFailedStart(cause)
	var notConverged *ProcessScopeNotConvergedError
	if !errors.As(err, &notConverged) || !errors.Is(err, cause) {
		t.Fatalf("failed-start cleanup = %v, want typed unproven cleanup", err)
	}
	if _, err := os.Stat(dir); err != nil {
		t.Fatalf("state directory was removed without a Job handle: %v", err)
	}
}

// TestWindowsHelperStartRejectsInvalidStatePath checks UTF-16 path validation occurs before process creation.
func TestWindowsHelperStartRejectsInvalidStatePath(t *testing.T) {
	if _, err := startWindowsHelper("bad\x00state", nil, nil); err == nil {
		t.Fatal("helper start accepted a state path containing NUL")
	}
}
