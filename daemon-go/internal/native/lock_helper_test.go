package native

import (
	"bytes"
	"io"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

type synchronizedBuffer struct {
	mu    sync.Mutex
	value bytes.Buffer
}

func (b *synchronizedBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.value.Write(p)
}
func (b *synchronizedBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.value.String()
}

// NAT-T-013, NAT-T-014
func TestLockCommand_HoldUntilStdinEOF(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	reader, writer := io.Pipe()
	var stdout, stderr synchronizedBuffer
	done := make(chan int, 1)
	go func() { done <- runLockHelper([]string{"hold", dir}, reader, &stdout, &stderr) }()
	deadline := time.Now().Add(time.Second)
	for stdout.String() != "locked\n" && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if stdout.String() != "locked\n" {
		_ = writer.Close()
		t.Fatalf("stdout = %q, stderr = %q", stdout.String(), stderr.String())
	}
	if _, err := AcquireDirectoryLock(dir, InstallLockFileName); !isLockBusy(err) {
		t.Fatalf("lock while held = %v", err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if code := <-done; code != 0 {
		t.Fatalf("hold exit = %d, stderr = %q", code, stderr.String())
	}
	lock, err := AcquireDirectoryLock(dir, InstallLockFileName)
	if err != nil {
		t.Fatal(err)
	}
	if err := lock.Close(); err != nil {
		t.Fatal(err)
	}
}

// NAT-T-019
func TestLockCommand_Usage(t *testing.T) {
	var stdout, stderr bytes.Buffer
	if code := runLockHelper([]string{"invalid"}, bytes.NewReader(nil), &stdout, &stderr); code != 2 {
		t.Fatalf("exit = %d", code)
	}
	if stderr.String() != lockHelperUsage {
		t.Fatalf("usage = %q", stderr.String())
	}
}
