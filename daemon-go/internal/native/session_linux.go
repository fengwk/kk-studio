//go:build linux

package native

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
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

func (m sessionMember) isLive() bool {
	return m.Threads > 1 || (m.State != "Z" && m.State != "X" && m.State != "x")
}

func sessionMembers(sid int) ([]sessionMember, error) {
	all, err := linuxProcessMembers()
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

func linuxProcessMembers() ([]sessionMember, error) {
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return nil, err
	}
	members := make([]sessionMember, 0, len(entries))
	for _, entry := range entries {
		pid, err := strconv.Atoi(entry.Name())
		if err != nil || pid <= 0 {
			continue
		}
		data, err := os.ReadFile(filepath.Join("/proc", entry.Name(), "stat"))
		if errors.Is(err, os.ErrNotExist) || errors.Is(err, unix.ESRCH) {
			continue
		}
		if err != nil {
			return nil, fmt.Errorf("cannot inspect process scope member %d: %w", pid, err)
		}
		fields := fieldsAfterComm(string(data))
		if len(fields) <= 19 {
			return nil, fmt.Errorf("cannot inspect process scope member %d", pid)
		}
		parent, parentErr := strconv.Atoi(fields[1])
		processSession, sessionErr := strconv.Atoi(fields[3])
		threads, threadsErr := strconv.Atoi(fields[17])
		start, identityErr := strconv.ParseUint(fields[19], 10, 64)
		if parentErr != nil || sessionErr != nil || threadsErr != nil || identityErr != nil {
			return nil, fmt.Errorf("cannot inspect process scope member %d", pid)
		}
		members = append(members, sessionMember{PID: pid, Parent: parent, Session: processSession, Identity: strconv.FormatUint(start, 10), State: fields[0], Threads: threads})
	}
	return members, nil
}

func directChildren(ownerPID int, excludedPIDs []int) ([]sessionMember, error) {
	all, err := linuxProcessMembers()
	if err != nil {
		return nil, err
	}
	excluded := make(map[int]struct{}, len(excludedPIDs))
	for _, pid := range excludedPIDs {
		excluded[pid] = struct{}{}
	}
	children := make([]sessionMember, 0)
	for _, member := range all {
		if member.Parent != ownerPID {
			continue
		}
		if _, ok := excluded[member.PID]; ok {
			continue
		}
		children = append(children, member)
	}
	sort.Slice(children, func(i, j int) bool { return children[i].PID < children[j].PID })
	return children, nil
}

func convergeAdoptedChildren(ctx context.Context, ownerPID int, grace time.Duration, excludedPIDs []int) error {
	ownerSession, err := unix.Getsid(ownerPID)
	if err != nil {
		return fmt.Errorf("cannot inspect adopted-child owner session: %w", err)
	}
	deadline := time.NewTimer(scopeConvergeBudget)
	defer deadline.Stop()
	started := time.Now()
	for {
		children, err := directChildren(ownerPID, excludedPIDs)
		if err != nil {
			return err
		}
		if len(children) == 0 {
			return nil
		}
		for _, child := range children {
			if child.Session <= 0 || child.Identity == "" {
				return errors.New("adopted child identity is unknown")
			}
			if child.isLive() {
				if child.Session == ownerSession && ownerPID != child.Session {
					return errors.New("adopted child shares an unowned process session")
				}
				if err := convergeSessionExcept(ctx, child.Session, grace, ownerPID); err != nil {
					return err
				}
			}
			var status unix.WaitStatus
			if _, err := unix.Wait4(child.PID, &status, unix.WNOHANG, nil); err != nil && !errors.Is(err, unix.ECHILD) && !errors.Is(err, unix.EINTR) {
				return fmt.Errorf("cannot reap adopted process: %w", err)
			}
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-deadline.C:
			return errors.New("adopted processes did not converge within their deadline")
		case <-time.After(scopePollInterval):
		}
		if grace > 0 && time.Since(started) >= grace {
			grace = 0
		}
	}
}

func fieldsAfterComm(stat string) []string {
	end := strings.LastIndex(stat, ")")
	if end < 0 || end+1 >= len(stat) {
		return nil
	}
	return strings.Fields(stat[end+1:])
}

func enableChildSubreaper() error { return unix.Prctl(unix.PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) }
