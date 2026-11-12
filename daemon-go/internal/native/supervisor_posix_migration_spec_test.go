//go:build linux || darwin

package native

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

// NAT-T-003 verifies TERM before permit converges the helper session without running the command.
func TestHelper_TermBeforePermit(t *testing.T) {
	dir := stateTestDir(t)
	marker := filepath.Join(t.TempDir(), "command-ran")
	launch, err := json.Marshal(launchSpec{Cwd: t.TempDir(), Args: []string{"/bin/sh", "-c", "printf ran > " + strconv.Quote(marker)}})
	if err != nil {
		t.Fatal(err)
	}
	if err := publish(dir, launchFile, launch); err != nil {
		t.Fatal(err)
	}
	ownerReader, ownerWriter, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer ownerWriter.Close()
	command := exec.Command(os.Args[0], "__native-helper", dir)
	command.ExtraFiles = []*os.File{ownerReader}
	command.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := command.Start(); err != nil {
		_ = ownerReader.Close()
		t.Fatal(err)
	}
	_ = ownerReader.Close()
	deadline := time.Now().Add(5 * time.Second)
	for {
		if _, err := readState(dir, scopeFile); err == nil {
			break
		}
		if time.Now().After(deadline) {
			_ = command.Process.Kill()
			_ = command.Wait()
			t.Fatal("helper did not publish its scope")
		}
		time.Sleep(5 * time.Millisecond)
	}
	if err := command.Process.Signal(syscall.SIGTERM); err != nil {
		t.Fatal(err)
	}
	waited := make(chan error, 1)
	go func() { waited <- command.Wait() }()
	select {
	case err := <-waited:
		if err != nil {
			t.Fatalf("helper after TERM: %v", err)
		}
	case <-time.After(5 * time.Second):
		_ = command.Process.Kill()
		t.Fatal("helper did not finish after TERM")
	}
	cleanup, err := readCleanup(dir)
	if err != nil || cleanup == nil || !*cleanup {
		t.Fatalf("cleanup after TERM = %v, %v", cleanup, err)
	}
	if _, err := readExit(dir); err == nil {
		t.Fatal("helper published a command exit before permit")
	}
	if _, err := os.Stat(marker); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("command marker exists or cannot be inspected: %v", err)
	}
}

// NAT-T-126 verifies the helper converges its command after its owner process disappears.
func TestPOSIXSupervisor_OwnerDeathFixtures(t *testing.T) {
	dir := stateTestDir(t)
	launch, err := json.Marshal(launchSpec{Cwd: t.TempDir(), Args: []string{"/bin/sleep", "30"}})
	if err != nil {
		t.Fatal(err)
	}
	if err := publish(dir, launchFile, launch); err != nil {
		t.Fatal(err)
	}
	ownerReader, ownerWriter, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer ownerWriter.Close()
	helper := exec.Command(os.Args[0], "__native-helper", dir)
	helper.ExtraFiles = []*os.File{ownerReader}
	helper.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := helper.Start(); err != nil {
		_ = ownerReader.Close()
		t.Fatal(err)
	}
	_ = ownerReader.Close()
	deadline := time.Now().Add(5 * time.Second)
	var scopePID int
	for {
		contents, readErr := readState(dir, scopeFile)
		if readErr == nil {
			var err error
			scopePID, err = strconv.Atoi(strings.TrimSpace(string(contents)))
			if err != nil {
				_ = helper.Process.Kill()
				_ = helper.Wait()
				t.Fatalf("invalid helper scope %q: %v", contents, err)
			}
			break
		}
		if time.Now().After(deadline) {
			_ = helper.Process.Kill()
			_ = helper.Wait()
			t.Fatal("helper did not publish scope")
		}
		time.Sleep(5 * time.Millisecond)
	}
	if err := publish(dir, permitFile, []byte("true")); err != nil {
		_ = helper.Process.Kill()
		_ = helper.Wait()
		t.Fatal(err)
	}
	deadline = time.Now().Add(5 * time.Second)
	for {
		members, err := sessionMembers(scopePID)
		if err != nil {
			_ = helper.Process.Kill()
			_ = helper.Wait()
			t.Fatal(err)
		}
		if len(members) > 1 {
			break
		}
		if time.Now().After(deadline) {
			_ = helper.Process.Kill()
			_ = helper.Wait()
			t.Fatalf("helper session did not include its command: %+v", members)
		}
		time.Sleep(5 * time.Millisecond)
	}
	if err := ownerWriter.Close(); err != nil {
		t.Fatal(err)
	}
	waited := make(chan error, 1)
	go func() { waited <- helper.Wait() }()
	select {
	case err := <-waited:
		if err != nil {
			t.Fatalf("helper after owner death: %v", err)
		}
	case <-time.After(8 * time.Second):
		_ = helper.Process.Kill()
		t.Fatal("helper did not converge after owner death")
	}
	cleanup, err := readCleanup(dir)
	if err != nil || cleanup == nil || !*cleanup {
		t.Fatalf("cleanup evidence = %v, %v", cleanup, err)
	}
	members, err := sessionMembers(scopePID)
	if err != nil {
		t.Fatal(err)
	}
	if len(members) != 0 {
		t.Fatalf("session remains after owner death: %+v", members)
	}
	_ = removeStateDir(dir)
}

