//go:build windows

package native

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf16"
	"unsafe"

	"golang.org/x/sys/windows"
)

const (
	procThreadAttributeJobList = 0x0002000D
	windowsScopePoll           = 5 * time.Millisecond
	windowsExitPoll            = 20 * time.Millisecond
	windowsPrepareBudget       = 20 * time.Second
	windowsDrainBudget         = 2 * time.Second
	windowsForceKillBudget     = time.Second
	windowsJobNamePrefix       = "kk-studio-process-scope-"
	windowsStdinHandleEnv      = "KK_STUDIO_NATIVE_STDIN_HANDLE"
	errorExitUnknown           = 0xFFFFFFFF
	windowsJobAllAccess        = 0x001F001F
)

type windowsJobAccounting struct {
	TotalUserTime            int64
	TotalKernelTime          int64
	ThisPeriodUserTime       int64
	ThisPeriodKernelTime     int64
	TotalPageFaultCount      uint32
	TotalProcesses           uint32
	ActiveProcesses          uint32
	TotalTerminatedProcesses uint32
}

type windowsSupervisor struct {
	mu        sync.Mutex
	closed    bool
	processes map[int]*windowsProcess
}

// NewSupervisor creates a Job-based Windows process supervisor.
func NewSupervisor() (Supervisor, error) {
	return &windowsSupervisor{processes: make(map[int]*windowsProcess)}, nil
}

func (s *windowsSupervisor) Start(ctx context.Context, spec ProcessSpec) (ManagedProcess, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if spec.Executable == "" {
		return nil, errors.New("the process executable must not be empty")
	}
	for _, value := range append([]string{spec.Executable, spec.Directory}, spec.Args...) {
		if strings.ContainsRune(value, '\x00') {
			return nil, errors.New("process arguments must not contain a NUL character")
		}
	}
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil, errors.New("the process supervisor is closed")
	}
	s.mu.Unlock()
	stateDir, err := createStateDir()
	if err != nil {
		return nil, err
	}
	cwd := spec.Directory
	if cwd == "" {
		cwd, err = os.Getwd()
		if err != nil {
			_ = removeStateDir(stateDir)
			return nil, err
		}
	}
	launch := launchSpec{Cwd: cwd, Args: append([]string{spec.Executable}, spec.Args...)}
	payload, err := json.Marshal(launch)
	if err != nil {
		_ = removeStateDir(stateDir)
		return nil, err
	}
	if err := publish(stateDir, launchFile, payload); err != nil {
		_ = removeStateDir(stateDir)
		return nil, err
	}
	if err := verifyPrivateFile(filepath.Join(stateDir, launchFile)); err != nil {
		_ = removeStateDir(stateDir)
		return nil, err
	}
	pipes, err := createWindowsHelperPipes()
	if err != nil {
		_ = removeStateDir(stateDir)
		return nil, err
	}
	helperEnv := mergeWindowsEnvironment(os.Environ(), spec.Env)
	helperEnv = mergeWindowsEnvironment(helperEnv, []string{windowsStdinHandleEnv + "=" + strconv.FormatUint(uint64(pipes.commandInput), 10)})
	helper, err := startWindowsHelper(stateDir, pipes, helperEnv)
	if err != nil {
		pipes.close()
		_ = removeStateDir(stateDir)
		return nil, err
	}
	threadCloseErr := windows.CloseHandle(helper.Thread)
	pipesCloseErr := pipes.closeChildHandles()
	p := &windowsProcess{supervisor: s, pid: int(helper.ProcessId), helper: helper.Process, helperThread: helper.Thread, inheritedHandles: append([]windows.Handle(nil), pipes.childHandles...), owner: pipes.ownerFile, stdin: &windowsManagedPipe{file: pipes.inputFile}, stdout: &windowsManagedPipe{file: pipes.outputFile}, stderr: &windowsManagedPipe{file: pipes.errorFile}, stateDir: stateDir, jobName: jobNameFor(stateDir), done: make(chan struct{}), waitFailure: make(chan struct{}), helperCode: errorExitUnknown}
	if threadCloseErr == nil {
		p.helperThread = 0
	}
	go p.waitHelper()
	if err := errors.Join(threadCloseErr, pipesCloseErr); err != nil {
		return nil, p.cleanupFailedStart(err)
	}
	if err := p.awaitScope(ctx); err != nil {
		return nil, p.cleanupFailedStart(err)
	}
	job, err := openWindowsJob(p.jobName)
	if err != nil {
		return nil, p.cleanupFailedStart(err)
	}
	p.job = job
	rootPID, err := p.readScopePID()
	if err != nil {
		return nil, p.cleanupFailedStart(err)
	}
	inJob, err := windowsProcessInJob(uint32(rootPID), job)
	if err != nil || !inJob {
		if err == nil {
			err = errors.New("the published root process is not in the scope job")
		}
		return nil, p.cleanupFailedStart(err)
	}
	if err := publishWindowsPermit(ctx, stateDir); err != nil {
		return nil, p.cleanupFailedStart(err)
	}
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil, p.cleanupFailedStart(errors.New("the process supervisor is closed"))
	}
	s.processes[p.pid] = p
	s.mu.Unlock()
	return p, nil
}

