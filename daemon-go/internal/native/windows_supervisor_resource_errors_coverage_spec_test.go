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