// NAT-T-137 verifies cancellation after scope establishment cannot publish a permit.
func TestPOSIXSupervisor_CancelAfterScope(t *testing.T) {
	dir := stateTestDir(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := publishPOSIXPermit(ctx, dir); !errors.Is(err, context.Canceled) {
		t.Fatalf("publishPOSIXPermit error = %v, want context cancellation", err)
	}
	if _, err := readState(dir, permitFile); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("permit after cancellation = %v", err)
	}
}

// NAT-T-004 verifies owner-channel EOF before permit converges without running the command.
func TestHelper_ParentDeathBeforePermit(t *testing.T) {
	dir := stateTestDir(t)
	marker := filepath.Join(t.TempDir(), "command-ran")
	launch, err := json.Marshal(launchSpec{Cwd: t.TempDir(), Args: []string{"/bin/sh", "-c", "printf ran > " + strconv.Quote(marker)}})
	if err != nil {
		t.Fatal(err)
	}
	if err := publish(dir, launchFile, launch); err != nil {
		t.Fatal(err)
	}
	ownerReader, ownerWriter, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	command := exec.Command(os.Args[0], "__native-helper", dir)
	command.ExtraFiles = []*os.File{ownerReader}
	command.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := command.Start(); err != nil {
		_ = ownerReader.Close()
		_ = ownerWriter.Close()
		t.Fatal(err)
	}
	_ = ownerReader.Close()
	if err := waitForPOSIXHelperScope(dir); err != nil {
		_ = ownerWriter.Close()
		_ = command.Process.Kill()
		_ = command.Wait()
		t.Fatal(err)
	}
	if err := ownerWriter.Close(); err != nil {
		t.Fatal(err)
	}
	if err := command.Wait(); err != nil {
		t.Fatalf("helper after owner EOF: %v", err)
	}
	cleanup, err := readCleanup(dir)
	if err != nil || cleanup == nil || !*cleanup {
		t.Fatalf("cleanup after owner EOF = %v, %v", cleanup, err)
	}
	if _, err := readExit(dir); err == nil {
		t.Fatal("helper published a command exit before permit")
	}
	if _, err := os.Stat(marker); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("command marker exists or cannot be inspected: %v", err)
	}
}

// NAT-T-005 verifies an empty permitted command fails with the fixed helper phase.
func TestHelper_EmptyCommandFailsClosed(t *testing.T) {
	dir := stateTestDir(t)
	launch, err := json.Marshal(launchSpec{Cwd: t.TempDir(), Args: []string{}})
	if err != nil {
		t.Fatal(err)
	}
	if err := publish(dir, launchFile, launch); err != nil {
		t.Fatal(err)
	}
	ownerReader, ownerWriter, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer ownerWriter.Close()
	command := exec.Command(os.Args[0], "__native-helper", dir)
	command.ExtraFiles = []*os.File{ownerReader}
	command.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := command.Start(); err != nil {
		_ = ownerReader.Close()
		t.Fatal(err)
	}
	_ = ownerReader.Close()
	if err := waitForPOSIXHelperScope(dir); err != nil {
		_ = command.Process.Kill()
		_ = command.Wait()
		t.Fatal(err)
	}
	if err := publishText(dir, permitFile, "true"); err != nil {
		t.Fatal(err)
	}
	var exitErr *exec.ExitError
	if err := command.Wait(); !errors.As(err, &exitErr) || exitErr.ExitCode() != 1 {
		t.Fatalf("helper empty-command exit = %v, want exit 1", err)
	}
	phase, err := readState(dir, errorFile)
	if err != nil || string(phase) != "helper-posix-failure" {
		t.Fatalf("helper empty-command phase = %q, %v", phase, err)
	}
}

