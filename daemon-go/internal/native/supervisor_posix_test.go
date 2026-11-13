//go:build linux || darwin

package native

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strconv"
	"syscall"
	"testing"
	"time"

	"go.uber.org/goleak"
)

func TestMain(m *testing.M) {
	if len(os.Args) > 1 && os.Args[1] == "__native-helper" {
		os.Exit(HelperMain(os.Args[2:]))
	}
	goleak.VerifyTestMain(m)
}

// NAT-F-005 verifies completed command wait and exit publication precede session convergence and adopted-child reaping.
func TestPOSIXHelper_ExitBeforeAdoptedReaper(t *testing.T) {
	events := []string{"command-wait-complete"}
	status := completeHelperCommandLifecycle(
		func() error {
			events = append(events, "publish-exit")
			return nil
		},
		func() error {
			events = append(events, "converge-session")
			return nil
		},
		func() error {
			events = append(events, "reap-adopted")
			return nil
		},
		func(value string) error {
			events = append(events, "publish-cleanup-"+value)
			return nil
		},
		func() { events = append(events, "publish-error") },
	)
	if status != 0 {
		t.Fatalf("helper completion status = %d, want 0", status)
	}
	want := []string{"command-wait-complete", "publish-exit", "converge-session", "reap-adopted", "publish-cleanup-true"}
	if !reflect.DeepEqual(events, want) {
		t.Fatalf("helper completion events = %v, want %v", events, want)
	}
}

func TestPOSIXHelper_CompletionFailureStopsLifecycle(t *testing.T) {
	exitFailure := errors.New("exit publication failed")
	sessionFailure := errors.New("session convergence failed")
	reaperFailure := errors.New("adopted reaping failed")
	cleanupFailure := errors.New("cleanup publication failed")
	for _, testCase := range []struct {
		name       string
		exitErr    error
		sessionErr error
		reaperErr  error
		cleanupErr error
		want       []string
	}{
		{name: "exit publication", exitErr: exitFailure, want: []string{"exit", "session", "cleanup-false", "error"}},
		{name: "session convergence", sessionErr: sessionFailure, want: []string{"exit", "session", "cleanup-false", "error"}},
		{name: "adopted reaper", reaperErr: reaperFailure, want: []string{"exit", "session", "reaper", "cleanup-false", "error"}},
		{name: "cleanup publication", cleanupErr: cleanupFailure, want: []string{"exit", "session", "reaper", "cleanup-true"}},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			var events []string
			status := completeHelperCommandLifecycle(
				func() error { events = append(events, "exit"); return testCase.exitErr },
				func() error { events = append(events, "session"); return testCase.sessionErr },
				func() error { events = append(events, "reaper"); return testCase.reaperErr },
				func(value string) error { events = append(events, "cleanup-"+value); return testCase.cleanupErr },
				func() { events = append(events, "error") },
			)
			if status != 1 || !reflect.DeepEqual(events, testCase.want) {
				t.Fatalf("helper status/events = %d/%v, want 1/%v", status, events, testCase.want)
			}
		})
	}
}