func (s *windowsSupervisor) Close(ctx context.Context) error {
	s.mu.Lock()
	s.closed = true
	pending := make([]*windowsProcess, 0, len(s.processes))
	for _, process := range s.processes {
		pending = append(pending, process)
	}
	s.mu.Unlock()
	var failures []error
	for _, process := range pending {
		if err := process.Terminate(ctx, time.Second); err != nil {
			failures = append(failures, err)
		}
	}
	return errors.Join(failures...)
}

// TrackDescendant confirms membership and never assigns a foreign process to the Job.
func (s *windowsSupervisor) TrackDescendant(ctx context.Context, rootPID, childPID int) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	s.mu.Lock()
	process := s.processes[rootPID]
	s.mu.Unlock()
	if process == nil {
		return errors.New("the process root is not managed by this supervisor")
	}
	process.mu.Lock()
	job := process.job
	settled := process.settled
	process.mu.Unlock()
	if settled || job == 0 {
		return errors.New("the scope job object is not open")
	}
	inJob, err := windowsProcessInJob(uint32(childPID), job)
	if err != nil {
		return err
	}
	if !inJob {
		return errors.New("the process is not a member of the scope job")
	}
	return nil
}

func publishWindowsPermit(ctx context.Context, stateDir string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	return publishText(stateDir, permitFile, "permit")
}

func jobNameFor(stateDir string) string {
	sum := sha256.Sum256([]byte(stateDir))
	return windowsJobNamePrefix + hex.EncodeToString(sum[:])[:24]
}

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

func jobActiveProcesses(job windows.Handle) (uint32, error) {
	var accounting windowsJobAccounting
	if err := windows.QueryInformationJobObject(job, windows.JobObjectBasicAccountingInformation, uintptr(unsafe.Pointer(&accounting)), uint32(unsafe.Sizeof(accounting)), nil); err != nil {
		return 0, fmt.Errorf("cannot read process scope Job accounting: %w", err)
	}
	return accounting.ActiveProcesses, nil
}

type windowsHelperPipes struct {
	owner        windows.Handle
	commandInput windows.Handle
	output       windows.Handle
	errorOutput  windows.Handle
	ownerFile    *os.File
	inputFile    *os.File
	outputFile   *os.File
	errorFile    *os.File
	childHandles []windows.Handle
}

