//go:build darwin

package native

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"syscall"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

const macEscapedChildPIDFile = "KK_STUDIO_NATIVE_MAC_ESCAPED_CHILD_PID_FILE"

// TestMacUntrackedEscapeFixture is re-executed as the supervised command.
func TestMacUntrackedEscapeFixture(t *testing.T) {
	pidFile := os.Getenv(macEscapedChildPIDFile)
	if pidFile == "" {
		return
	}
	child := exec.Command("/bin/sleep", "30")
	child.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := child.Start(); err != nil {
		t.Fatalf("start escaped child: %v", err)
	}
	if err := os.WriteFile(pidFile, []byte(strconv.Itoa(child.Process.Pid)), 0600); err != nil {
		_ = child.Process.Kill()
		_ = child.Wait()
		t.Fatal(err)
	}
	_ = child.Wait()
}

// NAT-T-124 verifies an unregistered child that leaves the managed session remains an explicit boundary.
func TestMacSupervisor_UntrackedEscapeBoundary(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	pidFile := filepath.Join(t.TempDir(), "escaped.pid")
	process, err := supervisor.Start(context.Background(), ProcessSpec{
		Executable: os.Args[0],
		Args:       []string{"-test.run=^TestMacUntrackedEscapeFixture$"},
		Env:        map[string]string{macEscapedChildPIDFile: pidFile},
	})
	if err != nil {
		t.Fatal(err)
	}
	escapedPID := waitPIDFile(t, pidFile)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := process.Terminate(ctx, 10*time.Millisecond); err != nil {
		t.Fatalf("terminate managed session: %v", err)
	}
	if err := unix.Kill(escapedPID, 0); err != nil {
		t.Fatalf("untracked escaped child %d did not remain alive: %v", escapedPID, err)
	}
	if err := unix.Kill(escapedPID, syscall.SIGKILL); err != nil {
		t.Fatalf("clean up escaped child %d: %v", escapedPID, err)
	}
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		if err := unix.Kill(escapedPID, 0); err == unix.ESRCH {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("escaped child %d survived cleanup", escapedPID)
}
