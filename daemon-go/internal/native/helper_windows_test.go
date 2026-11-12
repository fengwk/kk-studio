//go:build windows

package native

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
	"unicode/utf16"
	"unsafe"

	"golang.org/x/sys/windows"
)

type fakeWindowsAttributes struct {
	failAt    int
	updateErr error
	updates   int
	deleted   bool
	job       windows.Handle
	handles   []windows.Handle
}

func (a *fakeWindowsAttributes) Update(attribute uintptr, value unsafe.Pointer, size uintptr) error {
	a.updates++
	if a.failAt == a.updates {
		return a.updateErr
	}
	switch attribute {
	case procThreadAttributeJobList:
		a.job = *(*windows.Handle)(value)
	case windows.PROC_THREAD_ATTRIBUTE_HANDLE_LIST:
		a.handles = append([]windows.Handle(nil), unsafe.Slice((*windows.Handle)(value), int(size/unsafe.Sizeof(windows.Handle(0))))...)
	}
	return nil
}

func (a *fakeWindowsAttributes) Delete() { a.deleted = true }
func (a *fakeWindowsAttributes) List() *windows.ProcThreadAttributeList {
	return &windows.ProcThreadAttributeList{}
}

type fakeWindowsCommandAPI struct {
	stdout             windows.Handle
	stderr             windows.Handle
	getErrors          map[uint32]error
	setError           error
	attributeErr       error
	attributeFail      int
	attributeUpdateErr error
	attributes         *fakeWindowsAttributes
	createError        error
	information        windows.ProcessInformation
	app                string
	commandLine        string
	cwd                string
	flags              uint32
	inherit            bool
	attributesSet      bool
	inheritedHandles   []windows.Handle
	stdin              windows.Handle
	stdoutUsed         windows.Handle
	stderrUsed         windows.Handle
	environment        []uint16
	terminated         []windows.Handle
	closed             []windows.Handle
}

func (api *fakeWindowsCommandAPI) GetStdHandle(which uint32) (windows.Handle, error) {
	if err := api.getErrors[which]; err != nil {
		return 0, err
	}
	if which == windows.STD_OUTPUT_HANDLE {
		return api.stdout, nil
	}
	return api.stderr, nil
}

func (api *fakeWindowsCommandAPI) SetHandleInformation(handle windows.Handle, _, _ uint32) error {
	api.inheritedHandles = append(api.inheritedHandles, handle)
	return api.setError
}

func (api *fakeWindowsCommandAPI) NewProcThreadAttributeList(count uint32) (windowsProcThreadAttributes, error) {
	if count != 2 {
		return nil, errors.New("unexpected attribute count")
	}
	if api.attributeErr != nil {
		return nil, api.attributeErr
	}
	api.attributes = &fakeWindowsAttributes{failAt: api.attributeFail, updateErr: api.attributeUpdateErr}
	return api.attributes, nil
}

func (api *fakeWindowsCommandAPI) CreateProcess(app *uint16, commandLine *uint16, _, _ *windows.SecurityAttributes, inherit bool, flags uint32, environment *uint16, cwd *uint16, startup *windows.StartupInfo, out *windows.ProcessInformation) error {
	api.app = windows.UTF16PtrToString(app)
	api.commandLine = windows.UTF16PtrToString(commandLine)
	api.cwd = windows.UTF16PtrToString(cwd)
	api.flags, api.inherit = flags, inherit
	api.attributesSet = (*windows.StartupInfoEx)(unsafe.Pointer(startup)).ProcThreadAttributeList != nil
	api.stdin, api.stdoutUsed, api.stderrUsed = startup.StdInput, startup.StdOutput, startup.StdErr
	for i, previous := 0, uint16(1); i < 32768; i++ {
		value := *(*uint16)(unsafe.Add(unsafe.Pointer(environment), uintptr(i)*unsafe.Sizeof(uint16(0))))
		api.environment = append(api.environment, value)
		if i > 0 && previous == 0 && value == 0 {
			break
		}
		previous = value
	}
	*out = api.information
	return api.createError
}

func (api *fakeWindowsCommandAPI) TerminateProcess(handle windows.Handle, _ uint32) error {
	api.terminated = append(api.terminated, handle)
	return nil
}

func (api *fakeWindowsCommandAPI) CloseHandle(handle windows.Handle) error {
	api.closed = append(api.closed, handle)
	return nil
}

