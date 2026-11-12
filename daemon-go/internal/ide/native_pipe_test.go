//go:build linux || darwin

package ide

import (
	"context"
	"errors"
	"io"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"syscall"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/native"
)

const inheritedPipePendingWindow = 50 * time.Millisecond

func TestMain(m *testing.M) {
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "__native-helper":
			os.Exit(native.HelperMain(os.Args[2:]))
		case "__ide-pipe-parent":
			os.Exit(runPipeParent(os.Args[2]))
		case "__ide-pipe-descendant":
			os.Exit(runPipeDescendant(os.Args[2]))
		}
	}
	os.Exit(m.Run())
}

// IDE-T-040/155 verifies the native supervisor waits for an inherited-pipe descendant to leave scope.
func TestNativeSupervisorWaitsForInheritedPipes(t *testing.T) {
	directory := t.TempDir()
	gate := filepath.Join(directory, "release-descendant")
	ready := filepath.Join(directory, "descendant-ready")
	parentExited := filepath.Join(directory, "parent-exited")
	supervisor, err := native.NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	process, err := supervisor.Start(context.Background(), native.ProcessSpec{
		Executable: os.Args[0],
		Args:       []string{"__ide-pipe-parent", directory},
	})
	if err != nil {
		_ = supervisor.Close(context.Background())
		t.Fatal(err)
	}

	cleanup := func() {
		_ = os.WriteFile(gate, []byte("release"), 0600)
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = supervisor.Close(ctx)
	}
	defer cleanup()

	if err := waitForPipeTestFile(ready, 5*time.Second); err != nil {
		t.Fatal(err)
	}
	if err := waitForPipeTestFile(parentExited, 5*time.Second); err != nil {
		t.Fatal(err)
	}

	stdoutDone := make(chan struct{})
	stderrDone := make(chan struct{})
	go func() {
		_, _ = io.Copy(io.Discard, process.Stdout())
		close(stdoutDone)
	}()
	go func() {
		_, _ = io.Copy(io.Discard, process.Stderr())
		close(stderrDone)
	}()
	assertPending(t, stdoutDone, "stdout reached EOF while descendant retained its pipe")
	assertPending(t, stderrDone, "stderr reached EOF while descendant retained its pipe")

	waitCtx, cancelWait := context.WithTimeout(context.Background(), inheritedPipePendingWindow)
	defer cancelWait()
	if _, err := process.Wait(waitCtx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("wait before descendant release = %v, want deadline", err)
	}

	terminateDone := make(chan error, 1)
	go func() {
		terminateDone <- process.Terminate(context.Background(), 3*time.Second)
	}()
	assertPending(t, terminateDone, "terminate completed while descendant retained its pipes")

	closeDone := make(chan error, 1)
	go func() {
		closeDone <- supervisor.Close(context.Background())
	}()
	assertPending(t, closeDone, "supervisor close completed while descendant retained its pipes")

	if err := os.WriteFile(gate, []byte("release"), 0600); err != nil {
		t.Fatal(err)
	}
	awaitPipeTestResult(t, terminateDone, "terminate")
	awaitPipeTestResult(t, closeDone, "supervisor close")
}

// IDE-T-155 verifies a completed pipe drain preserves the direct child exit status.
func TestNativeSupervisorWaitReturnsDirectChildExitAfterPipeDrain(t *testing.T) {
	directory := t.TempDir()
	gate := filepath.Join(directory, "release-descendant")
	supervisor, err := native.NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	process, err := supervisor.Start(context.Background(), native.ProcessSpec{
		Executable: os.Args[0],
		Args:       []string{"__ide-pipe-parent", directory},
	})
	if err != nil {
		_ = supervisor.Close(context.Background())
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = os.WriteFile(gate, []byte("release"), 0600)
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = supervisor.Close(ctx)
	})
	if err := waitForPipeTestFile(filepath.Join(directory, "descendant-ready"), 5*time.Second); err != nil {
		t.Fatal(err)
	}
	if err := waitForPipeTestFile(filepath.Join(directory, "parent-exited"), 5*time.Second); err != nil {
		t.Fatal(err)
	}

	stdoutDone := make(chan struct{})
	stderrDone := make(chan struct{})
	go func() {
		_, _ = io.Copy(io.Discard, process.Stdout())
		close(stdoutDone)
	}()
	go func() {
		_, _ = io.Copy(io.Discard, process.Stderr())
		close(stderrDone)
	}()
	assertPending(t, stdoutDone, "stdout reached EOF while descendant retained its pipe")
	assertPending(t, stderrDone, "stderr reached EOF while descendant retained its pipe")

	waitCtx, cancelWait := context.WithTimeout(context.Background(), inheritedPipePendingWindow)
	defer cancelWait()
	if _, err := process.Wait(waitCtx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("wait with retained descendant pipes = %v, want deadline", err)
	}

	waitDone := make(chan struct {
		exit native.ProcessExit
		err  error
	}, 1)
	go func() {
		exit, err := process.Wait(context.Background())
		waitDone <- struct {
			exit native.ProcessExit
			err  error
		}{exit, err}
	}()
	assertPending(t, waitDone, "wait completed while descendant retained its pipes")
	if err := os.WriteFile(gate, []byte("release"), 0600); err != nil {
		t.Fatal(err)
	}
	select {
	case result := <-waitDone:
		if result.err != nil || result.exit.Code == nil || *result.exit.Code != 0 || result.exit.Signal != nil {
			t.Fatalf("wait after pipe closure = %+v, err=%v", result.exit, result.err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("wait did not finish after descendant released its pipes")
	}
	if err := supervisor.Close(context.Background()); err != nil {
		t.Fatalf("supervisor close after wait = %v", err)
	}
}

func runPipeParent(directory string) int {
	executable, err := os.Executable()
	if err != nil {
		return 2
	}
	command := exec.Command(executable, "__ide-pipe-descendant", directory)
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	if err := command.Start(); err != nil {
		return 3
	}
	if err := waitForPipeTestFile(filepath.Join(directory, "descendant-ready"), 5*time.Second); err != nil {
		return 4
	}
	if err := os.WriteFile(filepath.Join(directory, "parent-exited"), []byte("exit"), 0600); err != nil {
		return 5
	}
	return 0
}

func runPipeDescendant(directory string) int {
	signal.Ignore(syscall.SIGTERM)
	if err := os.WriteFile(filepath.Join(directory, "descendant-ready"), []byte("ready"), 0600); err != nil {
		return 2
	}
	for {
		if _, err := os.Stat(filepath.Join(directory, "release-descendant")); err == nil {
			return 0
		} else if !os.IsNotExist(err) {
			return 3
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func waitForPipeTestFile(path string, timeout time.Duration) error {
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	for {
		if _, err := os.Stat(path); err == nil {
			return nil
		} else if !os.IsNotExist(err) {
			return err
		}
		select {
		case <-deadline.C:
			return context.DeadlineExceeded
		case <-time.After(5 * time.Millisecond):
		}
	}
}

func assertPending[T any](t *testing.T, done <-chan T, failure string) {
	t.Helper()
	select {
	case <-done:
		t.Fatal(failure)
	case <-time.After(inheritedPipePendingWindow):
	}
}

func awaitPipeTestResult(t *testing.T, result <-chan error, operation string) {
	t.Helper()
	select {
	case err := <-result:
		if err != nil {
			t.Fatalf("%s after descendant release = %v", operation, err)
		}
	case <-time.After(5 * time.Second):
		t.Fatalf("%s did not finish after descendant release", operation)
	}
}
