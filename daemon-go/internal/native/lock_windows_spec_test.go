//go:build windows

package native

import (
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

// NAT-T-215 proves Windows kernel lock release when the process owning another handle is killed.
func TestWindowsDirectoryLock_ExternalHolderCrash(t *testing.T) {
	if os.Getenv("KK_NATIVE_LOCK_HOLDER_DIR") != "" {
		directory := os.Getenv("KK_NATIVE_LOCK_HOLDER_DIR")
		lock, err := AcquireDirectoryLock(directory, DaemonLockFileName)
		if err != nil {
			t.Fatal(err)
		}
		defer lock.Close()
		if err := os.WriteFile(os.Getenv("KK_NATIVE_LOCK_HOLDER_READY"), []byte("ready"), 0600); err != nil {
			t.Fatal(err)
		}
		for {
			time.Sleep(time.Hour)
		}
	}

	directory := t.TempDir()
	ready := filepath.Join(directory, "holder.ready")
	cmd := exec.Command(os.Args[0], "-test.run=^TestWindowsDirectoryLock_ExternalHolderCrash$")
	cmd.Env = append(os.Environ(), "KK_NATIVE_LOCK_HOLDER_DIR="+directory, "KK_NATIVE_LOCK_HOLDER_READY="+ready)
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
	}()
	deadline := time.Now().Add(10 * time.Second)
	for {
		if _, err := os.Stat(ready); err == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("external lock holder did not become ready")
		}
		time.Sleep(10 * time.Millisecond)
	}
	if _, err := AcquireDirectoryLock(directory, DaemonLockFileName); err == nil {
		t.Fatal("second process acquired a lock held by the external process")
	}
	if err := cmd.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	if err := cmd.Wait(); err != nil {
		if exitErr, ok := err.(*exec.ExitError); !ok || !exitErr.Exited() {
			t.Fatalf("wait after killing lock holder: %v", err)
		}
	}
	lock, err := AcquireDirectoryLock(directory, DaemonLockFileName)
	if err != nil {
		t.Fatalf("kernel did not release external holder lock: %v", err)
	}
	if err := lock.Close(); err != nil {
		t.Fatal(err)
	}
}
