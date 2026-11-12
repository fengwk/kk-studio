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

func allProcessMembers() ([]sessionMember, error) { return darwinProcessMembers() }

func sessionMembers(sid int) ([]sessionMember, error) {
	all, err := darwinProcessMembers()
	if err != nil {
		return nil, err
	}
	members := make([]sessionMember, 0)
	for _, member := range all {
		if member.Session == sid {
			members = append(members, member)
		}
	}
	return members, nil
}

type darwinProcessAPI interface {
	SysctlKinfoProcSlice(string) ([]unix.KinfoProc, error)
	Getsid(int) (int, error)
}

type nativeDarwinProcessAPI struct{}

func (nativeDarwinProcessAPI) SysctlKinfoProcSlice(name string) ([]unix.KinfoProc, error) {
	return unix.SysctlKinfoProcSlice(name)
}
func (nativeDarwinProcessAPI) Getsid(pid int) (int, error) { return unix.Getsid(pid) }

func darwinProcessMembers() ([]sessionMember, error) {
	return darwinProcessMembersWithAPI(nativeDarwinProcessAPI{})
}

func darwinProcessMembersWithAPI(api darwinProcessAPI) ([]sessionMember, error) {
	procs, err := api.SysctlKinfoProcSlice("kern.proc.all")
	if err != nil {
		return nil, fmt.Errorf("cannot enumerate process scope members: %w", err)
	}
	members := make([]sessionMember, 0)
	for _, proc := range procs {
		pid := int(proc.Proc.P_pid)
		if pid <= 0 {
			continue
		}
		got, err := api.Getsid(pid)
		if errors.Is(err, syscall.ESRCH) {
			continue
		}
		if err != nil {
			return nil, fmt.Errorf("cannot inspect process scope member: %w", err)
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