// NAT-N-003 verifies suspended creation, Job membership, the inherited-handle allowlist, and environment termination.
func TestCreateSuspendedWindowsCommand_UsesJobAndHandleLists(t *testing.T) {
	api := &fakeWindowsCommandAPI{
		stdout:      20,
		stderr:      21,
		information: windows.ProcessInformation{Process: 30, Thread: 31, ProcessId: 32},
	}
	launch := &launchSpec{Args: []string{"tool.exe", "two words", `tail\\`}, Cwd: `C:\work`}
	got, err := createSuspendedWindowsCommandWithAPI(api, 10, launch, 19)
	if err != nil {
		t.Fatal(err)
	}
	if got != api.information {
		t.Fatalf("process information = %#v, want %#v", got, api.information)
	}
	if api.app != "tool.exe" || api.commandLine != ComposeCommandLine(launch.Args) || api.cwd != launch.Cwd {
		t.Fatalf("CreateProcess parameters: app=%q commandLine=%q cwd=%q", api.app, api.commandLine, api.cwd)
	}
	wantFlags := uint32(windows.CREATE_SUSPENDED | windows.CREATE_UNICODE_ENVIRONMENT | windows.EXTENDED_STARTUPINFO_PRESENT)
	if api.flags != wantFlags || !api.inherit || !api.attributesSet {
		t.Fatalf("creation flags/inheritance/attributes = %#x/%t/%t, want %#x/true/true", api.flags, api.inherit, api.attributesSet, wantFlags)
	}
	if api.stdin != 19 || api.stdoutUsed != 20 || api.stderrUsed != 21 {
		t.Fatalf("standard handles = %d/%d/%d", api.stdin, api.stdoutUsed, api.stderrUsed)
	}
	if len(api.environment) < 2 || api.environment[len(api.environment)-1] != 0 || api.environment[len(api.environment)-2] != 0 {
		t.Fatalf("environment block is not double-NUL terminated: %#v", api.environment)
	}
	if !reflect.DeepEqual(api.inheritedHandles, []windows.Handle{19, 20, 21}) {
		t.Fatalf("inheritable handles = %v, want [19 20 21]", api.inheritedHandles)
	}
	if api.attributes.job != 10 || !reflect.DeepEqual(api.attributes.handles, []windows.Handle{19, 20, 21}) || !api.attributes.deleted {
		t.Fatalf("attributes: job=%d handles=%v deleted=%t", api.attributes.job, api.attributes.handles, api.attributes.deleted)
	}
}

// NAT-T-176 verifies filtered, sorted UTF-16 environment entries and duplicate-key replacement.
func TestWindowsEnvironmentBlock_FiltersAndSortsEntries(t *testing.T) {
	got := windowsEnvironmentBlock([]string{
		"B=2",
		"a=first",
		"A=last",
		"NODE_CHANNEL_FD=3",
		"malformed",
		"=empty-key",
		"NULL=value\x00tail",
		"UNICODE=雪",
	})
	want := utf16.Encode([]rune("A=last\x00B=2\x00UNICODE=雪\x00\x00"))
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("environment block = %q, want %q", utf16.Decode(got), utf16.Decode(want))
	}
}

