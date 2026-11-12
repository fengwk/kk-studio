//go:build linux || darwin

package native

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	scopePollInterval   = 5 * time.Millisecond
	scopeStartBudget    = 20 * time.Second
	scopeConvergeBudget = 4 * time.Second
	helperGracePeriod   = 600 * time.Millisecond
)

// PosixSupervisor supervises each command in a private process session.
type PosixSupervisor struct {
	mu        sync.Mutex
	closed    bool
	processes map[int]*posixProcess
}

// NewSupervisor enables the required host process adoption behavior and returns a POSIX supervisor.
func NewSupervisor() (Supervisor, error) {
	if runtime.GOOS == "linux" {
		if err := enableChildSubreaper(); err != nil {
			return nil, fmt.Errorf("cannot enable process subreaper: %w", err)
		}
	}
	return &PosixSupervisor{processes: make(map[int]*posixProcess)}, nil
}

func (s *PosixSupervisor) Start(ctx context.Context, spec ProcessSpec) (ManagedProcess, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if len(spec.Executable) == 0 {
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
	var baselinePIDs []int
	baselineKnown := false
	if runtime.GOOS == "linux" {
		if baseline, err := directChildren(os.Getpid(), nil); err == nil {
			baselineKnown = true
			for _, child := range baseline {
				baselinePIDs = append(baselinePIDs, child.PID)
			}
		}
	}
	dir, err := createStateDir()
	if err != nil {
		return nil, err
	}
	cwd := spec.Directory
	if cwd == "" {
		cwd, err = os.Getwd()
		if err != nil {
			_ = removeStateDir(dir)
			return nil, err
		}
	}
	launch := launchSpec{Cwd: cwd, Args: append([]string{spec.Executable}, spec.Args...)}
	payload, err := json.Marshal(launch)
	if err == nil {
		err = publish(dir, launchFile, payload)
	}
	if err != nil {
		_ = removeStateDir(dir)
		return nil, err
	}
	ownerReader, ownerWriter, err := os.Pipe()
	if err != nil {
		_ = removeStateDir(dir)
		return nil, err
	}
	executable, err := os.Executable()
	if err != nil {
		_ = ownerReader.Close()
		_ = ownerWriter.Close()
		_ = removeStateDir(dir)
		return nil, err
	}
	cmd := exec.Command(executable, "__native-helper", dir)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	cmd.ExtraFiles = []*os.File{ownerReader}
	cmd.Env = mergeEnvironment(os.Environ(), spec.Env)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		_ = ownerReader.Close()
		_ = ownerWriter.Close()
		_ = removeStateDir(dir)
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		_ = stdin.Close()
		_ = ownerReader.Close()
		_ = ownerWriter.Close()
		_ = removeStateDir(dir)
		return nil, err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		_ = stdin.Close()
		_ = ownerReader.Close()
		_ = ownerWriter.Close()
		_ = removeStateDir(dir)
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		_ = stdin.Close()
		_ = ownerReader.Close()
		_ = ownerWriter.Close()
		_ = removeStateDir(dir)
		return nil, fmt.Errorf("cannot start process scope helper: %w", err)
	}
	_ = ownerReader.Close()
	p := &posixProcess{supervisor: s, cmd: cmd, pid: cmd.Process.Pid, sid: cmd.Process.Pid, stateDir: dir, owner: ownerWriter, stdin: stdin, stdout: stdout, stderr: stderr, baselinePIDs: baselinePIDs, baselineKnown: baselineKnown, done: make(chan struct{})}
	go func() { p.waitErr = cmd.Wait(); close(p.done) }()
	if err := p.awaitScope(ctx); err != nil {
		failure := p.cleanupFailedStart(err)
		return nil, failure
	}
	if err := publishText(dir, permitFile, "true"); err != nil {
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
		if err := p.Terminate(ctx, time.Second); err != nil {
			failures = append(failures, err)
		}
	}
	return errors.Join(failures...)
}

func (s *PosixSupervisor) TrackDescendant(ctx context.Context, rootPID, pid int) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	s.mu.Lock()
	p := s.processes[rootPID]
	s.mu.Unlock()
	if p == nil {
		return errors.New("the process root is not managed by this supervisor")
	}
	members, err := sessionMembers(p.sid)
	if err != nil {
		return err
	}
	for _, member := range members {
		if member.PID == pid && member.Session == p.sid && member.Identity != "" {
			return nil
		}
	}
	return errors.New("the process is not a verified member of the managed session")
}

type posixProcess struct {
	supervisor    *PosixSupervisor
	cmd           *exec.Cmd
	pid           int
	sid           int
	stateDir      string
	owner         *os.File
	stdin         io.WriteCloser
	stdout        io.ReadCloser
	stderr        io.ReadCloser
	baselinePIDs  []int
	baselineKnown bool
	done          chan struct{}
	waitErr       error
	mu            sync.Mutex
	settled       bool
	termErr       error
}

