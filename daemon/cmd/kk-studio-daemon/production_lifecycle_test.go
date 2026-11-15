package main

import (
	"bytes"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
)

const productionLifecycleTimeout = 20 * time.Second

// TestProductionRuntimeProcessLifecycle exercises the compiled daemon: information commands,
// private startup state, the single-runtime directory lock, SIGTERM shutdown and lock release.
// It also proves a second daemon fails immediately instead of sharing the data directory.
func TestProductionRuntimeProcessLifecycle(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("the compiled-daemon lifecycle fixture requires POSIX SIGTERM semantics")
	}
	binary := buildDaemonForTest(t)
	dataDir := filepath.Join(t.TempDir(), "daemon")
	if err := native.EnsurePrivateDirectory(dataDir); err != nil {
		t.Fatal(err)
	}
	configPath := filepath.Join(dataDir, "daemon.json")
	configBody := `{"environmentId":"123e4567-e89b-42d3-a456-426614174000","version":"0","daemonConfig":{"studioUrl":"http://127.0.0.1:1"}}`
	if err := native.WritePrivateFile(configPath, []byte(configBody)); err != nil {
		t.Fatal(err)
	}
	if err := native.WritePrivateFile(filepath.Join(dataDir, "daemon.token"), []byte("compiled-lifecycle-token\n")); err != nil {
		t.Fatal(err)
	}

	for _, tc := range []struct {
		name string
		args []string
		want string
	}{
		{"version", []string{"--version"}, "kk-studio-daemon " + config.DaemonVersion},
		{"help", []string{"--help"}, "Usage:"},
		{"check-config", []string{"--check-config", configPath}, "Daemon configuration is valid"},
	} {
		output, err := exec.Command(binary, tc.args...).CombinedOutput()
		if err != nil {
			t.Fatalf("%s command failed: %v, output=%q", tc.name, err, output)
		}
		if !strings.Contains(string(output), tc.want) {
			t.Fatalf("%s output=%q, want substring %q", tc.name, output, tc.want)
		}
	}

	invalidConfig := filepath.Join(dataDir, "invalid.json")
	if err := native.WritePrivateFile(invalidConfig, []byte(`{"environmentId":"123e4567-e89b-42d3-a456-426614174000","version":"0","daemonConfig":{"studioUrl":"invalid"}}`)); err != nil {
		t.Fatal(err)
	}
	var checkStderr bytes.Buffer
	invalid := exec.Command(binary, "--check-config", invalidConfig)
	invalid.Stderr = &checkStderr
	checkErr := invalid.Run()
	var checkExit *exec.ExitError
	if !errors.As(checkErr, &checkExit) || checkExit.ExitCode() != 1 {
		t.Fatalf("compiled --check-config accepted an invalid configuration: err=%v stderr=%q", checkErr, checkStderr.String())
	}
	if !strings.HasPrefix(checkStderr.String(), "Invalid daemon configuration: ") {
		t.Fatalf("compiled --check-config diagnostic=%q", checkStderr.String())
	}

	first := exec.Command(binary, "--config", configPath)
	first.Dir = dataDir
	var firstStderr bytes.Buffer
	first.Stderr = &firstStderr
	if err := first.Start(); err != nil {
		t.Fatal(err)
	}
	firstDone := make(chan error, 1)
	go func() { firstDone <- first.Wait() }()
	exited := false
	stopFirst := func() {
		if exited {
			return
		}
		exited = true
		stopDaemonProcess(t, first, firstDone)
	}
	defer stopFirst()

	waitForDaemonLock(t, dataDir, firstDone, &firstStderr)
	if info, err := os.Stat(dataDir); err != nil || info.Mode().Perm() != 0o700 {
		t.Fatalf("private data directory mode = %v, err=%v", info, err)
	}
	if info, err := os.Stat(configPath); err != nil || info.Mode().Perm() != 0o600 {
		t.Fatalf("private config mode = %v, err=%v", info, err)
	}

	second := exec.Command(binary, "--config", configPath)
	var secondStderr bytes.Buffer
	second.Stderr = &secondStderr
	err := second.Run()
	var exitError *exec.ExitError
	if !errors.As(err, &exitError) || exitError.ExitCode() != 1 {
		t.Fatalf("second daemon error = %v, stderr=%q, want exit code 1", err, secondStderr.String())
	}
	if !strings.Contains(secondStderr.String(), "daemon directory lock is unavailable") {
		t.Fatalf("second daemon stderr=%q, want the runtime-lock diagnostic", secondStderr.String())
	}

	if err := first.Process.Signal(syscall.SIGTERM); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-firstDone:
		exited = true
		if err != nil {
			t.Fatalf("daemon exited with error after SIGTERM: %v, stderr=%q", err, firstStderr.String())
		}
	case <-time.After(productionLifecycleTimeout):
		t.Fatalf("daemon did not exit after SIGTERM, stderr=%q", firstStderr.String())
	}
	lock, err := native.AcquireDirectoryLock(dataDir, native.DaemonLockFileName)
	if err != nil {
		t.Fatalf("daemon lock was not released after shutdown: %v", err)
	}
	if err := lock.Close(); err != nil {
		t.Fatal(err)
	}
}

// stopDaemonProcess forwards SIGTERM so the h2-connect bootstrap parent can relay it to the
// daemon child, then escalates to Kill if the process tree does not converge.
func stopDaemonProcess(t *testing.T, command *exec.Cmd, done <-chan error) {
	t.Helper()
	if command.Process == nil {
		return
	}
	if err := command.Process.Signal(syscall.SIGTERM); err != nil {
		_ = command.Process.Kill()
	} else {
		select {
		case <-done:
			return
		case <-time.After(5 * time.Second):
			_ = command.Process.Kill()
		}
	}
	select {
	case <-done:
	case <-time.After(productionLifecycleTimeout):
		t.Errorf("daemon process tree did not stop after the test cleanup")
	}
}

func buildDaemonForTest(t *testing.T) string {
	t.Helper()
	goTool, err := exec.LookPath("go")
	if err != nil {
		t.Skipf("the Go toolchain is unavailable: %v", err)
	}
	binary := filepath.Join(t.TempDir(), "kk-studio-daemon")
	build := exec.Command(goTool, "build", "-o", binary, ".")
	build.Env = append(os.Environ(), "CGO_ENABLED=0")
	var output bytes.Buffer
	build.Stdout, build.Stderr = &output, &output
	if err := build.Run(); err != nil {
		t.Fatalf("build daemon: %v\n%s", err, output.String())
	}
	return binary
}

func waitForDaemonLock(t *testing.T, dataDir string, done <-chan error, stderr *bytes.Buffer) {
	t.Helper()
	deadline := time.NewTimer(productionLifecycleTimeout)
	defer deadline.Stop()
	ticker := time.NewTicker(50 * time.Millisecond)
	defer ticker.Stop()
	for {
		lock, err := native.AcquireDirectoryLock(dataDir, native.DaemonLockFileName)
		if err == nil {
			_ = lock.Close()
		} else if errors.Is(err, native.ErrDirectoryLockBusy) {
			return
		} else {
			t.Fatalf("inspect daemon lock: %v", err)
		}
		select {
		case err := <-done:
			t.Fatalf("daemon exited before acquiring its lock: %v, stderr=%q", err, stderr.String())
		case <-deadline.C:
			t.Fatalf("daemon did not acquire its lock, stderr=%q", stderr.String())
		case <-ticker.C:
		}
	}
}