// NAT-N-005 verifies fail-closed results and cleanup for injectable Win32 API failures.
func TestCreateSuspendedWindowsCommand_CleansUpOnAPIAndResultFailures(t *testing.T) {
	apiError := errors.New("Win32 API failed")
	tests := []struct {
		name          string
		wantAPIError  bool
		overrideStdin bool
		stdin         windows.Handle
		setup         func(*fakeWindowsCommandAPI) *launchSpec
	}{
		{name: "stdout", wantAPIError: true, setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.getErrors[windows.STD_OUTPUT_HANDLE] = apiError
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "stderr", wantAPIError: true, setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.getErrors[windows.STD_ERROR_HANDLE] = apiError
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "handle inheritance", wantAPIError: true, setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.setError = apiError
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "attribute creation", wantAPIError: true, setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.attributeErr = apiError
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "first attribute", wantAPIError: true, setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.attributeFail = 1
			api.attributeUpdateErr = apiError
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "second attribute", wantAPIError: true, setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.attributeFail = 2
			api.attributeUpdateErr = apiError
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "CreateProcess", wantAPIError: true, setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.createError = apiError
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "zero stdout handle", setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.stdout = 0
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "invalid stdout handle", setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.stdout = windows.InvalidHandle
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "zero stderr handle", setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.stderr = 0
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "invalid stderr handle", setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.stderr = windows.InvalidHandle
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "zero stdin handle", overrideStdin: true, stdin: 0, setup: func(*fakeWindowsCommandAPI) *launchSpec {
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "invalid stdin handle", overrideStdin: true, stdin: windows.InvalidHandle, setup: func(*fakeWindowsCommandAPI) *launchSpec {
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "missing process handle", setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.information = windows.ProcessInformation{Thread: 40, ProcessId: 41}
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "missing thread handle", setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.information = windows.ProcessInformation{Process: 40, ProcessId: 41}
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "missing process id", setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.information = windows.ProcessInformation{Process: 40, Thread: 41}
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "embedded NUL command", setup: func(*fakeWindowsCommandAPI) *launchSpec { return &launchSpec{Args: []string{"x\x00y"}} }},
		{name: "embedded NUL working directory", setup: func(*fakeWindowsCommandAPI) *launchSpec { return &launchSpec{Args: []string{"x"}, Cwd: "bad\x00cwd"} }},
		{name: "empty command", setup: func(*fakeWindowsCommandAPI) *launchSpec { return &launchSpec{} }},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			api := &fakeWindowsCommandAPI{stdout: 20, stderr: 21, getErrors: make(map[uint32]error)}
			launch := test.setup(api)
			stdin := windows.Handle(19)
			if test.overrideStdin {
				stdin = test.stdin
			}
			_, err := createSuspendedWindowsCommandWithAPI(api, 10, launch, stdin)
			if err == nil {
				t.Fatal("expected an error")
			}
			if test.wantAPIError && !errors.Is(err, apiError) {
				t.Fatalf("API error = %v, want wrapped %v", err, apiError)
			}
			if api.attributes != nil && !api.attributes.deleted {
				t.Fatal("attribute list was not deleted after failure")
			}
			if strings.Contains(test.name, "missing") {
				if len(api.closed) == 0 {
					t.Fatal("partial process information was not cleaned up")
				}
				if test.name == "missing thread handle" && !reflect.DeepEqual(api.terminated, []windows.Handle{40}) {
					t.Fatalf("terminated handles = %v, want [40]", api.terminated)
				}
			}
		})
	}
}

type fakeWindowsJobProtocolAPI struct {
	resumeErr  error
	waitStatus uint32
	waitErr    error
	exitCode   uint32
	exitErr    error
	drainErr   error
	closeErr   error
	calls      []string
	closed     []windows.Handle
}

func (api *fakeWindowsJobProtocolAPI) CloseHandle(handle windows.Handle) error {
	api.calls = append(api.calls, "close")
	api.closed = append(api.closed, handle)
	return api.closeErr
}

func (api *fakeWindowsJobProtocolAPI) TerminateJobObject(windows.Handle, uint32) error {
	api.calls = append(api.calls, "terminate")
	return nil
}

func (api *fakeWindowsJobProtocolAPI) ResumeThread(windows.Handle) (uint32, error) {
	api.calls = append(api.calls, "resume")
	return 1, api.resumeErr
}

func (api *fakeWindowsJobProtocolAPI) WaitForSingleObject(windows.Handle, uint32) (uint32, error) {
	api.calls = append(api.calls, "wait")
	return api.waitStatus, api.waitErr
}

func (api *fakeWindowsJobProtocolAPI) GetExitCodeProcess(_ windows.Handle, code *uint32) error {
	api.calls = append(api.calls, "exit-code")
	*code = api.exitCode
	return api.exitErr
}

func (api *fakeWindowsJobProtocolAPI) AwaitJobEmpty(context.Context, windows.Handle, time.Duration) error {
	api.calls = append(api.calls, "await-empty")
	return api.drainErr
}

