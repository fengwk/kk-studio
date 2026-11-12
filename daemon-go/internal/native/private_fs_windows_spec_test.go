//go:build windows

package native

import (
	"os"
	"path/filepath"
	"testing"
)

// TestWindowsPrivateFS_OwnerOnlyRoundTrip verifies directory and file ACL read-back on Windows.
func TestWindowsPrivateFS_OwnerOnlyRoundTrip(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "private")
	if err := os.Mkdir(directory, 0755); err != nil {
		t.Fatal(err)
	}
	if err := ensurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	if err := verifyPrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(directory, "state")
	if err := writePrivateFile(path, []byte("first")); err != nil {
		t.Fatal(err)
	}
	if err := verifyPrivateFile(path); err != nil {
		t.Fatal(err)
	}
	if err := writePrivateFile(path, []byte("replacement")); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "replacement" {
		t.Fatalf("file content = %q, want replacement", data)
	}
	entries, err := os.ReadDir(directory)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != "state" {
		t.Fatalf("directory entries = %v, want only state", entries)
	}
}

// TestWindowsPrivateFS_CreatesNestedDirectory verifies missing parent directories are created.
func TestWindowsPrivateFS_CreatesNestedDirectory(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "ide", "private")
	if err := ensurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	if err := verifyPrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
}

// TestWindowsPrivateFS_RejectDirectoriesAsFiles checks both read and write file boundaries.
func TestWindowsPrivateFS_RejectDirectoriesAsFiles(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(directory, "target")
	if err := ensurePrivateDirectory(target); err != nil {
		t.Fatal(err)
	}
	if err := verifyPrivateFile(target); err == nil {
		t.Fatal("verifyPrivateFile accepted a directory")
	}
	if err := writePrivateFile(target, []byte("secret")); err == nil {
		t.Fatal("writePrivateFile accepted a directory target")
	}
}

// TestWindowsDirectoryLock_ExclusiveAndStaleFile verifies kernel locking rather than file existence.
func TestWindowsDirectoryLock_ExclusiveAndStaleFile(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "locks")
	first, err := AcquireDirectoryLock(directory, DaemonLockFileName)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := AcquireDirectoryLock(directory, DaemonLockFileName); err == nil {
		t.Fatal("second acquisition succeeded while the first lock was held")
	}
	if err := first.Close(); err != nil {
		t.Fatal(err)
	}
	second, err := AcquireDirectoryLock(directory, DaemonLockFileName)
	if err != nil {
		t.Fatalf("stale lock file blocked a new acquisition: %v", err)
	}
	if err := second.Close(); err != nil {
		t.Fatal(err)
	}
}