func createWindowsHelperPipes() (*windowsHelperPipes, error) {
	pipes := &windowsHelperPipes{}
	ownerFile, ownerChild, err := windowsPipe(true)
	if err != nil {
		return nil, err
	}
	pipes.ownerFile, pipes.owner = ownerFile, ownerChild
	inputFile, inputChild, err := windowsPipe(true)
	if err != nil {
		pipes.close()
		return nil, err
	}
	pipes.inputFile, pipes.commandInput = inputFile, inputChild
	outputFile, outputChild, err := windowsPipe(false)
	if err != nil {
		pipes.close()
		return nil, err
	}
	pipes.outputFile, pipes.output = outputFile, outputChild
	errorFile, errorChild, err := windowsPipe(false)
	if err != nil {
		pipes.close()
		return nil, err
	}
	pipes.errorFile, pipes.errorOutput = errorFile, errorChild
	pipes.childHandles = []windows.Handle{ownerChild, inputChild, outputChild, errorChild}
	return pipes, nil
}

func windowsPipe(parentWrites bool) (*os.File, windows.Handle, error) {
	sa := windows.SecurityAttributes{Length: uint32(unsafe.Sizeof(windows.SecurityAttributes{})), InheritHandle: 1}
	var readHandle, writeHandle windows.Handle
	if err := windows.CreatePipe(&readHandle, &writeHandle, &sa, 0); err != nil {
		return nil, 0, err
	}
	parent, child := readHandle, writeHandle
	if parentWrites {
		parent, child = writeHandle, readHandle
	}
	if err := windows.SetHandleInformation(parent, windows.HANDLE_FLAG_INHERIT, 0); err != nil {
		_ = windows.CloseHandle(readHandle)
		_ = windows.CloseHandle(writeHandle)
		return nil, 0, err
	}
	file := os.NewFile(uintptr(parent), "native-pipe")
	if file == nil {
		_ = windows.CloseHandle(readHandle)
		_ = windows.CloseHandle(writeHandle)
		return nil, 0, errors.New("cannot wrap a native pipe handle")
	}
	return file, child, nil
}

func (p *windowsHelperPipes) closeChildHandles() error {
	var failures []error
	open := make([]windows.Handle, 0, len(p.childHandles))
	for _, handle := range p.childHandles {
		if err := windows.CloseHandle(handle); err != nil {
			failures = append(failures, err)
			open = append(open, handle)
		}
	}
	p.childHandles = open
	return errors.Join(failures...)
}

func (p *windowsHelperPipes) close() {
	_ = p.closeChildHandles()
	for _, file := range []*os.File{p.ownerFile, p.inputFile, p.outputFile, p.errorFile} {
		if file != nil {
			_ = file.Close()
		}
	}
}

func startWindowsHelper(stateDir string, pipes *windowsHelperPipes, env []string) (windows.ProcessInformation, error) {
	executable, err := os.Executable()
	if err != nil {
		return windows.ProcessInformation{}, err
	}
	app, err := windows.UTF16PtrFromString(executable)
	if err != nil {
		return windows.ProcessInformation{}, err
	}
	line, err := windows.UTF16PtrFromString(ComposeCommandLine([]string{executable, "__native-helper", stateDir}))
	if err != nil {
		return windows.ProcessInformation{}, err
	}
	currentDir, err := os.Getwd()
	if err != nil {
		return windows.ProcessInformation{}, err
	}
	currentDir16, err := windows.UTF16PtrFromString(currentDir)
	if err != nil {
		return windows.ProcessInformation{}, err
	}
	environment := windowsEnvironmentBlock(env)
	attributes, err := windows.NewProcThreadAttributeList(1)
	if err != nil {
		return windows.ProcessInformation{}, err
	}
	defer attributes.Delete()
	if err := attributes.Update(windows.PROC_THREAD_ATTRIBUTE_HANDLE_LIST, unsafe.Pointer(&pipes.childHandles[0]), uintptr(len(pipes.childHandles))*unsafe.Sizeof(pipes.childHandles[0])); err != nil {
		return windows.ProcessInformation{}, err
	}
	var startup windows.StartupInfoEx
	startup.Cb = uint32(unsafe.Sizeof(startup))
	startup.Flags = windows.STARTF_USESTDHANDLES
	startup.StdInput, startup.StdOutput, startup.StdErr = pipes.owner, pipes.output, pipes.errorOutput
	startup.ProcThreadAttributeList = attributes.List()
	var information windows.ProcessInformation
	flags := uint32(windows.EXTENDED_STARTUPINFO_PRESENT | windows.CREATE_UNICODE_ENVIRONMENT)
	if err := windows.CreateProcess(app, line, nil, nil, true, flags, &environment[0], currentDir16, &startup.StartupInfo, &information); err != nil {
		return windows.ProcessInformation{}, fmt.Errorf("cannot create the process scope helper: %w", err)
	}
	return information, nil
}

