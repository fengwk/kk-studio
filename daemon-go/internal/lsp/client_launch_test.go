package lsp

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/native"
)

// LSP-T-091 through LSP-T-094 verify launch exit and convergence error reporting.
func TestProtocol_LaunchFailureStates(t *testing.T) {
	cases := []struct {
		name      string
		cause     error
		converged bool
	}{{"spawn failure", errors.New("spawn failed"), true}, {"unconverged process scope", native.ErrScopeNotConverged, false}}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			supervisor := &fakeSupervisor{startErr: tc.cause}
			_, err := Launch(context.Background(), ServerConfig{ID: "broken", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
			var bootstrap *LspBootstrapError
			if !errors.As(err, &bootstrap) || bootstrap.Converged != tc.converged || !errors.Is(err, tc.cause) || bootstrap.Error() != "LSP server 'broken' cannot be started: fake" {
				t.Fatalf("launch error = %#v", err)
			}
		})
	}
	supervisor := &fakeSupervisor{earlyExit: &native.ProcessExit{Code: 7}}
	_, err := Launch(context.Background(), ServerConfig{ID: "dead", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	var bootstrap *LspBootstrapError
	if !errors.As(err, &bootstrap) || !bootstrap.Converged || bootstrap.Error() != "LSP server 'dead' exited before initialization (exit code 7)" {
		t.Fatalf("early exit error = %#v", err)
	}
}

// LSP-T-095 checks initialize timeout cleanup for a server that never replies.
func TestProtocol_InitializeTimeout(t *testing.T) {
	supervisor := &fakeSupervisor{blockMethod: "initialize"}
	client, err := Launch(context.Background(), ServerConfig{ID: "slow", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	err = client.Initialize(context.Background(), 20*time.Millisecond)
	if err == nil || (!strings.HasPrefix(err.Error(), "LSP request timed out after ") || !strings.Contains(err.Error(), " (initialize).")) || time.Since(start) > time.Second {
		t.Fatalf("initialize timeout = %v after %s", err, time.Since(start))
	}
	if err := client.Stop(10 * time.Millisecond); err != nil {
		t.Fatalf("stop after failed initialize = %v", err)
	}
}

// LSP-T-096 reports a server crash only after its managed stop converges.
func TestProtocol_Crash(t *testing.T) {
	supervisor := &fakeSupervisor{blockMethod: "slow/request"}
	client, err := Launch(context.Background(), ServerConfig{ID: "fake", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err = client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	requestResult := make(chan error, 1)
	finished := make(chan struct{}, 1)
	go func() {
		requestResult <- client.call(context.Background(), "slow/request", map[string]any{}, nil, func() {}, func() { finished <- struct{}{} })
	}()
	waitForFakeRequest(t, supervisor.process, "slow/request")
	if err = supervisor.process.stdoutW.Close(); err != nil {
		t.Fatal(err)
	}
	supervisor.process.exitOnce.Do(func() { supervisor.process.done <- native.ProcessExit{Code: 11} })
	select {
	case err = <-requestResult:
	case <-time.After(time.Second):
		t.Fatal("request did not observe process exit")
	}
	if err == nil || err.Error() != "LSP server 'fake' exited (exit code 11)" {
		t.Fatalf("crash error = %v", err)
	}
	select {
	case <-finished:
	case <-time.After(time.Second):
		t.Fatal("confirmed exit did not wait for managed stop")
	}
	if err := client.Stop(time.Millisecond); err != nil {
		t.Fatalf("repeated stop = %v", err)
	}
}

// LSP-T-090 cancels a sent request without confirming process exit.
func TestProtocol_RequestCancellation(t *testing.T) {
	supervisor := &fakeSupervisor{blockMethod: "slow/request"}
	client, err := Launch(context.Background(), ServerConfig{ID: "fake", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err = client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	requestResult := make(chan error, 1)
	finished := make(chan struct{}, 1)
	go func() {
		requestResult <- client.call(ctx, "slow/request", map[string]any{}, nil, func() {}, func() { finished <- struct{}{} })
	}()
	waitForFakeRequest(t, supervisor.process, "slow/request")
	cancel()
	select {
	case err = <-requestResult:
	case <-time.After(time.Second):
		t.Fatal("cancelled request did not return")
	}
	if err == nil || err.Error() != "Operation cancelled" {
		t.Fatalf("cancelled request error = %v", err)
	}
	select {
	case <-finished:
		t.Fatal("local cancellation confirmed server exit")
	case <-time.After(10 * time.Millisecond):
	}
	select {
	case <-supervisor.process.cancelled:
	case <-time.After(time.Second):
		t.Fatal("server did not receive cancellation notification")
	}
	if err := client.Stop(time.Millisecond); err != nil {
		t.Fatal(err)
	}
}

func waitForFakeRequest(t *testing.T, process *fakeProcess, want string) {
	t.Helper()
	deadline := time.After(time.Second)
	for {
		select {
		case method := <-process.requests:
			if method == want {
				return
			}
		case <-deadline:
			t.Fatalf("request %q was not sent", want)
		}
	}
}

// LSP-T-076 and LSP-T-089 redact server details but mark a response as completed.
func TestProtocol_ServerErrorCompletesRequest(t *testing.T) {
	supervisor := &fakeSupervisor{errorMethod: "test/error"}
	client, err := Launch(context.Background(), ServerConfig{ID: "fake", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err = client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	sent, finished := false, false
	err = client.call(context.Background(), "test/error", map[string]any{}, nil, func() { sent = true }, func() { finished = true })
	if err == nil || err.Error() != "LSP request failed (test/error)" || strings.Contains(err.Error(), "secret server details") || !sent || !finished {
		t.Fatalf("server error=%v sent=%v finished=%v", err, sent, finished)
	}
	if err = client.Stop(time.Millisecond); err != nil {
		t.Fatal(err)
	}
}

// LSP-T-078 verifies definition and workspace-symbol requests require advertised capabilities.
func TestProtocol_CapabilityRequired(t *testing.T) {
	supervisor := &fakeSupervisor{}
	client, err := Launch(context.Background(), ServerConfig{ID: "plain", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err = client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	client.mu.Lock()
	client.capabilities = map[string]json.RawMessage{"definitionProvider": json.RawMessage("false"), "workspaceSymbolProvider": json.RawMessage("null")}
	client.mu.Unlock()
	if _, err = client.Definition(context.Background(), "unused.go", 1, 0); err == nil || err.Error() != "LSP server 'plain' does not advertise go-to-definition support." {
		t.Fatalf("definition error = %v", err)
	}
	if _, err = client.WorkspaceSymbols(context.Background(), "x", 1); err == nil || err.Error() != "LSP server 'plain' does not advertise workspace symbol support." {
		t.Fatalf("workspace symbols error = %v", err)
	}
	if err := client.Stop(time.Millisecond); err != nil {
		t.Fatal(err)
	}
}
