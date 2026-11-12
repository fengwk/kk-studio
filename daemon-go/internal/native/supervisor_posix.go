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
	"os/signal"
	"path/filepath"
	"runtime"
	"sort"
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
	api := s.startAPI
	if api.createStateDir == nil {
		api = nativePOSIXStartAPI()
	}
	var baselinePIDs []int
	baselineKnown := false
	if runtime.GOOS == "linux" {
		baseline, err := s.snapshotDirectChildren(os.Getpid(), nil)
		if err != nil {
			return nil, fmt.Errorf("cannot snapshot pre-existing process children: %w", err)
		}
		baselineKnown = true
		for _, child := range baseline {
			baselinePIDs = append(baselinePIDs, child.PID)
		}
	}
	dir, err := api.createStateDir()
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
		publishLaunch := s.publishLaunch
		if publishLaunch != nil {
			err = publishLaunch(dir, launchFile, payload)
		} else {
			err = api.publishLaunch(dir, payload)
		}
	}
	if err != nil {
		_ = removeStateDir(dir)
		return nil, err
	}
	ownerReader, ownerWriter, err := api.pipe()
	if err != nil {
		_ = removeStateDir(dir)
		return nil, err
	}
	executable, err := api.executable()
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
	stdinReader, stdin, err := api.pipe()
	if err != nil {
		_ = ownerReader.Close()
		_ = ownerWriter.Close()
		_ = removeStateDir(dir)
		return nil, err
	}
	stdout, stdoutWriter, err := api.pipe()
	if err != nil {
		_ = stdinReader.Close()
		_ = stdin.Close()
		_ = ownerReader.Close()
		_ = ownerWriter.Close()
		_ = removeStateDir(dir)
		return nil, err
	}
	stderr, stderrWriter, err := api.pipe()
	if err != nil {
		_ = stdinReader.Close()
		_ = stdin.Close()
		_ = stdout.Close()
		_ = stdoutWriter.Close()
		_ = ownerReader.Close()
		_ = ownerWriter.Close()
		_ = removeStateDir(dir)
		return nil, err
	}
	cmd.Stdin, cmd.Stdout, cmd.Stderr = stdinReader, stdoutWriter, stderrWriter
	startHelper := s.startHelper
	if startHelper == nil {
		startHelper = api.start
	}
	if err := startHelper(cmd); err != nil {
		_ = stdinReader.Close()
		_ = stdin.Close()
		_ = stdout.Close()
		_ = stdoutWriter.Close()
		_ = stderr.Close()
		_ = stderrWriter.Close()
		_ = ownerReader.Close()
		_ = ownerWriter.Close()
		_ = removeStateDir(dir)
		return nil, fmt.Errorf("cannot start process scope helper: %w", err)
	}
	_ = stdinReader.Close()
	_ = stdoutWriter.Close()
	_ = stderrWriter.Close()
	_ = ownerReader.Close()
	p := &posixProcess{supervisor: s, cmd: cmd, pid: cmd.Process.Pid, sid: cmd.Process.Pid, stateDir: dir, owner: ownerWriter, stdin: stdin, stdout: stdout, stderr: stderr, baselinePIDs: baselinePIDs, baselineKnown: baselineKnown, trackedSessions: make(map[int]trackedSession), processGroupID: syscall.Getpgid, done: make(chan struct{})}
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

type posixProcess struct {
	supervisor      *PosixSupervisor
	cmd             *exec.Cmd
	pid             int
	sid             int
	stateDir        string
	owner           *os.File
	stdin           io.WriteCloser
	stdout          io.ReadCloser
	stderr          io.ReadCloser
	baselinePIDs    []int
	baselineKnown   bool
	trackedSessions map[int]trackedSession
	processGroupID  func(int) (int, error)
	scopeConfirmed  bool
	killHelper      func() error
	convergeScope   func(context.Context, int, time.Duration) error
	done            chan struct{}
	waitErr         error
	mu              sync.Mutex
	settled         bool
	termErr         error
}

