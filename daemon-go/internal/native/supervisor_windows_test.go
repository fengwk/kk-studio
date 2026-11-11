//go:build windows

package native

import (
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

func TestMain(m *testing.M) {
	if len(os.Args) > 1 && os.Args[1] == "__native-helper" {
		os.Exit(HelperMain(os.Args[2:]))
	}
	if len(os.Args) > 1 && os.Args[1] == "__native-fixture" {
		os.Exit(windowsFixture(os.Args[2:]))
	}
	if len(os.Args) > 1 && os.Args[1] == "__native-owner" {
		os.Exit(windowsOwner(os.Args[2:]))
	}
	os.Exit(m.Run())
}

func windowsFixture(args []string) int {
	if len(args) == 0 {
		return 2
	}
	switch args[0] {
	case "hold":
		for {
			time.Sleep(time.Hour)
		}
	case "tree":
		if len(args) != 2 {
			return 2
		}
		child := exec.Command(os.Args[0], "__native-fixture", "hold")
		if err := child.Start(); err != nil {
			return 31
		}
		if err := os.WriteFile(args[1], []byte(strconv.Itoa(child.Process.Pid)), 0600); err != nil {
			_ = child.Process.Kill()
			_ = child.Wait()
			return 32
		}
		return 19
	case "treehold":
		if len(args) != 2 {
			return 2
		}
		child := exec.Command(os.Args[0], "__native-fixture", "treechild", args[1])
		if err := child.Start(); err != nil {
			return 33
		}
		for {
			time.Sleep(time.Hour)
		}
	case "treechild":
		if len(args) != 2 {
			return 2
		}
		grandchild := exec.Command(os.Args[0], "__native-fixture", "hold")
		if err := grandchild.Start(); err != nil {
			return 35
		}
		if err := os.WriteFile(args[1], []byte(strconv.Itoa(grandchild.Process.Pid)), 0600); err != nil {
			_ = grandchild.Process.Kill()
			_ = grandchild.Wait()
			return 36
		}
		for {
			time.Sleep(time.Hour)
		}
	case "eof":
		input, err := io.ReadAll(os.Stdin)
		if err != nil {
			return 35
		}
		_, _ = fmt.Fprintf(os.Stdout, "OUT:%s", input)
		_, _ = fmt.Fprint(os.Stderr, "ERR-MARKER")
		return 0
	case "exit":
		return 21
	}
	return 2
}

func windowsOwner(args []string) int {
	if len(args) != 1 {
		return 2
	}
	supervisor, err := NewSupervisor()
	if err != nil {
		return 41
	}
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: os.Args[0], Args: []string{"__native-fixture", "treehold", args[0]}})
	if err != nil {
		return 42
	}
	_ = process
	for {
		time.Sleep(time.Hour)
	}
}

func windowsPIDAlive(pid uint32) (bool, error) {
	h, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION|windows.SYNCHRONIZE, false, pid)
	if err != nil {
		if err == windows.ERROR_INVALID_PARAMETER {
			return false, nil
		}
		return false, err
	}
	defer windows.CloseHandle(h)
	status, err := windows.WaitForSingleObject(h, 0)
	if err != nil {
		return false, err
	}
	return status == uint32(windows.WAIT_TIMEOUT), nil
}

