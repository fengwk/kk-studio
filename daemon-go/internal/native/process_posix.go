//go:build linux || darwin

package native

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"
)

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

func (p *posixProcess) PID() int { return p.pid }

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

func (p *posixProcess) convergeTrackedSessions(ctx context.Context, grace time.Duration) error {
	for _, tracked := range p.trackedSessions {
		if err := convergeSessionFenced(ctx, tracked.Session, grace, 0, &tracked); err != nil {
			return err
		}
	}
	return nil
}
