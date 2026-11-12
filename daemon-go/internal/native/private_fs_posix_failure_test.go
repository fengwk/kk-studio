//go:build linux || darwin

package native

import (
	"os"
	"path/filepath"
	"testing"
)

type privateFileInfoWithoutStat struct{ os.FileInfo }

func (privateFileInfoWithoutStat) Sys() any { return nil }

// NAT-F-013 verifies POSIX private-file helpers reject unverifiable paths and metadata.
func TestPrivateFS_POSIXFailurePaths(t *testing.T) {
	root := t.TempDir()
	filePath := filepath.Join(root, "regular-file")
	if err := os.WriteFile(filePath, nil, 0600); err != nil {
		t.Fatal(err)
	}
	if err := ensurePrivateDirectory(filepath.Join(filePath, "child")); err == nil {
		t.Fatal("non-directory parent was accepted")
	}
	if err := verifyPrivateDirectory(filePath); err == nil {
		t.Fatal("regular file verified as a private directory")
	}

	looseDirectory := filepath.Join(root, "loose")
	if err := os.Mkdir(looseDirectory, 0755); err != nil {
		t.Fatal(err)
	}
	if err := verifyPrivateDirectory(looseDirectory); err == nil {
		t.Fatal("group/world-accessible directory verified")
	}

	privateDirectory := filepath.Join(root, "private")
	if err := ensurePrivateDirectory(privateDirectory); err != nil {
		t.Fatal(err)
	}
	if err := createPrivateFile(filepath.Join(privateDirectory, "state")); err != nil {
		t.Fatal(err)
	}
	if err := createPrivateFile(filepath.Join(privateDirectory, "state")); err == nil {
		t.Fatal("existing private file was overwritten")
	}
	if err := createPrivateFile(filepath.Join(root, "missing", "state")); err == nil {
		t.Fatal("private file was created under a missing directory")
	}
	if err := writePrivateFile(filepath.Join(root, "missing", "state"), []byte("unsafe")); err == nil {
		t.Fatal("private file was written under a missing directory")
	}

	info, err := os.Stat(privateDirectory)
	if err != nil {
		t.Fatal(err)
	}
	if ownedByCurrentUser(privateFileInfoWithoutStat{FileInfo: info}) {
		t.Fatal("metadata without syscall identity was treated as owned")
	}
}
