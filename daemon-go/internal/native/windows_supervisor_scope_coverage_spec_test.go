//go:build windows

package native

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

func newWindowsCoverageProcess(t *testing.T) *windowsProcess {
	t.Helper()
	return &windowsProcess{
		supervisor:  &windowsSupervisor{processes: make(map[int]*windowsProcess)},
		pid:         123,
		stateDir:    stateTestDir(t),
		done:        make(chan struct{}),
		waitFailure: make(chan struct{}),
		helperCode:  errorExitUnknown,
	}
}

// TestWindowsSupervisor_StartAndTrackRejectInvalidRequests verifies pure input and cancellation guards.
func TestWindowsSupervisor_StartAndTrackRejectInvalidRequests(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	concreteSupervisor := supervisor.(*windowsSupervisor)
	if _, err := supervisor.Start(context.Background(), ProcessSpec{}); err == nil || !strings.Contains(err.Error(), "must not be empty") {
		t.Fatalf("empty executable error = %v", err)
	}
	if _, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "tool", Args: []string{"bad\x00arg"}}); err == nil || !strings.Contains(err.Error(), "NUL") {
		t.Fatalf("NUL argument error = %v", err)
	}
	process := newWindowsCoverageProcess(t)
	concreteSupervisor.processes[process.pid] = process
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := concreteSupervisor.TrackDescendant(ctx, process.pid, 456); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled descendant tracking error = %v", err)
	}
	if err := concreteSupervisor.TrackDescendant(context.Background(), process.pid, -1); err == nil {
		t.Fatal("tracking an unopenable child process succeeded")
	}
}

// TestWindowsSupervisor_ScopeReadersReportMalformedAndInterruptedState tests scope parsing and wait-failure boundaries.
func TestWindowsSupervisor_ScopeReadersReportMalformedAndInterruptedState(t *testing.T) {
	t.Run("scope path is not a file", func(t *testing.T) {
		process := newWindowsCoverageProcess(t)
		if err := os.Mkdir(filepath.Join(process.stateDir, scopeFile), 0700); err != nil {
			t.Fatal(err)
		}
		if _, err := process.readScopePID(); err == nil {
			t.Fatal("scope directory was accepted as a PID record")
		}
		if err := process.awaitScopeWithBudget(context.Background(), time.Second); err == nil {
			t.Fatal("scope directory error was treated as an unpublished scope")
		}
	})
	t.Run("waiter failure", func(t *testing.T) {
		process := newWindowsCoverageProcess(t)
		waitErr := errors.New("injected helper wait failure")
		process.helperMu.Lock()
		process.helperWaitErr = waitErr
		process.helperMu.Unlock()
		close(process.waitFailure)
		if err := process.awaitScope(context.Background()); !errors.Is(err, waitErr) {
			t.Fatalf("await scope error = %v, want %v", err, waitErr)
		}
		if err := process.helperFailure(); !errors.Is(err, waitErr) {
			t.Fatalf("helper failure = %v, want %v", err, waitErr)
		}
	})
	t.Run("context cancellation", func(t *testing.T) {
		process := newWindowsCoverageProcess(t)
		ctx, cancel := context.WithCancel(context.Background())
		timer := time.AfterFunc(10*time.Millisecond, cancel)
		defer timer.Stop()
		defer cancel()
		if err := process.awaitScopeWithBudget(ctx, time.Second); !errors.Is(err, context.Canceled) {
			t.Fatalf("await scope cancellation = %v", err)
		}
	})
}

