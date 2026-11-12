package native

import (
	"os"
	"path/filepath"
	"runtime"
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

// NAT-T-139..141 verify unreadable and non-object state payloads fail closed.
func TestState_ProtocolFailures(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "state")
	if err := os.WriteFile(dir, nil, 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := readLaunch(dir); err == nil {
		t.Fatal("launch from a non-directory state path was readable")
	}
	if err := os.Remove(dir); err != nil {
		t.Fatal(err)
	}
	if err := ensurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	if _, err := readLaunch(dir); err != nil {
		t.Fatalf("missing launch spec should remain a distinct absent state: %v", err)
	}
	if err := publishText(dir, launchFile, `[]`); err != nil {
		t.Fatal(err)
	}
	if _, err := readLaunch(dir); err == nil {
		t.Fatal("non-object launch payload accepted")
	}
	if err := publishText(dir, exitFile, `[]`); err != nil {
		t.Fatal(err)
	}
	if _, err := readExit(dir); err == nil {
		t.Fatal("non-object exit payload accepted")
	}
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

// Invalid temporary-directory configuration must fail without returning a scope path.
func TestState_CreateDirectoryFailure(t *testing.T) {
	invalidTemp := filepath.Join(t.TempDir(), "not-a-directory")
	if err := os.WriteFile(invalidTemp, nil, 0600); err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS == "windows" {
		t.Setenv("TMP", invalidTemp)
		t.Setenv("TEMP", invalidTemp)
	} else {
		t.Setenv("TMPDIR", invalidTemp)
	}
	if dir, err := createStateDir(); err == nil || dir != "" {
		t.Fatalf("createStateDir() = %q, %v; want failure without a directory", dir, err)
	}
}

// State filenames and non-private roots are rejected before accessing payloads.
func TestState_ReadAndPublishRejectInvalidPaths(t *testing.T) {
	dir := stateTestDir(t)
	if err := publish(dir, "../escape", []byte("secret")); err == nil {
		t.Fatal("publish accepted a path traversal name")
	}
	if _, err := readState(dir, "../escape"); err == nil {
		t.Fatal("readState accepted a path traversal name")
	}
	if _, err := readState(filepath.Join(t.TempDir(), "missing"), scopeFile); err == nil {
		t.Fatal("readState accepted a missing private directory")
	}
	if spec, err := readLaunch(dir); err != nil || spec != nil {
		t.Fatalf("missing launch = %#v, %v; want absent without error", spec, err)
	}
}

// Malformed state records are rejected rather than accepted as process outcomes.
func TestState_RejectMalformedExitAndMissingCleanup(t *testing.T) {
	dir := stateTestDir(t)
	if err := publishText(dir, exitFile, "not-json"); err != nil {
		t.Fatal(err)
	}
	if _, err := readExit(dir); err == nil {
		t.Fatal("malformed exit report was accepted")
	}
	if _, err := readCleanup(dir); err == nil {
		t.Fatal("missing cleanup evidence was accepted")
	}
	if err := removeStateDir(filepath.Join(t.TempDir(), "already-removed")); err != nil {
		t.Fatalf("removing absent state directory: %v", err)
	}
}
