package native

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"
)

// NAT-T-022..026
func TestDirectoryLock_ExclusiveAndClose(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	first, err := AcquireDirectoryLock(dir, DaemonLockFileName)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := AcquireDirectoryLock(dir, DaemonLockFileName); !errors.Is(err, ErrDirectoryLockBusy) {
		t.Fatalf("second acquire error = %v", err)
	}
	other, err := AcquireDirectoryLock(dir, InstallLockFileName)
	if err != nil {
		t.Fatalf("independent lock failed: %v", err)
	}
	if err := other.Close(); err != nil {
		t.Fatal(err)
	}
	if err := first.Close(); err != nil {
		t.Fatal(err)
	}
	if err := first.Close(); err != nil {
		t.Fatalf("second close failed: %v", err)
	}
}

// NAT-T-024
func TestDirectoryLock_WaitRelease(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	lock, err := AcquireDirectoryLock(dir, DaemonLockFileName)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	go func() { time.Sleep(20 * time.Millisecond); _ = lock.Close() }()
	if err := WaitForDirectoryLock(ctx, dir, DaemonLockFileName, time.Second); err != nil {
		t.Fatal(err)
	}
}

// NAT-T-017
func TestDirectoryLock_WaitTimeout(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	lock, err := AcquireDirectoryLock(dir, DaemonLockFileName)
	if err != nil {
		t.Fatal(err)
	}
	defer lock.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := WaitForDirectoryLock(ctx, dir, DaemonLockFileName, 15*time.Millisecond); !errors.Is(err, ErrDirectoryLockTimeout) {
		t.Fatalf("wait error = %v", err)
	}
}

// NAT-N-006 verifies the Go lock API applies only the timeout supplied by its caller.
func TestDirectoryLockWait_RequiresExplicitTimeout(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	lock, err := AcquireDirectoryLock(dir, DaemonLockFileName)
	if err != nil {
		t.Fatal(err)
	}
	defer lock.Close()
	if err := WaitForDirectoryLock(context.Background(), dir, DaemonLockFileName, 0); !errors.Is(err, ErrDirectoryLockTimeout) {
		t.Fatalf("explicit zero-timeout wait = %v, want timeout", err)
	}
}

// NAT-T-027
func TestDirectoryLock_RejectSymlink(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("symlink creation requires elevated Windows privileges")
	}
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(dir, "target"), filepath.Join(dir, DaemonLockFileName)); err != nil {
		t.Fatal(err)
	}
	if _, err := AcquireDirectoryLock(dir, DaemonLockFileName); err == nil {
		t.Fatal("symlink lock accepted")
	}
}

// NAT-T-025
func TestDirectoryLock_CloseIdempotent(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	lock, err := AcquireDirectoryLock(dir, DaemonLockFileName)
	if err != nil {
		t.Fatal(err)
	}
	if err := lock.Close(); err != nil {
		t.Fatal(err)
	}
	if err := lock.Close(); err != nil {
		t.Fatal(err)
	}
}
