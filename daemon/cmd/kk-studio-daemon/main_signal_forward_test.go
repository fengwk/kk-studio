package main

import (
	"os"
	"syscall"
	"testing"
	"time"
)

// forwardStopTimeout bounds the wait for the forwarding loop to observe the child exit.
const forwardStopTimeout = 5 * time.Second

// The bootstrap parent must forward signals for as long as the child runs, so a second shutdown
// signal (for example a second Ctrl-C while the child is still closing) reaches the child instead
// of being swallowed by the parent's capacity-1 signal channel.
func TestForwardSignalsForwardsEverySignalUntilChildExits(t *testing.T) {
	signals := make(chan os.Signal, 2)
	childExited := make(chan struct{})
	forwarded := make(chan os.Signal, 2)
	forwardDone := make(chan struct{})
	go func() {
		defer close(forwardDone)
		forwardSignals(signals, childExited, func(received os.Signal) { forwarded <- received })
	}()

	want := []os.Signal{os.Interrupt, syscall.SIGTERM}
	for _, signal := range want {
		signals <- signal
	}
	for index, signal := range want {
		select {
		case received := <-forwarded:
			if received != signal {
				t.Fatalf("forwarded signal %d = %v, want %v", index+1, received, signal)
			}
		case <-forwardDone:
			t.Fatalf("forwarded %d of %d signals before the child exited: the bootstrap stopped forwarding", index, len(want))
		}
	}
	close(childExited)
	select {
	case <-forwardDone:
	case <-time.After(forwardStopTimeout):
		t.Fatal("signal forwarding did not stop after the child exited")
	}
}
