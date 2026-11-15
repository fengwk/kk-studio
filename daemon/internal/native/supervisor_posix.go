//go:build linux || darwin

package native

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	scopePollInterval    = 5 * time.Millisecond
	scopeStartBudget     = 20 * time.Second
	scopeConvergeBudget  = 4 * time.Second
	defaultGracePeriod   = time.Second
	posixForceKillBudget = time.Second
	helperGracePeriod    = 600 * time.Millisecond
)

// PosixSupervisor supervises each command in a private process session.
type PosixSupervisor struct {
	mu                     sync.Mutex
	closed                 bool
	processes              map[int]*posixProcess
	snapshotDirectChildren func(int, []int) ([]sessionMember, error)
	startAPI               posixStartAPI
	publishLaunch          func(string, string, []byte) error
	startHelper            func(*exec.Cmd) error
}

type posixStartAPI struct {
	createStateDir func() (string, error)
	publishLaunch  func(string, []byte) error
	pipe           func() (*os.File, *os.File, error)
	executable     func() (string, error)
	start          func(*exec.Cmd) error
}

func nativePOSIXStartAPI() posixStartAPI {
	return posixStartAPI{
		createStateDir: createStateDir,
		publishLaunch:  func(dir string, payload []byte) error { return publish(dir, launchFile, payload) },
		pipe:           os.Pipe,
		executable:     os.Executable,
		start:          func(cmd *exec.Cmd) error { return cmd.Start() },
	}
}

// NewSupervisor enables the required host process adoption behavior and returns a POSIX supervisor.
func NewSupervisor() (Supervisor, error) {
	return newPOSIXSupervisor(enableChildSubreaper)
}

func newPOSIXSupervisor(enableSubreaper func() error) (Supervisor, error) {
	if runtime.GOOS == "linux" {
		if err := enableSubreaper(); err != nil {
			return nil, fmt.Errorf("cannot enable process subreaper: %w", err)
		}
	}
	return &PosixSupervisor{
		processes:              make(map[int]*posixProcess),
		snapshotDirectChildren: directChildren,
		startAPI:               nativePOSIXStartAPI(),
	}, nil
}

func validateProcessSpec(spec ProcessSpec) error {
	if len(spec.Executable) == 0 {
		return errors.New("the process executable must not be empty")
	}
	for _, value := range append([]string{spec.Executable, spec.Directory}, spec.Args...) {
		if strings.ContainsRune(value, '\x00') {
			return errors.New("process arguments must not contain a NUL character")
		}
	}
	return nil
}

func (s *PosixSupervisor) preparePOSIXStartState(spec ProcessSpec) (string, posixStartAPI, launchSpec, []int, bool, error) {
	api := s.startAPI
	if api.createStateDir == nil {
		api = nativePOSIXStartAPI()
	}
	var baselinePIDs []int
	baselineKnown := false
	if runtime.GOOS == "linux" {
		baseline, err := s.snapshotDirectChildren(os.Getpid(), nil)
		if err != nil {
			return "", api, launchSpec{}, nil, false, fmt.Errorf("cannot snapshot pre-existing process children: %w", err)
		}
		baselineKnown = true
		for _, child := range baseline {
			baselinePIDs = append(baselinePIDs, child.PID)
		}
	}
	dir, err := api.createStateDir()
	if err != nil {
		return "", api, launchSpec{}, nil, false, err
	}
	cwd := spec.Directory
	if cwd == "" {
		cwd, err = os.Getwd()
		if err != nil {
			_ = removeStateDir(dir)
			return "", api, launchSpec{}, nil, false, err
		}
	}
	launch := launchSpec{Cwd: cwd, Args: append([]string{spec.Executable}, spec.Args...)}
	payload, err := json.Marshal(launch)
	if err == nil {
		publishLaunch := s.publishLaunch
		if publishLaunch != nil {
			err = publishLaunch(dir, launchFile, payload)
		} else {
			err = api.publishLaunch(dir, payload)
		}
	}
	if err != nil {
		_ = removeStateDir(dir)
		return "", api, launchSpec{}, nil, false, err
	}
	return dir, api, launch, baselinePIDs, baselineKnown, nil
}

