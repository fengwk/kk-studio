package native

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"
)

type zeroProgressPrivateTemp struct {
	file *os.File
}

func (temp zeroProgressPrivateTemp) Write([]byte) (int, error) { return 0, nil }
func (temp zeroProgressPrivateTemp) Sync() error               { return temp.file.Sync() }
func (temp zeroProgressPrivateTemp) Close() error              { return temp.file.Close() }
func (temp zeroProgressPrivateTemp) Name() string              { return temp.file.Name() }

type controlledPrivateTemp struct {
	file     *os.File
	syncErr  error
	closeErr error
}

func (temp *controlledPrivateTemp) Write(data []byte) (int, error) { return temp.file.Write(data) }
func (temp *controlledPrivateTemp) Sync() error {
	if temp.syncErr != nil {
		return temp.syncErr
	}
	return temp.file.Sync()
}
func (temp *controlledPrivateTemp) Close() error {
	err := temp.file.Close()
	if temp.closeErr != nil {
		return temp.closeErr
	}
	return err
}
func (temp *controlledPrivateTemp) Name() string { return temp.file.Name() }

// A no-progress write must roll back the temporary file without replacing prior state.
func TestPublishPrivateFileTransactionRollsBackOnWriteFailure(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	if err := EnsurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(dir, "state")
	if err := os.WriteFile(target, []byte("old"), 0600); err != nil {
		t.Fatal(err)
	}
	tmpPath := filepath.Join(dir, ".state.partial.tmp")
	file, err := os.OpenFile(tmpPath, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	renamed := false
	err = publishPrivateFileTransaction(zeroProgressPrivateTemp{file: file}, target, []byte("new"), func(string, string) error {
		renamed = true
		return os.Rename(tmpPath, target)
	}, os.Remove, verifyPrivateFile)
	if !errors.Is(err, io.ErrShortWrite) {
		t.Fatalf("transaction error = %v, want %v", err, io.ErrShortWrite)
	}
	if renamed {
		t.Fatal("failed write attempted to rename the temporary file")
	}
	got, err := os.ReadFile(target)
	if err != nil || string(got) != "old" {
		t.Fatalf("existing target = %q, %v; want old contents", got, err)
	}
	if _, err := os.Lstat(tmpPath); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("temporary file remains after rollback: %v", err)
	}
}

func TestPublishPrivateFileTransactionRollsBackOnSyncCloseAndRenameFailures(t *testing.T) {
	injected := errors.New("injected transaction failure")
	for _, test := range []struct {
		name      string
		syncErr   error
		closeErr  error
		renameErr error
	}{
		{name: "sync", syncErr: injected},
		{name: "close", closeErr: injected},
		{name: "rename", renameErr: injected},
	} {
		t.Run(test.name, func(t *testing.T) {
			dir := filepath.Join(t.TempDir(), "private")
			if err := EnsurePrivateDirectory(dir); err != nil {
				t.Fatal(err)
			}
			target := filepath.Join(dir, "state")
			if err := os.WriteFile(target, []byte("old"), 0600); err != nil {
				t.Fatal(err)
			}
			tmpPath := filepath.Join(dir, ".state.partial.tmp")
			file, err := os.OpenFile(tmpPath, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
			if err != nil {
				t.Fatal(err)
			}
			temp := &controlledPrivateTemp{file: file, syncErr: test.syncErr, closeErr: test.closeErr}
			rename := func(source, destination string) error {
				if test.renameErr != nil {
					return test.renameErr
				}
				return os.Rename(source, destination)
			}
			err = publishPrivateFileTransaction(temp, target, []byte("new"), rename, os.Remove, verifyPrivateFile)
			if !errors.Is(err, injected) {
				t.Fatalf("transaction error = %v, want injected failure", err)
			}
			got, err := os.ReadFile(target)
			if err != nil || string(got) != "old" {
				t.Fatalf("existing target = %q, %v; want old contents", got, err)
			}
			if _, err := os.Lstat(tmpPath); !errors.Is(err, os.ErrNotExist) {
				t.Fatalf("temporary file remains after rollback: %v", err)
			}
		})
	}
}

func TestPublishPrivateFileTransactionReturnsVerificationFailureAfterRename(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	if err := EnsurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(dir, "state")
	tmpPath := filepath.Join(dir, ".state.partial.tmp")
	file, err := os.OpenFile(tmpPath, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	verifyErr := errors.New("verification failed")
	err = publishPrivateFileTransaction(&controlledPrivateTemp{file: file}, target, []byte("new"), os.Rename, os.Remove, func(string) error {
		return verifyErr
	})
	if !errors.Is(err, verifyErr) {
		t.Fatalf("transaction error = %v, want verification error", err)
	}
	if _, err := os.Lstat(tmpPath); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("renamed temporary path still exists: %v", err)
	}
	got, err := os.ReadFile(target)
	if err != nil || string(got) != "new" {
		t.Fatalf("renamed target = %q, %v; want new contents", got, err)
	}
}
