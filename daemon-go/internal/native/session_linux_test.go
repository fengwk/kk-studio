//go:build linux

package native

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

// NAT-T-089
func TestLinuxSupervisor_UnknownBaselineFailsBeforeStartingCommand(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	snapshotErr := errors.New("process list is unreadable")
	posixSupervisor := supervisor.(*PosixSupervisor)
	posixSupervisor.snapshotDirectChildren = func(int, []int) ([]sessionMember, error) {
		return nil, snapshotErr
	}
	marker := filepath.Join(t.TempDir(), "command-started")
	_, err = supervisor.Start(context.Background(), ProcessSpec{
		Executable: "/bin/sh",
		Args:       []string{"-c", "printf started > " + strconv.Quote(marker)},
	})
	if !errors.Is(err, snapshotErr) {
		t.Fatalf("Start() error = %v, want baseline error", err)
	}
	if _, err := os.Stat(marker); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("command marker exists or cannot be inspected: %v", err)
	}
}

func linuxStatRecord(pid, parent, sid, threads int, start string) []byte {
	fields := make([]string, 20)
	for index := range fields {
		fields[index] = "0"
	}
	fields[0] = "S"
	fields[1] = strconv.Itoa(parent)
	fields[3] = strconv.Itoa(sid)
	fields[17] = strconv.Itoa(threads)
	fields[19] = start
	return []byte(fmt.Sprintf("%d (command with ) delimiter) %s", pid, strings.Join(fields, " ")))
}

// NAT-F-006 verifies process snapshot errors and disappearing entries never become an empty successful snapshot.
func TestLinuxProcessMembersWithAPI_FailureAndSnapshotBranches(t *testing.T) {
	root := t.TempDir()
	for _, name := range []string{"noise", "0", "-1", "10", "20", "30", "40", "50"} {
		if err := os.WriteFile(filepath.Join(root, name), nil, 0600); err != nil {
			t.Fatal(err)
		}
	}

	readDirError := errors.New("injected proc directory error")
	api := linuxProcessSnapshotAPI{
		readDir:  func(string) ([]os.DirEntry, error) { return nil, readDirError },
		readFile: func(string) ([]byte, error) { return nil, nil },
	}
	if members, err := linuxProcessMembersWithAPI(root, api); !errors.Is(err, readDirError) || members != nil {
		t.Fatalf("directory error = %+v, %v", members, err)
	}

	fileError := errors.New("injected stat read error")
	api.readDir = os.ReadDir
	api.readFile = func(path string) ([]byte, error) {
		switch filepath.Base(filepath.Dir(path)) {
		case "10":
			return linuxStatRecord(10, 1, 1, 1, "123"), nil
		case "20", "30":
			return nil, os.ErrNotExist
		case "40":
			return nil, fileError
		default:
			return nil, nil
		}
	}
	if members, err := linuxProcessMembersWithAPI(root, api); !errors.Is(err, fileError) || members != nil {
		t.Fatalf("stat read error = %+v, %v", members, err)
	}

	api.readFile = func(path string) ([]byte, error) {
		switch filepath.Base(filepath.Dir(path)) {
		case "10":
			return linuxStatRecord(10, 1, 4, 2, "123"), nil
		case "20":
			return nil, os.ErrNotExist
		case "30":
			return nil, unix.ESRCH
		case "40":
			return []byte("10 (short) S 1"), nil
		case "50":
			return linuxStatRecord(50, 1, 4, 2, "not-a-number"), nil
		default:
			return nil, nil
		}
	}
	if members, err := linuxProcessMembersWithAPI(root, api); err == nil || members != nil {
		t.Fatalf("malformed stat record = %+v, %v", members, err)
	}
	api.readFile = func(path string) ([]byte, error) {
		if filepath.Base(filepath.Dir(path)) == "50" {
			return linuxStatRecord(50, 1, 4, 2, "not-a-number"), nil
		}
		return linuxStatRecord(10, 1, 4, 2, "123"), nil
	}
	if members, err := linuxProcessMembersWithAPI(root, api); err == nil || members != nil {
		t.Fatalf("invalid stat field = %+v, %v", members, err)
	}
	api.readFile = func(path string) ([]byte, error) {
		switch filepath.Base(filepath.Dir(path)) {
		case "10":
			return linuxStatRecord(10, 1, 4, 2, "123"), nil
		case "20":
			return nil, os.ErrNotExist
		case "30":
			return nil, unix.ESRCH
		default:
			return nil, os.ErrNotExist
		}
	}
	members, err := linuxProcessMembersWithAPI(root, api)
	if err != nil {
		t.Fatal(err)
	}
	want := []sessionMember{{PID: 10, Parent: 1, Session: 4, Identity: "123", State: "S", Threads: 2}}
	if !reflect.DeepEqual(members, want) {
		t.Fatalf("members = %+v, want %+v", members, want)
	}
}

