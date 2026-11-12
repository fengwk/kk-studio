package native

import (
	"bytes"
	"io"
	"os"
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

// NAT-T-014 verifies hold reports the prescribed busy status and message.
func TestLockCommand_HoldBusy(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	lock, err := AcquireDirectoryLock(dir, InstallLockFileName)
	if err != nil {
		t.Fatal(err)
	}
	defer lock.Close()
	var stdout, stderr bytes.Buffer
	if code := runLockHelper([]string{"hold", dir}, bytes.NewReader(nil), &stdout, &stderr); code != 75 {
		t.Fatalf("hold exit = %d", code)
	}
	if stderr.String() != "install operation lock is busy\n" || stdout.Len() != 0 {
		t.Fatalf("hold output = stdout %q, stderr %q", stdout.String(), stderr.String())
	}
}

// NAT-T-016..018 verify wait success, timeout, and lock inspection failure are distinct.
func TestLockCommand_WaitResults(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	invalidDir := filepath.Join(t.TempDir(), "not-a-directory")
	if err := os.WriteFile(invalidDir, nil, 0600); err != nil {
		t.Fatal(err)
	}
	for _, testCase := range []struct {
		name       string
		directory  string
		timeout    string
		locked     bool
		wantCode   int
		wantStderr string
	}{
		{name: "released", directory: dir, timeout: "1"},
		{name: "timeout", directory: dir, timeout: "0", locked: true, wantCode: 76, wantStderr: "daemon runtime lock wait timed out\n"},
		{name: "inspection failure", directory: invalidDir, timeout: "1", wantCode: 77, wantStderr: "cannot inspect the daemon runtime lock\n"},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			var lock DirectoryLock
			if testCase.locked {
				var err error
				lock, err = AcquireDirectoryLock(dir, DaemonLockFileName)
				if err != nil {
					t.Fatal(err)
				}
				defer lock.Close()
			}
			var stdout, stderr bytes.Buffer
			code := runLockHelper([]string{"wait", testCase.directory, testCase.timeout}, bytes.NewReader(nil), &stdout, &stderr)
			if code != testCase.wantCode || stderr.String() != testCase.wantStderr {
				t.Fatalf("wait result = %d, stdout %q, stderr %q", code, stdout.String(), stderr.String())
			}
		})
	}
}

// NAT-T-019 verifies malformed helper commands share the fixed usage error.
func TestLockCommand_Usage(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	for _, testCase := range []struct {
		name string
		args []string
	}{
		{name: "missing arguments"},
		{name: "unknown command", args: []string{"invalid", dir}},
		{name: "relative directory", args: []string{"hold", "relative"}},
		{name: "extra hold argument", args: []string{"hold", dir, "extra"}},
		{name: "malformed timeout", args: []string{"wait", dir, "-1"}},
		{name: "excessive timeout", args: []string{"wait", dir, "3601"}},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			var stdout, stderr bytes.Buffer
			if code := runLockHelper(testCase.args, bytes.NewReader(nil), &stdout, &stderr); code != 2 {
				t.Fatalf("exit = %d", code)
			}
			if stderr.String() != lockHelperUsage || stdout.Len() != 0 {
				t.Fatalf("usage output = stdout %q, stderr %q", stdout.String(), stderr.String())
			}
		})
	}

	originalStderr := os.Stderr
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	os.Stderr = writer
	defer func() { os.Stderr = originalStderr }()
	if code := LockHelperMain(nil); code != 2 {
		t.Fatalf("LockHelperMain usage exit = %d", code)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	usage, err := io.ReadAll(reader)
	_ = reader.Close()
	if err != nil || string(usage) != lockHelperUsage {
		t.Fatalf("LockHelperMain usage = %q, %v", usage, err)
	}
}
