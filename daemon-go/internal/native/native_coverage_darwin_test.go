//go:build linux || darwin

package native

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"syscall"
	"testing"
	"time"
)

func TestPOSIXMergeEnvironmentFiltersInvalidAndNodeEntries(t *testing.T) {
	got := mergeEnvironment(
		[]string{"Z=last", "NO_EQUALS", "=empty", "NODE_CHANNEL_FD=3", "A=one\x00bad"},
		[]string{"A=valid", "NODE_CHANNEL_FD=4"},
	)
	want := []string{"A=valid", "Z=last"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("mergeEnvironment() = %q, want %q", got, want)
	}
}

func TestSessionParentsBeforeChildrenRejectsInvalidPIDAndSortsRoots(t *testing.T) {
	if _, err := parentsBeforeChildren([]sessionMember{{PID: 0, Parent: 1}}); err == nil {
		t.Fatal("parentsBeforeChildren accepted PID zero")
	}
	got, err := parentsBeforeChildren([]sessionMember{
		{PID: 5, Parent: 99},
		{PID: 3, Parent: 99},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 || got[0].PID != 3 || got[1].PID != 5 {
		t.Fatalf("ordered roots = %+v, want PIDs [3 5]", got)
	}
}

func TestPOSIXConvergenceRejectsUnknownIdentityAndExpiredDeadline(t *testing.T) {
	t.Run("unknown identity", func(t *testing.T) {
		api := posixConvergenceAPI{
			members: func(int) ([]sessionMember, error) {
				return []sessionMember{{PID: 42, Parent: 1, Session: 42}}, nil
			},
			kill: func(int, syscall.Signal) error { t.Fatal("signalled member with unknown identity"); return nil },
			now:  time.Now,
			wait: func(context.Context, time.Duration) error { t.Fatal("waited after unknown identity"); return nil },
		}
		if err := convergeSessionFencedWithAPI(context.Background(), 42, 0, 0, nil, api); err == nil || err.Error() != "process scope member identity is unknown" {
			t.Fatalf("convergence error = %v, want unknown identity", err)
		}
	})

	t.Run("deadline already exhausted", func(t *testing.T) {
		start := time.Unix(1, 0)
		clockReads := 0
		api := posixConvergenceAPI{
			members: func(int) ([]sessionMember, error) {
				return []sessionMember{{PID: 42, Parent: 1, Session: 42, Identity: "identity"}}, nil
			},
			kill: func(int, syscall.Signal) error { return nil },
			now: func() time.Time {
				clockReads++
				if clockReads == 4 {
					return start.Add(scopeConvergeBudget)
				}
				return start
			},
			wait: func(context.Context, time.Duration) error { t.Fatal("waited after deadline expiry"); return nil },
		}
		if err := convergeSessionFencedWithAPI(context.Background(), 42, time.Hour, 0, nil, api); err == nil || err.Error() != "process scope did not converge within its deadline" {
			t.Fatalf("convergence error = %v, want expired deadline", err)
		}
	})
}

func TestPOSIXNativeWaitAndAwaitScopeReturnContextError(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := nativePOSIXConvergenceAPI().wait(ctx, time.Hour); !errors.Is(err, context.Canceled) {
		t.Fatalf("native convergence wait error = %v, want context cancellation", err)
	}

	p := &posixProcess{stateDir: stateTestDir(t), done: make(chan struct{})}
	if err := p.awaitScopeWithMembers(ctx, func(int) ([]sessionMember, error) {
		t.Fatal("queried members after context cancellation")
		return nil, nil
	}); !errors.Is(err, context.Canceled) {
		t.Fatalf("awaitScope error = %v, want context cancellation", err)
	}
}

func TestHelper_EmptyCommandFailsClosed(t *testing.T) {
	dir := stateTestDir(t)
	launch, err := json.Marshal(launchSpec{Cwd: t.TempDir(), Args: []string{}})
	if err != nil {
		t.Fatal(err)
	}
	if err := publish(dir, launchFile, launch); err != nil {
		t.Fatal(err)
	}
	if err := publishText(dir, permitFile, "true"); err != nil {
		t.Fatal(err)
	}
	cmd, ownerWriter := startPOSIXHelperForTest(t, dir)
	defer ownerWriter.Close()
	if err := cmd.Wait(); err == nil {
		t.Fatal("helper accepted an empty command")
	}
	phase, err := readState(dir, errorFile)
	if err != nil || string(phase) != "helper-posix-failure" {
		t.Fatalf("helper failure phase = %q, %v; want helper-posix-failure", phase, err)
	}
	cleanup, err := readCleanup(dir)
	if err != nil || cleanup == nil || !*cleanup {
		t.Fatalf("helper cleanup evidence = %v, %v; want true", cleanup, err)
	}
	if _, err := os.Stat(filepath.Join(dir, exitFile)); !os.IsNotExist(err) {
		t.Fatalf("empty command produced exit report: %v", err)
	}
}