// NAT-T-049 verifies deterministic parent-before-child session ordering.
func TestLinuxSession_ParentFirst(t *testing.T) {
	members := []sessionMember{
		{PID: 30, Parent: 20},
		{PID: 20, Parent: 10},
		{PID: 10, Parent: 999},
		{PID: 40, Parent: 10},
	}
	ordered, err := parentsBeforeChildren(members)
	if err != nil {
		t.Fatal(err)
	}
	positions := make(map[int]int, len(ordered))
	for index, member := range ordered {
		positions[member.PID] = index
	}
	for _, member := range members {
		if parentIndex, ok := positions[member.Parent]; ok && positions[member.PID] <= parentIndex {
			t.Fatalf("child %d did not follow parent %d: %+v", member.PID, member.Parent, ordered)
		}
	}
}

func deepSessionChain(size int) []sessionMember {
	members := make([]sessionMember, size)
	for index := range members {
		members[index] = sessionMember{PID: index + 1, Parent: index}
	}
	return members
}

// NAT-T-050 verifies that duplicate, cyclic, or overly deep snapshots cannot establish an order.
func TestLinuxSession_AmbiguousSnapshot(t *testing.T) {
	for _, testCase := range []struct {
		name    string
		members []sessionMember
	}{
		{name: "duplicate", members: []sessionMember{{PID: 10}, {PID: 10}}},
		{name: "cycle", members: []sessionMember{{PID: 10, Parent: 20}, {PID: 20, Parent: 10}}},
		{name: "too deep", members: deepSessionChain(maxSessionTreeDepth + 1)},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			if _, err := parentsBeforeChildren(testCase.members); err == nil {
				t.Fatal("ambiguous process snapshot was accepted")
			}
		})
	}
}

// NAT-T-059
func TestLinuxSession_DirectChildren(t *testing.T) {
	child := exec.Command("/bin/sleep", "30")
	if err := child.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() {
		_ = child.Process.Kill()
		_ = child.Wait()
	}()

	children, err := directChildren(os.Getpid(), nil)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, item := range children {
		if item.PID == child.Process.Pid && item.Parent == os.Getpid() && item.Session > 0 && item.Identity != "" {
			found = true
		}
	}
	if !found {
		t.Fatalf("direct child %d not found in %+v", child.Process.Pid, children)
	}

	children, err = directChildren(os.Getpid(), []int{child.Process.Pid})
	if err != nil {
		t.Fatal(err)
	}
	for _, item := range children {
		if item.PID == child.Process.Pid {
			t.Fatalf("excluded child %d was returned", item.PID)
		}
	}
}