func (p *posixProcess) PID() int              { return p.pid }
func (p *posixProcess) Stdin() io.WriteCloser { return p.stdin }
func (p *posixProcess) Stdout() io.ReadCloser { return p.stdout }
func (p *posixProcess) Stderr() io.ReadCloser { return p.stderr }

func (p *posixProcess) Wait(ctx context.Context) (ProcessExit, error) {
	select {
	case <-ctx.Done():
		return ProcessExit{}, ctx.Err()
	case <-p.done:
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.termErr != nil {
		return ProcessExit{}, p.termErr
	}
	if p.settled {
		return readExitFallback(p.stateDir, p.waitErr), nil
	}
	clean, err := readCleanup(p.stateDir)
	if err != nil || clean == nil || !*clean {
		cause := errors.New("the process scope helper did not prove cleanup")
		p.termErr = &ProcessScopeNotConvergedError{Cause: errors.Join(cause, p.closeStreams())}
		return ProcessExit{}, p.termErr
	}
	if err := p.closeStreams(); err != nil {
		p.termErr = err
		return ProcessExit{}, p.termErr
	}
	exit := readExitFallback(p.stateDir, p.waitErr)
	if err := removeStateDir(p.stateDir); err != nil {
		p.termErr = err
		return ProcessExit{}, p.termErr
	}
	p.settled = true
	p.supervisor.mu.Lock()
	delete(p.supervisor.processes, p.pid)
	p.supervisor.mu.Unlock()
	return exit, nil
}

func (p *posixProcess) closeStreams() error {
	streams := []struct {
		name string
		file io.Closer
	}{
		{name: "stdin", file: p.stdin},
		{name: "stdout", file: p.stdout},
		{name: "stderr", file: p.stderr},
		{name: "owner", file: p.owner},
	}
	var failures []error
	for _, stream := range streams {
		if err := stream.file.Close(); err != nil && !errors.Is(err, os.ErrClosed) {
			failures = append(failures, fmt.Errorf("cannot close process %s: %w", stream.name, err))
		}
	}
	return errors.Join(failures...)
}

func (p *posixProcess) convergeAdoptedChildren(ctx context.Context, grace time.Duration) error {
	if runtime.GOOS != "linux" || !p.baselineKnown {
		return nil
	}
	excluded := append([]int(nil), p.baselinePIDs...)
	p.supervisor.mu.Lock()
	for pid := range p.supervisor.processes {
		excluded = append(excluded, pid)
	}
	p.supervisor.mu.Unlock()
	return convergeAdoptedChildren(ctx, os.Getpid(), grace, excluded)
}

func (p *posixProcess) Terminate(ctx context.Context, grace time.Duration) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.termErr != nil {
		return p.termErr
	}
	if p.settled {
		return nil
	}
	streamErr := p.closeStreams()
	if grace < 0 {
		grace = 0
	}
	if err := convergeSession(ctx, p.sid, grace); err != nil {
		p.termErr = &ProcessScopeNotConvergedError{Cause: errors.Join(streamErr, err)}
		return p.termErr
	}
	select {
	case <-ctx.Done():
		p.termErr = &ProcessScopeNotConvergedError{Cause: errors.Join(streamErr, ctx.Err())}
		return p.termErr
	case <-p.done:
	}
	if err := p.convergeAdoptedChildren(ctx, grace); err != nil {
		p.termErr = &ProcessScopeNotConvergedError{Cause: errors.Join(streamErr, err)}
		return p.termErr
	}
	if streamErr != nil {
		p.termErr = streamErr
		return p.termErr
	}
	if err := removeStateDir(p.stateDir); err != nil {
		p.termErr = err
		return err
	}
	p.settled = true
	p.supervisor.mu.Lock()
	delete(p.supervisor.processes, p.pid)
	p.supervisor.mu.Unlock()
	return nil
}

func (p *posixProcess) awaitScope(ctx context.Context) error {
	deadline := time.NewTimer(scopeStartBudget)
	defer deadline.Stop()
	ticker := time.NewTicker(scopePollInterval)
	defer ticker.Stop()
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		if value, err := readState(p.stateDir, scopeFile); err == nil {
			if strings.TrimSpace(string(value)) != fmt.Sprint(p.sid) {
				return errors.New("the process scope helper published an untrusted scope")
			}
			members, err := sessionMembers(p.sid)
			if err != nil {
				return err
			}
			for _, member := range members {
				if member.PID == p.pid && member.Session == p.sid && member.Identity != "" {
					return nil
				}
			}
			return errors.New("the process scope helper is not the session leader")
		} else if !errors.Is(err, os.ErrNotExist) {
			return err
		}
		select {
		case <-p.done:
			if phase, err := readState(p.stateDir, errorFile); err == nil {
				return fmt.Errorf("the process scope helper failed: %s", strings.TrimSpace(string(phase)))
			}
			return errors.New("the process scope helper exited before establishing the scope")
		case <-ctx.Done():
			return ctx.Err()
		case <-deadline.C:
			return errors.New("the process scope helper did not establish the scope in time")
		case <-ticker.C:
		}
	}
}

