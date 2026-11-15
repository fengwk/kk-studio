//go:build linux || darwin

package native

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestStateRejectsUntrustedFiles(t *testing.T) {
	dir := stateTestDir(t)
	looseState := filepath.Join(dir, "loose-state")
	if err := os.WriteFile(looseState, []byte("state"), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := readState(dir, "loose-state"); err == nil {
		t.Fatal("readState accepted a non-private file")
	}

	looseDir := filepath.Join(t.TempDir(), "loose-dir")
	if err := os.Mkdir(looseDir, 0755); err != nil {
		t.Fatal(err)
	}
	if _, err := readState(looseDir, "missing"); err == nil {
		t.Fatal("readState accepted a non-private directory")
	}

	launchLink := filepath.Join(dir, launchFile)
	target := filepath.Join(dir, "launch-target")
	if err := os.WriteFile(target, []byte(`{"cwd":"/tmp","args":["true"]}`), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(target, launchLink); err != nil {
		t.Fatal(err)
	}
	if _, err := readLaunch(dir); err == nil {
		t.Fatal("readLaunch accepted a symbolic link")
	}
}

func TestPrivateFSRejectsNonRegularAndInvalidParent(t *testing.T) {
	dir := stateTestDir(t)
	if err := verifyPrivateFile(dir); err == nil {
		t.Fatal("directory verified as a private file")
	}
	parent := filepath.Join(t.TempDir(), "not-a-directory")
	if err := os.WriteFile(parent, nil, 0600); err != nil {
		t.Fatal(err)
	}
	if err := writePrivateFile(filepath.Join(parent, "state"), []byte("x")); err == nil {
		t.Fatal("file with non-directory parent accepted")
	}
	if err := ensurePrivateDirectory(filepath.Join(parent, "child")); err == nil {
		t.Fatal("directory with non-directory parent accepted")
	}
}

func TestDirectoryLockRejectsInvalidFilesystemStates(t *testing.T) {
	parent := filepath.Join(t.TempDir(), "not-a-directory")
	if err := os.WriteFile(parent, nil, 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := AcquireDirectoryLock(parent, DaemonLockFileName); err == nil {
		t.Fatal("lock path under non-directory was accepted")
	}
	if err := WaitForDirectoryLock(context.Background(), t.TempDir(), "bad.lock", time.Second); err == nil {
		t.Fatal("invalid lock name was accepted by wait")
	}

	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	lockPath := filepath.Join(dir, DaemonLockFileName)
	if err := os.WriteFile(lockPath, nil, 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := AcquireDirectoryLock(dir, DaemonLockFileName); err == nil {
		t.Fatal("lock accepted an existing file with loose permissions")
	}
}

func TestDirectoryLockPreservesSingleCloseFailure(t *testing.T) {
	closeErr := errors.New("handle close failed")
	handle := &fakeDirectoryLockHandle{closeErr: closeErr}
	lock := &directoryLock{handle: handle}
	if err := lock.Close(); !errors.Is(err, closeErr) {
		t.Fatalf("Close error = %v, want close failure", err)
	}
	if handle.unlockCalls != 1 || handle.closeCalls != 1 {
		t.Fatalf("unlock/close calls = %d/%d, want one each", handle.unlockCalls, handle.closeCalls)
	}
}

func TestUnixLockHandleRejectsMissingPath(t *testing.T) {
	if _, err := openPlatformLockHandle(filepath.Join(t.TempDir(), "missing.lock")); err == nil {
		t.Fatal("missing lock file opened successfully")
	}
}

func TestSessionMembersFiltersByCurrentSession(t *testing.T) {
	all, err := allProcessMembers()
	if err != nil {
		t.Fatal(err)
	}
	var own sessionMember
	for _, member := range all {
		if member.PID == os.Getpid() {
			own = member
			break
		}
	}
	if own.PID == 0 {
		t.Fatal("current process was absent from process snapshot")
	}
	members, err := sessionMembers(own.Session)
	if err != nil {
		t.Fatal(err)
	}
	foundSelf := false
	for _, member := range members {
		if member.Session != own.Session {
			t.Fatalf("sessionMembers returned unrelated session member %+v", member)
		}
		foundSelf = foundSelf || member.PID == own.PID
	}
	if !foundSelf {
		t.Fatalf("sessionMembers omitted current process %d", own.PID)
	}
}
