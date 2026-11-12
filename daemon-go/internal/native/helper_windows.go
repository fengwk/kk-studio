//go:build windows

package native

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

func helperMain(args []string) int { return runWindowsHelper(args) }

func runWindowsHelper(args []string) int {
	if len(args) != 1 || !filepath.IsAbs(args[0]) || hasControl(args[0]) {
		return 2
	}
	stateDir := args[0]
	inputHandle := os.Getenv(windowsStdinHandleEnv)
	inputValue, err := strconv.ParseUint(inputHandle, 10, 64)
	if err != nil || inputValue == 0 || inputValue == uint64(windows.InvalidHandle) {
		publishQuietly(stateDir, errorFile, "helper-launch-failure")
		return 1
	}
	if err := os.Unsetenv(windowsStdinHandleEnv); err != nil {
		publishQuietly(stateDir, errorFile, "helper-launch-failure")
		return 1
	}
	if err := verifyPrivateDirectory(stateDir); err != nil {
		publishQuietly(stateDir, errorFile, "helper-launch-failure")
		return 1
	}
	launch, err := readLaunch(stateDir)
	if err != nil || launch == nil {
		publishQuietly(stateDir, errorFile, "helper-launch-failure")
		return 1
	}
	if len(launch.Args) == 0 {
		publishQuietly(stateDir, errorFile, "helper-windows-failure")
		publishQuietly(stateDir, cleanupFile, "true")
		return 1
	}
	jobName := jobNameFor(stateDir)
	job, err := createWindowsJob(jobName)
	if err != nil {
		publishQuietly(stateDir, errorFile, "helper-windows-failure")
		publishQuietly(stateDir, cleanupFile, "true")
		return 1
	}
	processInfo, err := createSuspendedWindowsCommand(job, launch, windows.Handle(inputValue))
	if err != nil {
		_ = windows.TerminateJobObject(job, 1)
		drainCtx, cancel := context.WithTimeout(context.Background(), windowsDrainBudget)
		drainErr := awaitWindowsJobEmpty(drainCtx, job, windowsDrainBudget)
		cancel()
		closeErr := windows.CloseHandle(job)
		publishQuietly(stateDir, errorFile, "helper-windows-failure")
		if drainErr == nil && closeErr == nil {
			publishQuietly(stateDir, cleanupFile, "true")
		} else {
			publishQuietly(stateDir, cleanupFile, "false")
		}
		return 1
	}
	return runWindowsJobProtocol(stateDir, job, processInfo)
}

type windowsProcThreadAttributes interface {
	Update(uintptr, unsafe.Pointer, uintptr) error
	Delete()
	List() *windows.ProcThreadAttributeList
}

type windowsCommandAPI interface {
	GetStdHandle(uint32) (windows.Handle, error)
	SetHandleInformation(windows.Handle, uint32, uint32) error
	NewProcThreadAttributeList(uint32) (windowsProcThreadAttributes, error)
	CreateProcess(*uint16, *uint16, *windows.SecurityAttributes, *windows.SecurityAttributes, bool, uint32, *uint16, *uint16, *windows.StartupInfo, *windows.ProcessInformation) error
	TerminateProcess(windows.Handle, uint32) error
	CloseHandle(windows.Handle) error
}

type nativeWindowsCommandAPI struct{}

func (nativeWindowsCommandAPI) GetStdHandle(which uint32) (windows.Handle, error) {
	return windows.GetStdHandle(which)
}

func (nativeWindowsCommandAPI) SetHandleInformation(handle windows.Handle, mask uint32, flags uint32) error {
	return windows.SetHandleInformation(handle, mask, flags)
}

func (nativeWindowsCommandAPI) NewProcThreadAttributeList(count uint32) (windowsProcThreadAttributes, error) {
	return windows.NewProcThreadAttributeList(count)
}

func (nativeWindowsCommandAPI) CreateProcess(appName *uint16, commandLine *uint16, procSecurity *windows.SecurityAttributes, threadSecurity *windows.SecurityAttributes, inheritHandles bool, creationFlags uint32, env *uint16, currentDir *uint16, startupInfo *windows.StartupInfo, outProcInfo *windows.ProcessInformation) error {
	return windows.CreateProcess(appName, commandLine, procSecurity, threadSecurity, inheritHandles, creationFlags, env, currentDir, startupInfo, outProcInfo)
}

func (nativeWindowsCommandAPI) TerminateProcess(process windows.Handle, exitCode uint32) error {
	return windows.TerminateProcess(process, exitCode)
}