func waitForPOSIXHelperScope(stateDir string) error {
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if _, err := readState(stateDir, scopeFile); err == nil {
			return nil
		}
		time.Sleep(5 * time.Millisecond)
	}
	return errors.New("helper did not publish its scope")
}

// NAT-T-066 verifies a launch publication failure prevents helper creation and removes state.
func TestPOSIXStart_LaunchWriteFailure(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	posix := supervisor.(*PosixSupervisor)
	injected := errors.New("injected launch publication failure")
	stateDir := ""
	startCalls := 0
	posix.publishLaunch = func(dir, name string, data []byte) error {
		stateDir = dir
		if name != launchFile || len(data) == 0 {
			t.Fatalf("launch publication arguments = %q/%d", name, len(data))
		}
		return injected
	}
	posix.startHelper = func(*exec.Cmd) error {
		startCalls++
		return nil
	}
	if _, err := posix.Start(context.Background(), ProcessSpec{Executable: "/bin/true"}); !errors.Is(err, injected) {
		t.Fatalf("Start error = %v, want injected publication error", err)
	}
	if startCalls != 0 {
		t.Fatalf("helper starts = %d, want none", startCalls)
	}
	if _, err := os.Stat(stateDir); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("failed launch state directory remains: %v", err)
	}
}

// NAT-T-063 verifies a helper spawn failure releases all parent-owned resources.
func TestPOSIXStart_CleansResourcesAfterSpawnFailure(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	posix := supervisor.(*PosixSupervisor)
	injected := errors.New("injected helper spawn failure")
	stateDir := ""
	var inherited []*os.File
	var pipes []*os.File
	posix.startHelper = func(command *exec.Cmd) error {
		stateDir = command.Args[2]
		inherited = command.ExtraFiles
		pipes = []*os.File{command.Stdin.(*os.File), command.Stdout.(*os.File), command.Stderr.(*os.File)}
		return injected
	}
	if _, err := posix.Start(context.Background(), ProcessSpec{Executable: "/bin/true"}); !errors.Is(err, injected) {
		t.Fatalf("Start error = %v, want injected spawn failure", err)
	}
	if _, err := os.Stat(stateDir); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("failed start state directory remains: %v", err)
	}
	for _, file := range append(inherited, pipes...) {
		if _, err := file.Stat(); err == nil {
			t.Errorf("file %q remained open after spawn failure", file.Name())
		}
	}
}

// NAT-T-075 verifies failed startup kills only the known helper before scope confirmation.
func TestPOSIXState_KillHelperOnly(t *testing.T) {
	p := posixTestProcess(t)
	p.pid = 42
	p.sid = 999
	p.cmd = &exec.Cmd{}
	p.scopeConfirmed = false
	close(p.done)
	cause := errors.New("scope was not established")
	killCalls := 0
	convergeCalls := 0
	p.killHelper = func() error {
		killCalls++
		return nil
	}
	p.convergeScope = func(context.Context, int, time.Duration) error {
		convergeCalls++
		return nil
	}
	if err := p.cleanupFailedStart(cause); !errors.Is(err, cause) {
		t.Fatalf("failed-start error = %v, want original cause", err)
	}
	if killCalls != 1 || convergeCalls != 0 {
		t.Fatalf("helper kills/session convergence = %d/%d, want 1/0", killCalls, convergeCalls)
	}
}

