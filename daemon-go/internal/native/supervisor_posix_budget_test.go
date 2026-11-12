//go:build linux || darwin

package native

import (
	"context"
	"os"
	"strings"
	"syscall"
	"testing"
	"time"
)

// NAT-F-008 verifies POSIX timing constants stay aligned with the shared supervisor contract.
func TestPOSIXSupervisorBudgets(t *testing.T) {
	budgets := map[string]struct {
		got  time.Duration
		want time.Duration
	}{
		"default grace":     {got: defaultGracePeriod, want: time.Second},
		"force kill":        {got: posixForceKillBudget, want: time.Second},
		"helper grace":      {got: helperGracePeriod, want: 600 * time.Millisecond},
		"poll interval":     {got: scopePollInterval, want: 5 * time.Millisecond},
		"scope start":       {got: scopeStartBudget, want: 20 * time.Second},
		"scope convergence": {got: scopeConvergeBudget, want: 4 * time.Second},
	}
	for name, budget := range budgets {
		if budget.got != budget.want {
			t.Errorf("%s budget = %s, want %s", name, budget.got, budget.want)
		}
	}
}

// NAT-F-008 verifies Close routes through the specified one-second default grace.
func TestPOSIXSupervisor_CloseUsesDefaultGrace(t *testing.T) {
	stdinReader, stdinWriter, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	stdoutReader, stdoutWriter, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	stderrReader, stderrWriter, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	ownerReader, ownerWriter, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	_ = stdinReader.Close()
	_ = stdoutWriter.Close()
	_ = stderrWriter.Close()
	_ = ownerReader.Close()
	done := make(chan struct{})
	close(done)
	supervisor := &PosixSupervisor{processes: make(map[int]*posixProcess)}
	process := &posixProcess{
		supervisor: supervisor,
		pid:        42,
		sid:        42,
		stateDir:   stateTestDir(t),
		stdin:      stdinWriter,
		stdout:     stdoutReader,
		stderr:     stderrReader,
		owner:      ownerWriter,
		done:       done,
	}
	var observed time.Duration
	process.convergeScope = func(_ context.Context, _ int, grace time.Duration) error {
		observed = grace
		return nil
	}
	supervisor.processes[process.pid] = process
	if err := supervisor.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	if observed != defaultGracePeriod {
		t.Fatalf("Close grace = %s, want %s", observed, defaultGracePeriod)
	}
}

// NAT-F-008 exercises the real convergence timer rather than the fake-clock boundary.
func TestPOSIXConvergence_RealBudgetExpires(t *testing.T) {
	api := nativePOSIXConvergenceAPI()
	api.members = func(int) ([]sessionMember, error) {
		return []sessionMember{{PID: 42, Parent: 1, Session: 42, Identity: "still-live", State: "S", Threads: 1}}, nil
	}
	api.kill = func(int, syscall.Signal) error { return nil }
	started := time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), scopeConvergeBudget+time.Second)
	defer cancel()
	err := convergeSessionFencedWithAPI(ctx, 42, 0, 0, nil, api)
	elapsed := time.Since(started)
	if err == nil || !strings.Contains(err.Error(), "did not converge within its deadline") {
		t.Fatalf("convergence result = %v, want budget expiration", err)
	}
	if elapsed < scopeConvergeBudget || elapsed > scopeConvergeBudget+time.Second {
		t.Fatalf("real convergence elapsed = %s, want %s through %s", elapsed, scopeConvergeBudget, scopeConvergeBudget+time.Second)
	}
}
