//go:build linux || darwin

package native

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

const directoryLockFixtureEnv = "KK_STUDIO_NATIVE_DIRECTORY_LOCK_FIXTURE"
const lockHelperFixtureEnv = "KK_STUDIO_NATIVE_LOCK_HELPER_FIXTURE"

// TestDirectoryLockHolderProcessFixture is re-executed by external-holder tests.
func TestDirectoryLockHolderProcessFixture(t *testing.T) {
	directory := os.Getenv(directoryLockFixtureEnv)
	if directory == "" {
		return
	}
	lock, err := AcquireDirectoryLock(directory, DaemonLockFileName)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if _, err := fmt.Fprintln(os.Stdout, "locked"); err != nil {
		os.Exit(1)
	}
	_, _ = io.Copy(io.Discard, os.Stdin)
	if err := lock.Close(); err != nil {
		os.Exit(1)
	}
	os.Exit(0)
}

// TestLockCommandHolderProcessFixture is re-executed to test kernel lock release after process death.
func TestLockCommandHolderProcessFixture(t *testing.T) {
	directory := os.Getenv(lockHelperFixtureEnv)
	if directory != "" {
		os.Exit(runLockHelper([]string{"hold", directory}, os.Stdin, os.Stdout, os.Stderr))
	}
}

// NAT-T-030 verifies an external process holds the lock against this process.
func TestDirectoryLock_ExternalHolder(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	command, stdin, stdout := startLockProcessFixture(t, "TestDirectoryLockHolderProcessFixture", directoryLockFixtureEnv, directory)
	if _, err := bufio.NewReader(stdout).ReadString('\n'); err != nil {
		t.Fatalf("external holder readiness: %v", err)
	}
	if _, err := AcquireDirectoryLock(directory, DaemonLockFileName); !isLockBusy(err) {
		t.Fatalf("acquire while external holder is live = %v, want busy", err)
	}
	_ = stdin.Close()
	if err := command.Wait(); err != nil {
		t.Fatalf("external holder exit: %v", err)
	}
}

// NAT-T-031 verifies the kernel releases a POSIX directory lock when its owner is killed.
func TestDirectoryLock_KernelReleaseOnCrash(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	command, _, stdout := startLockProcessFixture(t, "TestDirectoryLockHolderProcessFixture", directoryLockFixtureEnv, directory)
	if _, err := bufio.NewReader(stdout).ReadString('\n'); err != nil {
		t.Fatalf("external holder readiness: %v", err)
	}
	if err := command.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	if err := command.Wait(); err == nil {
		t.Fatal("killed holder exited successfully")
	}
	lock, err := AcquireDirectoryLock(directory, DaemonLockFileName)
	if err != nil {
		t.Fatalf("acquire after holder death: %v", err)
	}
	if err := lock.Close(); err != nil {
		t.Fatal(err)
	}
}

// NAT-T-015 verifies the install lock helper's kernel lock is released on process death.
func TestLockCommand_KernelReleaseOnCrash(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	command, _, stdout := startLockProcessFixture(t, "TestLockCommandHolderProcessFixture", lockHelperFixtureEnv, directory)
	if line, err := bufio.NewReader(stdout).ReadString('\n'); err != nil || line != "locked\n" {
		t.Fatalf("lock helper readiness = %q, %v", line, err)
	}
	if _, err := AcquireDirectoryLock(directory, InstallLockFileName); !isLockBusy(err) {
		t.Fatalf("install lock while helper is live = %v, want busy", err)
	}
	if err := command.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	if err := command.Wait(); err == nil {
		t.Fatal("killed lock helper exited successfully")
	}
	lock, err := AcquireDirectoryLock(directory, InstallLockFileName)
	if err != nil {
		t.Fatalf("install lock after helper death: %v", err)
	}
	if err := lock.Close(); err != nil {
		t.Fatal(err)
	}
}

func startLockProcessFixture(t *testing.T, testName, envName, directory string) (*exec.Cmd, io.WriteCloser, io.ReadCloser) {
	t.Helper()
	command := exec.Command(os.Args[0], "-test.run=^"+testName+"$")
	command.Env = append(os.Environ(), envName+"="+directory)
	stdin, err := command.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	stdout, err := command.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	var stderr strings.Builder
	command.Stderr = &stderr
	if err := command.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = stdin.Close()
		if command.ProcessState == nil {
			_ = command.Process.Kill()
			_ = command.Wait()
		}
	})
	return command, stdin, stdout
}