// NAT-T-077 verifies an empty scope proves cleanup without helper cleanup-file evidence.
func TestPOSIXState_EmptySessionProof(t *testing.T) {
	p := posixTestProcess(t)
	close(p.done)
	snapshotCalls := 0
	p.convergeScope = func(ctx context.Context, sid int, grace time.Duration) error {
		return convergeSessionFencedWithAPI(ctx, sid, grace, 0, nil, posixConvergenceAPI{
			members: func(int) ([]sessionMember, error) {
				snapshotCalls++
				return nil, nil
			},
			kill: func(int, syscall.Signal) error { t.Fatal("empty scope was signalled"); return nil },
			now:  time.Now,
			wait: func(context.Context, time.Duration) error { t.Fatal("empty scope waited"); return nil },
		})
	}
	if err := p.Terminate(context.Background(), 0); err != nil {
		t.Fatalf("Terminate on empty session: %v", err)
	}
	if snapshotCalls != 1 {
		t.Fatalf("session snapshots = %d, want one", snapshotCalls)
	}
	if _, err := os.Stat(p.stateDir); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("state directory after empty-session proof: %v", err)
	}
}

// NAT-T-074 verifies concurrent termination calls share one successful close.
func TestPOSIXState_ConcurrentClose(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/sleep", Args: []string{"30"}})
	if err != nil {
		t.Fatal(err)
	}
	managed := process.(*posixProcess)
	var convergenceCalls atomic.Int32
	managed.convergeScope = func(ctx context.Context, sid int, grace time.Duration) error {
		convergenceCalls.Add(1)
		return convergeSession(ctx, sid, grace)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	start := make(chan struct{})
	var wait sync.WaitGroup
	for index := 0; index < 2; index++ {
		wait.Add(1)
		go func() {
			defer wait.Done()
			<-start
			if err := process.Terminate(ctx, 10*time.Millisecond); err != nil {
				t.Errorf("concurrent terminate: %v", err)
			}
		}()
	}
	close(start)
	wait.Wait()
	if calls := convergenceCalls.Load(); calls != 1 {
		t.Fatalf("session convergence calls = %d, want one shared termination", calls)
	}
	members, err := sessionMembers(managed.sid)
	if err != nil {
		t.Fatal(err)
	}
	if len(members) != 0 {
		t.Fatalf("session members after concurrent close = %+v", members)
	}
}

// NAT-T-116 verifies terminating a process scope also kills a nested child.
func TestPOSIXSupervisor_TerminateNested(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	pidFile := filepath.Join(t.TempDir(), "child.pid")
	process, err := supervisor.Start(context.Background(), ProcessSpec{
		Executable: "/bin/sh",
		Args:       []string{"-c", "sleep 30 & echo $! > " + strconv.Quote(pidFile) + "; wait"},
	})
	if err != nil {
		t.Fatal(err)
	}
	childPID := waitPIDFile(t, pidFile)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := process.Terminate(ctx, 10*time.Millisecond); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		err := unix.Kill(childPID, 0)
		if errors.Is(err, unix.ESRCH) {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("nested child %d survived scope termination", childPID)
}

// NAT-T-117 verifies session termination reaches a child in a distinct process group.
func TestPOSIXSupervisor_MultipleProcessGroups(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	pidFile := filepath.Join(t.TempDir(), "child.pid")
	process, err := supervisor.Start(context.Background(), ProcessSpec{
		Executable: "/bin/sh",
		Args:       []string{"-c", "set -m; sleep 30 & echo $! > " + strconv.Quote(pidFile) + "; wait"},
	})
	if err != nil {
		t.Fatal(err)
	}
	childPID := waitPIDFile(t, pidFile)
	managed := process.(*posixProcess)
	childSession, err := unix.Getsid(childPID)
	if err != nil {
		t.Fatal(err)
	}
	childGroup, err := unix.Getpgid(childPID)
	if err != nil {
		t.Fatal(err)
	}
	if childSession != managed.sid || childGroup == managed.sid {
		t.Fatalf("child session/group = %d/%d, want session %d and a distinct process group", childSession, childGroup, managed.sid)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := process.Terminate(ctx, 10*time.Millisecond); err != nil {
		t.Fatal(err)
	}
	if err := unix.Kill(childPID, 0); !errors.Is(err, unix.ESRCH) {
		t.Fatalf("different-group child %d survived termination: %v", childPID, err)
	}
}
