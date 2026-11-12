//go:build windows

package native

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

func newWindowsScopeTestProcess(t *testing.T) *windowsProcess {
	t.Helper()
	return &windowsProcess{
		supervisor:  &windowsSupervisor{processes: make(map[int]*windowsProcess)},
		pid:         1,
		stateDir:    stateTestDir(t),
		done:        make(chan struct{}),
		waitFailure: make(chan struct{}),
	}
}

// NAT-T-224..227 verify helper exit and persisted failure details are surfaced before scope publication.
func TestWindowsSupervisor_AwaitScopeReportsHelperFailure(t *testing.T) {
	t.Run("helper exits before scope", func(t *testing.T) {
		process := newWindowsScopeTestProcess(t)
		process.helperCode = 17
		close(process.done)
		if err := process.awaitScope(context.Background()); err == nil || !strings.Contains(err.Error(), "exited with code 17") {
			t.Fatalf("helper exit before scope = %v", err)
		}
	})
	t.Run("persisted helper failure", func(t *testing.T) {
		process := newWindowsScopeTestProcess(t)
		if err := publishText(process.stateDir, errorFile, "injected owner failure"); err != nil {
			t.Fatal(err)
		}
		close(process.done)
		if err := process.awaitScope(context.Background()); err == nil || err.Error() != "injected owner failure" {
			t.Fatalf("persisted helper failure = %v", err)
		}
	})
	t.Run("malformed scope id", func(t *testing.T) {
		process := newWindowsScopeTestProcess(t)
		if err := publishText(process.stateDir, scopeFile, "not-a-pid"); err != nil {
			t.Fatal(err)
		}
		if _, err := process.readScopePID(); err == nil || !strings.Contains(err.Error(), "invalid scope id") {
			t.Fatalf("malformed scope PID error = %v", err)
		}
	})
}

// NAT-T-228 verifies cancellation after scope verification prevents the helper permit from being published.
func TestWindowsSupervisor_CancelledAfterScopePublication(t *testing.T) {
	process := newWindowsScopeTestProcess(t)
	if err := publishText(process.stateDir, scopeFile, "1234"); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := publishWindowsPermit(ctx, process.stateDir); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled permit publication = %v", err)
	}
	if _, err := readState(process.stateDir, permitFile); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("permit file after cancellation = %v", err)
	}
}

// NAT-T-239 verifies the scope-publication deadline returns a bounded failure.
func TestWindowsSupervisor_ScopePublicationTimeout(t *testing.T) {
	process := newWindowsScopeTestProcess(t)
	err := process.awaitScopeWithBudget(context.Background(), 10*time.Millisecond)
	if err == nil || !strings.Contains(err.Error(), "did not establish the OS scope in time") {
		t.Fatalf("scope publication timeout = %v", err)
	}
}

// NAT-T-223 verifies failed-start cleanup returns typed uncertainty instead of discarding the state directory.
func TestWindowsSupervisor_FailedStartCleanupPreservesUnprovenScope(t *testing.T) {
	process := newWindowsScopeTestProcess(t)
	process.job = windows.InvalidHandle
	cause := errors.New("injected start failure")
	err := process.cleanupFailedStart(cause)
	var notConverged *ProcessScopeNotConvergedError
	if !errors.As(err, &notConverged) || !errors.Is(err, cause) {
		t.Fatalf("failed-start cleanup error = %v, want typed convergence failure containing %v", err, cause)
	}
	if _, err := os.Stat(process.stateDir); err != nil {
		t.Fatalf("state directory was removed despite unproven cleanup: %v", err)
	}
}

type failingWindowsProcessTerminationAPI struct {
	drainErr        error
	closeErr        error
	terminatedJobs  []windows.Handle
	drainedJobs     []windows.Handle
	closedResources []windows.Handle
}

func (*failingWindowsProcessTerminationAPI) TerminateProcess(windows.Handle, uint32) error {
	return nil
}
func (*failingWindowsProcessTerminationAPI) WaitForSingleObject(windows.Handle, uint32) (uint32, error) {
	return uint32(windows.WAIT_OBJECT_0), nil
}
func (api *failingWindowsProcessTerminationAPI) TerminateJobObject(job windows.Handle, _ uint32) error {
	api.terminatedJobs = append(api.terminatedJobs, job)
	return nil
}
func (api *failingWindowsProcessTerminationAPI) AwaitJobEmpty(_ context.Context, job windows.Handle, _ time.Duration) error {
	api.drainedJobs = append(api.drainedJobs, job)
	return api.drainErr
}
func (api *failingWindowsProcessTerminationAPI) CloseHandle(handle windows.Handle) error {
	api.closedResources = append(api.closedResources, handle)
	return api.closeErr
}

