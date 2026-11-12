package main

import (
	"bytes"
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/config"
)

// RUN-T-135 verifies that the single-argument help/version commands are side-effect free.
func TestInformationCommands(t *testing.T) {
	for _, tc := range []struct{ arg, want string }{{"--help", "Usage:"}, {"--version", "kk-studio-daemon 1.0.0"}, {"-h", "Usage:"}} {
		var out, errOut bytes.Buffer
		if code := RunCLI(context.Background(), []string{tc.arg}, Dependencies{}, &out, &errOut); code != 0 || !bytes.Contains(out.Bytes(), []byte(tc.want)) || errOut.Len() != 0 {
			t.Fatalf("%s: code=%d out=%q err=%q", tc.arg, code, out.String(), errOut.String())
		}
	}
}

// RUN-T-153 verifies internal helper arguments are dispatched without being re-parsed as service flags.
func TestInternalHelperDispatch(t *testing.T) {
	var out, errOut bytes.Buffer
	called := false
	deps := Dependencies{HelperMain: func(args []string) int {
		called = true
		if len(args) != 2 || args[1] != "raw value" {
			t.Fatalf("helper args changed: %#v", args)
		}
		return 7
	}}
	if code := RunCLI(context.Background(), []string{"--internal-install-lock", "raw value"}, deps, &out, &errOut); code != 7 || !called || out.Len() != 0 || errOut.Len() != 0 {
		t.Fatalf("helper dispatch code=%d called=%t stdout=%q stderr=%q", code, called, out.String(), errOut.String())
	}
}

// RUN-T-136 checks malformed command forms fail safely without echoing arguments.
func TestInvalidCommandsDoNotEchoArguments(t *testing.T) {
	var out, stderr bytes.Buffer
	code := RunCLI(context.Background(), []string{"--unknown", "sensitive"}, Dependencies{}, &out, &stderr)
	if code != 1 || bytes.Contains(stderr.Bytes(), []byte("sensitive")) {
		t.Fatalf("code=%d stderr=%q", code, stderr.String())
	}
}

// RUN-T-137 validates config checks and contains safe diagnostics at the CLI boundary.
func TestCheckConfigCommands(t *testing.T) {
	path := commandConfigFixture(t)
	var out, stderr bytes.Buffer
	if code := RunCLI(context.Background(), []string{"--check-config", path}, Dependencies{}, &out, &stderr); code != 0 || out.String() != "Daemon configuration is valid\n" || stderr.Len() != 0 {
		t.Fatalf("check-config success code=%d out=%q stderr=%q", code, out.String(), stderr.String())
	}
	out.Reset()
	stderr.Reset()
	if code := RunCLI(context.Background(), []string{"--check-config", filepath.Join(t.TempDir(), "missing.json")}, Dependencies{}, &out, &stderr); code != 1 || out.Len() != 0 || !strings.HasPrefix(stderr.String(), "Invalid daemon configuration: ") {
		t.Fatalf("check-config failure code=%d out=%q stderr=%q", code, out.String(), stderr.String())
	}
}

// RUN-T-138 requires exactly one absolute config path and a configured runtime.
func TestConfigRunRequiresConfiguredRuntime(t *testing.T) {
	path := commandConfigFixture(t)
	var out, stderr bytes.Buffer
	for _, args := range [][]string{{"--config"}, {"--config", path, "extra"}, {"--config", "relative.json"}} {
		stderr.Reset()
		if code := RunCLI(context.Background(), args, Dependencies{}, &out, &stderr); code != 1 || stderr.Len() == 0 {
			t.Fatalf("args=%q code=%d stderr=%q", args, code, stderr.String())
		}
	}
	stderr.Reset()
	if code := RunCLI(context.Background(), []string{"--config", path}, Dependencies{}, &out, &stderr); code != 1 || stderr.String() != "daemon failed: runtime is not configured\n" {
		t.Fatalf("unconfigured runtime code=%d stderr=%q", code, stderr.String())
	}
}

// RUN-T-136 rejects NUL argument transport and an unavailable private helper.
func TestInvalidArgumentTransportAndUnavailableHelper(t *testing.T) {
	var out, stderr bytes.Buffer
	if code := RunCLI(context.Background(), []string{"--help\x00"}, Dependencies{}, &out, &stderr); code != 2 || stderr.String() != "invalid daemon argument transport\n" {
		t.Fatalf("NUL argument code=%d stderr=%q", code, stderr.String())
	}
	stderr.Reset()
	if code := RunCLI(context.Background(), []string{"--internal-update"}, Dependencies{}, &out, &stderr); code != 1 || stderr.String() != "daemon: internal helper is unavailable\n" {
		t.Fatalf("helper code=%d stderr=%q", code, stderr.String())
	}
}

func TestConfigRunInvokesRuntimeAndContainsFailures(t *testing.T) {
	path := commandConfigFixture(t)
	var out, stderr bytes.Buffer
	runner := &recordingRuntimeRunner{}
	if code := RunCLI(context.Background(), []string{"--config", path}, Dependencies{Runtime: runner}, &out, &stderr); code != 0 || runner.calls != 1 || runner.config.EnvironmentID != "123e4567-e89b-42d3-a456-426614174000" || stderr.Len() != 0 {
		t.Fatalf("runtime run code=%d calls=%d config=%+v stderr=%q", code, runner.calls, runner.config, stderr.String())
	}
	runner.err = context.Canceled
	stderr.Reset()
	if code := RunCLI(context.Background(), []string{"--config", path}, Dependencies{Runtime: runner}, &out, &stderr); code != 1 || stderr.String() != "daemon failed: runtime execution failed\n" {
		t.Fatalf("runtime failure code=%d stderr=%q", code, stderr.String())
	}
}

func TestSafeErrorNormalizesDiagnostics(t *testing.T) {
	if got := safeError(nil); got != "unknown" {
		t.Fatalf("nil message = %q", got)
	}
	if got := safeError(context.Canceled); got != "operation cancelled" {
		t.Fatalf("cancellation message = %q", got)
	}
	if got := safeError(errors.New("first\nsecond\rthird")); got != "first second third" {
		t.Fatalf("normalized message = %q", got)
	}
	if got := safeError(errors.New(" \n\t")); got != "unknown" {
		t.Fatalf("blank message = %q", got)
	}
}

type recordingRuntimeRunner struct {
	calls  int
	config config.DaemonConfig
	err    error
}

func (r *recordingRuntimeRunner) Run(_ context.Context, cfg config.DaemonConfig) error {
	r.calls++
	r.config = cfg
	return r.err
}

// The ordinary CLI fixture includes the owner-only token required by LoadDaemonConfig.
func commandConfigFixture(t *testing.T) string {
	t.Helper()
	directory := t.TempDir()
	path := filepath.Join(directory, "daemon.json")
	content := `{"environmentId":"123e4567-e89b-42d3-a456-426614174000","version":"0","daemonConfig":{"studioUrl":"http://localhost","lsp":{"servers":{"x":{"command":["x"],"extensions":[".x"]}}}}}`
	if err := os.WriteFile(path, []byte(content), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(directory, "daemon.token"), []byte("token\n"), 0600); err != nil {
		t.Fatal(err)
	}
	return path
}
