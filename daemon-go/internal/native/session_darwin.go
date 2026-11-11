//go:build darwin

package native

import (
	"errors"
	"fmt"
	"strconv"
	"syscall"

	"golang.org/x/sys/unix"
)

type sessionMember struct {
	PID      int
	Session  int
	Identity string
	State    string
}

func sessionMembers(sid int) ([]sessionMember, error) {
	procs, err := unix.SysctlKinfoProcSlice("kern.proc.all")
	if err != nil {
		return nil, fmt.Errorf("cannot enumerate process scope members: %w", err)
	}
	members := make([]sessionMember, 0)
	for _, proc := range procs {
		pid := int(proc.Proc.P_pid)
		got, err := unix.Getsid(pid)
		if errors.Is(err, syscall.ESRCH) {
			continue
		}
		if err != nil {
			return nil, fmt.Errorf("cannot inspect process scope member")
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
		members = append(members, sessionMember{PID: pid, Session: got, Identity: identity, State: state})
	}
	return members, nil
}

func enableChildSubreaper() error { return nil }