func (nativeWindowsCommandAPI) CloseHandle(handle windows.Handle) error {
	return windows.CloseHandle(handle)
}

func createSuspendedWindowsCommand(job windows.Handle, launch *launchSpec, stdin windows.Handle) (windows.ProcessInformation, error) {
	return createSuspendedWindowsCommandWithAPI(nativeWindowsCommandAPI{}, job, launch, stdin)
}

func createSuspendedWindowsCommandWithAPI(api windowsCommandAPI, job windows.Handle, launch *launchSpec, stdin windows.Handle) (windows.ProcessInformation, error) {
	if len(launch.Args) == 0 || launch.Args[0] == "" {
		return windows.ProcessInformation{}, errors.New("empty command")
	}
	stdout, err := api.GetStdHandle(windows.STD_OUTPUT_HANDLE)
	if err != nil {
		return windows.ProcessInformation{}, err
	}
	stderr, err := api.GetStdHandle(windows.STD_ERROR_HANDLE)
	if err != nil {
		return windows.ProcessInformation{}, err
	}
	if stdin == 0 || stdin == windows.InvalidHandle || stdout == 0 || stderr == 0 || stdout == windows.InvalidHandle || stderr == windows.InvalidHandle {
		return windows.ProcessInformation{}, errors.New("the command standard handles are not available")
	}
	handles := uniqueWindowsHandles([]windows.Handle{stdin, stdout, stderr})
	for _, handle := range handles {
		if err := api.SetHandleInformation(handle, windows.HANDLE_FLAG_INHERIT, windows.HANDLE_FLAG_INHERIT); err != nil {
			return windows.ProcessInformation{}, err
		}
	}
	attributes, err := api.NewProcThreadAttributeList(2)
	if err != nil {
		return windows.ProcessInformation{}, err
	}
	defer attributes.Delete()
	jobHandle := job
	if err := attributes.Update(procThreadAttributeJobList, unsafe.Pointer(&jobHandle), unsafe.Sizeof(jobHandle)); err != nil {
		return windows.ProcessInformation{}, err
	}
	if err := attributes.Update(windows.PROC_THREAD_ATTRIBUTE_HANDLE_LIST, unsafe.Pointer(&handles[0]), uintptr(len(handles))*unsafe.Sizeof(handles[0])); err != nil {
		return windows.ProcessInformation{}, err
	}
	app, err := windows.UTF16PtrFromString(launch.Args[0])
	if err != nil {
		return windows.ProcessInformation{}, err
	}
	commandLine, err := windows.UTF16PtrFromString(ComposeCommandLine(launch.Args))
	if err != nil {
		return windows.ProcessInformation{}, err
	}
	cwd, err := windows.UTF16PtrFromString(launch.Cwd)
	if err != nil {
		return windows.ProcessInformation{}, err
	}
	environment := windowsEnvironmentBlock(os.Environ())
	var startup windows.StartupInfoEx
	startup.Cb = uint32(unsafe.Sizeof(startup))
	startup.Flags = windows.STARTF_USESTDHANDLES
	startup.StdInput, startup.StdOutput, startup.StdErr = stdin, stdout, stderr
	startup.ProcThreadAttributeList = attributes.List()
	var information windows.ProcessInformation
	flags := uint32(windows.CREATE_SUSPENDED | windows.CREATE_UNICODE_ENVIRONMENT | windows.EXTENDED_STARTUPINFO_PRESENT)
	if err := api.CreateProcess(app, commandLine, nil, nil, true, flags, &environment[0], cwd, &startup.StartupInfo, &information); err != nil {
		return windows.ProcessInformation{}, fmt.Errorf("cannot create suspended command process: %w", err)
	}
	if information.Process == 0 || information.Thread == 0 || information.ProcessId == 0 {
		if information.Process != 0 {
			_ = api.TerminateProcess(information.Process, 1)
			_ = api.CloseHandle(information.Process)
		}
		if information.Thread != 0 {
			_ = api.CloseHandle(information.Thread)
		}
		return windows.ProcessInformation{}, errors.New("the command process was created without process handles")
	}
	return information, nil
}

func uniqueWindowsHandles(handles []windows.Handle) []windows.Handle {
	seen := make(map[windows.Handle]bool, len(handles))
	result := make([]windows.Handle, 0, len(handles))
	for _, handle := range handles {
		if handle != 0 && !seen[handle] {
			seen[handle] = true
			result = append(result, handle)
		}
	}
	return result
}