func mergeWindowsEnvironment(base, overrides []string) []string {
	values := make(map[string]string, len(base)+len(overrides))
	for _, entry := range append(append([]string(nil), base...), overrides...) {
		key, value, ok := strings.Cut(entry, "=")
		if !ok || key == "" || strings.ContainsRune(entry, '\x00') || strings.EqualFold(key, "NODE_CHANNEL_FD") {
			continue
		}
		values[strings.ToUpper(key)] = key + "=" + value
	}
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	result := make([]string, 0, len(keys))
	for _, key := range keys {
		result = append(result, values[key])
	}
	return result
}

func windowsEnvironmentBlock(entries []string) []uint16 {
	values := make(map[string]string, len(entries))
	for _, entry := range entries {
		key, value, ok := strings.Cut(entry, "=")
		if !ok || key == "" || strings.ContainsRune(entry, '\x00') || strings.ContainsRune(value, '\x00') || strings.ContainsRune(key, '=') || strings.EqualFold(key, "NODE_CHANNEL_FD") {
			continue
		}
		values[strings.ToUpper(key)] = key + "=" + value
	}
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	items := make([]string, 0, len(keys))
	for _, key := range keys {
		items = append(items, values[key])
	}
	block := strings.Join(items, "\x00") + "\x00\x00"
	return utf16.Encode([]rune(block))
}

type windowsManagedPipe struct {
	file      *os.File
	closeOnce sync.Once
	closeErr  error
}

func (p *windowsManagedPipe) Read(data []byte) (int, error)  { return p.file.Read(data) }
func (p *windowsManagedPipe) Write(data []byte) (int, error) { return p.file.Write(data) }
func (p *windowsManagedPipe) Close() error {
	p.closeOnce.Do(func() { p.closeErr = p.file.Close() })
	return p.closeErr
}

type windowsProcess struct {
	supervisor       *windowsSupervisor
	pid              int
	helper           windows.Handle
	helperThread     windows.Handle
	inheritedHandles []windows.Handle
	job              windows.Handle
	owner            *os.File
	stdin            *windowsManagedPipe
	stdout           *windowsManagedPipe
	stderr           *windowsManagedPipe
	stateDir         string
	jobName          string
	done             chan struct{}
	waitFailure      chan struct{}
	doneOnce         sync.Once
	helperMu         sync.Mutex
	helperCode       uint32
	helperWaitErr    error
	mu               sync.Mutex
	settled          bool
	exit             ProcessExit
	waitErr          error
	terminationErr   error
	closeErr         error
	terminationAPI   windowsProcessTerminationAPI
}

func (p *windowsProcess) PID() int              { return p.pid }
func (p *windowsProcess) Stdin() io.WriteCloser { return p.stdin }
func (p *windowsProcess) Stdout() io.ReadCloser { return p.stdout }
func (p *windowsProcess) Stderr() io.ReadCloser { return p.stderr }

func (p *windowsProcess) waitHelper() {
	for {
		status, err := windows.WaitForSingleObject(p.helper, uint32(windowsExitPoll/time.Millisecond))
		if err != nil {
			p.setWaitFailure(err)
			return
		}
		if status == windows.WAIT_OBJECT_0 {
			p.recordHelperExit()
			return
		}
		if status != uint32(windows.WAIT_TIMEOUT) {
			p.setWaitFailure(fmt.Errorf("unexpected helper wait status: %#x", status))
			return
		}
	}
}

