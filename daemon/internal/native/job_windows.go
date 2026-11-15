//go:build windows

package native

import (
	"context"
	"errors"
	"fmt"
	"golang.org/x/sys/windows"
	"time"
	"unsafe"
)

func openWindowsJob(name string) (windows.Handle, error) {
	name16, err := windows.UTF16PtrFromString(name)
	if err != nil {
		return 0, err
	}
	proc := windows.NewLazySystemDLL("kernel32.dll").NewProc("OpenJobObjectW")
	handle, _, callErr := proc.Call(uintptr(windowsJobAllAccess), 0, uintptr(unsafe.Pointer(name16)))
	if handle == 0 {
		return 0, fmt.Errorf("cannot open the process scope job: %w", callErr)
	}
	return windows.Handle(handle), nil
}

type windowsMembershipAPI interface {
	OpenProcess(uint32, bool, uint32) (windows.Handle, error)
	IsProcessInJob(windows.Handle, windows.Handle) (bool, error)
	CloseHandle(windows.Handle) error
}

type nativeWindowsMembershipAPI struct{}

func (nativeWindowsMembershipAPI) OpenProcess(access uint32, inherit bool, pid uint32) (windows.Handle, error) {
	return windows.OpenProcess(access, inherit, pid)
}

func (nativeWindowsMembershipAPI) IsProcessInJob(process, job windows.Handle) (bool, error) {
	var inJob int32
	proc := windows.NewLazySystemDLL("kernel32.dll").NewProc("IsProcessInJob")
	result, _, callErr := proc.Call(uintptr(process), uintptr(job), uintptr(unsafe.Pointer(&inJob)))
	if result == 0 {
		return false, callErr
	}
	return inJob != 0, nil
}

func (nativeWindowsMembershipAPI) CloseHandle(handle windows.Handle) error {
	return windows.CloseHandle(handle)
}

func windowsProcessInJob(pid uint32, job windows.Handle) (bool, error) {
	return windowsProcessInJobWithAPI(pid, job, nativeWindowsMembershipAPI{})
}

func windowsProcessInJobWithAPI(pid uint32, job windows.Handle, api windowsMembershipAPI) (bool, error) {
	process, err := api.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
	if err != nil {
		return false, fmt.Errorf("cannot open the process for Job membership: %w", err)
	}
	inJob, err := api.IsProcessInJob(process, job)
	if err != nil {
		err = fmt.Errorf("cannot query Job membership: %w", err)
	}
	if closeErr := api.CloseHandle(process); closeErr != nil {
		err = errors.Join(err, fmt.Errorf("cannot close process membership handle: %w", closeErr))
	}
	return inJob, err
}

type windowsJobCreationAPI interface {
	CreateJobObject(*windows.SecurityAttributes, *uint16) (windows.Handle, error)
	SetInformationJobObject(windows.Handle, uint32, *windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION) error
	CloseHandle(windows.Handle) error
}

type nativeWindowsJobCreationAPI struct{}

func (nativeWindowsJobCreationAPI) CreateJobObject(security *windows.SecurityAttributes, name *uint16) (windows.Handle, error) {
	return windows.CreateJobObject(security, name)
}

func (nativeWindowsJobCreationAPI) SetInformationJobObject(job windows.Handle, class uint32, info *windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION) error {
	_, err := windows.SetInformationJobObject(job, class, uintptr(unsafe.Pointer(info)), uint32(unsafe.Sizeof(*info)))
	return err
}

func (nativeWindowsJobCreationAPI) CloseHandle(handle windows.Handle) error {
	return windows.CloseHandle(handle)
}

func createWindowsJob(name string) (windows.Handle, error) {
	return createWindowsJobWithAPI(name, nativeWindowsJobCreationAPI{})
}

func createWindowsJobWithAPI(name string, api windowsJobCreationAPI) (windows.Handle, error) {
	name16, err := windows.UTF16PtrFromString(name)
	if err != nil {
		return 0, err
	}
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return 0, err
	}
	sid := user.User.Sid.String()
	descriptor, err := windows.SecurityDescriptorFromString("O:" + sid + "D:P(A;;0x001F001F;;;" + sid + ")")
	if err != nil {
		return 0, err
	}
	security := windows.SecurityAttributes{Length: uint32(unsafe.Sizeof(windows.SecurityAttributes{})), SecurityDescriptor: descriptor}
	job, err := api.CreateJobObject(&security, name16)
	if err != nil {
		return 0, fmt.Errorf("cannot create the process scope job: %w", err)
	}
	if job == 0 || job == windows.InvalidHandle {
		return 0, errors.New("cannot create the process scope job: the API returned no Job handle")
	}
	info := windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION{}
	info.BasicLimitInformation.LimitFlags = windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
	if err := api.SetInformationJobObject(job, windows.JobObjectExtendedLimitInformation, &info); err != nil {
		_ = api.CloseHandle(job)
		return 0, fmt.Errorf("cannot configure the process scope job lifetime: %w", err)
	}
	return job, nil
}

