//go:build linux

package native

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

// NAT-N-004 verifies helper startup leaves thread-bound Pdeathsig unset.
func TestPOSIXSupervisor_HelperLeavesPdeathsigUnset(t *testing.T) {
	supervisorValue, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	supervisor := supervisorValue.(*PosixSupervisor)
	var observed *syscall.SysProcAttr
	supervisor.startHelper = func(command *exec.Cmd) error {
		if command.SysProcAttr == nil {
			return errors.New("helper SysProcAttr is missing")
		}
		copy := *command.SysProcAttr
		observed = &copy
		return command.Start()
	}
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/sh", Args: []string{"-c", "exit 0"}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := io.ReadAll(process.Stdout()); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := process.Wait(ctx); err != nil {
		t.Fatal(err)
	}
	if observed == nil || !observed.Setsid || observed.Pdeathsig != 0 {
		t.Fatalf("helper process attributes = %+v, want Setsid and no Pdeathsig", observed)
	}
	if err := supervisor.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
}

// NAT-N-004 kills a real owner process and proves subreaper convergence does not depend on Pdeathsig.
func TestLinuxOwnerSIGKILL_UsesSubreaperNotPdeathsig(t *testing.T) {
	if err := enableChildSubreaper(); err != nil {
		t.Fatal(err)
	}
	dir := stateTestDir(t)
	marker := filepath.Join(t.TempDir(), "command-started")
	launch, err := json.Marshal(launchSpec{Cwd: t.TempDir(), Args: []string{"/bin/sh", "-c", "trap '' TERM; printf started > " + strconv.Quote(marker) + "; while :; do sleep 1; done"}})
	if err != nil {
		t.Fatal(err)
	}
	if err := publish(dir, launchFile, launch); err != nil {
		t.Fatal(err)
	}
	owner := exec.Command(os.Args[0], "__native-owner-fixture", dir)
	if err := owner.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() {
		if owner.Process != nil {
			_ = owner.Process.Kill()
			_ = owner.Wait()
		}
	}()
	deadline := time.Now().Add(5 * time.Second)
	var helperPID int
	for time.Now().Before(deadline) {
		data, readErr := readState(dir, scopeFile)
		if readErr == nil {
			helperPID, err = strconv.Atoi(strings.TrimSpace(string(data)))
			if err != nil {
				t.Fatal(err)
			}
			break
		}
		time.Sleep(scopePollInterval)
	}
	if helperPID == 0 {
		t.Fatal("owner fixture did not publish helper scope")
	}
	if err := publishText(dir, permitFile, "true"); err != nil {
		t.Fatal(err)
	}
	deadline = time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if _, err := os.Stat(marker); err == nil {
			break
		}
		time.Sleep(scopePollInterval)
	}
	if _, err := os.Stat(marker); err != nil {
		t.Fatalf("command did not start after permit: %v", err)
	}
	killedAt := time.Now()
	if err := owner.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	if err := owner.Wait(); err == nil {
		t.Fatal("owner fixture exited without SIGKILL")
	}
	owner.Process = nil
	deadline = time.Now().Add(8 * time.Second)
	helperReaped := false
	for time.Now().Before(deadline) {
		var status unix.WaitStatus
		waited, waitErr := unix.Wait4(helperPID, &status, unix.WNOHANG, nil)
		if waitErr == nil && waited == helperPID {
			if !status.Exited() && !status.Signaled() {
				t.Fatalf("helper wait status = %#v", status)
			}
			helperReaped = true
			break
		}
		if waitErr != nil && !errors.Is(waitErr, unix.EINTR) {
			t.Fatalf("wait for adopted helper %d: %v", helperPID, waitErr)
		}
		time.Sleep(scopePollInterval)
	}
	if !helperReaped {
		t.Fatalf("helper %d was not reaped after owner SIGKILL", helperPID)
	}
	cleanup, err := readCleanup(dir)
	if err != nil || cleanup == nil || !*cleanup {
		t.Fatalf("cleanup after SIGKILL owner = %v, %v", cleanup, err)
	}
	if elapsed := time.Since(killedAt); elapsed < helperGracePeriod {
		t.Fatalf("owner-death convergence took %s, shorter than helper grace %s", elapsed, helperGracePeriod)
	}
	members, err := sessionMembers(helperPID)
	if err != nil {
		t.Fatal(err)
	}
	if len(members) != 0 {
		t.Fatalf("session remains after SIGKILL owner: %+v", members)
	}
	_ = removeStateDir(dir)
}