func (p *posixProcess) cleanupFailedStart(cause error) error {
	cause = errors.Join(cause, p.closeStreams())
	ctx, cancel := context.WithTimeout(context.Background(), scopeConvergeBudget)
	defer cancel()
	if err := convergeSession(ctx, p.sid, 0); err != nil {
		return &ProcessScopeNotConvergedError{Cause: errors.Join(cause, err)}
	}
	select {
	case <-p.done:
	case <-ctx.Done():
		return &ProcessScopeNotConvergedError{Cause: errors.Join(cause, ctx.Err())}
	}
	if err := p.convergeAdoptedChildren(ctx, 0); err != nil {
		return &ProcessScopeNotConvergedError{Cause: errors.Join(cause, err)}
	}
	_ = removeStateDir(p.stateDir)
	return cause
}

func readExitFallback(dir string, waitErr error) ProcessExit {
	if _, err := readState(dir, errorFile); err == nil {
		return ProcessExit{}
	}
	if report, err := readExit(dir); err == nil && report != nil {
		return ProcessExit{Code: report.ExitCode, Signal: report.Signal}
	}
	if waitErr == nil {
		code := 0
		return ProcessExit{Code: &code}
	}
	var exitErr *exec.ExitError
	if errors.As(waitErr, &exitErr) {
		status, ok := exitErr.Sys().(syscall.WaitStatus)
		if ok && status.Signaled() {
			signal := status.Signal().String()
			return ProcessExit{Signal: &signal}
		}
		code := exitErr.ExitCode()
		return ProcessExit{Code: &code}
	}
	return ProcessExit{}
}

func convergeSession(ctx context.Context, sid int, grace time.Duration) error {
	return convergeSessionExcept(ctx, sid, grace, 0)
}

func convergeSessionExcept(ctx context.Context, sid int, grace time.Duration, excludedPID int) error {
	deadline := time.NewTimer(scopeConvergeBudget)
	defer deadline.Stop()
	started := time.Now()
	for {
		members, err := sessionMembers(sid)
		if err != nil {
			return err
		}
		active := make([]sessionMember, 0, len(members))
		for _, m := range members {
			if m.PID == excludedPID {
				continue
			}
			if m.isLive() {
				active = append(active, m)
			}
		}
		if len(active) == 0 {
			return nil
		}
		signal := syscall.SIGTERM
		if time.Since(started) >= grace {
			signal = syscall.SIGKILL
		}
		for _, m := range active {
			if m.Identity == "" {
				return errors.New("process scope member identity is unknown")
			}
			current, err := sessionMembers(sid)
			if err != nil {
				return err
			}
			verified := false
			for _, item := range current {
				if item.PID == m.PID && item.Session == sid && item.Identity == m.Identity {
					verified = true
					break
				}
			}
			if !verified {
				continue
			}
			if err := syscall.Kill(m.PID, signal); err != nil && !errors.Is(err, syscall.ESRCH) {
				return fmt.Errorf("cannot signal process scope member: %w", err)
			}
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-deadline.C:
			return errors.New("process scope did not converge within its deadline")
		case <-time.After(scopePollInterval):
		}
	}
}

func mergeEnvironment(base, overrides []string) []string {
	values := make(map[string]string, len(base)+len(overrides))
	for _, entry := range append(append([]string(nil), base...), overrides...) {
		key, value, ok := strings.Cut(entry, "=")
		if !ok || key == "" || strings.ContainsRune(entry, '\x00') {
			continue
		}
		if key == "NODE_CHANNEL_FD" {
			continue
		}
		values[key] = value
	}
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	result := make([]string, 0, len(keys))
	for _, key := range keys {
		result = append(result, key+"="+values[key])
	}
	return result
}

