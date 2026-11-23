package lsp

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

// A stuck stdin write must not hold the caller's deadline or prevent managed cleanup.
func TestClient_BlockedWriteCancellationAndStop(t *testing.T) {
	terminateGate := make(chan struct{})
	events := make(chan fakeRequest, 32)
	supervisor := &fakeSupervisor{
		blockMethod:   "slow/request",
		events:        events,
		terminateGate: terminateGate,
	}
	client, err := Launch(context.Background(), ServerConfig{ID: "fake", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err = client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	var gateClosed atomic.Bool
	defer func() {
		if gateClosed.CompareAndSwap(false, true) {
			close(terminateGate)
		}
		select {
		case <-supervisor.process.cancelled:
		default:
		}
		_ = client.Stop(time.Millisecond)
	}()
	takeFakeEvent(t, events, "initialize")
	takeFakeEvent(t, events, "initialized")

	// The fake server blocks after reading cancellation, so subsequent stdin writes block.
	supervisor.process.cancelled <- struct{}{}
	firstContext, cancelFirst := context.WithCancel(context.Background())
	firstFinished := make(chan struct{}, 1)
	firstDone := make(chan error, 1)
	go func() {
		firstDone <- client.call(firstContext, "slow/request", nil, nil, nil, func() { firstFinished <- struct{}{} })
	}()
	waitForFakeRequest(t, supervisor.process, "slow/request")
	takeFakeEvent(t, events, "slow/request")
	cancelFirst()
	select {
	case err = <-firstDone:
		if err == nil || err.Error() != "Operation cancelled" {
			t.Fatalf("first request error = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("cancelled request waited for a cancellation-frame write")
	}
	takeFakeEvent(t, events, "$/cancelRequest")
	supervisor.process.blockStdinWrite.Store(true)

	var secondSent atomic.Bool
	secondFinished := make(chan struct{}, 1)
	secondContext, cancelSecond := context.WithTimeout(context.Background(), 80*time.Millisecond)
	secondErr := client.call(secondContext, "blocked/write", nil, nil, func() { secondSent.Store(true) }, func() { secondFinished <- struct{}{} })
	cancelSecond()
	var timeout *LspTimeoutError
	if !errors.As(secondErr, &timeout) || !secondSent.Load() {
		t.Fatalf("blocked write error=%v sent=%v", secondErr, secondSent.Load())
	}
	select {
	case <-supervisor.process.stdinWriteEntered:
	default:
		t.Fatal("request did not reach the blocking stdin writer")
	}
	select {
	case <-supervisor.process.stdinWriteDone:
		t.Fatal("stdin write returned before process cleanup")
	default:
	}
	if parked := client.parkedConfirmations.Load(); parked < 2 {
		t.Fatalf("in-flight requests parked %d exit confirmations, want at least 2", parked)
	}

	var lateSent atomic.Bool
	lateContext, cancelLate := context.WithCancel(context.Background())
	lateDone := make(chan error, 1)
	go func() {
		lateDone <- client.call(lateContext, "must-not-send", nil, nil, func() { lateSent.Store(true) }, nil)
	}()
	time.Sleep(20 * time.Millisecond)
	cancelLate()
	select {
	case err = <-lateDone:
		if err == nil || err.Error() != "Operation cancelled" {
			t.Fatalf("gate-waiting request error = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("request waiting for the send gate did not observe cancellation")
	}

	stopDone := make(chan error, 1)
	go func() { stopDone <- client.Stop(20 * time.Millisecond) }()
	deadline := time.After(time.Second)
	for supervisor.process.terminates.Load() == 0 {
		select {
		case <-time.After(time.Millisecond):
		case <-deadline:
			t.Fatal("Stop waited for the blocked send before starting managed termination")
		}
	}
	select {
	case <-firstFinished:
		t.Fatal("first request exit was confirmed before managed termination completed")
	default:
	}
	select {
	case <-secondFinished:
		t.Fatal("blocked request exit was confirmed before managed termination completed")
	default:
	}
	select {
	case <-supervisor.process.stdinWriteDone:
		t.Fatal("connection disposal released the blocked writer before process termination")
	default:
	}

	// Unblock the fake reader and managed process only after confirming cleanup began.
	<-supervisor.process.cancelled
	if gateClosed.CompareAndSwap(false, true) {
		close(terminateGate)
	}
	select {
	case err = <-stopDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("Stop did not finish after managed termination was released")
	}
	select {
	case <-supervisor.process.stdinWriteDone:
	case <-time.After(time.Second):
		t.Fatal("managed termination did not release the blocked writer")
	}
	for _, finished := range []<-chan struct{}{firstFinished, secondFinished} {
		select {
		case <-finished:
		case <-time.After(time.Second):
			t.Fatal("in-flight request confirmation did not follow process exit")
		}
	}
	if lateSent.Load() {
		t.Fatal("cancelled request waiting for the send gate was sent later")
	}
	for {
		select {
		case event := <-events:
			if event.Method == "must-not-send" {
				t.Fatal("cancelled request reached the language server")
			}
		default:
			return
		}
	}
}
