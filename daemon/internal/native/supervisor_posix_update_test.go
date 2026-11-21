//go:build linux || darwin

package native

import (
	"context"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"syscall"
	"testing"
	"time"
)

// Suspension drains tracked processes, refuses new Starts, and stays reversible until Close.
func TestPOSIXSupervisor_SuspendTerminatesRejectsAndResumes(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	pidFile := filepath.Join(t.TempDir(), "suspended.pid")
	if _, err := supervisor.Start(context.Background(), ProcessSpec{
		Executable: "/bin/sh",
		Args:       []string{"-c", "echo $$ > " + strconv.Quote(pidFile) + "; exec /bin/sleep 60"},
	}); err != nil {
		t.Fatal(err)
	}
	pid := waitPIDFile(t, pidFile)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := supervisor.Suspend(ctx); err != nil {
		t.Fatalf("Suspend() error = %v", err)
	}
	awaitProcessGone(t, pid, 5*time.Second)
	if tracked := trackedProcesses(supervisor); tracked != 0 {
		t.Fatalf("suspended supervisor still tracks %d processes", tracked)
	}
	marker := filepath.Join(t.TempDir(), "started")
	if _, err := supervisor.Start(context.Background(), ProcessSpec{
		Executable: "/bin/sh",
		Args:       []string{"-c", "printf started > " + strconv.Quote(marker)},
	}); !errors.Is(err, ErrSuspended) {
		t.Fatalf("Start() while suspended error = %v, want ErrSuspended", err)
	}
	if _, err := os.Stat(marker); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("Start launched a process while suspended: stat error = %v", err)
	}
	supervisor.Resume()
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/sh", Args: []string{"-c", "printf resumed"}})
	if err != nil {
		t.Fatalf("Start() after Resume error = %v", err)
	}
	output, err := io.ReadAll(process.Stdout())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := process.Wait(ctx); err != nil {
		t.Fatalf("Wait() after Resume error = %v", err)
	}
	if string(output) != "resumed" {
		t.Fatalf("stdout after Resume = %q, want resumed", output)
	}
	if err := supervisor.Close(ctx); err != nil {
		t.Fatalf("Close() error = %v", err)
	}
	supervisor.Resume()
	if _, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/sh", Args: []string{"-c", "printf reopened"}}); err == nil || errors.Is(err, ErrSuspended) {
		t.Fatalf("Start() after Close error = %v, want terminal closure error", err)
	}
}

// Suspension waits for an in-flight Start to register before draining its process.
func TestPOSIXSupervisor_SuspendWaitsForInFlightStart(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	posix := supervisor.(*PosixSupervisor)
	started := make(chan int, 1)
	release := make(chan struct{})
	defer func() {
		select {
		case <-release:
		default:
			close(release)
		}
	}()
	posix.startHelper = func(command *exec.Cmd) error {
		if err := command.Start(); err != nil {
			return err
		}
		started <- command.Process.Pid
		<-release
		return nil
	}
	startResult := make(chan error, 1)
	go func() {
		_, err := posix.Start(context.Background(), ProcessSpec{Executable: "/bin/sh", Args: []string{"-c", "exec /bin/sleep 60"}})
		startResult <- err
	}()
	var pid int
	select {
	case pid = <-started:
	case <-time.After(10 * time.Second):
		t.Fatal("Start() never launched the process scope helper")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	suspendResult := make(chan error, 1)
	go func() { suspendResult <- posix.Suspend(ctx) }()
	awaitInFlightSuspension(t, posix)
	select {
	case err := <-suspendResult:
		t.Fatalf("Suspend returned while a Start was in flight: %v", err)
	default:
	}
	close(release)
	if err := <-startResult; err != nil {
		t.Fatalf("admitted Start error = %v", err)
	}
	if err := <-suspendResult; err != nil {
		t.Fatalf("Suspend() error = %v", err)
	}
	awaitProcessGone(t, pid, 5*time.Second)
	if tracked := trackedProcesses(supervisor); tracked != 0 {
		t.Fatalf("suspended supervisor still tracks %d processes", tracked)
	}
}

func trackedProcesses(supervisor UpdateSupervisor) int {
	posix := supervisor.(*PosixSupervisor)
	posix.mu.Lock()
	defer posix.mu.Unlock()
	return len(posix.processes)
}

// awaitInFlightSuspension waits until a suspension is registered next to one admitted Start.
func awaitInFlightSuspension(t *testing.T, supervisor *PosixSupervisor) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		supervisor.admission.mu.Lock()
		pending := supervisor.admission.suspended && supervisor.admission.starting == 1
		supervisor.admission.mu.Unlock()
		if pending {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("suspension did not register next to the in-flight Start")
}

// awaitProcessGone waits until the reaped PID no longer exists.
func awaitProcessGone(t *testing.T, pid int, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if err := syscall.Kill(pid, 0); errors.Is(err, syscall.ESRCH) {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("process %d survived the suspended supervisor", pid)
}
