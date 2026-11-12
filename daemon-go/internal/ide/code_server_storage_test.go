package ide

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/native"
)

// IDE-T-030 verifies code-server storage helpers apply private modes and fail on non-directory paths.
func TestPrivateStorageHelpers(t *testing.T) {
	root := t.TempDir()
	directory := filepath.Join(root, "private")
	if err := os.Mkdir(directory, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := native.EnsurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(directory)
	if err != nil || runtime.GOOS != "windows" && info.Mode().Perm() != 0o700 {
		t.Fatalf("directory mode=%v err=%v", info, err)
	}
	file := filepath.Join(root, "file")
	if err := os.WriteFile(file, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := native.EnsurePrivateDirectory(file); err == nil {
		t.Fatal("regular file accepted as a private directory")
	}
	secret := filepath.Join(directory, "secret")
	if err := native.WritePrivateFile(secret, []byte("token")); err != nil {
		t.Fatal(err)
	}
	info, err = os.Stat(secret)
	if err != nil || runtime.GOOS != "windows" && info.Mode().Perm() != 0o600 {
		t.Fatalf("file mode=%v err=%v", info, err)
	}
	if err := native.WritePrivateFile(directory, []byte("not a file")); err == nil {
		t.Fatal("directory accepted as a private file")
	}
}

// IDE-T-030 verifies extension installation rejects symlinks and replaces stale digests atomically.
func TestBridgeExtensionInstallBoundaries(t *testing.T) {
	root := t.TempDir()
	distribution := filepath.Join(root, "distribution")
	extensions := filepath.Join(root, "extensions")
	if err := os.MkdirAll(extensions, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := installBridgeExtension(distribution, extensions); err == nil {
		t.Fatal("missing extension source accepted")
	}
	source := filepath.Join(distribution, "ide-extension")
	if err := os.MkdirAll(source, 0o700); err != nil {
		t.Fatal(err)
	}
	bridge := filepath.Join(source, "bridge.cjs")
	if err := os.WriteFile(bridge, []byte("safe"), 0o600); err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" {
		if err := os.Symlink(bridge, filepath.Join(source, "linked.cjs")); err != nil {
			t.Skipf("symlinks unavailable: %v", err)
		}
		if err := installBridgeExtension(distribution, extensions); err == nil {
			t.Fatal("extension symlink accepted")
		}
		if err := os.Remove(filepath.Join(source, "linked.cjs")); err != nil {
			t.Fatal(err)
		}
	}
	if err := installBridgeExtension(distribution, extensions); err != nil {
		t.Fatal(err)
	}
	installed := filepath.Join(extensions, bridgeExtensionName, "bridge.cjs")
	if data, err := os.ReadFile(installed); err != nil || string(data) != "safe" {
		t.Fatalf("installed extension=%q err=%v", data, err)
	}
	if err := os.WriteFile(bridge, []byte("updated"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := installBridgeExtension(distribution, extensions); err != nil {
		t.Fatal(err)
	}
	if data, err := os.ReadFile(installed); err != nil || string(data) != "updated" {
		t.Fatalf("updated extension=%q err=%v", data, err)
	}
}
