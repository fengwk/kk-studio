//go:build linux || darwin

package native

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"go.uber.org/goleak"
)

func TestMain(m *testing.M) {
	if len(os.Args) > 1 && os.Args[1] == "__native-helper" {
		os.Exit(HelperMain(os.Args[2:]))
	}
	goleak.VerifyTestMain(m)
}

// NAT-N-001, NAT-T-115
func TestPOSIXSupervisor_NaturalExitDrains(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/sh", Args: []string{"-c", "printf native-ok; exit 21"}})
	if err != nil {
		t.Fatal(err)
	}
	readDone := make(chan struct{})
	var stdout []byte
	go func() { stdout, _ = io.ReadAll(process.Stdout()); close(readDone) }()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	select {
	case <-readDone:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	exit, err := process.Wait(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if string(stdout) != "native-ok" {
		t.Fatalf("stdout = %q", stdout)
	}
	if exit.Code == nil || *exit.Code != 21 {
		t.Fatalf("exit = %+v", exit)
	}
}

// NAT-T-114 verifies that environment values supplied to the daemon reach the command.
func TestPOSIXSupervisor_InheritedEnvironment(t *testing.T) {
	t.Setenv("KK_STUDIO_NATIVE_TEST_ENV", "inherited")
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{
		Executable: "/bin/sh",
		Args:       []string{"-c", "printf '%s' \"$KK_STUDIO_NATIVE_TEST_ENV\""},
	})
	if err != nil {
		t.Fatal(err)
	}
	output, err := io.ReadAll(process.Stdout())
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := process.Wait(ctx); err != nil {
		t.Fatal(err)
	}
	if string(output) != "inherited" {
		t.Fatalf("inherited environment output = %q", output)
	}
}

// NAT-T-118 verifies that stdin EOF allows the command to exit with its own code.
func TestPOSIXSupervisor_StdinEOF(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{
		Executable: "/bin/sh",
		Args:       []string{"-c", "cat >/dev/null; exit 23"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := process.Stdin().Close(); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	exit, err := process.Wait(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if exit.Code == nil || *exit.Code != 23 {
		t.Fatalf("exit after stdin EOF = %+v", exit)
	}
}

// NAT-T-128 verifies terminating one process scope leaves another live scope untouched.
func TestPOSIXSupervisor_ScopeIsolation(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	first, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/sh", Args: []string{"-c", "sleep 30"}})
	if err != nil {
		t.Fatal(err)
	}
	second, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/sh", Args: []string{"-c", "sleep 30"}})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := first.Terminate(ctx, 10*time.Millisecond); err != nil {
		t.Fatal(err)
	}
	waitCtx, waitCancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer waitCancel()
	if _, err := second.Wait(waitCtx); err == nil {
		t.Fatal("unrelated process scope exited when the first scope terminated")
	}
	if err := second.Terminate(ctx, 10*time.Millisecond); err != nil {
		t.Fatal(err)
	}
}

// NAT-T-119
func TestPOSIXSupervisor_DuplexStreams(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{
		Executable: "/bin/sh",
		Args:       []string{"-c", "IFS= read -r value; printf 'out:%s' \"$value\"; printf 'native-error' >&2; exit 7"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := io.WriteString(process.Stdin(), "native-input\n"); err != nil {
		t.Fatal(err)
	}
	if err := process.Stdin().Close(); err != nil {
		t.Fatal(err)
	}
	stdout, err := io.ReadAll(process.Stdout())
	if err != nil {
		t.Fatal(err)
	}
	stderr, err := io.ReadAll(process.Stderr())
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	exit, err := process.Wait(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if string(stdout) != "out:native-input" || string(stderr) != "native-error" {
		t.Fatalf("stdout/stderr = %q/%q", stdout, stderr)
	}
	if exit.Code == nil || *exit.Code != 7 {
		t.Fatalf("exit = %+v", exit)
	}
}

// NAT-T-133
func TestPOSIXSupervisor_StartAfterClose(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	if err := supervisor.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	if _, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/true"}); err == nil {
		t.Fatal("start after close succeeded")
	}
}

func TestContainsTrackedAnchor_RequiresExactIdentity(t *testing.T) {
	tracked := trackedSession{ChildPID: 42, Session: 40, Identity: "start-1"}
	members := []sessionMember{{PID: 42, Session: 40, Identity: "start-1"}}
	if !containsTrackedAnchor(members, tracked) {
		t.Fatal("exact tracked identity was not found")
	}
	for _, changed := range []sessionMember{
		{PID: 43, Session: 40, Identity: "start-1"},
		{PID: 42, Session: 41, Identity: "start-1"},
		{PID: 42, Session: 40, Identity: "start-2"},
	} {
		if containsTrackedAnchor([]sessionMember{changed}, tracked) {
			t.Fatalf("changed tracked identity was accepted: %+v", changed)
		}
	}
}

// Exit fallback must preserve unknown status, prefer published evidence, and decode helper wait results.
func TestPOSIXExitFallback_PreservesExitEvidence(t *testing.T) {
	dir := stateTestDir(t)
	exit := readExitFallback(dir, nil)
	if exit.Code != nil || exit.Signal != nil {
		t.Fatalf("clean helper exit without command evidence fabricated status: %+v", exit)
	}

	code := 7
	signal := "SIGTERM"
	payload, err := json.Marshal(exitReport{ExitCode: &code, Signal: &signal})
	if err != nil {
		t.Fatal(err)
	}
	if err := publish(dir, exitFile, payload); err != nil {
		t.Fatal(err)
	}
	exit = readExitFallback(dir, nil)
	if exit.Code == nil || *exit.Code != code || exit.Signal == nil || *exit.Signal != signal {
		t.Fatalf("published exit report not preserved: %+v", exit)
	}
	if err := publishText(dir, errorFile, "helper-failure"); err != nil {
		t.Fatal(err)
	}
	exit = readExitFallback(dir, nil)
	if exit.Code != nil || exit.Signal != nil {
		t.Fatalf("startup failure reported an exit status: %+v", exit)
	}

	waitErr := exec.Command("sh", "-c", "exit 23").Run()
	if waitErr == nil {
		t.Fatal("expected non-zero exit status")
	}
	exit = readExitFallback(stateTestDir(t), waitErr)
	if exit.Code == nil || *exit.Code != 23 || exit.Signal != nil {
		t.Fatalf("helper wait exit fallback = %+v, want code 23", exit)
	}

	signalErr := exec.Command("sh", "-c", "kill -TERM $$").Run()
	exit = readExitFallback(stateTestDir(t), signalErr)
	if exit.Signal == nil || *exit.Signal == "" || exit.Code != nil {
		t.Fatalf("helper wait signal fallback = %+v, want a signal", exit)
	}
	exit = readExitFallback(stateTestDir(t), errors.New("wait failed"))
	if exit.Code != nil || exit.Signal != nil {
		t.Fatalf("unknown helper wait error reported an exit status: %+v", exit)
	}
}

// NAT-T-132
func TestPOSIXSupervisor_ExecFailureHasUnknownExit(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/no/such/native-command"})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	exit, err := process.Wait(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if exit.Code != nil || exit.Signal != nil {
		t.Fatalf("failed exec reported an exit status: %+v", exit)
	}
}

// NAT-T-116
func TestPOSIXSupervisor_Terminate(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/sh", Args: []string{"-c", "sleep 30"}})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := process.Terminate(ctx, 10*time.Millisecond); err != nil {
		t.Fatal(err)
	}
}

// NAT-T-129, NAT-T-130 verify descendant tracking rejects unknown roots and foreign processes.
func TestPOSIXSupervisor_TrackDescendantRejectsForeignProcess(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	if err := supervisor.(DescendantTracker).TrackDescendant(context.Background(), 99999999, os.Getpid()); err == nil {
		t.Fatal("unknown root was accepted")
	}
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/sh", Args: []string{"-c", "sleep 30"}})
	if err != nil {
		t.Fatal(err)
	}
	if err := supervisor.(DescendantTracker).TrackDescendant(context.Background(), process.PID(), os.Getpid()); err == nil {
		t.Fatal("foreign process was accepted as a managed descendant")
	}
}

// NAT-T-138 verifies that a descendant already in the managed session is accepted.
func TestPOSIXSupervisor_TrackExistingMember(t *testing.T) {
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
	if err := supervisor.(DescendantTracker).TrackDescendant(context.Background(), process.PID(), childPID); err != nil {
		t.Fatalf("TrackDescendant(%d) error = %v", childPID, err)
	}
}

// NAT-T-120
func TestPOSIXSupervisor_PreCancelled(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := supervisor.Start(ctx, ProcessSpec{Executable: "/bin/true"}); err == nil {
		t.Fatal("cancelled start succeeded")
	}
}
