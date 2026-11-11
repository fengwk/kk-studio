//go:build linux

package native

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"golang.org/x/sys/unix"
)

type sessionMember struct {
	PID      int
	Session  int
	Identity string
	State    string
}

func sessionMembers(sid int) ([]sessionMember, error) {
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return nil, err
	}
	members := make([]sessionMember, 0)
	for _, entry := range entries {
		pid, err := strconv.Atoi(entry.Name())
		if err != nil {
			continue
		}
		data, err := os.ReadFile(filepath.Join("/proc", entry.Name(), "stat"))
		if errors.Is(err, os.ErrNotExist) || errors.Is(err, unix.ESRCH) {
			continue
		}
		if err != nil {
			return nil, fmt.Errorf("cannot inspect process scope member")
		}
		fields := fieldsAfterComm(string(data))
		if len(fields) <= 19 {
			return nil, fmt.Errorf("cannot inspect process scope member")
		}
		processSession, sessionErr := strconv.Atoi(fields[3])
		start, identityErr := strconv.ParseUint(fields[19], 10, 64)
		if sessionErr != nil || identityErr != nil {
			return nil, fmt.Errorf("cannot inspect process scope member")
		}
		if processSession == sid {
			members = append(members, sessionMember{PID: pid, Session: processSession, Identity: strconv.FormatUint(start, 10), State: fields[0]})
		}
	}
	return members, nil
}

func fieldsAfterComm(stat string) []string {
	end := strings.LastIndex(stat, ")")
	if end < 0 || end+1 >= len(stat) {
		return nil
	}
	return strings.Fields(stat[end+1:])
}

func enableChildSubreaper() error { return unix.Prctl(unix.PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) }
