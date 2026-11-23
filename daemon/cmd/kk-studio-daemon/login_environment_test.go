package main

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/lsp"
	"github.com/fengwk/kk-studio/daemon/internal/native"
)

// A login startup file exposing a tool must affect both Bash and LSP discovery.
func TestLoginEnvironmentAlignsBashAndLSP(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("POSIX shell fixture")
	}
	bash := loginTestBash(t)
	bin := t.TempDir()
	tool := "kk-studio-login-only-lsp"
	if err := os.WriteFile(filepath.Join(bin, tool), []byte("#!/bin/sh\nexit 0\n"), 0700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("KK_TEST_LOGIN_BIN", bin)
	// BASH_ENV is loaded by non-interactive Bash after the system login profile.
	// This fixture remains isolated even when /etc/profile overwrites HOME.
	setLoginTestProfile(t, `export PATH="$KK_TEST_LOGIN_BIN:$PATH"`)
	if err := exec.Command(bash, "-lc", `cd -- "$1" || exit 1; shift; command -v kk-studio-login-only-lsp`, "bash", bin).Run(); err != nil {
		t.Fatal(err)
	}
	processes := loginTestSupervisor(t)
	facade := lsp.NewFacade(processes, lsp.PoolOptions{})
	t.Cleanup(func() {
		if err := facade.Close(); err != nil {
			t.Error(err)
		}
	})
	load := func(ctx context.Context, bash string) ([]string, error) {
		return loadLoginEnvironment(ctx, processes, bash)
	}
	prepare := prepareProductionEnvironment(facade, nil, load)
	candidate := config.DaemonConfig{BashExecutable: &bash, LSP: []config.LSPServerConfig{{ID: "fixture", Command: []string{tool}, Extensions: []string{".fixture"}}}}
	previous := candidate // Same proxy policy: no network resources are needed by this test.
	prepared, err := prepare(context.Background(), candidate, &previous)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := prepared.Publish(); err != nil {
		t.Fatal(err)
	}
	if !facade.Support("example.fixture").Available {
		t.Fatal("Bash found the login-only tool but LSP did not")
	}
	// A later application cannot substitute the daemon PATH when login startup no longer finds the tool.
	t.Setenv("PATH", bin)
	setLoginTestProfile(t, `export PATH=`)
	_, err = prepare(context.Background(), candidate, &previous)
	var validation *config.EnvironmentConfigError
	if !errors.As(err, &validation) || validation.Field != "lsp.servers.fixture.command" {
		t.Fatalf("missing login executable: %v", err)
	}
	if !facade.Support("example.fixture").Available {
		t.Fatal("failed application changed the current generation")
	}
}

// Noise and values containing newlines and equals signs must not affect framing.
func TestLoginEnvironmentIgnoresProfileOutputAndFiltersShellVariables(t *testing.T) {
	bash := loginTestBash(t)
	setLoginTestProfile(t, `
    printf 'profile stdout\000with noise\n'
    printf 'profile stderr\n' >&2
    if IFS= read -r input; then exit 91; fi
    export KK_LOGIN_FIXTURE=$'first=a\nsecond=b'
    export OLDPWD=fixture
 `)
	environment, err := loadLoginEnvironment(context.Background(), loginTestSupervisor(t), bash)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Contains(environment, "KK_LOGIN_FIXTURE=first=a\nsecond=b") {
		t.Fatal("login variable missing or corrupted")
	}
	for _, entry := range environment {
		key, _, _ := strings.Cut(entry, "=")
		switch strings.ToUpper(key) {
		case "PWD", "OLDPWD", "SHLVL", "_":
			t.Fatalf("unexpected shell key %s", key)
		}
	}
}

// A profile can exit before the helper, without disclosing its output in the diagnostic.
func TestLoginEnvironmentReportsSafeFailureReasons(t *testing.T) {
	bash := loginTestBash(t)
	processes := loginTestSupervisor(t)
	for _, tc := range []struct{ code, reason string }{{"0", "missing output marker"}, {"23", "exited with code 23"}} {
		t.Run(tc.code, func(t *testing.T) {
			setLoginTestProfile(t, "printf 'private-fixture-value\n'; printf 'private-fixture-value\n' >&2; exit "+tc.code)
			_, err := loadLoginEnvironment(context.Background(), processes, bash)
			if err == nil || !strings.Contains(err.Error(), tc.reason) || strings.Contains(err.Error(), "private-fixture-value") {
				t.Fatalf("unexpected probe diagnostic: %v", err)
			}
		})
	}
}

