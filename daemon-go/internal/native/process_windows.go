//go:build windows

package native

import (
	"context"
	"errors"
	"fmt"
	"golang.org/x/sys/windows"
	"io"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

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

func (p *windowsProcess) PID() int { return p.pid }

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
	resourceErrors := p.closeOwnerPipe()
	converged, convergenceErrors := p.convergeJobScope(api)
	resourceErrors = append(resourceErrors, p.releaseJobHandle(api)...)
	convergenceErrors = append(convergenceErrors, p.awaitHelperExitWithKill(api, converged)...)
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

func (p *windowsProcess) closeOwnerPipe() []error {
	var failures []error
	if p.owner != nil {
		if err := p.owner.Close(); err != nil {
			failures = append(failures, err)
		}
		p.owner = nil
	}
	return failures
}

func (p *windowsProcess) convergeJobScope(api windowsProcessTerminationAPI) (bool, []error) {
	var failures []error
	converged := false
	if p.job != 0 {
		_ = api.TerminateJobObject(p.job, 1)
		err := api.AwaitJobEmpty(context.Background(), p.job, windowsDrainBudget)
		if err != nil {
			failures = append(failures, err)
		} else {
			converged = true
		}
	} else if err := terminateWindowsProcessWithAPI(context.Background(), p.helper, windowsForceKillBudget, api); err != nil {
		failures = append(failures, err)
	} else {
		converged = true
	}
	return converged, failures
}

func (p *windowsProcess) releaseJobHandle(api windowsProcessTerminationAPI) []error {
	var failures []error
	if p.job != 0 {
		if err := api.CloseHandle(p.job); err != nil {
			failures = append(failures, fmt.Errorf("cannot close process scope job: %w", err))
		}
		p.job = 0
	}
	return failures
}

func (p *windowsProcess) awaitHelperExitWithKill(api windowsProcessTerminationAPI, converged bool) []error {
	var failures []error
	if converged {
		if err := p.awaitHelperExit(context.Background(), windowsForceKillBudget); err != nil {
			if p.helper != 0 {
				_ = api.TerminateProcess(p.helper, 1)
			}
			if err := p.awaitHelperExit(context.Background(), windowsForceKillBudget); err != nil {
				failures = append(failures, err)
			}
		}
	}
	return failures
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
