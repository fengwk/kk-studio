//go:build linux

package native

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

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

func waitPIDFile(t *testing.T, path string) int {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		data, err := os.ReadFile(path)
		if err == nil && len(data) != 0 {
			pid, parseErr := strconv.Atoi(strings.TrimSpace(string(data)))
			if parseErr != nil {
				t.Fatal(parseErr)
			}
			return pid
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("pid file %s was not published", path)
	return 0
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
