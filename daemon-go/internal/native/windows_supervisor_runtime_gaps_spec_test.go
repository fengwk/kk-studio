//go:build windows

package native

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"testing"
	"time"

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

func startTerminatedWindowsFixture(t *testing.T) windows.Handle {
	t.Helper()
	cmd := exec.Command(os.Args[0], "__native-fixture", "hold")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if cmd.ProcessState == nil {
			_ = cmd.Process.Kill()
			_ = cmd.Wait()
		}
	})
	handle, err := windows.OpenProcess(windows.PROCESS_TERMINATE|windows.SYNCHRONIZE, false, uint32(cmd.Process.Pid))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = windows.CloseHandle(handle) })
	if err := windows.TerminateProcess(handle, 1); err != nil {
		t.Fatal(err)
	}
	if status, err := windows.WaitForSingleObject(handle, 1000); err != nil || status != windows.WAIT_OBJECT_0 {
		t.Fatalf("wait for terminated fixture = %#x, %v", status, err)
	}
	if err := cmd.Wait(); err == nil {
		t.Fatal("terminated fixture exited successfully")
	}
	return handle
}

// TestWindowsSupervisor_WaitSettlesAndClosesManagedPipes verifies valid cleanup and exit evidence settles resources.
func TestWindowsSupervisor_WaitSettlesAndClosesManagedPipes(t *testing.T) {
	dir := stateTestDir(t)
	job, err := createWindowsJob(jobNameFor(dir))
	if err != nil {
		t.Fatal(err)
	}
	defer windows.CloseHandle(job)
	helper := startTerminatedWindowsFixture(t)
	process := &windowsProcess{
		supervisor:  &windowsSupervisor{processes: make(map[int]*windowsProcess)},
		pid:         123,
		helper:      helper,
		job:         job,
		owner:       nil,
		stateDir:    dir,
		done:        make(chan struct{}),
		waitFailure: make(chan struct{}),
		helperCode:  errorExitUnknown,
		stdin:       &windowsManagedPipe{file: createCoverageTempFile(t)},
		stdout:      &windowsManagedPipe{file: createCoverageTempFile(t)},
		stderr:      &windowsManagedPipe{file: createCoverageTempFile(t)},
	}
	process.supervisor.processes[process.pid] = process
	if err := publishText(dir, cleanupFile, "true"); err != nil {
		t.Fatal(err)
	}
	if err := publish(dir, exitFile, []byte(`{"exitCode":17,"signal":null}`)); err != nil {
		t.Fatal(err)
	}
	close(process.done)
	exit, err := process.Wait(context.Background())
	if err != nil || exit.Code == nil || *exit.Code != 17 {
		t.Fatalf("Wait result = %+v, %v", exit, err)
	}
	if !process.settled || process.stdin != nil || process.stdout != nil || process.stderr != nil {
		t.Fatalf("settlement/managed pipes = %t/%v/%v/%v", process.settled, process.stdin, process.stdout, process.stderr)
	}
	if _, err := os.Stat(dir); !os.IsNotExist(err) {
		t.Fatalf("state directory after successful Wait = %v", err)
	}
}

func createCoverageTempFile(t *testing.T) *os.File {
	t.Helper()
	file, err := os.CreateTemp(t.TempDir(), "managed-pipe")
	if err != nil {
		t.Fatal(err)
	}
	return file
}

type killingWindowsJobAPI struct{}

func (killingWindowsJobAPI) TerminateProcess(process windows.Handle, code uint32) error {
	return windows.TerminateProcess(process, code)
}
func (killingWindowsJobAPI) WaitForSingleObject(process windows.Handle, milliseconds uint32) (uint32, error) {
	return windows.WaitForSingleObject(process, milliseconds)
}
func (killingWindowsJobAPI) TerminateJobObject(windows.Handle, uint32) error { return nil }
func (killingWindowsJobAPI) AwaitJobEmpty(context.Context, windows.Handle, time.Duration) error {
	return nil
}
func (killingWindowsJobAPI) CloseHandle(handle windows.Handle) error {
	if handle == 42 {
		return nil
	}
	return windows.CloseHandle(handle)
}

// TestWindowsSupervisor_TerminateEscalatesToHelper verifies helper kill follows a converged Job with a lingering helper.
func TestWindowsSupervisor_TerminateEscalatesToHelper(t *testing.T) {
	dir := stateTestDir(t)
	cmd := exec.Command(os.Args[0], "__native-fixture", "hold")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if cmd.ProcessState == nil {
			_ = cmd.Process.Kill()
			_ = cmd.Wait()
		}
	})
	helper, err := windows.OpenProcess(windows.PROCESS_TERMINATE|windows.SYNCHRONIZE, false, uint32(cmd.Process.Pid))
	if err != nil {
		t.Fatal(err)
	}
	process := &windowsProcess{
		supervisor:     &windowsSupervisor{processes: make(map[int]*windowsProcess)},
		pid:            321,
		helper:         helper,
		job:            42,
		stateDir:       dir,
		done:           make(chan struct{}),
		terminationAPI: killingWindowsJobAPI{},
	}
	process.supervisor.processes[process.pid] = process
	if err := process.Terminate(context.Background(), 0); err != nil {
		t.Fatalf("termination requiring helper escalation = %v", err)
	}
	if err := cmd.Wait(); err == nil {
		t.Fatal("escalated helper exited successfully")
	}
}

// TestWindowsHelperStartRejectsInvalidStatePath checks UTF-16 path validation occurs before process creation.
func TestWindowsHelperStartRejectsInvalidStatePath(t *testing.T) {
	if _, err := startWindowsHelper("bad\x00state", nil, nil); err == nil {
		t.Fatal("helper start accepted a state path containing NUL")
	}
}
