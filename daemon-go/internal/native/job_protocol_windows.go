//go:build windows

package native

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"golang.org/x/sys/windows"
	"io"
	"os"
	"strconv"
	"strings"
	"time"
)

type windowsJobProtocolAPI interface {
	CloseHandle(windows.Handle) error
	TerminateJobObject(windows.Handle, uint32) error
	ResumeThread(windows.Handle) (uint32, error)
	WaitForSingleObject(windows.Handle, uint32) (uint32, error)
	GetExitCodeProcess(windows.Handle, *uint32) error
	AwaitJobEmpty(context.Context, windows.Handle, time.Duration) error
}

type nativeWindowsJobProtocolAPI struct{}

func (nativeWindowsJobProtocolAPI) CloseHandle(handle windows.Handle) error {
	return windows.CloseHandle(handle)
}

func (nativeWindowsJobProtocolAPI) TerminateJobObject(job windows.Handle, exitCode uint32) error {
	return windows.TerminateJobObject(job, exitCode)
}

func (nativeWindowsJobProtocolAPI) ResumeThread(thread windows.Handle) (uint32, error) {
	return windows.ResumeThread(thread)
}

func (nativeWindowsJobProtocolAPI) WaitForSingleObject(handle windows.Handle, milliseconds uint32) (uint32, error) {
	return windows.WaitForSingleObject(handle, milliseconds)
}

func (nativeWindowsJobProtocolAPI) GetExitCodeProcess(process windows.Handle, code *uint32) error {
	return windows.GetExitCodeProcess(process, code)
}

func (nativeWindowsJobProtocolAPI) AwaitJobEmpty(ctx context.Context, job windows.Handle, timeout time.Duration) error {
	return awaitWindowsJobEmpty(ctx, job, timeout)
}

func runWindowsJobProtocol(stateDir string, job windows.Handle, process windows.ProcessInformation) int {
	return runWindowsJobProtocolWithAPI(stateDir, job, process, os.Stdin, nativeWindowsJobProtocolAPI{})
}

func runWindowsJobProtocolWithAPI(stateDir string, job windows.Handle, process windows.ProcessInformation, ownerInput io.Reader, api windowsJobProtocolAPI) (result int) {
	defer func() {
		var failures []error
		if err := api.CloseHandle(process.Thread); err != nil {
			failures = append(failures, err)
		}
		if err := api.CloseHandle(process.Process); err != nil {
			failures = append(failures, err)
		}
		if err := api.CloseHandle(job); err != nil {
			failures = append(failures, err)
		}
		if err := errors.Join(failures...); err != nil {
			publishQuietly(stateDir, errorFile, "helper-windows-failure")
			result = 1
		}
	}()
	if err := publishText(stateDir, scopeFile, strconv.FormatUint(uint64(process.ProcessId), 10)); err != nil {
		_ = api.TerminateJobObject(job, 1)
		if api.AwaitJobEmpty(context.Background(), job, windowsDrainBudget) == nil {
			publishQuietly(stateDir, cleanupFile, "true")
		} else {
			publishQuietly(stateDir, cleanupFile, "false")
		}
		publishQuietly(stateDir, errorFile, "helper-windows-failure")
		return 1
	}
	ownerClosed, ok := awaitWindowsPermit(stateDir, job, ownerInput, api)
	if !ok {
		return shutdownWindowsJobWithAPI(stateDir, job, api)
	}
	if _, err := api.ResumeThread(process.Thread); err != nil {
		publishQuietly(stateDir, errorFile, "helper-windows-failure")
		return shutdownWindowsJobWithAPI(stateDir, job, api)
	}
	processDone := waitWindowsCommandExit(process, api)
	select {
	case waitErr := <-processDone:
		if waitErr != nil {
			publishQuietly(stateDir, errorFile, "helper-windows-failure")
			return shutdownWindowsJobWithAPI(stateDir, job, api)
		}
		var code uint32
		if err := api.GetExitCodeProcess(process.Process, &code); err != nil {
			publishQuietly(stateDir, errorFile, "helper-windows-failure")
			return shutdownWindowsJobWithAPI(stateDir, job, api)
		}
		if err := publishWindowsExitReport(stateDir, code); err != nil {
			publishQuietly(stateDir, errorFile, "helper-windows-failure")
			return shutdownWindowsJobWithAPI(stateDir, job, api)
		}
		return shutdownWindowsJobWithAPI(stateDir, job, api)
	case <-ownerClosed:
		return shutdownWindowsJobWithAPI(stateDir, job, api)
	}
}

func awaitWindowsPermit(stateDir string, job windows.Handle, ownerInput io.Reader, api windowsJobProtocolAPI) (<-chan struct{}, bool) {
	ownerClosed := make(chan struct{})
	go func() { _, _ = io.Copy(io.Discard, ownerInput); close(ownerClosed) }()
	timer := time.NewTimer(windowsPrepareBudget)
	defer timer.Stop()
	ticker := time.NewTicker(windowsScopePoll)
	defer ticker.Stop()
	for {
		permit, err := readState(stateDir, permitFile)
		if err == nil {
			if strings.TrimSpace(string(permit)) != "permit" {
				publishQuietly(stateDir, errorFile, "helper-windows-failure")
				return ownerClosed, false
			}
			return ownerClosed, true
		}
		if !errors.Is(err, os.ErrNotExist) {
			publishQuietly(stateDir, errorFile, "helper-windows-failure")
			return ownerClosed, false
		}
		select {
		case <-ownerClosed:
			return ownerClosed, false
		case <-timer.C:
			publishQuietly(stateDir, errorFile, "helper-windows-failure")
			return ownerClosed, false
		case <-ticker.C:
		}
	}
}

func waitWindowsCommandExit(process windows.ProcessInformation, api windowsJobProtocolAPI) <-chan error {
	processDone := make(chan error, 1)
	go func() {
		for {
			status, err := api.WaitForSingleObject(process.Process, 0)
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
	return processDone
}

func publishWindowsExitReport(stateDir string, code uint32) error {
	report := exitReport{ExitCode: intPointer(int(code))}
	payload, err := json.Marshal(report)
	if err != nil {
		return err
	}
	return publishText(stateDir, exitFile, string(payload))
}

func shutdownWindowsJobWithAPI(stateDir string, job windows.Handle, api windowsJobProtocolAPI) int {
	_ = api.TerminateJobObject(job, 1)
	if err := api.AwaitJobEmpty(context.Background(), job, windowsDrainBudget); err != nil {
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