func (p *posixProcess) PID() int              { return p.pid }
func (p *posixProcess) Stdin() io.WriteCloser { return p.stdin }
func (p *posixProcess) Stdout() io.ReadCloser { return p.stdout }
func (p *posixProcess) Stderr() io.ReadCloser { return p.stderr }

func (p *posixProcess) converge(ctx context.Context, sid int, grace time.Duration) error {
	if p.convergeScope != nil {
		return p.convergeScope(ctx, sid, grace)
	}
	return convergeSession(ctx, sid, grace)
}

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
	if err := p.converge(ctx, p.sid, grace); err != nil {
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
	if err := p.convergeTrackedSessions(ctx, grace); err != nil {
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
	return p.awaitScopeWithMembers(ctx, sessionMembers)
}

func (p *posixProcess) awaitScopeWithMembers(ctx context.Context, membersForSession func(int) ([]sessionMember, error)) error {
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
			members, err := membersForSession(p.sid)
			if err != nil {
				return err
			}
			for _, member := range members {
				if member.PID == p.pid && member.Session == p.sid && member.Identity != "" {
					getpgid := p.processGroupID
					if getpgid == nil {
						getpgid = syscall.Getpgid
					}
					pgid, err := getpgid(p.pid)
					if err != nil {
						return fmt.Errorf("cannot verify the process scope helper process group: %w", err)
					}
					if pgid != p.pid {
						return errors.New("the process scope helper is not the process group leader")
					}
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

func (p *posixProcess) killUnestablishedHelper() error {
	if p.killHelper != nil {
		return p.killHelper()
	}
	if p.cmd == nil || p.cmd.Process == nil {
		return errors.New("the process scope helper has no process handle")
	}
	if err := p.cmd.Process.Kill(); err != nil && !errors.Is(err, os.ErrProcessDone) {
		return fmt.Errorf("cannot kill process scope helper: %w", err)
	}
	return nil
}

func (p *posixProcess) cleanupFailedStart(cause error) error {
	cause = errors.Join(cause, p.closeStreams())
	ctx, cancel := context.WithTimeout(context.Background(), posixForceKillBudget)
	defer cancel()
	if p.scopeConfirmed {
		if err := p.converge(ctx, p.sid, 0); err != nil {
			return &ProcessScopeNotConvergedError{Cause: errors.Join(cause, err)}
		}
	} else if p.cmd != nil {
		if err := p.killUnestablishedHelper(); err != nil {
			return &ProcessScopeNotConvergedError{Cause: errors.Join(cause, err)}
		}
	} else if err := p.converge(ctx, p.sid, 0); err != nil {
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
		return ProcessExit{}
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

func (p *posixProcess) convergeTrackedSessions(ctx context.Context, grace time.Duration) error {
	for _, tracked := range p.trackedSessions {
		if err := convergeSessionFenced(ctx, tracked.Session, grace, 0, &tracked); err != nil {
			return err
		}
	}
	return nil
}

func convergeSessionExcept(ctx context.Context, sid int, grace time.Duration, excludedPID int) error {
	return convergeSessionFenced(ctx, sid, grace, excludedPID, nil)
}

type posixConvergenceAPI struct {
	members func(int) ([]sessionMember, error)
	kill    func(int, syscall.Signal) error
	now     func() time.Time
	wait    func(context.Context, time.Duration) error
}

func nativePOSIXConvergenceAPI() posixConvergenceAPI {
	return posixConvergenceAPI{
		members: sessionMembers,
		kill:    syscall.Kill,
		now:     time.Now,
		wait: func(ctx context.Context, interval time.Duration) error {
			timer := time.NewTimer(interval)
			defer timer.Stop()
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-timer.C:
				return nil
			}
		},
	}
}

func convergeSessionFenced(ctx context.Context, sid int, grace time.Duration, excludedPID int, anchor *trackedSession) error {
	return convergeSessionFencedWithAPI(ctx, sid, grace, excludedPID, anchor, nativePOSIXConvergenceAPI())
}

func convergeSessionFencedWithAPI(ctx context.Context, sid int, grace time.Duration, excludedPID int, anchor *trackedSession, api posixConvergenceAPI) error {
	deadline := api.now().Add(scopeConvergeBudget)
	started := api.now()
	for {
		members, err := api.members(sid)
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
		if anchor != nil && !containsTrackedAnchor(members, *anchor) {
			return errors.New("tracked process identity changed before session convergence")
		}
		active, err = parentsBeforeChildren(active)
		if err != nil {
			return err
		}
		signal := syscall.SIGTERM
		if api.now().Sub(started) >= grace {
			signal = syscall.SIGKILL
		}
		for _, m := range active {
			if m.Identity == "" {
				return errors.New("process scope member identity is unknown")
			}
			current, err := api.members(sid)
			if err != nil {
				return err
			}
			if anchor != nil && !containsTrackedAnchor(current, *anchor) {
				return errors.New("tracked process identity changed before session signal")
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
			if err := api.kill(m.PID, signal); err != nil && !errors.Is(err, syscall.ESRCH) {
				return fmt.Errorf("cannot signal process scope member: %w", err)
			}
		}
		remaining := deadline.Sub(api.now())
		if remaining <= 0 {
			return errors.New("process scope did not converge within its deadline")
		}
		interval := scopePollInterval
		deadlineWait := remaining <= interval
		if deadlineWait {
			interval = remaining
		}
		if err := api.wait(ctx, interval); err != nil {
			return err
		}
		if deadlineWait {
			return errors.New("process scope did not converge within its deadline")
		}
	}
}

func containsTrackedAnchor(members []sessionMember, tracked trackedSession) bool {
	for _, member := range members {
		if member.PID == tracked.ChildPID && member.Session == tracked.Session && member.Identity == tracked.Identity {
			return true
		}
	}
	return false
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

func completeHelperCommandLifecycle(publishExit func() error, converge func() error, reapAdopted func() error, publishCleanup func(string) error, publishFailure func()) int {
	if err := publishExit(); err != nil {
		_ = converge()
		_ = publishCleanup("false")
		publishFailure()
		return 1
	}
	if err := converge(); err != nil {
		_ = publishCleanup("false")
		publishFailure()
		return 1
	}
	if err := reapAdopted(); err != nil {
		_ = publishCleanup("false")
		publishFailure()
		return 1
	}
	if err := publishCleanup("true"); err != nil {
		return 1
	}
	return 0
}

func helperMain(args []string) int {
	return helperMainWithSubreaper(args, runtime.GOOS, enableChildSubreaper)
}

func helperMainWithSubreaper(args []string, goos string, enableSubreaper func() error) int {
	if len(args) != 1 || !filepath.IsAbs(args[0]) || hasControl(args[0]) {
		_, _ = fmt.Fprintln(os.Stderr, "helper-launch-failure")
		return 2
	}
	if goos == "linux" {
		if err := enableSubreaper(); err != nil {
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
	terminated := make(chan os.Signal, 1)
	signal.Notify(terminated, syscall.SIGTERM)
	defer signal.Stop(terminated)
	deadline := time.NewTimer(scopeStartBudget)
	defer deadline.Stop()
	ticker := time.NewTicker(scopePollInterval)
	defer ticker.Stop()
	for {
		permit, permitErr := readState(args[0], permitFile)
		if permitErr == nil {
			if strings.TrimSpace(string(permit)) == "true" {
				signal.Stop(terminated)
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
		case <-terminated:
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
	return completeHelperCommandLifecycle(
		func() error { return publishText(args[0], exitFile, string(payload)) },
		func() error {
			return convergeSessionExcept(context.Background(), os.Getpid(), helperGracePeriod, os.Getpid())
		},
		func() error {
			return convergeAdoptedChildren(context.Background(), os.Getpid(), helperGracePeriod, nil)
		},
		func(value string) error { return publishText(args[0], cleanupFile, value) },
		func() { publishQuietly(args[0], errorFile, "helper-posix-failure") },
	)
}
