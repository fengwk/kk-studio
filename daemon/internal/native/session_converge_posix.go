//go:build linux || darwin

package native

import (
	"context"
	"errors"
	"fmt"
	"syscall"
	"time"
)

func convergeSession(ctx context.Context, sid int, grace time.Duration) error {
	return convergeSessionExcept(ctx, sid, grace, 0)
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
		active := selectScopeMembers(members, excludedPID)
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
		if err := signalScopeMembers(sid, active, anchor, signal, api); err != nil {
			return err
		}
		remaining := deadline.Sub(api.now())
		if remaining <= 0 {
			return errors.New("process scope did not converge within its deadline")
		}
		interval, deadlineWait := scopeConvergenceInterval(remaining)
		if err := api.wait(ctx, interval); err != nil {
			return err
		}
		if deadlineWait {
			return errors.New("process scope did not converge within its deadline")
		}
	}
}

func selectScopeMembers(members []sessionMember, excludedPID int) []sessionMember {
	active := make([]sessionMember, 0, len(members))
	for _, member := range members {
		if member.PID == excludedPID {
			continue
		}
		if member.isLive() {
			active = append(active, member)
		}
	}
	return active
}

func signalScopeMembers(sid int, members []sessionMember, anchor *trackedSession, signal syscall.Signal, api posixConvergenceAPI) error {
	for _, member := range members {
		if member.Identity == "" {
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
			if item.PID == member.PID && item.Session == sid && item.Identity == member.Identity {
				verified = true
				break
			}
		}
		if !verified {
			continue
		}
		if err := api.kill(member.PID, signal); err != nil && !errors.Is(err, syscall.ESRCH) {
			return fmt.Errorf("cannot signal process scope member: %w", err)
		}
	}
	return nil
}

func scopeConvergenceInterval(remaining time.Duration) (time.Duration, bool) {
	interval := scopePollInterval
	deadlineWait := remaining <= interval
	if deadlineWait {
		interval = remaining
	}
	return interval, deadlineWait
}

func containsTrackedAnchor(members []sessionMember, tracked trackedSession) bool {
	for _, member := range members {
		if member.PID == tracked.ChildPID && member.Session == tracked.Session && member.Identity == tracked.Identity {
			return true
		}
	}
	return false
}