// NAT-T-231..232,237..238 verify unproven termination is cached and aggregates injected drain/close errors.
func TestWindowsSupervisor_UnprovenTerminationPreservesScopeAndAggregatesCleanupFailure(t *testing.T) {
	process := newWindowsScopeTestProcess(t)
	process.job = 42
	drainError := errors.New("injected Job drain failure")
	closeError := errors.New("injected Job close failure")
	api := &failingWindowsProcessTerminationAPI{drainErr: drainError, closeErr: closeError}
	process.terminationAPI = api
	start := make(chan struct{})
	results := make(chan error, 2)
	for range 2 {
		go func() {
			<-start
			results <- process.Terminate(context.Background(), 0)
		}()
	}
	close(start)
	first, second := <-results, <-results
	var notConverged *ProcessScopeNotConvergedError
	if !errors.As(first, &notConverged) {
		t.Fatalf("termination error = %v, want ProcessScopeNotConvergedError", first)
	}
	if !errors.Is(first, drainError) || !errors.Is(first, closeError) || !strings.Contains(notConverged.Cause.Error(), "cannot close process scope job") {
		t.Fatalf("termination cause did not aggregate injected drain and close errors: %v", notConverged.Cause)
	}
	if !reflect.DeepEqual(api.terminatedJobs, []windows.Handle{42}) || !reflect.DeepEqual(api.drainedJobs, []windows.Handle{42}) || !reflect.DeepEqual(api.closedResources, []windows.Handle{42}) {
		t.Fatalf("termination API calls = terminate %v, drain %v, close %v", api.terminatedJobs, api.drainedJobs, api.closedResources)
	}
	if _, err := os.Stat(process.stateDir); err != nil {
		t.Fatalf("state directory was removed despite unproven termination: %v", err)
	}
	if second != first {
		t.Fatalf("concurrent termination error = %v, want cached %v", second, first)
	}
}

type survivingWindowsProcessAPI struct{}

func (survivingWindowsProcessAPI) TerminateProcess(windows.Handle, uint32) error {
	return windows.ERROR_ACCESS_DENIED
}
func (survivingWindowsProcessAPI) WaitForSingleObject(windows.Handle, uint32) (uint32, error) {
	return uint32(windows.WAIT_TIMEOUT), nil
}
func (survivingWindowsProcessAPI) TerminateJobObject(windows.Handle, uint32) error { return nil }
func (survivingWindowsProcessAPI) AwaitJobEmpty(context.Context, windows.Handle, time.Duration) error {
	return nil
}
func (survivingWindowsProcessAPI) CloseHandle(windows.Handle) error { return nil }

// NAT-T-229 verifies descendant membership fails closed when a managed state has no open Job.
func TestWindowsSupervisor_TrackDescendantWithoutJob(t *testing.T) {
	process := newWindowsScopeTestProcess(t)
	process.supervisor.processes[process.pid] = process
	err := process.supervisor.TrackDescendant(context.Background(), process.pid, 42)
	if err == nil || !strings.Contains(err.Error(), "job object is not open") {
		t.Fatalf("track without Job = %v", err)
	}
}

// NAT-T-230 verifies pre-scope termination targets only the known helper process.
func TestWindowsSupervisor_TerminateBeforeScopeKillsHelper(t *testing.T) {
	cmd := exec.Command(os.Args[0], "__native-fixture", "hold")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
	}()
	helper, err := windows.OpenProcess(windows.PROCESS_TERMINATE|windows.SYNCHRONIZE, false, uint32(cmd.Process.Pid))
	if err != nil {
		t.Fatal(err)
	}
	process := newWindowsScopeTestProcess(t)
	process.helper = helper
	if err := process.Terminate(context.Background(), 0); err != nil {
		t.Fatalf("terminate before scope = %v", err)
	}
	if err := cmd.Wait(); err != nil {
		if exitErr, ok := err.(*exec.ExitError); !ok || !exitErr.Exited() {
			t.Fatalf("wait for killed helper: %v", err)
		}
	}
}