func TestPOSIXConvergence_InjectedFailureBranches(t *testing.T) {
	active := []sessionMember{{PID: 42, Parent: 1, Session: 42, Identity: "identity", State: "S", Threads: 1}}
	queryFailure := errors.New("snapshot failure")
	signalFailure := errors.New("signal failure")
	for _, testCase := range []struct {
		name         string
		snapshots    [][]sessionMember
		queryErrorAt int
		queryError   error
		anchor       *trackedSession
		killError    error
		waitError    error
		wantError    error
		wantSignals  int
	}{
		{name: "snapshot error", queryErrorAt: 1, queryError: queryFailure, wantError: queryFailure},
		{name: "duplicate process identity", snapshots: [][]sessionMember{{active[0], active[0]}}, wantError: errors.New("process scope snapshot contains a duplicate PID")},
		{name: "tracked anchor missing", snapshots: [][]sessionMember{active}, anchor: &trackedSession{ChildPID: 99, Session: 42, Identity: "stale"}, wantError: errors.New("tracked process identity changed before session convergence")},
		{name: "unknown member identity", snapshots: [][]sessionMember{{{PID: 42, Parent: 1, Session: 42, State: "S", Threads: 1}}}, wantError: errors.New("process scope member identity is unknown")},
		{name: "verification snapshot error", snapshots: [][]sessionMember{active}, queryErrorAt: 2, queryError: queryFailure, wantError: queryFailure},
		{name: "identity changes before signal", snapshots: [][]sessionMember{active, {{PID: 42, Parent: 1, Session: 42, Identity: "reused", State: "S", Threads: 1}}, nil}},
		{name: "signal failure", snapshots: [][]sessionMember{active, active}, killError: signalFailure, wantError: signalFailure, wantSignals: 1},
		{name: "vanished process signal", snapshots: [][]sessionMember{active, active, nil}, killError: syscall.ESRCH, wantSignals: 1},
		{name: "cancelled poll", snapshots: [][]sessionMember{active, active}, waitError: context.Canceled, wantError: context.Canceled, wantSignals: 1},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			var snapshotIndex, queryCalls int
			var signals []syscall.Signal
			api := posixConvergenceAPI{
				members: func(int) ([]sessionMember, error) {
					queryCalls++
					if queryCalls == testCase.queryErrorAt {
						return nil, testCase.queryError
					}
					if snapshotIndex >= len(testCase.snapshots) {
						return nil, nil
					}
					members := testCase.snapshots[snapshotIndex]
					snapshotIndex++
					return members, nil
				},
				kill: func(_ int, signal syscall.Signal) error {
					signals = append(signals, signal)
					return testCase.killError
				},
				now:  func() time.Time { return time.Unix(100, 0) },
				wait: func(context.Context, time.Duration) error { return testCase.waitError },
			}
			err := convergeSessionFencedWithAPI(context.Background(), 42, 0, 0, testCase.anchor, api)
			if testCase.wantError != nil {
				if err == nil || (testCase.wantError == queryFailure || testCase.wantError == signalFailure || testCase.wantError == context.Canceled) && !errors.Is(err, testCase.wantError) {
					t.Fatalf("convergence error = %v, want %v", err, testCase.wantError)
				}
			} else if err != nil {
				t.Fatalf("convergence error = %v", err)
			}
			if len(signals) != testCase.wantSignals {
				t.Fatalf("signals = %v, want %d", signals, testCase.wantSignals)
			}
		})
	}
}

// NAT-F-008 verifies convergence returns a distinct error when its independent four-second budget expires.
func TestPOSIXConvergence_IndependentBudgetExpires(t *testing.T) {
	start := time.Unix(200, 0)
	current := start
	active := []sessionMember{{PID: 42, Parent: 1, Session: 42, Identity: "identity", State: "S", Threads: 1}}
	queries, clockReads := 0, 0
	waits := 0
	api := posixConvergenceAPI{
		members: func(int) ([]sessionMember, error) {
			queries++
			return active, nil
		},
		kill: func(int, syscall.Signal) error { return nil },
		now: func() time.Time {
			clockReads++
			if clockReads == 4 {
				return start.Add(scopeConvergeBudget - time.Millisecond)
			}
			return current
		},
		wait: func(_ context.Context, duration time.Duration) error {
			waits++
			if duration != time.Millisecond {
				t.Fatalf("wait duration = %s, want remaining budget 1ms", duration)
			}
			current = start.Add(scopeConvergeBudget)
			return nil
		},
	}
	err := convergeSessionFencedWithAPI(context.Background(), 42, 0, 0, nil, api)
	if err == nil || err.Error() != "process scope did not converge within its deadline" {
		t.Fatalf("convergence error = %v, want internal budget expiration", err)
	}
	if queries != 2 || waits != 1 {
		t.Fatalf("snapshot queries/waits = %d/%d, want 2/1", queries, waits)
	}
}

