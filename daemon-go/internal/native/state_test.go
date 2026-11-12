package native

import (
	"os"
	"path/filepath"
	"testing"
)

func stateTestDir(t *testing.T) string {
	t.Helper()
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	return dir
}

// NAT-T-142
func TestState_CreatePrivateDirectory(t *testing.T) {
	dir, err := createStateDir()
	if err != nil {
		t.Fatal(err)
	}
	defer removeStateDir(dir)
	if _, err := os.Stat(dir); err != nil {
		t.Fatal(err)
	}
}

// NAT-T-143
func TestState_PublishReadPrivate(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	if err := publishText(dir, scopeFile, "123"); err != nil {
		t.Fatal(err)
	}
	data, err := readState(dir, scopeFile)
	if err != nil || string(data) != "123" {
		t.Fatalf("read = %q, %v", data, err)
	}
	if err := verifyPrivateFile(filepath.Join(dir, scopeFile)); err != nil {
		t.Fatal(err)
	}
}

// NAT-T-144
func TestState_ReadMissing(t *testing.T) {
	_, err := readState(stateTestDir(t), "missing")
	if !os.IsNotExist(err) {
		t.Fatalf("read error = %v", err)
	}
}

// NAT-T-145
func TestState_PublishQuietly(t *testing.T) {
	publishQuietly(stateTestDir(t), "../escape", "sensitive")
}

// NAT-T-146
func TestState_LaunchOneShot(t *testing.T) {
	dir := stateTestDir(t)
	if err := publish(dir, launchFile, []byte(`{"cwd":"/tmp","args":["cmd"]}`)); err != nil {
		t.Fatal(err)
	}
	spec, err := readLaunch(dir)
	if err != nil || spec == nil || spec.Args[0] != "cmd" {
		t.Fatalf("launch = %#v, %v", spec, err)
	}
	if _, err := os.Stat(filepath.Join(dir, launchFile)); !os.IsNotExist(err) {
		t.Fatalf("launch was not removed: %v", err)
	}
}

// NAT-T-147
func TestState_RejectNUL(t *testing.T) {
	dir := stateTestDir(t)
	if err := publish(dir, launchFile, []byte(`{"cwd":"/tmp","args":["bad\u0000arg"]}`)); err != nil {
		t.Fatal(err)
	}
	if _, err := readLaunch(dir); err == nil {
		t.Fatal("NUL argument accepted")
	}
}

// NAT-T-148
func TestState_CorruptLaunch(t *testing.T) {
	dir := stateTestDir(t)
	if err := publish(dir, launchFile, []byte("{")); err != nil {
		t.Fatal(err)
	}
	if _, err := readLaunch(dir); err == nil {
		t.Fatal("corrupt launch accepted")
	}
}

// NAT-T-149
func TestState_IncompleteLaunch(t *testing.T) {
	dir := stateTestDir(t)
	if err := publish(dir, launchFile, []byte(`{"cwd":"/tmp"}`)); err != nil {
		t.Fatal(err)
	}
	if _, err := readLaunch(dir); err == nil {
		t.Fatal("incomplete launch accepted")
	}
}

// NAT-T-150
func TestState_ExitReport(t *testing.T) {
	dir := stateTestDir(t)
	code := 21
	if err := publish(dir, exitFile, []byte(`{"exitCode":21,"signal":null}`)); err != nil {
		t.Fatal(err)
	}
	got, err := readExit(dir)
	if err != nil || got.ExitCode == nil || *got.ExitCode != code {
		t.Fatalf("exit = %#v, %v", got, err)
	}
	if err := publish(dir, exitFile, []byte(`{"exitCode":null,"signal":null}`)); err != nil {
		t.Fatal(err)
	}
	if _, err := readExit(dir); err == nil {
		t.Fatal("exit report without a status was accepted")
	}
}

// NAT-T-151
func TestState_CleanupTruth(t *testing.T) {
	dir := stateTestDir(t)
	for _, item := range []struct {
		value string
		want  bool
	}{{"true", true}, {"false", false}} {
		if err := publishText(dir, cleanupFile, item.value); err != nil {
			t.Fatal(err)
		}
		got, err := readCleanup(dir)
		if err != nil || got == nil || *got != item.want {
			t.Fatalf("cleanup %q = %v, %v", item.value, got, err)
		}
	}
	if err := publishText(dir, cleanupFile, "yes"); err != nil {
		t.Fatal(err)
	}
	if _, err := readCleanup(dir); err == nil {
		t.Fatal("invalid cleanup accepted")
	}
}

// NAT-T-152
func TestState_RemoveDirectory(t *testing.T) {
	dir := stateTestDir(t)
	if err := publishText(dir, scopeFile, "123"); err != nil {
		t.Fatal(err)
	}
	if err := removeStateDir(dir); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(dir); !os.IsNotExist(err) {
		t.Fatalf("state directory still exists: %v", err)
	}
}

// NAT-T-153
func TestState_AtomicPublish(t *testing.T) {
	dir := stateTestDir(t)
	if err := publishText(dir, scopeFile, "session"); err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != scopeFile {
		t.Fatalf("published files = %v", entries)
	}
}
