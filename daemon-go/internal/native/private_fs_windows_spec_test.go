//go:build windows

package native

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"golang.org/x/sys/windows"
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
	ownerBefore, err := windowsPrivatePathOwner(directory)
	if err != nil {
		t.Fatal(err)
	}
	if err := ensurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	ownerAfter, err := windowsPrivatePathOwner(directory)
	if err != nil {
		t.Fatal(err)
	}
	if !ownerBefore.Equals(ownerAfter) {
		t.Fatalf("existing directory owner changed from %s to %s", ownerBefore, ownerAfter)
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

func windowsPrivatePathOwner(path string) (*windows.SID, error) {
	descriptor, err := windows.GetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.OWNER_SECURITY_INFORMATION)
	if err != nil {
		return nil, err
	}
	owner, _, err := descriptor.Owner()
	return owner, err
}

// TestWindowsPrivateFS_CreatesNestedDirectory verifies missing parents are also protected.
func TestWindowsPrivateFS_CreatesNestedDirectory(t *testing.T) {
	root := t.TempDir()
	directory := filepath.Join(root, "ide", "private")
	if err := ensurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	if err := verifyPrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	if err := verifyPrivateDirectory(filepath.Dir(directory)); err != nil {
		t.Fatalf("created parent directory is not private: %v", err)
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

type fakeWindowsPrivatePathAPI struct {
	attrs     uint32
	user      *windows.SID
	owner     *windows.SID
	dacl      *windows.ACL
	protected bool
}

func (api fakeWindowsPrivatePathAPI) GetFileAttributes(string) (uint32, error) { return api.attrs, nil }
func (api fakeWindowsPrivatePathAPI) CurrentUserSID() (*windows.SID, error)    { return api.user, nil }
func (api fakeWindowsPrivatePathAPI) Security(string) (*windows.SID, *windows.ACL, bool, error) {
	return api.owner, api.dacl, api.protected, nil
}

// NAT-T-202 verifies a foreign owner is rejected by the same owner comparison used on real Windows paths.
func TestWindowsPrivateFS_RejectsForeignOwner(t *testing.T) {
	user, err := windows.StringToSid("S-1-5-18")
	if err != nil {
		t.Fatal(err)
	}
	owner, err := windows.StringToSid("S-1-5-19")
	if err != nil {
		t.Fatal(err)
	}
	aclSD, err := windows.SecurityDescriptorFromString("D:P(A;;FA;;;S-1-5-18)")
	if err != nil {
		t.Fatal(err)
	}
	dacl, _, err := aclSD.DACL()
	if err != nil {
		t.Fatal(err)
	}
	api := fakeWindowsPrivatePathAPI{attrs: windows.FILE_ATTRIBUTE_NORMAL, user: user, owner: owner, dacl: dacl, protected: true}
	if err := verifyWindowsOwnerOnlyWithAPI("foreign", api); err == nil || !strings.Contains(err.Error(), "not owned by the current user") {
		t.Fatalf("foreign owner verification = %v", err)
	}
}

func setWindowsTestLooseDACL(t *testing.T, path string) {
	t.Helper()
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		t.Fatal(err)
	}
	sddl := "D:P(A;;FA;;;" + user.User.Sid.String() + ")(A;;FR;;;WD)"
	descriptor, err := windows.SecurityDescriptorFromString(sddl)
	if err != nil {
		t.Fatal(err)
	}
	dacl, _, err := descriptor.DACL()
	if err != nil {
		t.Fatal(err)
	}
	if err := windows.SetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, dacl, nil); err != nil {
		t.Fatal(err)
	}
}

// NAT-T-207..208 verify foreign ACEs are rejected before a private file can be replaced.
func TestWindowsPrivateFS_RejectsForeignACEWithoutOverwriting(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(directory, "secret")
	if err := writePrivateFile(path, []byte("original")); err != nil {
		t.Fatal(err)
	}
	setWindowsTestLooseDACL(t, path)
	if err := verifyPrivateFile(path); err == nil {
		t.Fatal("verifyPrivateFile accepted a file with an Everyone read ACE")
	}
	if err := writePrivateFile(path, []byte("replacement")); err == nil {
		t.Fatal("writePrivateFile overwrote a file with a foreign ACE")
	}
	data, err := os.ReadFile(path)
	if err != nil || string(data) != "original" {
		t.Fatalf("existing file after rejection = %q, %v", data, err)
	}
}

// NAT-T-213 verifies an owner-only file cannot be published below a directory with a foreign ACE.
func TestWindowsPrivateFS_RejectsLooseParent(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	setWindowsTestLooseDACL(t, directory)
	if err := writePrivateFile(filepath.Join(directory, "secret"), []byte("secret")); err == nil {
		t.Fatal("writePrivateFile accepted a parent directory with a foreign ACE")
	}
}

