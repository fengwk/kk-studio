package coding

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

// COD-F-007
func TestSyncParentDirectoryReturnsOpenError(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows does not sync directory handles")
	}
	if err := syncParentDirectory(filepath.Join(t.TempDir(), "missing")); err == nil {
		t.Fatal("sync of missing directory succeeded")
	}
}

// COD-F-007
func TestAtomicWriteRejectsDirectoryDestination(t *testing.T) {
	root := t.TempDir()
	destination := filepath.Join(root, "directory")
	if err := os.Mkdir(destination, 0700); err != nil {
		t.Fatal(err)
	}
	if err := atomicWrite(destination, []byte("data"), 0600); err == nil {
		t.Fatal("atomic write replaced a directory")
	}
}

// COD-F-015
func TestOpenTextOutputStoreRejectsSymlinkWorkspaceRoot(t *testing.T) {
	dataDir := t.TempDir()
	tmp := filepath.Join(dataDir, "tmp")
	if err := os.Mkdir(tmp, 0700); err != nil {
		t.Fatal(err)
	}
	target := t.TempDir()
	if err := os.Symlink(target, filepath.Join(tmp, "workspaces")); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	if _, err := OpenTextOutputStore(dataDir); err == nil || !strings.Contains(err.Error(), "not a real directory") {
		t.Fatalf("symlink workspace root error=%v", err)
	}
}

// COD-F-015
func TestOutputStoreHandlesMissingPublishAndEmptyStaging(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err := store.DeleteStaging(""); err != nil {
		t.Fatalf("delete empty staging: %v", err)
	}
	if _, err := store.Publish(filepath.Join(store.Root(), "missing.part")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("publish missing file error=%v", err)
	}
	spool, err := NewOutputSpool(store, "empty", defaultCaptureBudget)
	if err != nil {
		t.Fatal(err)
	}
	spool.Write(nil)
	if spool.TotalBytes() != 0 || spool.totalLines() != 0 {
		t.Fatalf("empty spool bytes=%d lines=%d", spool.TotalBytes(), spool.totalLines())
	}
	result := spool.Finish(false, "note")
	if result.Error || result.Contents[0].Text != "note" {
		t.Fatalf("empty output=%#v", result)
	}
}

// COD-F-015
func TestOutputSpoolBoundsAndAlignsUTF8Helpers(t *testing.T) {
	spool, err := NewOutputSpool(&TextOutputStore{}, "invalid", 1)
	if err != nil {
		t.Fatal(err)
	}
	if err := spool.writeCaptured([]byte("x")); err == nil {
		t.Fatal("write without a staging file succeeded")
	}
	for _, test := range []struct {
		data []byte
		end  int
		want int
	}{
		{nil, 0, 0},
		{[]byte{0x80, 0x80}, 2, 0},
		{[]byte("界"), 2, 0},
		{[]byte("界"), 3, 3},
	} {
		if got := alignUTF8(test.data, test.end); got != test.want {
			t.Fatalf("alignUTF8(%x,%d)=%d want=%d", test.data, test.end, got, test.want)
		}
	}
	if got := utf8Prefix([]byte{0x80, 0x80, 'x'}); got != 2 {
		t.Fatalf("UTF-8 prefix=%d", got)
	}
	if got := utf8Prefix([]byte("text")); got != 0 {
		t.Fatalf("ASCII prefix=%d", got)
	}
}

// COD-F-015
func TestSweepSkipsMalformedCreatedAtAndRecentlyCreatedOutput(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	old := filepath.Join(store.Root(), "11111111-1111-4111-8111-111111111111")
	newer := filepath.Join(store.Root(), "22222222-2222-4222-8222-222222222222")
	for _, workspace := range []string{old, newer} {
		if err := os.Mkdir(workspace, 0700); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(old, "created-at"), []byte("not-time"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(newer, "created-at"), []byte("9999999999"), 0600); err != nil {
		t.Fatal(err)
	}
	if removed, err := store.Sweep(time.Hour); err != nil || removed != 0 {
		t.Fatalf("sweep removed=%d err=%v", removed, err)
	}
}