type windowsJobAccountingAPI interface {
	QueryInformationJobObject(windows.Handle, *windowsJobAccounting) error
}

type nativeWindowsJobAccountingAPI struct{}

func (nativeWindowsJobAccountingAPI) QueryInformationJobObject(job windows.Handle, accounting *windowsJobAccounting) error {
	return windows.QueryInformationJobObject(job, windows.JobObjectBasicAccountingInformation, uintptr(unsafe.Pointer(accounting)), uint32(unsafe.Sizeof(*accounting)), nil)
}

func jobActiveProcesses(job windows.Handle) (uint32, error) {
	return jobActiveProcessesWithAPI(job, nativeWindowsJobAccountingAPI{})
}

func jobActiveProcessesWithAPI(job windows.Handle, api windowsJobAccountingAPI) (uint32, error) {
	var accounting windowsJobAccounting
	if err := api.QueryInformationJobObject(job, &accounting); err != nil {
		return 0, fmt.Errorf("cannot read process scope Job accounting: %w", err)
	}
	return accounting.ActiveProcesses, nil
}

func awaitWindowsJobEmpty(ctx context.Context, job windows.Handle, budget time.Duration) error {
	return awaitWindowsJobEmptyWithAPI(ctx, job, budget, nativeWindowsJobAccountingAPI{})
}

func awaitWindowsJobEmptyWithAPI(ctx context.Context, job windows.Handle, budget time.Duration, api windowsJobAccountingAPI) error {
	deadline := time.NewTimer(budget)
	defer deadline.Stop()
	ticker := time.NewTicker(windowsScopePoll)
	defer ticker.Stop()
	var lastErr error
	for {
		active, err := jobActiveProcessesWithAPI(job, api)
		if err == nil && active == 0 {
			return nil
		}
		if err != nil {
			lastErr = err
		} else {
			lastErr = fmt.Errorf("the process scope Job still reports %d active process(es)", active)
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-deadline.C:
			return fmt.Errorf("the process scope Job could not be proven empty: %w", lastErr)
		case <-ticker.C:
		}
	}
}

type windowsProcessTerminationAPI interface {
	TerminateProcess(windows.Handle, uint32) error
	WaitForSingleObject(windows.Handle, uint32) (uint32, error)
	TerminateJobObject(windows.Handle, uint32) error
	AwaitJobEmpty(context.Context, windows.Handle, time.Duration) error
	CloseHandle(windows.Handle) error
}

type nativeWindowsProcessTerminationAPI struct{}

func (nativeWindowsProcessTerminationAPI) TerminateProcess(process windows.Handle, code uint32) error {
	return windows.TerminateProcess(process, code)
}

func (nativeWindowsProcessTerminationAPI) WaitForSingleObject(process windows.Handle, milliseconds uint32) (uint32, error) {
	return windows.WaitForSingleObject(process, milliseconds)
}

func (nativeWindowsProcessTerminationAPI) TerminateJobObject(job windows.Handle, code uint32) error {
	return windows.TerminateJobObject(job, code)
}

func (nativeWindowsProcessTerminationAPI) AwaitJobEmpty(ctx context.Context, job windows.Handle, budget time.Duration) error {
	return awaitWindowsJobEmpty(ctx, job, budget)
}

func (nativeWindowsProcessTerminationAPI) CloseHandle(handle windows.Handle) error {
	return windows.CloseHandle(handle)
}

func terminateWindowsProcessWithAPI(ctx context.Context, process windows.Handle, budget time.Duration, api windowsProcessTerminationAPI) error {
	if process == 0 {
		return nil
	}
	if err := api.TerminateProcess(process, 1); err != nil && err != windows.ERROR_ACCESS_DENIED {
		return err
	}
	deadline := time.NewTimer(budget)
	defer deadline.Stop()
	for {
		status, err := api.WaitForSingleObject(process, 0)
		if err != nil {
			return err
		}
		if status == windows.WAIT_OBJECT_0 {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-deadline.C:
			return errors.New("the process scope helper could not be terminated")
		case <-time.After(windowsScopePoll):
		}
	}
}