func (p *windowsProcess) setWaitFailure(err error) {
	p.helperMu.Lock()
	p.helperWaitErr = err
	p.helperMu.Unlock()
	close(p.waitFailure)
}

func (p *windowsProcess) recordHelperExit() {
	var code uint32
	err := windows.GetExitCodeProcess(p.helper, &code)
	p.helperMu.Lock()
	p.helperCode, p.helperWaitErr = code, err
	p.helperMu.Unlock()
	p.doneOnce.Do(func() { close(p.done) })
}

func (p *windowsProcess) awaitScope(ctx context.Context) error {
	return p.awaitScopeWithBudget(ctx, windowsPrepareBudget)
}

func (p *windowsProcess) awaitScopeWithBudget(ctx context.Context, budget time.Duration) error {
	deadline := time.NewTimer(budget)
	defer deadline.Stop()
	ticker := time.NewTicker(windowsScopePoll)
	defer ticker.Stop()
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		if _, err := p.readScopePID(); err == nil {
			return nil
		} else if !errors.Is(err, os.ErrNotExist) {
			return err
		}
		select {
		case <-p.done:
			return p.helperFailure()
		case <-p.waitFailure:
			return p.helperFailure()
		case <-ctx.Done():
			return ctx.Err()
		case <-deadline.C:
			return errors.New("the process scope helper did not establish the OS scope in time")
		case <-ticker.C:
		}
	}
}

func (p *windowsProcess) readScopePID() (int, error) {
	data, err := readState(p.stateDir, scopeFile)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return 0, err
		}
		return 0, err
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(data)))
	if err != nil || pid <= 0 {
		return 0, errors.New("the process scope helper published an invalid scope id")
	}
	return pid, nil
}

func (p *windowsProcess) helperFailure() error {
	phase, err := readState(p.stateDir, errorFile)
	if err == nil {
		return errors.New(strings.TrimSpace(string(phase)))
	}
	p.helperMu.Lock()
	code, waitErr := p.helperCode, p.helperWaitErr
	p.helperMu.Unlock()
	if waitErr != nil {
		return fmt.Errorf("cannot wait for the process scope helper: %w", waitErr)
	}
	return fmt.Errorf("the process scope helper exited with code %d before establishing the OS scope", code)
}