func waitWindowsPIDState(t *testing.T, pid uint32, alive bool, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		got, err := windowsPIDAlive(pid)
		if err != nil {
			t.Fatal(err)
		}
		if got == alive {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	got, err := windowsPIDAlive(pid)
	t.Fatalf("pid %d alive=%t, want %t (err=%v)", pid, got, alive, err)
}

func fixturePID(t *testing.T, path string) uint32 {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		data, err := os.ReadFile(path)
		if err == nil {
			pid, parseErr := strconv.ParseUint(strings.TrimSpace(string(data)), 10, 32)
			if parseErr != nil {
				t.Fatal(parseErr)
			}
			return uint32(pid)
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("fixture did not publish pid file %q", path)
	return 0
}

// NAT-T-108, NAT-T-115 (Windows behavior)
func TestWindowsSupervisor_NaturalExitDrainsJob(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	pidFile := filepath.Join(t.TempDir(), "child.pid")
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: os.Args[0], Args: []string{"__native-fixture", "tree", pidFile}})
	if err != nil {
		t.Fatal(err)
	}
	childPID := fixturePID(t, pidFile)
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	exit, err := process.Wait(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if exit.Code == nil || *exit.Code != 19 {
		t.Fatalf("exit = %+v", exit)
	}
	waitWindowsPIDState(t, childPID, false, 3*time.Second)
}

// NAT-T-109, NAT-T-110
func TestWindowsSupervisor_StdinEOF(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: os.Args[0], Args: []string{"__native-fixture", "eof"}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := process.Stdin().Write([]byte("payload")); err != nil {
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
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	exit, err := process.Wait(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if exit.Code == nil || *exit.Code != 0 || string(stdout) != "OUT:payload" || string(stderr) != "ERR-MARKER" {
		t.Fatalf("exit=%+v stdout=%q stderr=%q", exit, stdout, stderr)
	}
}

// NAT-T-109
func TestWindowsSupervisor_TerminateNestedTree(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	pidFile := filepath.Join(t.TempDir(), "child.pid")
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: os.Args[0], Args: []string{"__native-fixture", "treehold", pidFile}})
	if err != nil {
		t.Fatal(err)
	}
	childPID := fixturePID(t, pidFile)
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	if err := process.Terminate(ctx, time.Second); err != nil {
		t.Fatal(err)
	}
	waitWindowsPIDState(t, childPID, false, 3*time.Second)
}

// NAT-T-112
func TestWindowsSupervisor_OwnerDeath(t *testing.T) {
	pidFile := filepath.Join(t.TempDir(), "owner-child.pid")
	cmd := exec.Command(os.Args[0], "__native-owner", pidFile)
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = cmd.Process.Kill(); _ = cmd.Wait() }()
	childPID := fixturePID(t, pidFile)
	if err := cmd.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	_ = cmd.Wait()
	waitWindowsPIDState(t, childPID, false, 8*time.Second)
}

// NAT-T-111
func TestWindowsSupervisor_TrackDescendantConfirmsJobMembership(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	firstFile := filepath.Join(t.TempDir(), "first-child.pid")
	secondFile := filepath.Join(t.TempDir(), "second-child.pid")
	first, err := supervisor.Start(context.Background(), ProcessSpec{Executable: os.Args[0], Args: []string{"__native-fixture", "treehold", firstFile}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := supervisor.Start(context.Background(), ProcessSpec{Executable: os.Args[0], Args: []string{"__native-fixture", "treehold", secondFile}}); err != nil {
		t.Fatal(err)
	}
	firstChild := fixturePID(t, firstFile)
	secondChild := fixturePID(t, secondFile)
	tracker, ok := supervisor.(DescendantTracker)
	if !ok {
		t.Fatal("Windows supervisor does not expose descendant tracking")
	}
	if err := tracker.TrackDescendant(context.Background(), first.PID(), int(firstChild)); err != nil {
		t.Fatal(err)
	}
	if err := tracker.TrackDescendant(context.Background(), first.PID(), int(secondChild)); err == nil {
		t.Fatal("foreign process was accepted into the scope")
	}
	waitWindowsPIDState(t, secondChild, true, time.Second)
}

// NAT-T-113
func TestWindowsSupervisor_ScopeIsolation(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	firstFile := filepath.Join(t.TempDir(), "first-child.pid")
	secondFile := filepath.Join(t.TempDir(), "second-child.pid")
	first, err := supervisor.Start(context.Background(), ProcessSpec{Executable: os.Args[0], Args: []string{"__native-fixture", "tree", firstFile}})
	if err != nil {
		t.Fatal(err)
	}
	second, err := supervisor.Start(context.Background(), ProcessSpec{Executable: os.Args[0], Args: []string{"__native-fixture", "treehold", secondFile}})
	if err != nil {
		t.Fatal(err)
	}
	firstChild := fixturePID(t, firstFile)
	secondChild := fixturePID(t, secondFile)
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	if _, err := first.Wait(ctx); err != nil {
		t.Fatal(err)
	}
	waitWindowsPIDState(t, firstChild, false, 3*time.Second)
	waitWindowsPIDState(t, secondChild, true, time.Second)
	if err := second.Terminate(ctx, time.Second); err != nil {
		t.Fatal(err)
	}
	waitWindowsPIDState(t, secondChild, false, 3*time.Second)
}
