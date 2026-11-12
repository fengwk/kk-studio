//go:build linux || darwin

package native

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"syscall"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

// NAT-T-048 verifies a real session leader appears in its enumerated session.
func TestPOSIXSession_RealLeader(t *testing.T) {
	pidFile := filepath.Join(t.TempDir(), "leader.pid")
	command := exec.Command("/bin/sh", "-c", "echo $$ > "+strconv.Quote(pidFile)+"; exec sleep 30")
	command.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := command.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() {
		_ = command.Process.Kill()
		_ = command.Wait()
	}()
	leaderPID := command.Process.Pid
	if got, err := unix.Getsid(leaderPID); err != nil || got != leaderPID {
		t.Fatalf("leader session = %d, %v; want %d", got, err, leaderPID)
	}
	if got, err := unix.Getpgid(leaderPID); err != nil || got != leaderPID {
		t.Fatalf("leader process group = %d, %v; want %d", got, err, leaderPID)
	}
	members, err := sessionMembers(leaderPID)
	if err != nil {
		t.Fatal(err)
	}
	for _, member := range members {
		if member.PID == leaderPID && member.Session == leaderPID && member.Identity != "" {
			return
		}
	}
	t.Fatalf("session leader %d absent from members %+v", leaderPID, members)
}

// NAT-T-054 verifies an unknown session is a known-empty snapshot.
func TestPOSIXSession_UnknownIsEmpty(t *testing.T) {
	members, err := sessionMembers(1 << 30)
	if err != nil {
		t.Fatal(err)
	}
	if len(members) != 0 {
		t.Fatalf("unknown session members = %+v, want empty", members)
	}
}

// NAT-T-058 verifies descendant traversal against a real multi-generation process tree.
func TestPOSIXSession_RealDescendant(t *testing.T) {
	pidFile := filepath.Join(t.TempDir(), "child.pid")
	command := exec.Command("/bin/sh", "-c", "sleep 30 & echo $! > "+strconv.Quote(pidFile)+"; wait")
	if err := command.Start(); err != nil {
		t.Fatal(err)
	}
	childPID := waitPIDFile(t, pidFile)
	defer func() {
		_ = unix.Kill(childPID, syscall.SIGKILL)
		_ = command.Process.Kill()
		_ = command.Wait()
	}()
	members, err := allProcessMembers()
	if err != nil {
		t.Fatal(err)
	}
	if !isDescendantOf(members, childPID, command.Process.Pid) {
		t.Fatalf("PID %d was not recognized as a descendant of %d: %+v", childPID, command.Process.Pid, members)
	}
}

type foreignOwnerFileInfo struct {
	mode os.FileMode
	uid  uint32
}

func (info foreignOwnerFileInfo) Name() string       { return "foreign" }
func (info foreignOwnerFileInfo) Size() int64        { return 1 }
func (info foreignOwnerFileInfo) Mode() os.FileMode  { return info.mode }
func (info foreignOwnerFileInfo) ModTime() time.Time { return time.Time{} }
func (info foreignOwnerFileInfo) IsDir() bool        { return info.mode.IsDir() }
func (info foreignOwnerFileInfo) Sys() any           { return &syscall.Stat_t{Uid: info.uid} }

// NAT-T-093 verifies an owner-only POSIX file owned by another user is rejected.
func TestPrivateFS_RejectForeignFileOwner(t *testing.T) {
	info := foreignOwnerFileInfo{mode: 0600, uid: uint32(os.Getuid()) + 1}
	err := verifyPrivateFileWithLstat("/foreign", func(string) (os.FileInfo, error) { return info, nil })
	if err == nil {
		t.Fatal("foreign-owned private file was accepted")
	}
}

// NAT-T-094 verifies foreign-owner directories are rejected before chmod.
func TestPrivateFS_NoChmodForeignDirectory(t *testing.T) {
	info := foreignOwnerFileInfo{mode: os.ModeDir | 0755, uid: uint32(os.Getuid()) + 1}
	chmodCalls := 0
	api := posixPrivateFSAPI{
		lstat:    func(string) (os.FileInfo, error) { return info, nil },
		mkdirAll: func(string, os.FileMode) error { t.Fatal("foreign directory was replaced"); return nil },
		chmod: func(string, os.FileMode) error {
			chmodCalls++
			return nil
		},
	}
	if err := ensurePrivateDirectoryWithAPI("/foreign", api); err == nil {
		t.Fatal("foreign-owned directory was accepted")
	}
	if chmodCalls != 0 {
		t.Fatalf("chmod calls = %d, want none", chmodCalls)
	}
}

// NAT-T-026 verifies a new POSIX directory lock has owner-only filesystem permissions.
func TestDirectoryLock_PrivatePermissions(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	lock, err := AcquireDirectoryLock(directory, DaemonLockFileName)
	if err != nil {
		t.Fatal(err)
	}
	defer lock.Close()
	for path, want := range map[string]os.FileMode{
		directory: 0700,
		filepath.Join(directory, DaemonLockFileName): 0600,
	} {
		info, err := os.Stat(path)
		if err != nil {
			t.Fatal(err)
		}
		if got := info.Mode().Perm(); got != want {
			t.Errorf("%s permissions = %04o, want %04o", path, got, want)
		}
	}
}

// NAT-T-091 verifies a tracked session with no members is already converged.
func TestPOSIXState_OldSessionEmpty(t *testing.T) {
	calls := 0
	api := posixConvergenceAPI{
		members: func(int) ([]sessionMember, error) {
			calls++
			return nil, nil
		},
		kill: func(int, syscall.Signal) error { t.Fatal("empty session was signalled"); return nil },
		now:  func() time.Time { return time.Unix(1, 0) },
		wait: func(context.Context, time.Duration) error { t.Fatal("empty session waited"); return nil },
	}
	anchor := &trackedSession{ChildPID: 42, Session: 42, Identity: "old-start-time"}
	if err := convergeSessionFencedWithAPI(context.Background(), 42, 0, 0, anchor, api); err != nil {
		t.Fatalf("empty tracked session convergence = %v", err)
	}
	if calls != 1 {
		t.Fatalf("session snapshots = %d, want one empty snapshot", calls)
	}
}

// NAT-T-052 verifies zombie leaders with live worker threads remain live members.
func TestLinuxSession_ZombieLeaderWithWorkers(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("Linux process states are specific to Linux")
	}
	member := sessionMember{PID: 42, Session: 42, Identity: "start", State: "Z", Threads: 2}
	if !member.isLive() {
		t.Fatal("zombie leader with live worker threads was considered dead")
	}
}

// NAT-T-127 verifies termination leaves an unrelated daemon child alive.
func TestPOSIXSupervisor_LeavesUnrelatedChild(t *testing.T) {
	unrelated := exec.Command("/bin/sleep", "30")
	if err := unrelated.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() {
		_ = unrelated.Process.Kill()
		_ = unrelated.Wait()
	}()
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/sleep", Args: []string{"30"}})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := process.Terminate(ctx, 10*time.Millisecond); err != nil {
		t.Fatal(err)
	}
	if err := unix.Kill(unrelated.Process.Pid, 0); err != nil {
		t.Fatalf("unrelated child %d was terminated: %v", unrelated.Process.Pid, err)
	}
}