func runWindowsJobProtocol(stateDir string, job windows.Handle, process windows.ProcessInformation) (result int) {
	defer func() {
		var failures []error
		if err := windows.CloseHandle(process.Thread); err != nil {
			failures = append(failures, err)
		}
		if err := windows.CloseHandle(process.Process); err != nil {
			failures = append(failures, err)
		}
		if err := windows.CloseHandle(job); err != nil {
			failures = append(failures, err)
		}
		if err := errors.Join(failures...); err != nil {
			publishQuietly(stateDir, errorFile, "helper-windows-failure")
			result = 1
		}
	}()
	if err := publishText(stateDir, scopeFile, strconv.FormatUint(uint64(process.ProcessId), 10)); err != nil {
		_ = windows.TerminateJobObject(job, 1)
		if awaitWindowsJobEmpty(context.Background(), job, windowsDrainBudget) == nil {
			publishQuietly(stateDir, cleanupFile, "true")
		} else {
			publishQuietly(stateDir, cleanupFile, "false")
		}
		publishQuietly(stateDir, errorFile, "helper-windows-failure")
		return 1
	}
	ownerClosed := make(chan struct{})
	go func() { _, _ = io.Copy(io.Discard, os.Stdin); close(ownerClosed) }()
	timer := time.NewTimer(windowsPrepareBudget)
	defer timer.Stop()
	ticker := time.NewTicker(windowsScopePoll)
	defer ticker.Stop()
	for {
		permit, err := readState(stateDir, permitFile)
		if err == nil {
			if strings.TrimSpace(string(permit)) != "permit" {
				publishQuietly(stateDir, errorFile, "helper-windows-failure")
				return shutdownWindowsJob(stateDir, job)
			}
			break
		}
		if !errors.Is(err, os.ErrNotExist) {
			publishQuietly(stateDir, errorFile, "helper-windows-failure")
			return shutdownWindowsJob(stateDir, job)
		}
		select {
		case <-ownerClosed:
			return shutdownWindowsJob(stateDir, job)
		case <-timer.C:
			publishQuietly(stateDir, errorFile, "helper-windows-failure")
			return shutdownWindowsJob(stateDir, job)
		case <-ticker.C:
		}
	}
	if _, err := windows.ResumeThread(process.Thread); err != nil {
		publishQuietly(stateDir, errorFile, "helper-windows-failure")
		return shutdownWindowsJob(stateDir, job)
	}
	processDone := make(chan error, 1)
	go func() {
		for {
			status, err := windows.WaitForSingleObject(process.Process, 0)
			if err != nil {
				processDone <- err
				return
			}
			if status == windows.WAIT_OBJECT_0 {
				processDone <- nil
				return
			}
			if status != uint32(windows.WAIT_TIMEOUT) {
				processDone <- fmt.Errorf("unexpected command wait status: %#x", status)
				return
			}
			time.Sleep(windowsExitPoll)
		}
	}()
	select {
	case waitErr := <-processDone:
		if waitErr != nil {
			publishQuietly(stateDir, errorFile, "helper-windows-failure")
			return shutdownWindowsJob(stateDir, job)
		}
		var code uint32
		if err := windows.GetExitCodeProcess(process.Process, &code); err != nil {
			publishQuietly(stateDir, errorFile, "helper-windows-failure")
			return shutdownWindowsJob(stateDir, job)
		}
		report := exitReport{ExitCode: intPointer(int(code))}
		payload, err := json.Marshal(report)
		if err != nil || publishText(stateDir, exitFile, string(payload)) != nil {
			publishQuietly(stateDir, errorFile, "helper-windows-failure")
			return shutdownWindowsJob(stateDir, job)
		}
		return shutdownWindowsJob(stateDir, job)
	case <-ownerClosed:
		return shutdownWindowsJob(stateDir, job)
	}
}

func shutdownWindowsJob(stateDir string, job windows.Handle) int {
	_ = windows.TerminateJobObject(job, 1)
	if err := awaitWindowsJobEmpty(context.Background(), job, windowsDrainBudget); err != nil {
		publishQuietly(stateDir, cleanupFile, "false")
		publishQuietly(stateDir, errorFile, "helper-windows-failure")
		return 1
	}
	if err := publishText(stateDir, cleanupFile, "true"); err != nil {
		publishQuietly(stateDir, errorFile, "helper-windows-failure")
		return 1
	}
	if _, err := readState(stateDir, errorFile); err == nil {
		return 1
	}
	return 0
}

func intPointer(value int) *int { return &value }
