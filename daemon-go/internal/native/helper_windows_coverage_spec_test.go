//go:build windows

package native

import (
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"golang.org/x/sys/windows"
)

// TestWindowsHelper_RejectsInvalidInputs verifies helper launch records fail closed before native work.
func TestWindowsHelper_RejectsInvalidInputs(t *testing.T) {
	for _, args := range [][]string{nil, {"relative"}, {filepath.Join(t.TempDir(), "bad\x00path")}} {
		if got := runWindowsHelper(args); got != 2 {
			t.Fatalf("helper result for %q = %d, want invalid arguments", args, got)
		}
	}

	dir := stateTestDir(t)
	t.Setenv(windowsStdinHandleEnv, "")
	if got := runWindowsHelper([]string{dir}); got != 1 {
		t.Fatalf("helper result without stdin handle = %d, want failure", got)
	}
	if failure, err := readState(dir, errorFile); err != nil || string(failure) != "helper-launch-failure" {
		t.Fatalf("helper launch failure record = %q, %v", failure, err)
	}
}

// TestWindowsHelper_RejectsPrivateStateAndLaunchFailures verifies private state and launch validation errors are recorded.
func TestWindowsHelper_RejectsPrivateStateAndLaunchFailures(t *testing.T) {
	t.Run("unverified state directory", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "not-private")
		if err := os.WriteFile(path, []byte("not a directory"), 0600); err != nil {
			t.Fatal(err)
		}
		t.Setenv(windowsStdinHandleEnv, "1234")
		if got := runWindowsHelper([]string{path}); got != 1 {
			t.Fatalf("helper result = %d, want launch failure", got)
		}
	})
	t.Run("invalid launch record", func(t *testing.T) {
		dir := stateTestDir(t)
		if err := publishText(dir, launchFile, "{"); err != nil {
			t.Fatal(err)
		}
		t.Setenv(windowsStdinHandleEnv, "1234")
		if got := runWindowsHelper([]string{dir}); got != 1 {
			t.Fatalf("helper result = %d, want launch failure", got)
		}
		if failure, err := readState(dir, errorFile); err != nil || string(failure) != "helper-launch-failure" {
			t.Fatalf("launch failure record = %q, %v", failure, err)
		}
	})
	for _, launch := range []string{`{"cwd":"C:\\work","args":[]}`, `{"cwd":"C:\\work","args":null}`} {
		t.Run(launch, func(t *testing.T) {
			dir := stateTestDir(t)
			if err := publishText(dir, launchFile, launch); err != nil {
				t.Fatal(err)
			}
			t.Setenv(windowsStdinHandleEnv, "1234")
			if got := runWindowsHelper([]string{dir}); got != 1 {
				t.Fatalf("helper result = %d, want launch failure", got)
			}
			if failure, err := readState(dir, errorFile); err != nil || string(failure) != "helper-windows-failure" {
				t.Fatalf("command failure record = %q, %v", failure, err)
			}
			if cleanup, err := readState(dir, cleanupFile); err != nil || string(cleanup) != "true" {
				t.Fatalf("cleanup record = %q, %v", cleanup, err)
			}
		})
	}
}

// TestWindowsHelper_FailedCommandCreationDrainsJob confirms a failed child launch releases its newly created Job.
func TestWindowsHelper_FailedCommandCreationDrainsJob(t *testing.T) {
	dir := stateTestDir(t)
	if err := publish(dir, launchFile, []byte(`{"cwd":"C:\\work","args":["Z:\\missing\\kk-native-command.exe"]}`)); err != nil {
		t.Fatal(err)
	}
	t.Setenv(windowsStdinHandleEnv, "1234")
	if got := runWindowsHelper([]string{dir}); got != 1 {
		t.Fatalf("helper result = %d, want failed child launch", got)
	}
	if failure, err := readState(dir, errorFile); err != nil || string(failure) != "helper-windows-failure" {
		t.Fatalf("child launch failure = %q, %v", failure, err)
	}
	if cleanup, err := readState(dir, cleanupFile); err != nil || string(cleanup) != "true" {
		t.Fatalf("Job cleanup = %q, %v", cleanup, err)
	}
}

// TestWindowsJobProtocol_RejectsUnreadablePermitAndExitRecords verifies state-file type errors trigger safe cleanup.
func TestWindowsJobProtocol_RejectsUnreadablePermitAndExitRecords(t *testing.T) {
	t.Run("permit is a directory", func(t *testing.T) {
		dir := stateTestDir(t)
		if err := os.Mkdir(filepath.Join(dir, permitFile), 0700); err != nil {
			t.Fatal(err)
		}
		api := &fakeWindowsJobProtocolAPI{waitStatus: windows.WAIT_OBJECT_0}
		owner, writer := io.Pipe()
		defer owner.Close()
		defer writer.Close()
		got := runWindowsJobProtocolWithAPI(dir, 5, windows.ProcessInformation{Process: 10, Thread: 11, ProcessId: 12}, owner, api)
		if got != 1 {
			t.Fatalf("protocol result = %d, want failure", got)
		}
		if !strings.Contains(strings.Join(api.calls, ","), "terminate") {
			t.Fatalf("protocol did not terminate the Job after invalid permit: %v", api.calls)
		}
	})
	t.Run("exit target is a directory", func(t *testing.T) {
		dir := stateTestDir(t)
		if err := publishText(dir, permitFile, "permit"); err != nil {
			t.Fatal(err)
		}
		if err := os.Mkdir(filepath.Join(dir, exitFile), 0700); err != nil {
			t.Fatal(err)
		}
		api := &fakeWindowsJobProtocolAPI{waitStatus: windows.WAIT_OBJECT_0, exitCode: 7}
		owner, writer := io.Pipe()
		defer owner.Close()
		defer writer.Close()
		got := runWindowsJobProtocolWithAPI(dir, 5, windows.ProcessInformation{Process: 10, Thread: 11, ProcessId: 12}, owner, api)
		if got != 1 {
			t.Fatalf("protocol result = %d, want failure", got)
		}
		if _, err := readState(dir, errorFile); err != nil {
			t.Fatalf("exit publication error was not recorded: %v", err)
		}
	})
}

// TestWindowsJobProtocol_FailsWhenCleanupRecordCannotPublish checks cleanup publication errors fail the helper closed.
func TestWindowsJobProtocol_FailsWhenCleanupRecordCannotPublish(t *testing.T) {
	dir := stateTestDir(t)
	if err := publishText(dir, permitFile, "permit"); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(dir, cleanupFile), 0700); err != nil {
		t.Fatal(err)
	}
	api := &fakeWindowsJobProtocolAPI{waitStatus: windows.WAIT_OBJECT_0, exitCode: 7}
	owner, writer := io.Pipe()
	defer owner.Close()
	defer writer.Close()
	got := runWindowsJobProtocolWithAPI(dir, 5, windows.ProcessInformation{Process: 10, Thread: 11, ProcessId: 12}, owner, api)
	if got != 1 {
		t.Fatalf("protocol result = %d, want cleanup failure", got)
	}
}