func (p *windowsProcess) Wait(ctx context.Context) (ProcessExit, error) {
	select {
	case <-ctx.Done():
		return ProcessExit{}, ctx.Err()
	case <-p.done:
	case <-p.waitFailure:
		p.helperMu.Lock()
		waitErr := p.helperWaitErr
		p.helperMu.Unlock()
		return ProcessExit{}, fmt.Errorf("cannot wait for the process scope helper: %w", waitErr)
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.terminationErr != nil {
		return ProcessExit{}, p.terminationErr
	}
	if p.settled {
		return p.exit, errors.Join(p.waitErr, p.closeErr)
	}
	cleanup, err := readCleanup(p.stateDir)
	if err != nil || cleanup == nil || !*cleanup {
		return ProcessExit{}, p.cacheConvergenceError(errors.New("the process scope helper did not prove cleanup"))
	}
	if p.job == 0 {
		return ProcessExit{}, p.cacheConvergenceError(errors.New("the process scope job handle is missing"))
	}
	if err := awaitWindowsJobEmpty(ctx, p.job, windowsDrainBudget); err != nil {
		return ProcessExit{}, p.cacheConvergenceError(err)
	}
	p.exit = p.readExitFallback()
	if phase, err := readState(p.stateDir, errorFile); err == nil {
		p.waitErr = errors.New(strings.TrimSpace(string(phase)))
	}
	_, helperErr := p.helperStatus()
	p.waitErr = errors.Join(p.waitErr, helperErr)
	p.settled = true
	p.closeErr = p.closeResources()
	if p.closeErr == nil {
		p.closeErr = removeStateDir(p.stateDir)
	}
	if p.closeErr == nil {
		p.supervisor.remove(p.pid)
	}
	return p.exit, errors.Join(p.waitErr, p.closeErr)
}

func (s *windowsSupervisor) remove(pid int) { s.mu.Lock(); delete(s.processes, pid); s.mu.Unlock() }

func (p *windowsProcess) readExitFallback() ProcessExit {
	if _, err := readState(p.stateDir, errorFile); err == nil {
		return ProcessExit{}
	}
	if report, err := readExit(p.stateDir); err == nil && report != nil {
		return ProcessExit{Code: report.ExitCode, Signal: report.Signal}
	}
	code, waitErr := p.helperStatus()
	if waitErr != nil || code == errorExitUnknown {
		return ProcessExit{}
	}
	result := int(code)
	return ProcessExit{Code: &result}
}

func (p *windowsProcess) helperStatus() (uint32, error) {
	p.helperMu.Lock()
	defer p.helperMu.Unlock()
	return p.helperCode, p.helperWaitErr
}

func (p *windowsProcess) Terminate(ctx context.Context, _ time.Duration) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.terminationErr != nil {
		return p.terminationErr
	}
	if p.settled {
		return p.closeErr
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	api := p.terminationAPI
	if api == nil {
		api = nativeWindowsProcessTerminationAPI{}
	}
	var resourceErrors []error
	if p.owner != nil {
		if err := p.owner.Close(); err != nil {
			resourceErrors = append(resourceErrors, err)
		}
		p.owner = nil
	}
	var convergenceErrors []error
	converged := false
	if p.job != 0 {
		_ = api.TerminateJobObject(p.job, 1)
		err := api.AwaitJobEmpty(context.Background(), p.job, windowsDrainBudget)
		if err != nil {
			convergenceErrors = append(convergenceErrors, err)
		} else {
			converged = true
		}
	} else if err := terminateWindowsProcessWithAPI(context.Background(), p.helper, windowsForceKillBudget, api); err != nil {
		convergenceErrors = append(convergenceErrors, err)
	} else {
		converged = true
	}
	if p.job != 0 {
		if err := api.CloseHandle(p.job); err != nil {
			resourceErrors = append(resourceErrors, fmt.Errorf("cannot close process scope job: %w", err))
		}
		p.job = 0
	}
	if converged {
		if err := p.awaitHelperExit(context.Background(), windowsForceKillBudget); err != nil {
			if p.helper != 0 {
				_ = api.TerminateProcess(p.helper, 1)
			}
			if err := p.awaitHelperExit(context.Background(), windowsForceKillBudget); err != nil {
				convergenceErrors = append(convergenceErrors, err)
			}
		}
	}
	if !converged || p.helperStillAlive() {
		if len(convergenceErrors) == 0 {
			convergenceErrors = append(convergenceErrors, errors.New("the process scope could not be proven converged"))
		}
	}
	if len(convergenceErrors) > 0 {
		failures := append(convergenceErrors, resourceErrors...)
		p.terminationErr = &ProcessScopeNotConvergedError{Cause: errors.Join(failures...)}
		return p.terminationErr
	}
	p.settled = true
	resourceErrors = append(resourceErrors, p.closeResources())
	if err := errors.Join(resourceErrors...); err != nil {
		p.terminationErr = err
		return err
	}
	if err := removeStateDir(p.stateDir); err != nil {
		p.terminationErr = err
		return err
	}
	p.supervisor.remove(p.pid)
	return nil
}

func (p *windowsProcess) helperStillAlive() bool {
	if p.helper == 0 {
		return false
	}
	status, err := windows.WaitForSingleObject(p.helper, 0)
	return err != nil || status != windows.WAIT_OBJECT_0
}

func (p *windowsProcess) closeResources() error {
	var failures []error
	if p.owner != nil {
		if err := p.owner.Close(); err != nil {
			failures = append(failures, err)
		}
		p.owner = nil
	}
	if p.stdin != nil {
		if err := p.stdin.Close(); err != nil {
			failures = append(failures, err)
		}
		p.stdin = nil
	}
	if p.stdout != nil {
		if err := p.stdout.Close(); err != nil {
			failures = append(failures, err)
		}
		p.stdout = nil
	}
	if p.stderr != nil {
		if err := p.stderr.Close(); err != nil {
			failures = append(failures, err)
		}
		p.stderr = nil
	}
	for _, handle := range p.inheritedHandles {
		if err := windows.CloseHandle(handle); err != nil {
			failures = append(failures, err)
		}
	}
	p.inheritedHandles = nil
	if p.helperThread != 0 {
		if err := windows.CloseHandle(p.helperThread); err != nil {
			failures = append(failures, fmt.Errorf("cannot close helper thread handle: %w", err))
		} else {
			p.helperThread = 0
		}
	}
	if p.helperStillAlive() {
		failures = append(failures, errors.New("cannot close helper process handle before exit is proven"))
	} else if p.helper != 0 {
		if err := windows.CloseHandle(p.helper); err != nil {
			failures = append(failures, fmt.Errorf("cannot close helper process handle: %w", err))
		} else {
			p.helper = 0
		}
	}
	return errors.Join(failures...)
}

func (p *windowsProcess) cacheConvergenceError(cause error) error {
	p.terminationErr = &ProcessScopeNotConvergedError{Cause: cause}
	return p.terminationErr
}

func (p *windowsProcess) cleanupFailedStart(cause error) error {
	var resourceErrors []error
	var proofErrors []error
	if p.owner != nil {
		if err := p.owner.Close(); err != nil {
			resourceErrors = append(resourceErrors, err)
		}
		p.owner = nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), windowsDrainBudget+windowsForceKillBudget)
	defer cancel()
	if p.job == 0 {
		if _, err := p.readScopePID(); err == nil {
			job, openErr := openWindowsJob(p.jobName)
			if openErr == nil {
				p.job = job
			} else {
				proofErrors = append(proofErrors, openErr)
			}
		}
	}
	if p.job != 0 {
		_ = windows.TerminateJobObject(p.job, 1)
		if err := awaitWindowsJobEmpty(ctx, p.job, windowsDrainBudget); err != nil {
			proofErrors = append(proofErrors, err)
		}
		if err := windows.CloseHandle(p.job); err != nil {
			resourceErrors = append(resourceErrors, err)
		}
		p.job = 0
	}
	if p.helperStillAlive() {
		if p.helper != 0 {
			_ = windows.TerminateProcess(p.helper, 1)
		}
		if err := p.awaitHelperExit(ctx, windowsForceKillBudget); err != nil {
			proofErrors = append(proofErrors, err)
		}
	}
	if err := p.closeResources(); err != nil {
		resourceErrors = append(resourceErrors, err)
	}
	if len(proofErrors) > 0 {
		return &ProcessScopeNotConvergedError{Cause: errors.Join(append([]error{cause}, proofErrors...)...)}
	}
	if err := removeStateDir(p.stateDir); err != nil {
		resourceErrors = append(resourceErrors, err)
	}
	if len(resourceErrors) > 0 {
		return errors.Join(cause, errors.Join(resourceErrors...))
	}
	return cause
}

func awaitWindowsJobEmpty(ctx context.Context, job windows.Handle, budget time.Duration) error {
	deadline := time.NewTimer(budget)
	defer deadline.Stop()
	ticker := time.NewTicker(windowsScopePoll)
	defer ticker.Stop()
	var lastErr error
	for {
		active, err := jobActiveProcesses(job)
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

func (p *windowsProcess) awaitHelperExit(ctx context.Context, budget time.Duration) error {
	timer := time.NewTimer(budget)
	defer timer.Stop()
	ticker := time.NewTicker(windowsScopePoll)
	defer ticker.Stop()
	for {
		status, err := windows.WaitForSingleObject(p.helper, 0)
		if err != nil {
			return fmt.Errorf("cannot observe process scope helper exit: %w", err)
		}
		if status == windows.WAIT_OBJECT_0 {
			p.recordHelperExit()
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-timer.C:
			return errors.New("the process scope helper did not exit within its deadline")
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
