//go:build windows

package native

import (
	"context"
	"errors"
	"os"
	"strings"
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

type resultWindowsTerminationAPI struct {
	terminateErr error
	waitStatus   uint32
	waitErr      error
}

func (api resultWindowsTerminationAPI) TerminateProcess(windows.Handle, uint32) error {
	return api.terminateErr
}
func (api resultWindowsTerminationAPI) WaitForSingleObject(windows.Handle, uint32) (uint32, error) {
	return api.waitStatus, api.waitErr
}
func (resultWindowsTerminationAPI) TerminateJobObject(windows.Handle, uint32) error { return nil }
func (resultWindowsTerminationAPI) AwaitJobEmpty(context.Context, windows.Handle, time.Duration) error {
	return nil
}
func (resultWindowsTerminationAPI) CloseHandle(windows.Handle) error { return nil }

// TestWindowsSupervisor_TerminateHelperFailureResults verifies helper termination reports API, wait, and cancellation errors.
func TestWindowsSupervisor_TerminateHelperFailureResults(t *testing.T) {
	if err := terminateWindowsProcessWithAPI(context.Background(), 0, time.Second, resultWindowsTerminationAPI{}); err != nil {
		t.Fatalf("zero process handle termination = %v", err)
	}
	terminateErr := errors.New("injected TerminateProcess failure")
	if err := terminateWindowsProcessWithAPI(context.Background(), 42, time.Second, resultWindowsTerminationAPI{terminateErr: terminateErr}); !errors.Is(err, terminateErr) {
		t.Fatalf("TerminateProcess failure = %v", err)
	}
	waitErr := errors.New("injected helper wait failure")
	if err := terminateWindowsProcessWithAPI(context.Background(), 42, time.Second, resultWindowsTerminationAPI{waitErr: waitErr}); !errors.Is(err, waitErr) {
		t.Fatalf("helper wait failure = %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := terminateWindowsProcessWithAPI(ctx, 42, time.Second, resultWindowsTerminationAPI{waitStatus: uint32(windows.WAIT_TIMEOUT)}); !errors.Is(err, context.Canceled) {
		t.Fatalf("helper termination cancellation = %v", err)
	}
}

// TestWindowsSupervisor_JobDrainReportsNativeAndContextFailures verifies Job drain cannot claim empty without evidence.
func TestWindowsSupervisor_JobDrainReportsNativeAndContextFailures(t *testing.T) {
	if err := awaitWindowsJobEmpty(context.Background(), windows.Handle(0x12345678), time.Millisecond); err == nil || !strings.Contains(err.Error(), "could not be proven empty") {
		t.Fatalf("invalid Job drain = %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := awaitWindowsJobEmpty(ctx, windows.Handle(0x12345678), time.Second); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled Job drain = %v", err)
	}
}

// TestWindowsSupervisor_CloseResourcesAggregatesHandleFailures verifies every owned resource failure is retained.
func TestWindowsSupervisor_CloseResourcesAggregatesHandleFailures(t *testing.T) {
	files := make([]*os.File, 4)
	for i := range files {
		file, err := os.CreateTemp(t.TempDir(), "closed-resource")
		if err != nil {
			t.Fatal(err)
		}
		if err := file.Close(); err != nil {
			t.Fatal(err)
		}
		files[i] = file
	}
	process := &windowsProcess{
		owner:            files[0],
		stdin:            &windowsManagedPipe{file: files[1]},
		stdout:           &windowsManagedPipe{file: files[2]},
		stderr:           &windowsManagedPipe{file: files[3]},
		inheritedHandles: []windows.Handle{windows.Handle(0x12345678)},
		helperThread:     windows.Handle(0x12345678),
		helper:           windows.Handle(0x12345678),
	}
	if err := process.closeResources(); err == nil {
		t.Fatal("resource close failures were discarded")
	}
	if process.owner != nil || process.stdin != nil || process.stdout != nil || process.stderr != nil || process.inheritedHandles != nil {
		t.Fatal("resource references were retained after close attempts")
	}
}

// TestWindowsSupervisor_NativeWindowsPipeWrapperCreatesPipe verifies the default native pipe path remains wired.
func TestWindowsSupervisor_NativeWindowsPipeWrapperCreatesPipe(t *testing.T) {
	file, child, err := windowsPipe(true)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	defer windows.CloseHandle(child)
}

// TestWindowsSupervisor_NativeJobCloseReportsInvalidHandle verifies native Job handle closure surfaces Win32 errors.
func TestWindowsSupervisor_NativeJobCloseReportsInvalidHandle(t *testing.T) {
	if err := (nativeWindowsJobCreationAPI{}).CloseHandle(windows.Handle(0x12345678)); err == nil {
		t.Fatal("native Job close accepted an invalid handle")
	}
}

// TestWindowsSupervisor_FailedStartCleanupReportsResourceFailures verifies failed cleanup retains the launch cause and resource error.
func TestWindowsSupervisor_FailedStartCleanupReportsResourceFailures(t *testing.T) {
	owner, err := os.CreateTemp(t.TempDir(), "closed-owner")
	if err != nil {
		t.Fatal(err)
	}
	if err := owner.Close(); err != nil {
		t.Fatal(err)
	}
	cause := errors.New("injected launch failure")
	process := &windowsProcess{owner: owner, stateDir: "bad\x00state"}
	cleanupErr := process.cleanupFailedStart(cause)
	if !errors.Is(cleanupErr, cause) || process.owner != nil {
		t.Fatalf("failed-start cleanup = %v; owner = %v", cleanupErr, process.owner)
	}
}

// TestWindowsSupervisor_ChildHandleCloseRetainsFailure verifies failed inherited-handle cleanup stays retryable.
func TestWindowsSupervisor_ChildHandleCloseRetainsFailure(t *testing.T) {
	pipes := &windowsHelperPipes{childHandles: []windows.Handle{windows.Handle(0x12345678)}}
	if err := pipes.closeChildHandles(); err == nil {
		t.Fatal("invalid child handle close unexpectedly succeeded")
	}
	if len(pipes.childHandles) != 1 {
		t.Fatalf("failed child handle was discarded: %v", pipes.childHandles)
	}
}

// TestWindowsSupervisor_RejectsInvalidHelperStatePath verifies helper launch rejects NUL-containing protocol paths.
func TestWindowsSupervisor_RejectsInvalidHelperStatePath(t *testing.T) {
	if _, err := startWindowsHelper("bad\x00state", &windowsHelperPipes{}, nil); err == nil {
		t.Fatal("helper accepted a NUL-containing state path")
	}
}

// TestWindowsJobCreation_RejectsMalformedName verifies the Win32 name conversion rejects embedded NULs.
func TestWindowsJobCreation_RejectsMalformedName(t *testing.T) {
	if _, err := createWindowsJobWithAPI("bad\x00name", &fakeWindowsJobCreationAPI{}); err == nil {
		t.Fatal("Job creation accepted an embedded NUL in its name")
	}
}

// TestWindowsSupervisor_StartReportsStateDirectoryFailure verifies startup stops before publishing into an invalid temp root.
func TestWindowsSupervisor_StartReportsStateDirectoryFailure(t *testing.T) {
	missingTemp := t.TempDir() + "\\missing"
	t.Setenv("TMP", missingTemp)
	t.Setenv("TEMP", missingTemp)
	supervisor := &windowsSupervisor{processes: make(map[int]*windowsProcess)}
	if _, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "fixture"}); err == nil {
		t.Fatal("startup succeeded without a usable temporary directory")
	}
}

