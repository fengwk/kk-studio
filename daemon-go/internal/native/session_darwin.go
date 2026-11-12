//go:build darwin

package native

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

type sessionMember struct {
	PID      int
	Parent   int
	Session  int
	Identity string
	State    string
	Threads  int
}

func (m sessionMember) isLive() bool { return m.State != "Z" && m.State != "X" && m.State != "x" }

// Darwin does not support Linux's child-subreaper behavior; orphaned children
// are reparented to launchd rather than adopted by this process.
func directChildren(ownerPID int, excludedPIDs []int) ([]sessionMember, error) { return nil, nil }

func convergeAdoptedChildren(ctx context.Context, ownerPID int, grace time.Duration, excludedPIDs []int) error {
	return nil
}

func sessionMembers(sid int) ([]sessionMember, error) {
	procs, err := unix.SysctlKinfoProcSlice("kern.proc.all")
	if err != nil {
		return nil, fmt.Errorf("cannot enumerate process scope members: %w", err)
	}
	members := make([]sessionMember, 0)
	for _, proc := range procs {
		pid := int(proc.Proc.P_pid)
		if pid <= 0 {
			continue
		}
		got, err := unix.Getsid(pid)
		if errors.Is(err, syscall.ESRCH) {
			continue
		}
		if err != nil {
			return nil, fmt.Errorf("cannot inspect process scope member: %w", err)
		}
		if got != sid {
			continue
		}
		start := proc.Proc.P_starttime
		identity := fmt.Sprintf("%d.%d", start.Sec, start.Usec)
		state := strconv.Itoa(int(proc.Proc.P_stat))
		if proc.Proc.P_stat == 5 {
			state = "Z"
		}
		members = append(members, sessionMember{
			PID:      pid,
			Parent:   int(proc.Eproc.Ppid),
			Session:  got,
			Identity: identity,
			State:    state,
		})
	}
	return members, nil
}

func enableChildSubreaper() error { return nil }
