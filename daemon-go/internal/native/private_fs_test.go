package native

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

// NAT-T-096
func TestPrivateFS_CreatePrivateDirectory(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	if err := verifyPrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" {
		info, err := os.Stat(dir)
		if err != nil {
			t.Fatal(err)
		}
		if info.Mode().Perm() != 0700 {
			t.Fatalf("mode = %o", info.Mode().Perm())
		}
	}
}

// NAT-T-097
func TestPrivateFS_TightenExistingDirectory(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	if err := os.Mkdir(dir, 0755); err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS == "windows" {
		t.Skip("Windows private ACLs are not represented by POSIX mode bits")
	}
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(dir)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0700 {
		t.Fatalf("mode = %o", info.Mode().Perm())
	}
}

// NAT-T-098
func TestPrivateFS_RejectDirectorySymlink(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("symlink creation requires elevated Windows privileges")
	}
	dir := t.TempDir()
	target := filepath.Join(dir, "target")
	if err := os.Mkdir(target, 0700); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(dir, "link")
	if err := os.Symlink(target, link); err != nil {
		t.Fatal(err)
	}
	if err := ensurePrivateDirectory(link); err == nil {
		t.Fatal("directory symlink accepted")
	}
}

// NAT-T-099, NAT-T-103, NAT-T-106
func TestPrivateFS_WritePrivateFile(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "state")
	if err := writePrivateFile(path, []byte{0, 1, 255}); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string([]byte{0, 1, 255}) {
		t.Fatalf("payload = %v", got)
	}
	if err := verifyPrivateFile(path); err != nil {
		t.Fatal(err)
	}
	if err := writePrivateFile(path, []byte("replacement")); err != nil {
		t.Fatal(err)
	}
	got, err = os.ReadFile(path)
	if err != nil || string(got) != "replacement" {
		t.Fatalf("replacement = %q, %v", got, err)
	}
}

// NAT-T-100
func TestPrivateFS_RejectLooseFileMode(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows permissions use DACLs")
	}
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "loose")
	if err := os.WriteFile(path, nil, 0644); err != nil {
		t.Fatal(err)
	}
	if err := verifyPrivateFile(path); err == nil {
		t.Fatal("loose file accepted")
	}
}

// NAT-T-102 verifies private-file validation rejects a symlink.
func TestPrivateFS_VerifyRejectsSymlink(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("symlink creation requires elevated Windows privileges")
	}
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(dir, "target")
	if err := os.WriteFile(target, nil, 0600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(dir, "link")
	if err := os.Symlink(target, link); err != nil {
		t.Fatal(err)
	}
	if err := verifyPrivateFile(link); err == nil {
		t.Fatal("symlink verified as a private file")
	}
}

// NAT-T-101
func TestPrivateFS_RejectSymlinkTarget(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("symlink creation requires elevated Windows privileges")
	}
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(dir, "link")
	if err := os.Symlink(filepath.Join(dir, "target"), link); err != nil {
		t.Fatal(err)
	}
	if err := writePrivateFile(link, []byte("unsafe")); err == nil {
		t.Fatal("symlink target accepted")
	}
}

// NAT-T-107 verifies private-file publication rejects a directory target.
func TestPrivateFS_RejectDirectoryTarget(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(dir, "target")
	if err := os.Mkdir(target, 0700); err != nil {
		t.Fatal(err)
	}
	if err := writePrivateFile(target, []byte("unsafe")); err == nil {
		t.Fatal("directory target accepted as a private file")
	}
}

// NAT-T-105
func TestPrivateFS_RejectFileAsDirectory(t *testing.T) {
	path := filepath.Join(t.TempDir(), "file")
	if err := os.WriteFile(path, nil, 0600); err != nil {
		t.Fatal(err)
	}
	if err := ensurePrivateDirectory(path); err == nil {
		t.Fatal("regular file accepted as directory")
	}
}

// NAT-T-104
func TestPrivateFS_MissingFile(t *testing.T) {
	if err := verifyPrivateFile(filepath.Join(t.TempDir(), "missing")); err == nil {
		t.Fatal("missing file verified")
	}
}