func TestRunWindowsJobProtocolWithAPI(t *testing.T) {
	apiError := errors.New("injected Win32 failure")
	tests := []struct {
		name             string
		permit           bool
		invalidPermit    bool
		ownerClosed      bool
		scopeUnavailable bool
		setup            func(*fakeWindowsJobProtocolAPI)
		wantResult       int
		wantError        bool
		wantCleanup      string
		wantExitCode     *int
	}{
		{name: "successful command", permit: true, setup: func(api *fakeWindowsJobProtocolAPI) { api.exitCode = 7 }, wantCleanup: "true", wantExitCode: func() *int { v := 7; return &v }()},
		{name: "owner closes before permit", ownerClosed: true, wantCleanup: "true"},
		{name: "invalid permit", permit: true, invalidPermit: true, wantResult: 1, wantError: true, wantCleanup: "true"},
		{name: "scope publication failure", permit: true, scopeUnavailable: true, wantResult: 1, wantError: true, wantCleanup: "true"},
		{name: "resume failure", permit: true, setup: func(api *fakeWindowsJobProtocolAPI) { api.resumeErr = apiError }, wantResult: 1, wantError: true, wantCleanup: "true"},
		{name: "wait failure", permit: true, setup: func(api *fakeWindowsJobProtocolAPI) { api.waitErr = apiError }, wantResult: 1, wantError: true, wantCleanup: "true"},
		{name: "unexpected wait status", permit: true, setup: func(api *fakeWindowsJobProtocolAPI) { api.waitStatus = 0x80 }, wantResult: 1, wantError: true, wantCleanup: "true"},
		{name: "exit code failure", permit: true, setup: func(api *fakeWindowsJobProtocolAPI) { api.exitErr = apiError }, wantResult: 1, wantError: true, wantCleanup: "true"},
		{name: "drain failure", permit: true, setup: func(api *fakeWindowsJobProtocolAPI) { api.drainErr = apiError }, wantResult: 1, wantError: true, wantCleanup: "false", wantExitCode: func() *int { v := 0; return &v }()},
		{name: "close failure", permit: true, setup: func(api *fakeWindowsJobProtocolAPI) { api.closeErr = apiError }, wantResult: 1, wantError: true, wantCleanup: "true", wantExitCode: func() *int { v := 0; return &v }()},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			dir := stateTestDir(t)
			if test.permit {
				permit := "permit"
				if test.invalidPermit {
					permit = "invalid"
				}
				if err := publishText(dir, permitFile, permit); err != nil {
					t.Fatal(err)
				}
			}
			if test.scopeUnavailable {
				if err := os.Mkdir(filepath.Join(dir, scopeFile), 0700); err != nil {
					t.Fatal(err)
				}
			}
			api := &fakeWindowsJobProtocolAPI{waitStatus: windows.WAIT_OBJECT_0}
			if test.setup != nil {
				test.setup(api)
			}
			var ownerInput io.Reader
			var ownerWriter *io.PipeWriter
			if test.ownerClosed {
				ownerInput = strings.NewReader("")
			} else {
				ownerReader, writer := io.Pipe()
				ownerInput, ownerWriter = ownerReader, writer
			}
			result := runWindowsJobProtocolWithAPI(dir, 5, windows.ProcessInformation{Process: 10, Thread: 11, ProcessId: 12}, ownerInput, api)
			if ownerWriter != nil {
				_ = ownerWriter.Close()
			}
			if result != test.wantResult {
				t.Fatalf("result = %d, want %d; API calls: %v", result, test.wantResult, api.calls)
			}
			_, errorErr := readState(dir, errorFile)
			if (errorErr == nil) != test.wantError {
				t.Fatalf("error file present = %t, want %t (err=%v)", errorErr == nil, test.wantError, errorErr)
			}
			cleanup, err := readState(dir, cleanupFile)
			if err != nil || string(cleanup) != test.wantCleanup {
				t.Fatalf("cleanup = %q, %v; want %q", cleanup, err, test.wantCleanup)
			}
			exit, exitErr := readState(dir, exitFile)
			if test.wantExitCode == nil {
				if exitErr == nil {
					t.Fatalf("unexpected exit report: %s", exit)
				}
			} else {
				var report exitReport
				if err := json.Unmarshal(exit, &report); err != nil || report.ExitCode == nil || *report.ExitCode != *test.wantExitCode {
					t.Fatalf("exit report = %s, %v", exit, err)
				}
			}
			if !reflect.DeepEqual(api.closed, []windows.Handle{11, 10, 5}) {
				t.Fatalf("closed handles = %v, want [11 10 5]", api.closed)
			}
			if test.wantExitCode != nil && !reflect.DeepEqual(api.calls, []string{"resume", "wait", "exit-code", "terminate", "await-empty", "close", "close", "close"}) {
				t.Fatalf("API call order = %v", api.calls)
			}
		})
	}
}