// TestWindowsSupervisor_TerminateConvergedJobReleasesOwnedResources verifies clean termination removes state and process ownership.
func TestWindowsSupervisor_TerminateConvergedJobReleasesOwnedResources(t *testing.T) {
	stateDir := t.TempDir()
	process := &windowsProcess{
		supervisor: &windowsSupervisor{processes: make(map[int]*windowsProcess)},
		pid:        1,
		stateDir:   stateDir,
		done:       make(chan struct{}),
	}
	cmd := exec.Command(os.Args[0], "__native-fixture", "hold")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
	}()
	helper, err := windows.OpenProcess(windows.PROCESS_TERMINATE|windows.SYNCHRONIZE, false, uint32(cmd.Process.Pid))
	if err != nil {
		t.Fatal(err)
	}
	if err := windows.TerminateProcess(helper, 1); err != nil {
		t.Fatal(err)
	}
	if status, err := windows.WaitForSingleObject(helper, 1000); err != nil || status != windows.WAIT_OBJECT_0 {
		t.Fatalf("wait for terminated helper = %#x, %v", status, err)
	}
	if err := cmd.Wait(); err == nil {
		t.Fatal("terminated fixture exited successfully")
	}
	process.helper = helper
	process.job = 42
	owner, err := os.CreateTemp(t.TempDir(), "owner")
	if err != nil {
		t.Fatal(err)
	}
	process.owner = owner
	process.supervisor.processes[process.pid] = process
	process.terminationAPI = survivingWindowsProcessAPI{}
	if err := process.Terminate(context.Background(), 0); err != nil {
		t.Fatalf("terminate converged Job = %v", err)
	}
	if !process.settled || process.job != 0 {
		t.Fatalf("settled/job = %t/%d", process.settled, process.job)
	}
	if _, err := os.Stat(process.stateDir); !os.IsNotExist(err) {
		t.Fatalf("state directory after converged termination = %v", err)
	}
	if _, ok := process.supervisor.processes[process.pid]; ok {
		t.Fatal("terminated process remains tracked")
	}
	if process.owner != nil {
		t.Fatal("owner pipe remains attached after cleanup")
	}
	if _, err := owner.Stat(); err == nil {
		t.Fatal("owner pipe remains open")
	}
}

// NAT-T-235 verifies the public process exposes its helper pid and managed standard streams.
func TestWindowsSupervisor_ManagedProcessHelperFields(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: os.Args[0], Args: []string{"__native-fixture", "hold"}})
	if err != nil {
		t.Fatal(err)
	}
	if process.PID() <= 0 || process.Stdin() == nil || process.Stdout() == nil || process.Stderr() == nil {
		t.Fatalf("managed process helper fields: pid=%d stdin=%t stdout=%t stderr=%t", process.PID(), process.Stdin() != nil, process.Stdout() != nil, process.Stderr() != nil)
	}
	helper, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION|windows.SYNCHRONIZE, false, uint32(process.PID()))
	if err != nil {
		t.Fatalf("managed helper PID %d is not an open process: %v", process.PID(), err)
	}
	if err := windows.CloseHandle(helper); err != nil {
		t.Fatal(err)
	}
	if err := process.Terminate(context.Background(), 0); err != nil {
		t.Fatal(err)
	}
}

// NAT-T-240 verifies a helper that survives the kill deadline cannot be reported as terminated.
func TestWindowsSupervisor_SurvivingHelperIsNotConverged(t *testing.T) {
	err := terminateWindowsProcessWithAPI(context.Background(), 42, 10*time.Millisecond, survivingWindowsProcessAPI{})
	if err == nil || !strings.Contains(err.Error(), "could not be terminated") {
		t.Fatalf("surviving helper termination = %v", err)
	}
}

// NAT-T-236,241 verify malformed or failed helper reports use the documented empty/fallback exit result.
func TestWindowsSupervisor_ExitFallbackWithoutValidReport(t *testing.T) {
	process := newWindowsScopeTestProcess(t)
	process.helperCode = 0
	if err := publishText(process.stateDir, errorFile, "helper failed"); err != nil {
		t.Fatal(err)
	}
	if exit := process.readExitFallback(); exit.Code != nil || exit.Signal != nil {
		t.Fatalf("error-report fallback = %+v, want an empty exit", exit)
	}
	process.settled = true
	process.job = windows.InvalidHandle
	process.helper = windows.InvalidHandle
	if err := process.Terminate(context.Background(), 0); err != nil {
		t.Fatalf("terminating an already settled helper error = %v", err)
	}
	if process.job != windows.InvalidHandle || process.helper != windows.InvalidHandle {
		t.Fatal("terminate touched process handles after helper error settlement")
	}
	if err := os.Remove(filepath.Join(process.stateDir, errorFile)); err != nil {
		t.Fatal(err)
	}
	if err := publishText(process.stateDir, exitFile, "malformed"); err != nil {
		t.Fatal(err)
	}
	if exit := process.readExitFallback(); exit.Code == nil || *exit.Code != 0 {
		t.Fatalf("malformed-exit fallback = %+v, want helper exit code 0", exit)
	}
}