// NAT-T-203 verifies concurrent creation accepts the winning owner-only directory and rejects other objects.
func TestWindowsPrivateFS_ConcurrentDirectoryCreation(t *testing.T) {
	path := filepath.Join(t.TempDir(), "raced")
	var wait sync.WaitGroup
	errs := make(chan error, 8)
	for range 8 {
		wait.Add(1)
		go func() {
			defer wait.Done()
			errs <- ensurePrivateDirectory(path)
		}()
	}
	wait.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatalf("concurrent ensurePrivateDirectory failed: %v", err)
		}
	}
	file := filepath.Join(t.TempDir(), "not-a-directory")
	if err := os.WriteFile(file, []byte("x"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := ensurePrivateDirectory(file); err == nil {
		t.Fatal("ensurePrivateDirectory accepted a regular file")
	}
}

// NAT-T-210 verifies an actual Windows directory junction is rejected as a reparse-point lock path.
func TestWindowsPrivateFS_RejectsReparseLock(t *testing.T) {
	base := t.TempDir()
	lockDir := filepath.Join(base, "locks")
	target := filepath.Join(base, "target")
	if err := os.Mkdir(lockDir, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(target, 0700); err != nil {
		t.Fatal(err)
	}
	junction := filepath.Join(lockDir, DaemonLockFileName)
	if output, err := exec.Command("cmd", "/c", "mklink", "/J", junction, target).CombinedOutput(); err != nil {
		t.Fatalf("create junction: %v (%s)", err, output)
	}
	if _, err := AcquireDirectoryLock(lockDir, DaemonLockFileName); err == nil {
		t.Fatal("junction lock acquisition succeeded")
	}
}

type zeroProgressWindowsTempFile struct{ windowsPrivateTempFile }

func (zeroProgressWindowsTempFile) Write([]byte) (int, error) { return 0, nil }

type zeroProgressWindowsPrivateFileAPI struct {
	nativeWindowsPrivateFileAPI
	renamed bool
	removed bool
}

func (api *zeroProgressWindowsPrivateFileAPI) OpenTemp(directory, name string) (windowsPrivateTempFile, error) {
	file, err := api.nativeWindowsPrivateFileAPI.OpenTemp(directory, name)
	if err != nil {
		return nil, err
	}
	return zeroProgressWindowsTempFile{windowsPrivateTempFile: file}, nil
}
func (api *zeroProgressWindowsPrivateFileAPI) Rename(oldPath, newPath string) error {
	api.renamed = true
	return api.nativeWindowsPrivateFileAPI.Rename(oldPath, newPath)
}
func (api *zeroProgressWindowsPrivateFileAPI) Remove(path string) error {
	api.removed = true
	return api.nativeWindowsPrivateFileAPI.Remove(path)
}

// NAT-T-197 ensures a short write aborts the private-file transaction without replacing the old target.
func TestWindowsPrivateFS_ZeroWriteDoesNotRename(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(directory, "secret")
	if err := writePrivateFile(path, []byte("original")); err != nil {
		t.Fatal(err)
	}
	api := &zeroProgressWindowsPrivateFileAPI{}
	if err := writePrivateFileTransaction(path, []byte("replacement"), api); err == nil {
		t.Fatal("zero-progress write succeeded")
	}
	data, err := os.ReadFile(path)
	if err != nil || string(data) != "original" {
		t.Fatalf("old target after short write = %q, %v", data, err)
	}
	entries, err := os.ReadDir(directory)
	if err != nil {
		t.Fatal(err)
	}
	if api.renamed || !api.removed || len(entries) != 1 || entries[0].Name() != "secret" {
		t.Fatalf("transaction rename/remove/entries = %t/%t/%v", api.renamed, api.removed, entries)
	}
}

// NAT-T-204 verifies an existing foreign-ACE lock file is rejected without rewriting its bytes.
func TestWindowsDirectoryLock_RejectsForeignACEWithoutRewrite(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "locks")
	if err := ensurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(directory, DaemonLockFileName)
	if err := os.WriteFile(path, []byte("original"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := setWindowsOwnerOnly(path); err != nil {
		t.Fatal(err)
	}
	setWindowsTestLooseDACL(t, path)
	if _, err := AcquireDirectoryLock(directory, DaemonLockFileName); err == nil {
		t.Fatal("lock acquisition accepted a file with a foreign ACE")
	}
	data, err := os.ReadFile(path)
	if err != nil || string(data) != "original" {
		t.Fatalf("foreign lock file after rejection = %q, %v", data, err)
	}
}