// NAT-F-008 verifies the deadline-driven transition from TERM through grace to KILL and re-observation.
func TestPOSIXConvergence_TermGraceKillAndReobserve(t *testing.T) {
	start := time.Unix(100, 0)
	current := start
	active := []sessionMember{{PID: 42, Parent: 1, Session: 42, Identity: "identity", State: "S", Threads: 1}}
	snapshots := [][]sessionMember{active, active, active, active, nil}
	snapshotIndex := 0
	var signals []syscall.Signal
	var waits []time.Duration
	var events []string
	api := posixConvergenceAPI{
		members: func(sid int) ([]sessionMember, error) {
			if sid != 42 {
				t.Fatalf("session query SID = %d, want 42", sid)
			}
			events = append(events, "observe")
			members := snapshots[snapshotIndex]
			snapshotIndex++
			return members, nil
		},
		kill: func(pid int, signal syscall.Signal) error {
			if pid != 42 {
				t.Fatalf("signalled PID = %d, want 42", pid)
			}
			signals = append(signals, signal)
			if signal == syscall.SIGTERM {
				events = append(events, "TERM")
			} else if signal == syscall.SIGKILL {
				events = append(events, "KILL")
			}
			return nil
		},
		now: func() time.Time { return current },
		wait: func(_ context.Context, duration time.Duration) error {
			waits = append(waits, duration)
			events = append(events, "wait")
			current = current.Add(duration)
			return nil
		},
	}
	if err := convergeSessionFencedWithAPI(context.Background(), 42, 5*time.Millisecond, 0, nil, api); err != nil {
		t.Fatal(err)
	}
	if len(signals) != 2 || signals[0] != syscall.SIGTERM || signals[1] != syscall.SIGKILL {
		t.Fatalf("signals = %v, want [TERM KILL]", signals)
	}
	if len(waits) != 2 || waits[0] != 5*time.Millisecond || waits[1] != 5*time.Millisecond {
		t.Fatalf("wait intervals = %v, want grace and post-kill observation intervals", waits)
	}
	wantEvents := []string{"observe", "observe", "TERM", "wait", "observe", "observe", "KILL", "wait", "observe"}
	if !reflect.DeepEqual(events, wantEvents) {
		t.Fatalf("convergence events = %v, want %v", events, wantEvents)
	}
	if snapshotIndex != len(snapshots) {
		t.Fatalf("snapshot observations = %d, want %d", snapshotIndex, len(snapshots))
	}
}

