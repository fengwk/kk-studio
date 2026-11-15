//go:build !windows

package coding

import (
	"os"
	"path/filepath"
	"syscall"
	"testing"
)

// A new file is created with 0644, so the umask applies.
func TestWriteCreatesNewFileWithMode(t *testing.T) {
	previous := syscall.Umask(0o022)
	defer syscall.Umask(previous)
	path := filepath.Join(t.TempDir(), "new.txt")
	if _, err := Write(path, "created\n", nil); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o644 {
		t.Fatalf("mode=%#o", info.Mode().Perm())
	}
}
