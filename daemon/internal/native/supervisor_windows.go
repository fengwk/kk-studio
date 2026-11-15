//go:build windows

package native

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

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
	if err := validateWindowsProcessSpec(spec); err != nil {
		return nil, err
	}
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil, errors.New("the process supervisor is closed")
	}
	s.mu.Unlock()
	stateDir, err := prepareWindowsScopeState(spec)
	if err != nil {
		return nil, err
	}
	pipes, helper, err := startWindowsHelperProcess(stateDir, spec)
	if err != nil {
		return nil, err
	}
	threadCloseErr := windows.CloseHandle(helper.Thread)
	pipesCloseErr := pipes.closeChildHandles()
	p := &windowsProcess{
		supervisor:       s,
		pid:              int(helper.ProcessId),
		helper:           helper.Process,
		helperThread:     helper.Thread,
		inheritedHandles: append([]windows.Handle(nil), pipes.childHandles...),
		owner:            pipes.ownerFile,
		stdin:            &windowsManagedPipe{file: pipes.inputFile},
		stdout:           &windowsManagedPipe{file: pipes.outputFile},
		stderr:           &windowsManagedPipe{file: pipes.errorFile},
		stateDir:         stateDir,
		jobName:          jobNameFor(stateDir),
		done:             make(chan struct{}),
		waitFailure:      make(chan struct{}),
		helperCode:       errorExitUnknown,
	}
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

func validateWindowsProcessSpec(spec ProcessSpec) error {
	if spec.Executable == "" {
		return errors.New("the process executable must not be empty")
	}
	for _, value := range append([]string{spec.Executable, spec.Directory}, spec.Args...) {
		if strings.ContainsRune(value, '\x00') {
			return errors.New("process arguments must not contain a NUL character")
		}
	}
	return nil
}

func prepareWindowsScopeState(spec ProcessSpec) (stateDir string, err error) {
	stateDir, err = createStateDir()
	if err != nil {
		return "", err
	}
	cwd := spec.Directory
	if cwd == "" {
		cwd, err = os.Getwd()
		if err != nil {
			_ = removeStateDir(stateDir)
			return "", err
		}
	}
	launch := launchSpec{Cwd: cwd, Args: append([]string{spec.Executable}, spec.Args...)}
	payload, err := json.Marshal(launch)
	if err != nil {
		_ = removeStateDir(stateDir)
		return "", err
	}
	if err := publish(stateDir, launchFile, payload); err != nil {
		_ = removeStateDir(stateDir)
		return "", err
	}
	if err := verifyPrivateFile(filepath.Join(stateDir, launchFile)); err != nil {
		_ = removeStateDir(stateDir)
		return "", err
	}
	return stateDir, nil
}

func startWindowsHelperProcess(stateDir string, spec ProcessSpec) (*windowsHelperPipes, windows.ProcessInformation, error) {
	pipes, err := createWindowsHelperPipes()
	if err != nil {
		_ = removeStateDir(stateDir)
		return nil, windows.ProcessInformation{}, err
	}
	helperEnv := mergeWindowsEnvironment(os.Environ(), spec.Env)
	helperEnv = mergeWindowsEnvironment(helperEnv, []string{windowsStdinHandleEnv + "=" + strconv.FormatUint(uint64(pipes.commandInput), 10)})
	helper, err := startWindowsHelper(stateDir, pipes, helperEnv)
	if err != nil {
		pipes.close()
		_ = removeStateDir(stateDir)
		return nil, windows.ProcessInformation{}, err
	}
	return pipes, helper, nil
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

func (s *windowsSupervisor) remove(pid int) { s.mu.Lock(); delete(s.processes, pid); s.mu.Unlock() }