// Timeouts must kill a descendant as well as the login shell.
func TestLoginEnvironmentTimeoutTerminatesTree(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("POSIX descendant liveness check")
	}
	bash := loginTestBash(t)
	pidFile := filepath.Join(t.TempDir(), "pid")
	t.Setenv("KK_TEST_PID_FILE", pidFile)
	setLoginTestProfile(t, `sleep 60 & printf '%s\n' "$!" > "$KK_TEST_PID_FILE"; wait`)
	_, err := probeLoginEnvironment(context.Background(), loginTestSupervisor(t), bash, time.Second)
	if err == nil || err.Error() != "Bash login environment: timed out" {
		t.Fatalf("unexpected timeout diagnostic: %v", err)
	}
	data, err := os.ReadFile(pidFile)
	if err != nil {
		t.Fatal(err)
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(data)))
	if err != nil || pid <= 0 {
		t.Fatal("invalid fixture PID")
	}
	child, err := os.FindProcess(pid)
	if err != nil {
		t.Fatal(err)
	}
	if err := child.Signal(syscall.Signal(0)); !errors.Is(err, os.ErrProcessDone) && !errors.Is(err, syscall.ESRCH) {
		t.Fatalf("profile descendant did not exit: %v", err)
	}
}

// The Windows CI Git Bash must hand a native, semicolon-separated PATH to the helper.
func TestGitBashLoginEnvironmentResolvesNativePath(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("requires Git Bash on Windows")
	}
	bash := loginTestBash(t)
	bin := filepath.Join(t.TempDir(), "login bin")
	tool := filepath.Join(bin, "kk-login-tool.exe")
	writeLSPFixtureFile(t, tool, "fixture")
	t.Setenv("KK_TEST_LOGIN_BIN", filepath.ToSlash(bin))
	setLoginTestProfile(t, `export PATH="$(cygpath -u "$KK_TEST_LOGIN_BIN"):$PATH"; export PATHEXT=.EXE`)
	environment, err := loadLoginEnvironment(context.Background(), loginTestSupervisor(t), bash)
	if err != nil {
		t.Fatal(err)
	}
	options := lsp.ResolverOptionsForEnvironment(environment)
	if !strings.Contains(options.Path, ";") {
		t.Fatal("Git Bash did not provide a native Windows PATH list")
	}
	want, err := filepath.EvalSymlinks(tool)
	if err != nil {
		t.Fatal(err)
	}
	if resolved, ok := lsp.NewExecutableResolver(options).Resolve("kk-login-tool"); !ok || !strings.EqualFold(resolved, want) {
		t.Fatal("could not resolve executable from Git Bash native PATH")
	}
}

func loginTestBash(t *testing.T) string {
	t.Helper()
	if runtime.GOOS == "windows" {
		// Prefer Git Bash to a potential WSL launcher on the system PATH.
		candidate := filepath.Join(os.Getenv("ProgramFiles"), "Git", "bin", "bash.exe")
		if bash, err := config.ResolveBash(&candidate); err == nil {
			return bash
		}
	}
	bash, err := config.ResolveBash(nil)
	if err != nil {
		t.Skip("Bash is unavailable")
	}
	return bash
}

func setLoginTestProfile(t *testing.T, contents string) {
	t.Helper()
	profile := filepath.Join(t.TempDir(), "profile")
	writeLSPFixtureFile(t, profile, contents+"\n")
	t.Setenv("BASH_ENV", filepath.ToSlash(profile))
}

func loginTestSupervisor(t *testing.T) native.Supervisor {
	t.Helper()
	processes, err := native.NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := processes.Close(context.Background()); err != nil {
			t.Error(err)
		}
	})
	return processes
}
