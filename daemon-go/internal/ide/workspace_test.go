package ide

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/config"
)

// IDE-T-140
func TestIdentityAndEpochValidation(t *testing.T) {
	if got, err := ValidateViewID("123e4567-e89b-12d3-a456-426614174000"); err != nil || got == "" {
		t.Fatalf("valid UUID: got %q, err %v", got, err)
	}
	for _, value := range []string{"123E4567-E89B-12D3-A456-426614174000", "bad"} {
		if _, err := ValidateViewID(value); err == nil {
			t.Errorf("ValidateViewID(%q) succeeded", value)
		}
	}
	for _, value := range []string{"", "0", "01", "9223372036854775808", "-1"} {
		if _, err := ValidateBindingEpoch(value); err == nil {
			t.Errorf("ValidateBindingEpoch(%q) succeeded", value)
		}
	}
	if _, err := ValidateBindingEpoch("9223372036854775807"); err != nil {
		t.Fatalf("maximum epoch rejected: %v", err)
	}
	if _, err := ValidateExpectedBindingEpoch("0"); err != nil {
		t.Fatalf("zero expected epoch rejected: %v", err)
	}
	for _, value := range []string{"00", "9223372036854775808"} {
		if _, err := ValidateExpectedBindingEpoch(value); err == nil {
			t.Errorf("ValidateExpectedBindingEpoch(%q) succeeded", value)
		}
	}
}

// IDE-T-142
func TestCanonicalDirectory(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "target")
	if err := os.Mkdir(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(root, "link")
	if err := os.Symlink(dir, link); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	if got, err := CanonicalDirectory(link); err != nil || got != dir {
		t.Fatalf("CanonicalDirectory(%q) = %q, %v", link, got, err)
	}
	if _, err := CanonicalDirectory("relative"); err == nil {
		t.Fatal("relative path accepted")
	}
	if _, err := CanonicalDirectory(filepath.Join(root, "missing")); err == nil {
		t.Fatal("missing path accepted")
	}
	if err := os.WriteFile(filepath.Join(root, "file"), nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := CanonicalDirectory(filepath.Join(root, "file")); err == nil {
		t.Fatal("regular file accepted")
	}
	if _, err := CanonicalDirectory("/invalid\x00path"); err == nil {
		t.Fatal("NUL path accepted")
	}
}

// IDE-T-143
func TestWorkspaceFileContent(t *testing.T) {
	want := "{\n  \"folders\": [\n    {\n      \"path\": \"/work\"\n    }\n  ]\n}\n"
	if got := WorkspaceFileContent("/work"); got != want {
		t.Fatalf("workspace content mismatch:\n%s", got)
	}
}

// IDE-T-138
func TestListCanonicalDirectories(t *testing.T) {
	root := t.TempDir()
	for _, name := range []string{"zeta", "alpha"} {
		if err := os.Mkdir(filepath.Join(root, name), 0o700); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(root, "file"), nil, 0o600); err != nil {
		t.Fatal(err)
	}
	listing, err := ListDirectories(root)
	if err != nil {
		t.Fatal(err)
	}
	if len(listing.Children) != 2 || listing.Children[0].Name != "alpha" || listing.Children[1].Name != "zeta" {
		t.Fatalf("unexpected listing: %#v", listing)
	}
}

// IDE-T-144
func TestUnsafeDirectoryNames(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("unsafe directory names are platform-specific")
	}
	root := t.TempDir()
	for _, name := range []string{"valid", "line\nbreak"} {
		if err := os.Mkdir(filepath.Join(root, name), 0o700); err != nil {
			t.Fatal(err)
		}
	}
	listing, err := ListDirectories(root)
	if err != nil || len(listing.Children) != 1 || listing.Children[0].Name != "valid" || !listing.Truncated {
		t.Fatalf("unsafe name leaked: %#v, %v", listing, err)
	}
	if _, err := ListDirectories(filepath.Join(root, "line\nbreak")); err == nil || err.Error() != "directory path is invalid" {
		t.Fatalf("unsafe directory error = %v", err)
	}
}