// NAT-T-057
func TestLinuxSession_NoAdoptedChildren(t *testing.T) {
	children, err := directChildren(os.Getpid(), nil)
	if err != nil {
		t.Fatal(err)
	}
	excluded := make([]int, 0, len(children))
	for _, child := range children {
		excluded = append(excluded, child.PID)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := convergeAdoptedChildren(ctx, os.Getpid(), 0, excluded); err != nil {
		t.Fatal(err)
	}
}

// NAT-T-051, NAT-T-121 verify explicitly tracked escaped sessions converge on terminate.
func TestLinuxSupervisor_ConvergesTrackedEscapedSession(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	pidFile := filepath.Join(t.TempDir(), "escaped.pid")
	process, err := supervisor.Start(context.Background(), ProcessSpec{
		Executable: "/bin/sh",
		Args:       []string{"-c", "setsid /bin/sleep 30 & echo $! > " + strconv.Quote(pidFile) + "; wait"},
	})
	if err != nil {
		t.Fatal(err)
	}
	childPID := waitPIDFile(t, pidFile)
	if err := supervisor.(DescendantTracker).TrackDescendant(context.Background(), process.PID(), childPID); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	if err := process.Terminate(ctx, 10*time.Millisecond); err != nil {
		t.Fatal(err)
	}
	waitPIDGone(t, childPID, time.Second)
}

// NAT-T-122
func TestLinuxSupervisor_ConvergesAdoptedSessionAfterNaturalExit(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	pidFile := filepath.Join(t.TempDir(), "detached.pid")
	command := fmt.Sprintf("setsid /bin/sleep 30 & echo $! > %s", strconv.Quote(pidFile))
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/sh", Args: []string{"-c", command}})
	if err != nil {
		t.Fatal(err)
	}
	childPID := waitPIDFile(t, pidFile)
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	exit, err := process.Wait(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if exit.Code == nil || *exit.Code != 0 {
		t.Fatalf("exit = %+v", exit)
	}
	waitPIDGone(t, childPID, time.Second)
}

// NAT-T-123
func TestLinuxSupervisor_ConvergesAdoptedSessionAfterHelperDeath(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	pidFile := filepath.Join(t.TempDir(), "detached.pid")
	command := fmt.Sprintf("setsid /bin/sleep 30 & echo $! > %s; /bin/sleep 30", strconv.Quote(pidFile))
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/sh", Args: []string{"-c", command}})
	if err != nil {
		t.Fatal(err)
	}
	managed := process.(*posixProcess)
	childPID := waitPIDFile(t, pidFile)
	t.Cleanup(func() {
		_ = unix.Kill(childPID, unix.SIGKILL)
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		_ = convergeSessionExcept(ctx, managed.sid, 0, 0)
		_ = managed.convergeAdoptedChildren(ctx, 0)
		_ = removeStateDir(managed.stateDir)
	})
	if !managed.baselineKnown {
		t.Fatal("process baseline was not safely captured")
	}
	if err := unix.Kill(managed.pid, unix.SIGKILL); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	if err := managed.Terminate(ctx, 10*time.Millisecond); err != nil {
		t.Fatal(err)
	}
	waitPIDGone(t, childPID, time.Second)
}

func waitPIDGone(t *testing.T, pid int, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		err := unix.Kill(pid, 0)
		if errors.Is(err, unix.ESRCH) {
			return
		}
		if err != nil {
			t.Fatalf("cannot inspect process %d: %v", pid, err)
		}
		time.Sleep(10 * time.Millisecond)
	}
	_ = unix.Kill(pid, syscall.SIGKILL)
	t.Fatalf("process %d is still present", pid)
}

func TestLinuxProcStatRejectsMissingCommandDelimiter(t *testing.T) {
	for _, record := range []string{"missing delimiter", "123 (truncated)"} {
		if fields := fieldsAfterComm(record); fields != nil {
			t.Fatalf("fieldsAfterComm(%q) = %v, want nil", record, fields)
		}
	}
}

func TestLinuxConvergeAdoptedChildrenRejectsUnknownOwner(t *testing.T) {
	err := convergeAdoptedChildren(context.Background(), int(^uint(0)>>1), 0, nil)
	if err == nil || !strings.Contains(err.Error(), "cannot inspect adopted-child owner session") {
		t.Fatalf("convergeAdoptedChildren error = %v, want owner-session error", err)
	}
}
