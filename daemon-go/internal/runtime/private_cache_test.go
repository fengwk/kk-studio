package runtime

import (
	"context"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

// RUN-T-199 writes a complete private cache with owner-only permissions and atomically replaces prior contents.
func TestFilePrivateCacheWriterWritesPrivateAtomicFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "daemon.json")
	writer := FilePrivateCacheWriter{}
	for _, value := range [][]byte{[]byte(`{"version":"1"}`), []byte(`{"version":"2"}`)} {
		if err := writer.WritePrivateAtomic(context.Background(), path, value); err != nil {
			t.Fatal(err)
		}
		actual, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		if string(actual) != string(value) {
			t.Fatalf("cache content = %s, want %s", actual, value)
		}
		info, err := os.Lstat(path)
		if err != nil {
			t.Fatal(err)
		}
		if !info.Mode().IsRegular() || runtime.GOOS != "windows" && info.Mode().Perm() != 0o600 {
			t.Fatalf("cache file mode/type = %v, want a regular private file", info.Mode())
		}
	}
}

// RUN-T-199 refuses a symbolic-link target instead of following or replacing it.
func TestFilePrivateCacheWriterRejectsSymlink(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "target")
	link := filepath.Join(dir, "link")
	if err := os.WriteFile(target, []byte("unchanged"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("symlink creation is unavailable: %v", err)
	}
	if err := (FilePrivateCacheWriter{}).WritePrivateAtomic(context.Background(), link, []byte("replacement")); err == nil {
		t.Fatal("symlink cache path was accepted")
	}
	actual, err := os.ReadFile(target)
	if err != nil || string(actual) != "unchanged" {
		t.Fatalf("symlink target changed: %q, %v", actual, err)
	}
}