// IDE-T-141
func TestHostHome(t *testing.T) {
	if home, err := HostHome(map[string]string{"HOME": "/home/test"}); err != nil || home != "/home/test" {
		t.Fatalf("HOME = %q, %v", home, err)
	}
	if home, err := HostHome(map[string]string{"USERPROFILE": "C:\\\\Users\\\\test"}); err != nil || home == "" {
		t.Fatalf("USERPROFILE = %q, %v", home, err)
	}
	if _, err := HostHome(nil); err == nil {
		t.Fatal("missing home accepted")
	}
}

// IDE-T-139
func TestListingLimits(t *testing.T) {
	root, err := os.MkdirTemp(os.TempDir(), "i")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(root)
	for i := 0; i < 1001; i++ {
		if err := os.Mkdir(filepath.Join(root, fmt.Sprintf("d%04d", i)), 0o700); err != nil {
			t.Fatal(err)
		}
	}
	listing, err := ListDirectories(root)
	if err != nil || len(listing.Children) != 1000 || !listing.Truncated {
		t.Fatalf("entry limit: children=%d truncated=%v err=%v", len(listing.Children), listing.Truncated, err)
	}
	root = t.TempDir()
	for i := 0; i < 250; i++ {
		name := fmt.Sprintf("%03d-%s", i, strings.Repeat("x", 220))
		if err := os.Mkdir(filepath.Join(root, name), 0o700); err != nil {
			t.Fatal(err)
		}
	}
	listing, err = ListDirectories(root)
	if err != nil || len(listing.Children) >= 250 || !listing.Truncated {
		t.Fatalf("byte limit: children=%d truncated=%v err=%v", len(listing.Children), listing.Truncated, err)
	}
}

// IDE-T-152
func TestSafeErrorMessages(t *testing.T) {
	if got := FailureCode(ErrCancelled); got != "BUSY" {
		t.Fatalf("cancel code = %q", got)
	}
	if got := FailureCode(os.ErrPermission); got != "UNAVAILABLE" {
		t.Fatalf("unknown code = %q", got)
	}
	if got := FailureCode(NewError("visible", "DIRTY")); got != "DIRTY" {
		t.Fatalf("IdeError code = %q", got)
	}
	if got := NewError("default", "").Code; got != "UNAVAILABLE" {
		t.Fatalf("default code = %q", got)
	}
	if got := NewError("message", "BUSY").Error(); got != "message" {
		t.Fatalf("error text = %q", got)
	}
	if got := SafeMessage(NewError("visible", "BUSY")); got != "visible" {
		t.Fatalf("IdeError message = %q", got)
	}
	if got := SafeMessage(ErrCancelled); got != "ide request was cancelled" {
		t.Fatalf("cancel message = %q", got)
	}
	if got := SafeMessage(os.ErrPermission); got != "ide request failed" || strings.Contains(got, "permission") {
		t.Fatalf("unsafe error message = %q", got)
	}
}

