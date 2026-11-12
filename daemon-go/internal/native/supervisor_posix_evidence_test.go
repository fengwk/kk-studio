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
