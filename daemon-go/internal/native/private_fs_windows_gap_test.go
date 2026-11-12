//go:build windows

package native

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

type secureFailureWindowsPrivateFileAPI struct {
	nativeWindowsPrivateFileAPI
	secureErr error
	tempPath  string
	removed   bool
}

func (api *secureFailureWindowsPrivateFileAPI) OpenTemp(directory, name string) (windowsPrivateTempFile, error) {
	temp, err := api.nativeWindowsPrivateFileAPI.OpenTemp(directory, name)
	if err == nil {
		api.tempPath = temp.Name()
	}
	return temp, err
}

func (api *secureFailureWindowsPrivateFileAPI) Secure(string) error { return api.secureErr }

func (api *secureFailureWindowsPrivateFileAPI) Remove(path string) error {
	api.removed = true
	return api.nativeWindowsPrivateFileAPI.Remove(path)
}

// TestWindowsPrivateFS_ReportsInvalidPathsAndParentFailures exercises native path errors without ACL fixtures.
func TestWindowsPrivateFS_ReportsInvalidPathsAndParentFailures(t *testing.T) {
	t.Run("invalid directory path", func(t *testing.T) {
		if err := ensurePrivateDirectory(filepath.Join(t.TempDir(), "bad\x00path")); err == nil {
			t.Fatal("invalid directory path was accepted")
		}
	})
	t.Run("nested directory under file", func(t *testing.T) {
		base := t.TempDir()
		file := filepath.Join(base, "regular-file")
		if err := os.WriteFile(file, []byte("x"), 0600); err != nil {
			t.Fatal(err)
		}
		if err := ensurePrivateDirectory(filepath.Join(file, "child", "grandchild")); err == nil {
			t.Fatal("directory beneath a regular file was created")
		}
	})
}

// TestWindowsPrivateFS_RejectsInvalidFileOperations verifies path validation and exclusive creation behavior.
func TestWindowsPrivateFS_RejectsInvalidFileOperations(t *testing.T) {
	dir := stateTestDir(t)
	if err := writePrivateFile(filepath.Join(dir, "bad\x00path"), []byte("value")); err == nil {
		t.Fatal("file path containing NUL was accepted")
	}

	base := t.TempDir()
	file := filepath.Join(base, "regular-file")
	if err := os.WriteFile(file, []byte("x"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := createPrivateFile(filepath.Join(file, "child")); err == nil {
		t.Fatal("private file beneath a regular file was created")
	}
	existing := filepath.Join(dir, scopeFile)
	if err := createPrivateFile(existing); err != nil {
		t.Fatal(err)
	}
	if err := createPrivateFile(existing); err == nil {
		t.Fatal("existing private file was created a second time")
	}
}

// TestWindowsPrivateFS_SecureFailureRemovesTemporaryFile checks failed ACL application closes and removes the temp file.
func TestWindowsPrivateFS_SecureFailureRemovesTemporaryFile(t *testing.T) {
	dir := stateTestDir(t)
	secureErr := errors.New("injected ACL failure")
	api := &secureFailureWindowsPrivateFileAPI{secureErr: secureErr}
	target := filepath.Join(dir, "target")
	if err := writePrivateFileTransaction(target, []byte("value"), api); !errors.Is(err, secureErr) {
		t.Fatalf("transaction error = %v, want %v", err, secureErr)
	}
	if !api.removed {
		t.Fatal("failed temporary file was not removed")
	}
	if _, err := os.Stat(api.tempPath); !os.IsNotExist(err) {
		t.Fatalf("temporary file still exists: %v", err)
	}
	if _, err := os.Stat(target); !os.IsNotExist(err) {
		t.Fatalf("target was published after ACL failure: %v", err)
	}
}

// TestWindowsPrivatePath_NativeSecurityQueryErrors checks native ACL queries propagate missing-path errors.
func TestWindowsPrivatePath_NativeSecurityQueryErrors(t *testing.T) {
	missing := filepath.Join(t.TempDir(), "missing")
	if _, _, _, err := (nativeWindowsPrivatePathAPI{}).Security(missing); err == nil {
		t.Fatal("security query for a missing path succeeded")
	}
	if err := verifyWindowsOwner(missing); err == nil {
		t.Fatal("owner verification for a missing path succeeded")
	}
}
