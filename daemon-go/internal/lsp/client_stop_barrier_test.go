package lsp

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/native"
)

// LSP-T-085 verifies a locally stopped request is rejected before confirmation until managed cleanup settles.
func TestProtocol_StopExitBarrier(t *testing.T) {
	gate := make(chan struct{})
	supervisor := &fakeSupervisor{blockMethod: "slow/request", terminateGate: gate}
	client, err := Launch(context.Background(), ServerConfig{ID: "fake", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err := client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	finished := make(chan struct{}, 1)
	request := make(chan error, 1)
	go func() {
		request <- client.call(context.Background(), "slow/request", map[string]any{}, nil, nil, func() { finished <- struct{}{} })
	}()
	waitForFakeRequest(t, supervisor.process, "slow/request")
	stop := make(chan error, 1)
	go func() { stop <- client.Stop(10 * time.Millisecond) }()
	select {
	case err := <-request:
		if err == nil || err.Error() != "LSP client stopped" {
			t.Fatalf("request error = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("stopped request did not settle")
	}
	select {
	case <-finished:
		t.Fatal("confirmed exit before managed termination completed")
	default:
	}
	close(gate)
	if err := <-stop; err != nil {
		t.Fatal(err)
	}
	select {
	case <-finished:
	case <-time.After(time.Second):
		t.Fatal("exit was not confirmed after termination")
	}
}

// LSP-T-086 waits for the same managed stop after an external process exit before confirming the request.
func TestProtocol_ExitWaitsForStop(t *testing.T) {
	gate := make(chan struct{})
	supervisor := &fakeSupervisor{blockMethod: "slow/request", terminateGate: gate}
	client, err := Launch(context.Background(), ServerConfig{ID: "fake", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err := client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	finished := make(chan struct{}, 1)
	request := make(chan error, 1)
	go func() {
		request <- client.call(context.Background(), "slow/request", map[string]any{}, nil, nil, func() { finished <- struct{}{} })
	}()
	waitForFakeRequest(t, supervisor.process, "slow/request")
	supervisor.process.exitOnce.Do(func() { supervisor.process.done <- native.ProcessExit{Code: 12} })
	select {
	case <-client.exitSignal:
	case <-time.After(time.Second):
		t.Fatal("process exit was not observed")
	}
	select {
	case err := <-request:
		if err == nil || err.Error() != "LSP server 'fake' exited (exit code 12)" {
			t.Fatalf("request error = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("request waited for managed stop")
	}
	stopDeadline := time.After(time.Second)
	for supervisor.process.terminates.Load() == 0 {
		select {
		case <-time.After(time.Millisecond):
		case <-stopDeadline:
			t.Fatal("managed stop did not begin")
		}
	}
	select {
	case <-finished:
		t.Fatal("exit was confirmed before stop settled")
	default:
	}
	close(gate)
	select {
	case <-finished:
	case <-time.After(time.Second):
		t.Fatal("exit was not confirmed after stop settled")
	}
	if err := client.Stop(time.Second); err != nil {
		t.Fatal(err)
	}
}

// LSP-T-087 keeps an exit unconfirmed after managed termination fails and never retries the stop.
func TestProtocol_StopFailureNoRetry(t *testing.T) {
	gate := make(chan struct{})
	terminateErr := errors.New("termination unavailable")
	supervisor := &fakeSupervisor{blockMethod: "slow/request", terminateGate: gate, terminateErr: terminateErr}
	client, err := Launch(context.Background(), ServerConfig{ID: "fake", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err := client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	finished := make(chan struct{}, 1)
	request := make(chan error, 1)
	go func() {
		request <- client.call(context.Background(), "slow/request", map[string]any{}, nil, nil, func() { finished <- struct{}{} })
	}()
	waitForFakeRequest(t, supervisor.process, "slow/request")
	supervisor.process.exitOnce.Do(func() { supervisor.process.done <- native.ProcessExit{Code: 13} })
	select {
	case <-client.exitSignal:
	case <-time.After(time.Second):
		t.Fatal("process exit was not observed")
	}
	if err := <-request; err == nil {
		t.Fatal("expected process exit error")
	}
	stopDeadline := time.After(time.Second)
	for supervisor.process.terminates.Load() == 0 {
		select {
		case <-time.After(time.Millisecond):
		case <-stopDeadline:
			t.Fatal("managed stop did not start")
		}
	}
	close(gate)
	first := client.Stop(time.Second)
	second := client.Stop(time.Second)
	if first == nil || first != second || !errors.Is(first, terminateErr) || supervisor.process.terminates.Load() != 1 {
		t.Fatalf("stop errors=%v,%v terminations=%d", first, second, supervisor.process.terminates.Load())
	}
	select {
	case <-finished:
		t.Fatal("failed stop confirmed process exit")
	default:
	}
}

// LSP-T-088 verifies explicit stop can observe an exit report without waiting on its own stop scope.
func TestProtocol_StopDoesNotSelfWait(t *testing.T) {
	exitSeen := make(chan struct{}, 1)
	allowExit := make(chan struct{})
	supervisor := &fakeSupervisor{blockMethod: "slow/request", exitBlocks: true, exitSeen: exitSeen, allowProcessExit: allowExit}
	client, err := Launch(context.Background(), ServerConfig{ID: "fake", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err := client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	finished := make(chan struct{}, 1)
	request := make(chan error, 1)
	go func() {
		request <- client.call(context.Background(), "slow/request", map[string]any{}, nil, nil, func() { finished <- struct{}{} })
	}()
	waitForFakeRequest(t, supervisor.process, "slow/request")
	stop := make(chan error, 1)
	go func() { stop <- client.Stop(time.Second) }()
	select {
	case <-exitSeen:
	case <-time.After(time.Second):
		t.Fatal("server did not observe exit")
	}
	supervisor.process.exitOnce.Do(func() { supervisor.process.done <- native.ProcessExit{Code: 0} })
	select {
	case err := <-stop:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("stop self-waited after observing exit")
	}
	close(allowExit)
	select {
	case <-request:
	case <-time.After(time.Second):
		t.Fatal("request did not observe process exit")
	}
	select {
	case <-finished:
	case <-time.After(time.Second):
		t.Fatal("request exit was not confirmed")
	}
	if err := client.Stop(time.Second); err != nil {
		t.Fatal(err)
	}
}
