//go:build windows

package native

import (
	"context"
	"errors"
	"io"
	"reflect"
	"strings"
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

type fakeWindowsJobCreationAPI struct {
	createHandle windows.Handle
	createErr    error
	configureErr error
	closeErr     error
	createdName  string
	configured   bool
	closed       []windows.Handle
	limitFlags   uint32
}

func (api *fakeWindowsJobCreationAPI) CreateJobObject(_ *windows.SecurityAttributes, name *uint16) (windows.Handle, error) {
	api.createdName = windows.UTF16PtrToString(name)
	return api.createHandle, api.createErr
}

func (api *fakeWindowsJobCreationAPI) SetInformationJobObject(job windows.Handle, class uint32, info *windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION) error {
	api.configured = true
	if job != 17 || class != windows.JobObjectExtendedLimitInformation || info == nil {
		return errors.New("unexpected Job configuration parameters")
	}
	api.limitFlags = info.BasicLimitInformation.LimitFlags
	return api.configureErr
}

func (api *fakeWindowsJobCreationAPI) CloseHandle(handle windows.Handle) error {
	api.closed = append(api.closed, handle)
	return api.closeErr
}

// NAT-T-166..167 assert Job creation/configuration errors and handle cleanup through an injectable Win32 seam.
func TestWindowsJobCreation_FailsClosedAndClosesOnConfigurationFailure(t *testing.T) {
	apiError := errors.New("injected Job API error")
	t.Run("missing job handle", func(t *testing.T) {
		api := &fakeWindowsJobCreationAPI{createHandle: 0}
		if _, err := createWindowsJobWithAPI("test-job", api); err == nil || !strings.Contains(err.Error(), "no Job handle") {
			t.Fatalf("create error = %v, want missing Job handle", err)
		}
		if api.configured || len(api.closed) != 0 {
			t.Fatalf("unexpected calls after missing handle: configured=%t closed=%v", api.configured, api.closed)
		}
	})
	t.Run("create error", func(t *testing.T) {
		api := &fakeWindowsJobCreationAPI{createErr: apiError}
		if _, err := createWindowsJobWithAPI("test-job", api); !errors.Is(err, apiError) {
			t.Fatalf("create error = %v, want wrapped %v", err, apiError)
		}
		if api.configured || len(api.closed) != 0 {
			t.Fatalf("unexpected calls after create error: configured=%t closed=%v", api.configured, api.closed)
		}
	})
	t.Run("lifetime limit error", func(t *testing.T) {
		api := &fakeWindowsJobCreationAPI{createHandle: 17, configureErr: apiError}
		if _, err := createWindowsJobWithAPI("test-job", api); !errors.Is(err, apiError) {
			t.Fatalf("configuration error = %v, want wrapped %v", err, apiError)
		}
		if api.createdName != "test-job" || !api.configured || api.limitFlags != windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE || !reflect.DeepEqual(api.closed, []windows.Handle{17}) {
			t.Fatalf("creation/configuration/cleanup = %q/%t/%#x/%v", api.createdName, api.configured, api.limitFlags, api.closed)
		}
	})
}

type fakeWindowsMembershipAPI struct {
	openHandle windows.Handle
	openErr    error
	queryValue bool
	queryErr   error
	closeErr   error
	queried    bool
	closed     []windows.Handle
}

func (api *fakeWindowsMembershipAPI) OpenProcess(access uint32, inherit bool, pid uint32) (windows.Handle, error) {
	if access != windows.PROCESS_QUERY_LIMITED_INFORMATION || inherit || pid != 4242 {
		return 0, errors.New("unexpected process open parameters")
	}
	return api.openHandle, api.openErr
}

func (api *fakeWindowsMembershipAPI) IsProcessInJob(process, job windows.Handle) (bool, error) {
	api.queried = true
	if process != 23 || job != 29 {
		return false, errors.New("unexpected membership query handles")
	}
	return api.queryValue, api.queryErr
}

func (api *fakeWindowsMembershipAPI) CloseHandle(handle windows.Handle) error {
	api.closed = append(api.closed, handle)
	return api.closeErr
}

// NAT-T-186..187 verify process-open and membership-query failures fail closed and always close opened handles.
func TestWindowsProcessInJob_FailureBranches(t *testing.T) {
	apiError := errors.New("injected membership API error")
	t.Run("process handle cannot open", func(t *testing.T) {
		api := &fakeWindowsMembershipAPI{openErr: apiError}
		if inJob, err := windowsProcessInJobWithAPI(4242, 29, api); inJob || !errors.Is(err, apiError) {
			t.Fatalf("membership result = %t, %v", inJob, err)
		}
		if api.queried || len(api.closed) != 0 {
			t.Fatalf("queried/closed after open failure = %t/%v", api.queried, api.closed)
		}
	})
	t.Run("membership query fails", func(t *testing.T) {
		api := &fakeWindowsMembershipAPI{openHandle: 23, queryErr: apiError}
		if inJob, err := windowsProcessInJobWithAPI(4242, 29, api); inJob || !errors.Is(err, apiError) {
			t.Fatalf("membership result = %t, %v", inJob, err)
		}
		if !api.queried || !reflect.DeepEqual(api.closed, []windows.Handle{23}) {
			t.Fatalf("query/close = %t/%v", api.queried, api.closed)
		}
	})
	t.Run("close error is returned", func(t *testing.T) {
		api := &fakeWindowsMembershipAPI{openHandle: 23, queryValue: true, closeErr: apiError}
		if inJob, err := windowsProcessInJobWithAPI(4242, 29, api); !inJob || !errors.Is(err, apiError) {
			t.Fatalf("membership result = %t, %v", inJob, err)
		}
	})
}

type pollingWindowsJobAPI struct {
	statuses     []uint32
	calls        int
	waitTimeouts []uint32
}

func (*pollingWindowsJobAPI) CloseHandle(windows.Handle) error                { return nil }
func (*pollingWindowsJobAPI) TerminateJobObject(windows.Handle, uint32) error { return nil }
func (*pollingWindowsJobAPI) ResumeThread(windows.Handle) (uint32, error)     { return 1, nil }
func (api *pollingWindowsJobAPI) WaitForSingleObject(_ windows.Handle, milliseconds uint32) (uint32, error) {
	api.waitTimeouts = append(api.waitTimeouts, milliseconds)
	status := api.statuses[api.calls]
	api.calls++
	return status, nil
}
func (*pollingWindowsJobAPI) GetExitCodeProcess(_ windows.Handle, code *uint32) error {
	*code = 19
	return nil
}
func (*pollingWindowsJobAPI) AwaitJobEmpty(context.Context, windows.Handle, time.Duration) error {
	return nil
}

// NAT-T-183 verifies command exit polling retries finite waits until the process is signaled.
func TestWindowsJobProtocol_PollsUntilCommandExit(t *testing.T) {
	api := &pollingWindowsJobAPI{statuses: []uint32{uint32(windows.WAIT_TIMEOUT), uint32(windows.WAIT_TIMEOUT), uint32(windows.WAIT_OBJECT_0)}}
	ownerInput, ownerWriter := io.Pipe()
	defer ownerWriter.Close()
	dir := stateTestDir(t)
	if err := publishText(dir, permitFile, "permit"); err != nil {
		t.Fatal(err)
	}
	result := runWindowsJobProtocolWithAPI(dir, 5, windows.ProcessInformation{Process: 10, Thread: 11, ProcessId: 12}, ownerInput, api)
	if result != 0 || api.calls != 3 || !reflect.DeepEqual(api.waitTimeouts, []uint32{0, 0, 0}) {
		t.Fatalf("protocol result/wait calls/timeouts = %d/%d/%v, want 0/3/[0 0 0]", result, api.calls, api.waitTimeouts)
	}
	data, err := readState(dir, exitFile)
	if err != nil || !strings.Contains(string(data), `"exitCode":19`) {
		t.Fatalf("exit report = %q, %v", data, err)
	}
}

type observedWindowsJobProtocolAPI struct {
	fakeWindowsJobProtocolAPI
	stateDir          string
	exitPublishedWhen []bool
}

func (api *observedWindowsJobProtocolAPI) CloseHandle(handle windows.Handle) error {
	_, err := readState(api.stateDir, exitFile)
	api.exitPublishedWhen = append(api.exitPublishedWhen, err == nil)
	return api.fakeWindowsJobProtocolAPI.CloseHandle(handle)
}

// NAT-T-234 verifies the command exit report is durable before the helper closes any process handle.
func TestWindowsJobProtocol_PublishesExitBeforeClosingHandles(t *testing.T) {
	dir := stateTestDir(t)
	if err := publishText(dir, permitFile, "permit"); err != nil {
		t.Fatal(err)
	}
	api := &observedWindowsJobProtocolAPI{stateDir: dir, fakeWindowsJobProtocolAPI: fakeWindowsJobProtocolAPI{waitStatus: uint32(windows.WAIT_OBJECT_0), exitCode: 7}}
	ownerInput, ownerWriter := io.Pipe()
	result := runWindowsJobProtocolWithAPI(dir, 5, windows.ProcessInformation{Process: 10, Thread: 11, ProcessId: 12}, ownerInput, api)
	_ = ownerWriter.Close()
	if result != 0 || !reflect.DeepEqual(api.exitPublishedWhen, []bool{true, true, true}) {
		t.Fatalf("result/exit-before-close = %d/%v", result, api.exitPublishedWhen)
	}
}

// NAT-T-177 verifies a failed resume terminates the suspended Job and closes every handle despite close errors.
func TestWindowsJobProtocol_ResumeAndCloseFailuresStillCleanUp(t *testing.T) {
	dir := stateTestDir(t)
	if err := publishText(dir, permitFile, "permit"); err != nil {
		t.Fatal(err)
	}
	apiError := errors.New("injected resume and close failure")
	api := &fakeWindowsJobProtocolAPI{resumeErr: apiError, closeErr: apiError}
	result := runWindowsJobProtocolWithAPI(dir, 5, windows.ProcessInformation{Process: 10, Thread: 11, ProcessId: 12}, strings.NewReader("owner closed"), api)
	if result != 1 || !reflect.DeepEqual(api.closed, []windows.Handle{11, 10, 5}) {
		t.Fatalf("result/closed handles = %d/%v", result, api.closed)
	}
	if !reflect.DeepEqual(api.calls, []string{"resume", "terminate", "await-empty", "close", "close", "close"}) {
		t.Fatalf("protocol calls = %v", api.calls)
	}
	if _, err := readState(dir, errorFile); err != nil {
		t.Fatalf("combined failure was not reported: %v", err)
	}
}