// IDE-T-146
func TestRecoverySafeErrors(t *testing.T) {
	cfg := config.DaemonConfig{DataDir: t.TempDir()}
	if got, err := ReadRecoveryBackup(cfg, "123e4567-e89b-12d3-a456-426614174000", 1024); err != nil || got != nil {
		t.Fatalf("missing backup = %#v, %v", got, err)
	}
	path := filepath.Join(recoveryDirectory(cfg), "123e4567-e89b-12d3-a456-426614174000.json")
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("{"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadRecoveryBackup(cfg, "123e4567-e89b-12d3-a456-426614174000", 1024); err == nil || err.Error() != "recovery backup is corrupt" {
		t.Fatalf("corrupt backup error = %v", err)
	}
	if err := os.WriteFile(path, []byte(strings.Repeat("x", 20)), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadRecoveryBackup(cfg, "123e4567-e89b-12d3-a456-426614174000", 1); err == nil || err.Error() != "recovery backup exceeds the configured quota" {
		t.Fatalf("oversize backup error = %v", err)
	}
	if _, err := ReadRecoveryBackup(cfg, "invalid", 1024); err == nil || err.Error() != "recovery backup is corrupt" {
		t.Fatalf("invalid view error = %v", err)
	}
}

// IDE-T-146
func TestRecoveryFilesystemFailure(t *testing.T) {
	cfg := config.DaemonConfig{DataDir: t.TempDir()}
	recovery := recoveryDirectory(cfg)
	if err := os.MkdirAll(filepath.Dir(recovery), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(recovery, []byte("not a directory"), 0o600); err != nil {
		t.Fatal(err)
	}
	backup := RecoveryBackup{ViewID: "123e4567-e89b-12d3-a456-426614174000", BindingEpoch: "1", Directory: "/work", CreatedAt: "now", Documents: []RecoveryDocument{}}
	if _, err := WriteRecoveryBackup(cfg, backup, 4096); err == nil || err.Error() != "recovery directory is unavailable" {
		t.Fatalf("write failure = %v", err)
	}
	if _, err := ReadRecoveryBackup(cfg, backup.ViewID, 4096); err == nil || !strings.HasPrefix(err.Error(), "recovery backup is unreadable: ") || strings.Contains(err.Error(), cfg.DataDir) {
		t.Fatalf("read failure is not safe: %v", err)
	}
}

// IDE-T-147
func TestDanglingRecoveryEntry(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("dangling symlink behavior differs on Windows")
	}
	cfg := config.DaemonConfig{DataDir: t.TempDir()}
	dir := recoveryDirectory(cfg)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(dir, "missing"), filepath.Join(dir, "dangling.json")); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	backup := RecoveryBackup{ViewID: "123e4567-e89b-12d3-a456-426614174000", BindingEpoch: "1", Directory: "/work", CreatedAt: "now", Documents: []RecoveryDocument{}}
	if _, err := WriteRecoveryBackup(cfg, backup, 4096); err == nil || !strings.HasPrefix(err.Error(), "recovery quota could not be measured:") {
		t.Fatalf("dangling entry error = %v", err)
	}
}

// IDE-T-145
func TestRecoveryAggregateQuota(t *testing.T) {
	cfg := config.DaemonConfig{DataDir: t.TempDir()}
	first := RecoveryBackup{ViewID: "123e4567-e89b-12d3-a456-426614174000", BindingEpoch: "1", Directory: "/work", CreatedAt: "now", Documents: []RecoveryDocument{}}
	second := first
	second.ViewID = "123e4567-e89b-12d3-a456-426614174001"
	bytes, err := WriteRecoveryBackup(cfg, first, 4096)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := WriteRecoveryBackup(cfg, second, bytes*2-1); err == nil || err.Error() != "recovery data quota is exhausted" {
		t.Fatalf("aggregate quota error = %v", err)
	}
	if _, err := WriteRecoveryBackup(cfg, second, 0); err == nil || err.Error() != "recovery quota is invalid" {
		t.Fatalf("invalid quota error = %v", err)
	}
	if got, err := ReadRecoveryBackup(cfg, second.ViewID, 4096); err != nil || got != nil {
		t.Fatalf("rejected record = %#v, %v", got, err)
	}
}

// IDE-T-150
func TestCorruptRecoveryVariants(t *testing.T) {
	cfg := config.DaemonConfig{DataDir: t.TempDir()}
	viewID := "123e4567-e89b-12d3-a456-426614174000"
	path := filepath.Join(recoveryDirectory(cfg), viewID+".json")
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	for _, content := range []string{`[]`, `{"viewId":"123e4567-e89b-12d3-a456-426614174000","bindingEpoch":"0","directory":"/work","createdAt":"now","complete":true,"documents":[]}`, `{"viewId":"123e4567-e89b-12d3-a456-426614174000","bindingEpoch":"1","directory":"/work","createdAt":"now","complete":true,"documents":[{"path":"/bad\u0000path","text":"x"}]}`} {
		if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
		if _, err := ReadRecoveryBackup(cfg, viewID, 4096); err == nil || err.Error() != "recovery backup is corrupt" {
			t.Errorf("content %s error = %v", content, err)
		}
	}
}

// IDE-T-149
func TestRecoveryWriteAndReadLimits(t *testing.T) {
	cfg := config.DaemonConfig{DataDir: t.TempDir()}
	backup := RecoveryBackup{ViewID: "123e4567-e89b-12d3-a456-426614174000", BindingEpoch: "1", Directory: "/work", CreatedAt: "now", Documents: []RecoveryDocument{{Path: "/work/file", Text: "unsaved"}}}
	if _, err := WriteRecoveryBackup(cfg, backup, 4096); err != nil {
		t.Fatal(err)
	}
	tooLarge := backup
	tooLarge.ViewID = "123e4567-e89b-12d3-a456-426614174001"
	if _, err := WriteRecoveryBackup(cfg, tooLarge, 1); err == nil {
		t.Fatal("over-quota write succeeded")
	} else {
		var quotaErr *RecoveryQuotaError
		if !errors.As(err, &quotaErr) {
			t.Fatalf("write error type = %T", err)
		}
	}
	if _, err := ReadRecoveryBackup(cfg, backup.ViewID, 0); err == nil {
		t.Fatal("zero read quota succeeded")
	} else {
		var quotaErr *RecoveryQuotaError
		if !errors.As(err, &quotaErr) {
			t.Fatalf("read error type = %T", err)
		}
	}
	got, err := ReadRecoveryBackup(cfg, backup.ViewID, 4096)
	if err != nil || got == nil || got.Documents[0].Text != "unsaved" {
		t.Fatalf("failed write damaged existing backup: %#v, %v", got, err)
	}
}

// IDE-T-151
func TestPrivateRecoveryBudget(t *testing.T) {
	cfg := config.DaemonConfig{DataDir: t.TempDir()}
	backup := RecoveryBackup{ViewID: "123e4567-e89b-12d3-a456-426614174000", BindingEpoch: "1", Directory: "/work", CreatedAt: "now", Documents: []RecoveryDocument{{Path: "/work/file", Text: "unsaved"}}}
	if _, err := WriteRecoveryBackup(cfg, backup, 4096); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(recoveryDirectory(cfg), backup.ViewID+".json")
	data, err := os.ReadFile(path)
	if err != nil || !strings.Contains(string(data), "unsaved") {
		t.Fatalf("stored backup data = %q, %v", data, err)
	}
	if _, err := WriteRecoveryBackup(cfg, backup, 1); err == nil {
		t.Fatal("over-quota replacement succeeded")
	}
	got, err := ReadRecoveryBackup(cfg, backup.ViewID, 4096)
	if err != nil || got == nil || got.Documents[0].Text != "unsaved" {
		t.Fatalf("failed replacement damaged backup: %#v, %v", got, err)
	}
}

// IDE-T-148
func TestRecoveryRoundTrip(t *testing.T) {
	cfg := config.DaemonConfig{DataDir: t.TempDir()}
	backup := RecoveryBackup{
		ViewID: "123e4567-e89b-12d3-a456-426614174000", BindingEpoch: "1",
		Directory: "/work", CreatedAt: "2025-01-01T00:00:00Z", Complete: true,
		Documents: []RecoveryDocument{{Path: "/work/a.go", Text: "unsaved"}},
	}
	if _, err := WriteRecoveryBackup(cfg, backup, 1024); err != nil {
		t.Fatal(err)
	}
	got, err := ReadRecoveryBackup(cfg, backup.ViewID, 1024)
	if err != nil || got == nil || got.Documents[0].Text != "unsaved" {
		t.Fatalf("read backup = %#v, %v", got, err)
	}
}