// TestWindowsSupervisor_WaitFailsClosedWithoutCleanupEvidence verifies cleanup and Job evidence are both required.
func TestWindowsSupervisor_WaitFailsClosedWithoutCleanupEvidence(t *testing.T) {
	t.Run("missing cleanup record", func(t *testing.T) {
		process := newWindowsCoverageProcess(t)
		close(process.done)
		_, err := process.Wait(context.Background())
		var notConverged *ProcessScopeNotConvergedError
		if !errors.As(err, &notConverged) || !strings.Contains(notConverged.Error(), "could not be proven empty") {
			t.Fatalf("Wait error = %v, want unproven cleanup", err)
		}
	})
	t.Run("missing Job handle", func(t *testing.T) {
		process := newWindowsCoverageProcess(t)
		if err := publishText(process.stateDir, cleanupFile, "true"); err != nil {
			t.Fatal(err)
		}
		close(process.done)
		_, err := process.Wait(context.Background())
		var notConverged *ProcessScopeNotConvergedError
		if !errors.As(err, &notConverged) || !strings.Contains(notConverged.Error(), "job handle is missing") {
			t.Fatalf("Wait error = %v, want missing Job handle", err)
		}
	})
	t.Run("helper error record", func(t *testing.T) {
		process := newWindowsCoverageProcess(t)
		process.job = 0
		if err := publishText(process.stateDir, cleanupFile, "true"); err != nil {
			t.Fatal(err)
		}
		if err := publishText(process.stateDir, errorFile, "helper reported failure"); err != nil {
			t.Fatal(err)
		}
		close(process.done)
		_, err := process.Wait(context.Background())
		if err == nil || !strings.Contains(err.Error(), "job handle is missing") {
			t.Fatalf("Wait error = %v, want missing Job handle before helper cleanup", err)
		}
	})
}

// TestWindowsSupervisor_WaitAndFallbackHonorCancellation verifies cancellation and unknown exit status are not fabricated.
func TestWindowsSupervisor_WaitAndFallbackHonorCancellation(t *testing.T) {
	process := newWindowsCoverageProcess(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := process.Wait(ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled Wait error = %v", err)
	}
	if exit := process.readExitFallback(); exit.Code != nil || exit.Signal != nil {
		t.Fatalf("fallback without exit evidence = %#v", exit)
	}
}

// TestWindowsSupervisor_WaitHelperPublishesNativeWaitFailure checks a failed OS wait becomes observable to waiters.
func TestWindowsSupervisor_WaitHelperPublishesNativeWaitFailure(t *testing.T) {
	process := newWindowsCoverageProcess(t)
	process.helper = windows.Handle(0x12345678)
	process.waitHelper()
	select {
	case <-process.waitFailure:
	default:
		t.Fatal("helper wait error did not notify waiters")
	}
	if _, err := process.Wait(context.Background()); err == nil || !strings.Contains(err.Error(), "cannot wait") {
		t.Fatalf("Wait error after helper wait failure = %v", err)
	}
}

// TestWindowsSupervisor_OpenWindowsJobRejectsInvalidNames verifies invalid and absent named Jobs do not open.
func TestWindowsSupervisor_OpenWindowsJobRejectsInvalidNames(t *testing.T) {
	if _, err := openWindowsJob("invalid\x00job"); err == nil {
		t.Fatal("Job name containing NUL was accepted")
	}
	if _, err := openWindowsJob("kk-studio-native-test-missing-job"); err == nil {
		t.Fatal("unknown named Job unexpectedly opened")
	}
}

// TestWindowsHelperPipes_CloseReleasesFiles confirms all parent pipe endpoints close even if child handles are invalid.
func TestWindowsHelperPipes_CloseReleasesFiles(t *testing.T) {
	files := make([]*os.File, 4)
	for i := range files {
		file, err := os.CreateTemp(t.TempDir(), "pipe-owner")
		if err != nil {
			t.Fatal(err)
		}
		files[i] = file
	}
	pipes := &windowsHelperPipes{
		ownerFile: files[0], inputFile: files[1], outputFile: files[2], errorFile: files[3],
		childHandles: []windows.Handle{windows.InvalidHandle},
	}
	pipes.close()
	for _, file := range files {
		if _, err := file.Stat(); err == nil {
			t.Fatalf("pipe endpoint %q remains open", file.Name())
		}
	}
}

// TestWindowsSupervisor_MergeEnvironmentDropsInvalidEntries verifies invalid and Node-managed environment entries are omitted.
func TestWindowsSupervisor_MergeEnvironmentDropsInvalidEntries(t *testing.T) {
	got := mergeWindowsEnvironment([]string{"A=1", "malformed", "=empty", "NODE_CHANNEL_FD=4", "NUL=x\x00y"}, nil)
	if len(got) != 1 || got[0] != "A=1" {
		t.Fatalf("merged environment = %v", got)
	}
}
