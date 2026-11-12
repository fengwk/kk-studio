//go:build windows

package native

import (
	"errors"
	"reflect"
	"strings"
	"testing"
	"unsafe"

	"golang.org/x/sys/windows"
)

type fakeWindowsAttributes struct {
	failAt  int
	updates int
	deleted bool
	job     windows.Handle
	handles []windows.Handle
}

func (a *fakeWindowsAttributes) Update(attribute uintptr, value unsafe.Pointer, size uintptr) error {
	a.updates++
	if a.failAt == a.updates {
		return errors.New("attribute update failed")
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
	stdout        windows.Handle
	stderr        windows.Handle
	getErrors     map[uint32]error
	setError      error
	attributeErr  error
	attributeFail int
	attributes    *fakeWindowsAttributes
	createError   error
	information   windows.ProcessInformation
	app           string
	commandLine   string
	cwd           string
	flags         uint32
	inherit       bool
	attributesSet bool
	stdin         windows.Handle
	stdoutUsed    windows.Handle
	stderrUsed    windows.Handle
	environment   []uint16
	terminated    []windows.Handle
	closed        []windows.Handle
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

func (api *fakeWindowsCommandAPI) SetHandleInformation(windows.Handle, uint32, uint32) error {
	return api.setError
}

func (api *fakeWindowsCommandAPI) NewProcThreadAttributeList(count uint32) (windowsProcThreadAttributes, error) {
	if count != 2 {
		return nil, errors.New("unexpected attribute count")
	}
	if api.attributeErr != nil {
		return nil, api.attributeErr
	}
	api.attributes = &fakeWindowsAttributes{failAt: api.attributeFail}
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
	if api.attributes.job != 10 || !reflect.DeepEqual(api.attributes.handles, []windows.Handle{19, 20, 21}) || !api.attributes.deleted {
		t.Fatalf("attributes: job=%d handles=%v deleted=%t", api.attributes.job, api.attributes.handles, api.attributes.deleted)
	}
}

// NAT-N-005 verifies fail-closed results and cleanup for injectable Win32 API failures.
func TestCreateSuspendedWindowsCommand_CleansUpOnAPIAndResultFailures(t *testing.T) {
	apiError := errors.New("Win32 API failed")
	tests := []struct {
		name  string
		setup func(*fakeWindowsCommandAPI) *launchSpec
	}{
		{name: "stdout", setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.getErrors[windows.STD_OUTPUT_HANDLE] = apiError
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "stderr", setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.getErrors[windows.STD_ERROR_HANDLE] = apiError
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "handle inheritance", setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.setError = apiError
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "attribute creation", setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.attributeErr = apiError
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "first attribute", setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.attributeFail = 1
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "second attribute", setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.attributeFail = 2
			return &launchSpec{Args: []string{"x"}}
		}},
		{name: "CreateProcess", setup: func(api *fakeWindowsCommandAPI) *launchSpec {
			api.createError = apiError
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
			_, err := createSuspendedWindowsCommandWithAPI(api, 10, launch, 19)
			if err == nil {
				t.Fatal("expected an error")
			}
			if test.name == "CreateProcess" && !errors.Is(err, apiError) {
				t.Fatalf("CreateProcess error = %v, want wrapped %v", err, apiError)
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