func (s *PosixSupervisor) Start(ctx context.Context, spec ProcessSpec) (ManagedProcess, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if err := validateProcessSpec(spec); err != nil {
		return nil, err
	}
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil, errors.New("the process supervisor is closed")
	}
	s.mu.Unlock()
	dir, api, _, baselinePIDs, baselineKnown, err := s.preparePOSIXStartState(spec)
	if err != nil {
		return nil, err
	}
	cleanupStart := func(files ...*os.File) {
		for _, file := range files {
			_ = file.Close()
		}
		_ = removeStateDir(dir)
	}
	ownerReader, ownerWriter, err := api.pipe()
	if err != nil {
		cleanupStart()
		return nil, err
	}
	executable, err := api.executable()
	if err != nil {
		cleanupStart(ownerReader, ownerWriter)
		return nil, err
	}
	cmd := exec.Command(executable, "__native-helper", dir)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	cmd.ExtraFiles = []*os.File{ownerReader}
	cmd.Env = mergeEnvironment(os.Environ(), spec.Env)
	stdinReader, stdin, err := api.pipe()
	if err != nil {
		cleanupStart(ownerReader, ownerWriter)
		return nil, err
	}
	stdout, stdoutWriter, err := api.pipe()
	if err != nil {
		cleanupStart(stdinReader, stdin, ownerReader, ownerWriter)
		return nil, err
	}
	stderr, stderrWriter, err := api.pipe()
	if err != nil {
		cleanupStart(stdinReader, stdin, stdout, stdoutWriter, ownerReader, ownerWriter)
		return nil, err
	}
	cmd.Stdin, cmd.Stdout, cmd.Stderr = stdinReader, stdoutWriter, stderrWriter
	startHelper := s.startHelper
	if startHelper == nil {
		startHelper = api.start
	}
	if err := startHelper(cmd); err != nil {
		cleanupStart(stdinReader, stdin, stdout, stdoutWriter, stderr, stderrWriter, ownerReader, ownerWriter)
		return nil, fmt.Errorf("cannot start process scope helper: %w", err)
	}
	_ = stdinReader.Close()
	_ = stdoutWriter.Close()
	_ = stderrWriter.Close()
	_ = ownerReader.Close()
	p := &posixProcess{
		supervisor:      s,
		cmd:             cmd,
		pid:             cmd.Process.Pid,
		sid:             cmd.Process.Pid,
		stateDir:        dir,
		owner:           ownerWriter,
		stdin:           stdin,
		stdout:          stdout,
		stderr:          stderr,
		baselinePIDs:    baselinePIDs,
		baselineKnown:   baselineKnown,
		trackedSessions: make(map[int]trackedSession),
		processGroupID:  syscall.Getpgid,
		done:            make(chan struct{}),
	}
	go func() { p.waitErr = cmd.Wait(); close(p.done) }()
	if err := p.awaitScope(ctx); err != nil {
		failure := p.cleanupFailedStart(err)
		return nil, failure
	}
	p.scopeConfirmed = true
	if err := publishPOSIXPermit(ctx, dir); err != nil {
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

func publishPOSIXPermit(ctx context.Context, stateDir string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	return publishText(stateDir, permitFile, "true")
}

func (s *PosixSupervisor) Close(ctx context.Context) error {
	s.mu.Lock()
	s.closed = true
	processes := make([]*posixProcess, 0, len(s.processes))
	for _, p := range s.processes {
		processes = append(processes, p)
	}
	s.mu.Unlock()
	var failures []error
	for _, p := range processes {
		if err := p.Terminate(ctx, defaultGracePeriod); err != nil {
			failures = append(failures, err)
		}
	}
	return errors.Join(failures...)
}

func (s *PosixSupervisor) TrackDescendant(ctx context.Context, rootPID, pid int) error {
	return s.trackDescendantWithMembers(ctx, rootPID, pid, allProcessMembers)
}

func (s *PosixSupervisor) trackDescendantWithMembers(ctx context.Context, rootPID, pid int, snapshot func() ([]sessionMember, error)) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	s.mu.Lock()
	p := s.processes[rootPID]
	s.mu.Unlock()
	if p == nil {
		return errors.New("the process root is not managed by this supervisor")
	}
	members, err := snapshot()
	if err != nil {
		return err
	}
	if !isDescendantOf(members, pid, rootPID) {
		return errors.New("the process is not a verified descendant of the managed process")
	}
	for _, member := range members {
		if member.PID != pid {
			continue
		}
		if member.Session <= 0 || member.Identity == "" {
			return errors.New("the process session identity is unknown")
		}
		if member.Session == p.sid {
			return nil
		}
		p.mu.Lock()
		p.trackedSessions[member.Session] = trackedSession{ChildPID: member.PID, Session: member.Session, Identity: member.Identity}
		p.mu.Unlock()
		return nil
	}
	return errors.New("the descendant process could not be verified")
}
