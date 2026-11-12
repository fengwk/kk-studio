//go:build linux || darwin

package native

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

// The real re-exec path makes helperMain's launch, permit, exit, and cleanup branches observable.
func TestPOSIXHelper_ReexecPermitAndExitLifecycle(t *testing.T) {
	dir := stateTestDir(t)
	launch, err := json.Marshal(launchSpec{Cwd: t.TempDir(), Args: []string{"/bin/sh", "-c", "exit 21"}})
	if err != nil {
		t.Fatal(err)
	}
	if err := publish(dir, launchFile, launch); err != nil {
		t.Fatal(err)
	}

	ownerReader, ownerWriter, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer ownerWriter.Close()
	cmd := exec.Command(os.Args[0], "__native-helper", dir)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	cmd.ExtraFiles = []*os.File{ownerReader}
	if err := cmd.Start(); err != nil {
		_ = ownerReader.Close()
		t.Fatal(err)
	}
	_ = ownerReader.Close()
	defer func() {
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
			_, _ = cmd.Process.Wait()
		}
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	for {
		if value, err := readState(dir, scopeFile); err == nil {
			if strings.TrimSpace(string(value)) != fmt.Sprint(cmd.Process.Pid) {
				t.Fatalf("helper scope = %q, want its PID %d", value, cmd.Process.Pid)
			}
			break
		}
		select {
		case <-ctx.Done():
			t.Fatalf("helper did not publish its scope: %v", ctx.Err())
		case <-time.After(scopePollInterval):
		}
	}
	if err := publishText(dir, permitFile, "true"); err != nil {
		t.Fatal(err)
	}
	if err := cmd.Wait(); err != nil {
		t.Fatalf("helper exit = %v, want success", err)
	}

	exit, err := readExit(dir)
	if err != nil || exit == nil || exit.ExitCode == nil || *exit.ExitCode != 21 {
		t.Fatalf("published command exit = %+v, %v; want code 21", exit, err)
	}
	cleanup, err := readCleanup(dir)
	if err != nil || cleanup == nil || !*cleanup {
		t.Fatalf("published cleanup = %v, %v; want true", cleanup, err)
	}
	if _, err := os.Stat(filepath.Join(dir, errorFile)); !os.IsNotExist(err) {
		t.Fatalf("unexpected helper failure record: %v", err)
	}
}

func TestPOSIXHelper_RejectsInvalidPermitRecords(t *testing.T) {
	for _, test := range []struct {
		name      string
		permitDir bool
		permit    string
		wantPhase string
		wantClean bool
	}{
		{name: "false permit", permit: "false", wantPhase: "helper-permit-failure", wantClean: true},
		{name: "unreadable permit", permitDir: true, wantPhase: "helper-posix-failure"},
	} {
		t.Run(test.name, func(t *testing.T) {
			dir := stateTestDir(t)
			launch, err := json.Marshal(launchSpec{Cwd: t.TempDir(), Args: []string{"/bin/true"}})
			if err != nil {
				t.Fatal(err)
			}
			if err := publish(dir, launchFile, launch); err != nil {
				t.Fatal(err)
			}
			permitPath := filepath.Join(dir, permitFile)
			if test.permitDir {
				if err := os.Mkdir(permitPath, 0700); err != nil {
					t.Fatal(err)
				}
			} else if err := publishText(dir, permitFile, test.permit); err != nil {
				t.Fatal(err)
			}
			cmd, ownerWriter := startPOSIXHelperForTest(t, dir)
			defer func() {
				_ = ownerWriter.Close()
				if cmd.Process != nil {
					_ = cmd.Process.Kill()
					_, _ = cmd.Process.Wait()
				}
			}()
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			waitForPOSIXHelperScope(t, ctx, dir, cmd.Process.Pid)
			if err := cmd.Wait(); err == nil {
				t.Fatal("helper accepted invalid permit record")
			}
			phase, err := readState(dir, errorFile)
			if err != nil || string(phase) != test.wantPhase {
				t.Fatalf("helper phase = %q, %v; want %q", phase, err, test.wantPhase)
			}
			cleanup, err := readCleanup(dir)
			if test.wantClean {
				if err != nil || cleanup == nil || !*cleanup {
					t.Fatalf("cleanup evidence = %v, %v; want true", cleanup, err)
				}
			} else if !os.IsNotExist(err) {
				t.Fatalf("cleanup evidence = %v, %v; want absent", cleanup, err)
			}
		})
	}
}

func startPOSIXHelperForTest(t *testing.T, dir string) (*exec.Cmd, *os.File) {
	t.Helper()
	ownerReader, ownerWriter, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(os.Args[0], "__native-helper", dir)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	cmd.ExtraFiles = []*os.File{ownerReader}
	if err := cmd.Start(); err != nil {
		_ = ownerReader.Close()
		_ = ownerWriter.Close()
		t.Fatal(err)
	}
	_ = ownerReader.Close()
	return cmd, ownerWriter
}

func waitForPOSIXHelperScope(t *testing.T, ctx context.Context, dir string, pid int) {
	t.Helper()
	for {
		if value, err := readState(dir, scopeFile); err == nil {
			if strings.TrimSpace(string(value)) != fmt.Sprint(pid) {
				t.Fatalf("helper scope = %q, want its PID %d", value, pid)
			}
			return
		}
		select {
		case <-ctx.Done():
			t.Fatalf("helper did not publish its scope: %v", ctx.Err())
		case <-time.After(scopePollInterval):
		}
	}
}

func TestPOSIXHelper_OwnerDeathBeforePermitDoesNotRunCommand(t *testing.T) {
	dir := stateTestDir(t)
	marker := filepath.Join(t.TempDir(), "command-ran")
	launch, err := json.Marshal(launchSpec{Cwd: t.TempDir(), Args: []string{"/bin/sh", "-c", "touch \"$1\"", "helper-command", marker}})
	if err != nil {
		t.Fatal(err)
	}
	if err := publish(dir, launchFile, launch); err != nil {
		t.Fatal(err)
	}

	ownerReader, ownerWriter, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(os.Args[0], "__native-helper", dir)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	cmd.ExtraFiles = []*os.File{ownerReader}
	if err := cmd.Start(); err != nil {
		_ = ownerReader.Close()
		_ = ownerWriter.Close()
		t.Fatal(err)
	}
	_ = ownerReader.Close()
	defer func() {
		_ = ownerWriter.Close()
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
			_, _ = cmd.Process.Wait()
		}
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	for {
		if _, err := readState(dir, scopeFile); err == nil {
			break
		}
		select {
		case <-ctx.Done():
			t.Fatalf("helper did not publish its scope: %v", ctx.Err())
		case <-time.After(scopePollInterval):
		}
	}
	if err := ownerWriter.Close(); err != nil {
		t.Fatal(err)
	}
	if err := cmd.Wait(); err != nil {
		t.Fatalf("helper after owner death = %v, want clean convergence", err)
	}
	if _, err := os.Stat(marker); !os.IsNotExist(err) {
		t.Fatalf("command ran before permit; marker stat error = %v", err)
	}
	cleanup, err := readCleanup(dir)
	if err != nil || cleanup == nil || !*cleanup {
		t.Fatalf("published cleanup = %v, %v; want true", cleanup, err)
	}
	if _, err := os.Stat(filepath.Join(dir, exitFile)); !os.IsNotExist(err) {
		t.Fatalf("command exit record exists without permit: %v", err)
	}
}
