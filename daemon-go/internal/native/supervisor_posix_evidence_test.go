//go:build linux || darwin

package native

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// NAT-F-008 verifies failed-start cleanup uses its independent force-kill budget and retains evidence if proof fails.
func TestPOSIXCleanupFailedStart_BudgetAndFailureEvidence(t *testing.T) {
	for _, testCase := range []struct {
		name          string
		convergeError error
	}{
		{name: "converges"},
		{name: "cannot prove convergence", convergeError: errors.New("injected convergence failure")},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			dir := stateTestDir(t)
			stream, err := os.OpenFile(filepath.Join(dir, "stream"), os.O_RDWR|os.O_CREATE, 0600)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = stream.Close() })
			done := make(chan struct{})
			close(done)
			cause := errors.New("startup failed")
			process := &posixProcess{
				stateDir: dir,
				done:     done,
				owner:    stream,
				stdin:    stream,
				stdout:   stream,
				stderr:   stream,
				convergeScope: func(ctx context.Context, sid int, grace time.Duration) error {
					if sid != 0 || grace != 0 {
						t.Errorf("converge arguments = SID %d, grace %s", sid, grace)
					}
					deadline, ok := ctx.Deadline()
					if !ok || time.Until(deadline) > posixForceKillBudget || time.Until(deadline) <= 0 {
						t.Errorf("cleanup context deadline = %v, want within %s", deadline, posixForceKillBudget)
					}
					return testCase.convergeError
				},
			}
			err = process.cleanupFailedStart(cause)
			if testCase.convergeError == nil {
				if !errors.Is(err, cause) {
					t.Fatalf("cleanup error = %v, want original cause", err)
				}
				if _, statErr := os.Stat(dir); !errors.Is(statErr, os.ErrNotExist) {
					t.Fatalf("state directory remains after proven cleanup: %v", statErr)
				}
				return
			}
			var notConverged *ProcessScopeNotConvergedError
			if !errors.As(err, &notConverged) || !errors.Is(err, cause) || !errors.Is(err, testCase.convergeError) {
				t.Fatalf("cleanup error = %v, want original and convergence failures", err)
			}
			if _, statErr := os.Stat(dir); statErr != nil {
				t.Fatalf("state evidence was removed without convergence proof: %v", statErr)
			}
		})
	}
}

// NAT-T-001
func TestPOSIXSupervisor_UnprovenConvergenceRetainsEvidence(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/true"})
	if err != nil {
		t.Fatal(err)
	}
	managed := process.(*posixProcess)
	t.Cleanup(func() { _ = removeStateDir(managed.stateDir) })
	<-managed.done
	cleanupPath := filepath.Join(managed.stateDir, cleanupFile)
	if _, err := os.Stat(cleanupPath); err != nil {
		t.Fatalf("cleanup evidence missing before tampering: %v", err)
	}
	if err := os.Remove(cleanupPath); err != nil {
		t.Fatal(err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	_, firstErr := managed.Wait(ctx)
	if !errors.Is(firstErr, ErrScopeNotConverged) {
		t.Fatalf("first wait error = %v", firstErr)
	}
	if firstErr != managed.termErr {
		t.Fatal("wait did not cache the failure")
	}
	if _, err := os.Stat(managed.stateDir); err != nil {
		t.Fatalf("state evidence was removed: %v", err)
	}
	if _, err := managed.stdin.Write([]byte("closed")); !errors.Is(err, os.ErrClosed) {
		t.Fatalf("stdin write after failed wait = %v", err)
	}
	if _, err := managed.stdout.Read(make([]byte, 1)); !errors.Is(err, os.ErrClosed) {
		t.Fatalf("stdout read after failed wait = %v", err)
	}
	if _, secondErr := managed.Wait(ctx); secondErr != firstErr {
		t.Fatalf("second wait error %v differs from first %v", secondErr, firstErr)
	}
	if err := managed.Terminate(ctx, 0); err != firstErr {
		t.Fatalf("terminate error %v differs from cached error %v", err, firstErr)
	}
}