// TestWindowsSupervisor_WaitSettledAndCancellation verifies Wait returns cached results and honors canceled contexts.
func TestWindowsSupervisor_WaitSettledAndCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := (&windowsProcess{}).Wait(ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled Wait = %v", err)
	}
	terminationErr := errors.New("cached termination failure")
	process := &windowsProcess{done: make(chan struct{}), waitFailure: make(chan struct{}), terminationErr: terminationErr}
	close(process.done)
	if _, err := process.Wait(context.Background()); !errors.Is(err, terminationErr) {
		t.Fatalf("cached termination result = %v", err)
	}
	waitErr := errors.New("cached wait failure")
	closeErr := errors.New("cached close failure")
	code := 7
	process = &windowsProcess{done: make(chan struct{}), waitFailure: make(chan struct{}), settled: true, exit: ProcessExit{Code: &code}, waitErr: waitErr, closeErr: closeErr}
	close(process.done)
	exit, err := process.Wait(context.Background())
	if exit.Code == nil || *exit.Code != code || !errors.Is(err, waitErr) || !errors.Is(err, closeErr) {
		t.Fatalf("cached settled result = %#v, %v", exit, err)
	}
}

// TestWindowsSupervisor_CloseAggregatesProcessFailures verifies supervisor closure preserves managed-process errors.
func TestWindowsSupervisor_CloseAggregatesProcessFailures(t *testing.T) {
	closeErr := errors.New("cached process close failure")
	process := &windowsProcess{settled: true, closeErr: closeErr}
	supervisor := &windowsSupervisor{processes: map[int]*windowsProcess{1: process}}
	if err := supervisor.Close(context.Background()); !errors.Is(err, closeErr) {
		t.Fatalf("supervisor close = %v", err)
	}
}

// TestWindowsSupervisor_TrackDescendantReportsMembershipQueryFailure verifies a failed native membership check is returned.
func TestWindowsSupervisor_TrackDescendantReportsMembershipQueryFailure(t *testing.T) {
	process := &windowsProcess{job: windows.Handle(0x12345678)}
	supervisor := &windowsSupervisor{processes: map[int]*windowsProcess{1: process}}
	if err := supervisor.TrackDescendant(context.Background(), 1, 0); err == nil {
		t.Fatal("descendant membership query failure was discarded")
	}
}