func helperMain(args []string) int {
	if len(args) != 1 || !filepath.IsAbs(args[0]) || hasControl(args[0]) {
		_, _ = fmt.Fprintln(os.Stderr, "helper-launch-failure")
		return 2
	}
	if runtime.GOOS == "linux" {
		if err := enableChildSubreaper(); err != nil {
			publishQuietly(args[0], errorFile, "helper-posix-failure")
			return 1
		}
	}
	if err := verifyPrivateDirectory(args[0]); err != nil {
		publishQuietly(args[0], errorFile, "helper-launch-failure")
		return 1
	}
	launch, err := readLaunch(args[0])
	if err != nil || launch == nil {
		publishQuietly(args[0], errorFile, "helper-launch-failure")
		return 1
	}
	if err := publishText(args[0], scopeFile, fmt.Sprint(os.Getpid())); err != nil {
		publishQuietly(args[0], errorFile, "helper-posix-failure")
		return 1
	}
	owner := os.NewFile(3, "owner-lifecycle")
	if owner == nil {
		publishQuietly(args[0], errorFile, "helper-posix-failure")
		return 1
	}
	ownerClosed := make(chan struct{})
	go func() { _, _ = io.Copy(io.Discard, owner); close(ownerClosed) }()
	deadline := time.NewTimer(scopeStartBudget)
	defer deadline.Stop()
	ticker := time.NewTicker(scopePollInterval)
	defer ticker.Stop()
	for {
		permit, permitErr := readState(args[0], permitFile)
		if permitErr == nil {
			if strings.TrimSpace(string(permit)) == "true" {
				break
			}
			publishQuietly(args[0], errorFile, "helper-permit-failure")
			if err := convergeSessionExcept(context.Background(), os.Getpid(), 0, os.Getpid()); err == nil {
				_ = publishText(args[0], cleanupFile, "true")
			} else {
				publishQuietly(args[0], cleanupFile, "false")
			}
			return 1
		}
		if !errors.Is(permitErr, os.ErrNotExist) {
			publishQuietly(args[0], errorFile, "helper-posix-failure")
			return 1
		}
		select {
		case <-ownerClosed:
			if err := convergeSessionExcept(context.Background(), os.Getpid(), 0, os.Getpid()); err != nil {
				publishQuietly(args[0], cleanupFile, "false")
				return 1
			}
			if err := publishText(args[0], cleanupFile, "true"); err != nil {
				return 1
			}
			return 0
		case <-deadline.C:
			publishQuietly(args[0], errorFile, "helper-permit-failure")
			if err := convergeSessionExcept(context.Background(), os.Getpid(), 0, os.Getpid()); err != nil {
				publishQuietly(args[0], cleanupFile, "false")
				return 1
			}
			_ = publishText(args[0], cleanupFile, "true")
			return 1
		case <-ticker.C:
		}
	}
	if len(launch.Args) == 0 {
		publishQuietly(args[0], errorFile, "helper-posix-failure")
		publishQuietly(args[0], cleanupFile, "true")
		return 1
	}
	command := exec.Command(launch.Args[0], launch.Args[1:]...)
	command.Dir = launch.Cwd
	command.Env = mergeEnvironment(os.Environ(), nil)
	command.Stdin, command.Stdout, command.Stderr = os.Stdin, os.Stdout, os.Stderr
	if err := command.Start(); err != nil {
		publishQuietly(args[0], errorFile, "helper-posix-failure")
		publishQuietly(args[0], cleanupFile, "true")
		return 1
	}
	commandDone := make(chan error, 1)
	go func() { commandDone <- command.Wait() }()
	var waitErr error
	select {
	case waitErr = <-commandDone:
	case <-ownerClosed:
		_ = convergeSessionExcept(context.Background(), os.Getpid(), helperGracePeriod, os.Getpid())
		waitErr = <-commandDone
	}
	report := exitReport{}
	if waitErr == nil {
		code := 0
		report.ExitCode = &code
	} else {
		var exitErr *exec.ExitError
		if errors.As(waitErr, &exitErr) {
			status, ok := exitErr.Sys().(syscall.WaitStatus)
			if ok && status.Signaled() {
				signal := status.Signal().String()
				report.Signal = &signal
			} else {
				code := exitErr.ExitCode()
				report.ExitCode = &code
			}
		}
	}
	payload, err := json.Marshal(report)
	if err != nil {
		publishQuietly(args[0], errorFile, "helper-posix-failure")
		return 1
	}
	if err := publishText(args[0], exitFile, string(payload)); err != nil {
		_ = convergeSessionExcept(context.Background(), os.Getpid(), helperGracePeriod, os.Getpid())
		publishQuietly(args[0], cleanupFile, "false")
		publishQuietly(args[0], errorFile, "helper-posix-failure")
		return 1
	}
	if err := convergeSessionExcept(context.Background(), os.Getpid(), helperGracePeriod, os.Getpid()); err != nil {
		publishQuietly(args[0], cleanupFile, "false")
		publishQuietly(args[0], errorFile, "helper-posix-failure")
		return 1
	}
	if err := convergeAdoptedChildren(context.Background(), os.Getpid(), helperGracePeriod, nil); err != nil {
		publishQuietly(args[0], cleanupFile, "false")
		publishQuietly(args[0], errorFile, "helper-posix-failure")
		return 1
	}
	if err := publishText(args[0], cleanupFile, "true"); err != nil {
		return 1
	}
	return 0
}