// NAT-N-001, NAT-T-115
func TestPOSIXSupervisor_NaturalExitDrains(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/sh", Args: []string{"-c", "printf native-ok; exit 21"}})
	if err != nil {
		t.Fatal(err)
	}
	readDone := make(chan struct{})
	var stdout []byte
	go func() { stdout, _ = io.ReadAll(process.Stdout()); close(readDone) }()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	select {
	case <-readDone:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	exit, err := process.Wait(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if string(stdout) != "native-ok" {
		t.Fatalf("stdout = %q", stdout)
	}
	if exit.Code == nil || *exit.Code != 21 {
		t.Fatalf("exit = %+v", exit)
	}
}

// NAT-T-114 verifies that environment values supplied to the daemon reach the command.
func TestPOSIXSupervisor_InheritedEnvironment(t *testing.T) {
	t.Setenv("KK_STUDIO_NATIVE_TEST_ENV", "inherited")
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{
		Executable: "/bin/sh",
		Args:       []string{"-c", "printf '%s' \"$KK_STUDIO_NATIVE_TEST_ENV\""},
	})
	if err != nil {
		t.Fatal(err)
	}
	output, err := io.ReadAll(process.Stdout())
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := process.Wait(ctx); err != nil {
		t.Fatal(err)
	}
	if string(output) != "inherited" {
		t.Fatalf("inherited environment output = %q", output)
	}
}

// NAT-T-118 verifies that stdin EOF allows the command to exit with its own code.
func TestPOSIXSupervisor_StdinEOF(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{
		Executable: "/bin/sh",
		Args:       []string{"-c", "cat >/dev/null; exit 23"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := process.Stdin().Close(); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	exit, err := process.Wait(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if exit.Code == nil || *exit.Code != 23 {
		t.Fatalf("exit after stdin EOF = %+v", exit)
	}
}

// NAT-T-128 verifies terminating one process scope leaves another live scope untouched.
func TestPOSIXSupervisor_ScopeIsolation(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	first, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/sh", Args: []string{"-c", "sleep 30"}})
	if err != nil {
		t.Fatal(err)
	}
	second, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/sh", Args: []string{"-c", "sleep 30"}})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := first.Terminate(ctx, 10*time.Millisecond); err != nil {
		t.Fatal(err)
	}
	waitCtx, waitCancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer waitCancel()
	if _, err := second.Wait(waitCtx); err == nil {
		t.Fatal("unrelated process scope exited when the first scope terminated")
	}
	if err := second.Terminate(ctx, 10*time.Millisecond); err != nil {
		t.Fatal(err)
	}
}

// NAT-T-119
func TestPOSIXSupervisor_DuplexStreams(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{
		Executable: "/bin/sh",
		Args:       []string{"-c", "IFS= read -r value; printf 'out:%s' \"$value\"; printf 'native-error' >&2; exit 7"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := io.WriteString(process.Stdin(), "native-input\n"); err != nil {
		t.Fatal(err)
	}
	if err := process.Stdin().Close(); err != nil {
		t.Fatal(err)
	}
	stdout, err := io.ReadAll(process.Stdout())
	if err != nil {
		t.Fatal(err)
	}
	stderr, err := io.ReadAll(process.Stderr())
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	exit, err := process.Wait(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if string(stdout) != "out:native-input" || string(stderr) != "native-error" {
		t.Fatalf("stdout/stderr = %q/%q", stdout, stderr)
	}
	if exit.Code == nil || *exit.Code != 7 {
		t.Fatalf("exit = %+v", exit)
	}
}

// NAT-T-133
func TestPOSIXSupervisor_StartAfterClose(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	if err := supervisor.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	if _, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/true"}); err == nil {
		t.Fatal("start after close succeeded")
	}
}

func TestContainsTrackedAnchor_RequiresExactIdentity(t *testing.T) {
	tracked := trackedSession{ChildPID: 42, Session: 40, Identity: "start-1"}
	members := []sessionMember{{PID: 42, Session: 40, Identity: "start-1"}}
	if !containsTrackedAnchor(members, tracked) {
		t.Fatal("exact tracked identity was not found")
	}
	for _, changed := range []sessionMember{
		{PID: 43, Session: 40, Identity: "start-1"},
		{PID: 42, Session: 41, Identity: "start-1"},
		{PID: 42, Session: 40, Identity: "start-2"},
	} {
		if containsTrackedAnchor([]sessionMember{changed}, tracked) {
			t.Fatalf("changed tracked identity was accepted: %+v", changed)
		}
	}
}

// Exit fallback must preserve unknown status, prefer published evidence, and decode helper wait results.
func TestPOSIXExitFallback_PreservesExitEvidence(t *testing.T) {
	dir := stateTestDir(t)
	exit := readExitFallback(dir, nil)
	if exit.Code != nil || exit.Signal != nil {
		t.Fatalf("clean helper exit without command evidence fabricated status: %+v", exit)
	}

	code := 7
	signal := "SIGTERM"
	payload, err := json.Marshal(exitReport{ExitCode: &code, Signal: &signal})
	if err != nil {
		t.Fatal(err)
	}
	if err := publish(dir, exitFile, payload); err != nil {
		t.Fatal(err)
	}
	exit = readExitFallback(dir, nil)
	if exit.Code == nil || *exit.Code != code || exit.Signal == nil || *exit.Signal != signal {
		t.Fatalf("published exit report not preserved: %+v", exit)
	}
	if err := publishText(dir, errorFile, "helper-failure"); err != nil {
		t.Fatal(err)
	}
	exit = readExitFallback(dir, nil)
	if exit.Code != nil || exit.Signal != nil {
		t.Fatalf("startup failure reported an exit status: %+v", exit)
	}

	waitErr := exec.Command("sh", "-c", "exit 23").Run()
	if waitErr == nil {
		t.Fatal("expected non-zero exit status")
	}
	exit = readExitFallback(stateTestDir(t), waitErr)
	if exit.Code == nil || *exit.Code != 23 || exit.Signal != nil {
		t.Fatalf("helper wait exit fallback = %+v, want code 23", exit)
	}

	signalErr := exec.Command("sh", "-c", "kill -TERM $$").Run()
	exit = readExitFallback(stateTestDir(t), signalErr)
	if exit.Signal == nil || *exit.Signal == "" || exit.Code != nil {
		t.Fatalf("helper wait signal fallback = %+v, want a signal", exit)
	}
	exit = readExitFallback(stateTestDir(t), errors.New("wait failed"))
	if exit.Code != nil || exit.Signal != nil {
		t.Fatalf("unknown helper wait error reported an exit status: %+v", exit)
	}
}

func TestPOSIXSupervisor_RejectsInvalidStartSpec(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())

	for _, spec := range []ProcessSpec{
		{},
		{Executable: "cmd\x00bad"},
		{Executable: "cmd", Directory: "dir\x00bad"},
		{Executable: "cmd", Args: []string{"arg\x00bad"}},
	} {
		if _, err := supervisor.Start(context.Background(), spec); err == nil {
			t.Fatalf("invalid process spec was accepted: %+v", spec)
		}
	}
}

// NAT-T-132
func TestPOSIXSupervisor_ExecFailureHasUnknownExit(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/no/such/native-command"})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	exit, err := process.Wait(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if exit.Code != nil || exit.Signal != nil {
		t.Fatalf("failed exec reported an exit status: %+v", exit)
	}
}

// NAT-T-116
func TestPOSIXSupervisor_Terminate(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/sh", Args: []string{"-c", "sleep 30"}})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := process.Terminate(ctx, 10*time.Millisecond); err != nil {
		t.Fatal(err)
	}
}

// NAT-T-129, NAT-T-130 verify descendant tracking rejects unknown roots and foreign processes.
func TestPOSIXSupervisor_TrackDescendantRejectsForeignProcess(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	if err := supervisor.(DescendantTracker).TrackDescendant(context.Background(), 99999999, os.Getpid()); err == nil {
		t.Fatal("unknown root was accepted")
	}
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/sh", Args: []string{"-c", "sleep 30"}})
	if err != nil {
		t.Fatal(err)
	}
	if err := supervisor.(DescendantTracker).TrackDescendant(context.Background(), process.PID(), os.Getpid()); err == nil {
		t.Fatal("foreign process was accepted as a managed descendant")
	}
}

// NAT-T-138 verifies that a descendant already in the managed session is accepted.
func TestPOSIXSupervisor_TrackExistingMember(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	pidFile := filepath.Join(t.TempDir(), "child.pid")
	process, err := supervisor.Start(context.Background(), ProcessSpec{
		Executable: "/bin/sh",
		Args:       []string{"-c", "sleep 30 & echo $! > " + strconv.Quote(pidFile) + "; wait"},
	})
	if err != nil {
		t.Fatal(err)
	}
	childPID := waitPIDFile(t, pidFile)
	if err := supervisor.(DescendantTracker).TrackDescendant(context.Background(), process.PID(), childPID); err != nil {
		t.Fatalf("TrackDescendant(%d) error = %v", childPID, err)
	}
}

// NAT-T-120
func TestPOSIXSupervisor_PreCancelled(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := supervisor.Start(ctx, ProcessSpec{Executable: "/bin/true"}); err == nil {
		t.Fatal("cancelled start succeeded")
	}
}
