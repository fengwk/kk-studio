package lsp

import (
	"context"
	"testing"
	"time"
)

// Verifies only a request the server never answered waits for the process to stop before completing.
func TestProtocol_ConfirmationParking(t *testing.T) {
	supervisor := &fakeSupervisor{blockMethod: "slow/request"}
	client, err := Launch(context.Background(), ServerConfig{ID: "fake", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err = client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	finished := make(chan struct{}, 1)
	if err = client.call(context.Background(), "workspace/symbol", map[string]any{}, nil, nil, func() { finished <- struct{}{} }); err != nil {
		t.Fatal(err)
	}
	select {
	case <-finished:
	case <-time.After(time.Second):
		t.Fatal("successful call did not complete")
	}
	if parked := client.parkedConfirmations.Load(); parked != 0 {
		t.Fatalf("successful call parked %d exit confirmations", parked)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	request := make(chan error, 1)
	finished = make(chan struct{}, 1)
	go func() {
		request <- client.call(ctx, "slow/request", map[string]any{}, nil, nil, func() { finished <- struct{}{} })
	}()
	waitForFakeRequest(t, supervisor.process, "slow/request")
	cancel()
	select {
	case err = <-request:
	case <-time.After(time.Second):
		t.Fatal("cancelled request did not return")
	}
	if err == nil || err.Error() != "Operation cancelled" {
		t.Fatalf("cancelled request error = %v", err)
	}
	if parked := client.parkedConfirmations.Load(); parked != 1 {
		t.Fatalf("unconfirmed call parked %d exit confirmations", parked)
	}
	if err = client.Stop(time.Millisecond); err != nil {
		t.Fatal(err)
	}
	select {
	case <-finished:
	case <-time.After(time.Second):
		t.Fatal("parked confirmation did not complete after the process stopped")
	}
}
