//go:build windows

package native

import (
	"context"
	"testing"
)

// TestWindowsSupervisor_RejectsStartAfterClose verifies the closed supervisor fails before launching.
func TestWindowsSupervisor_RejectsStartAfterClose(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	if err := supervisor.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	if _, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "missing.exe"}); err == nil {
		t.Fatal("Start succeeded after Close")
	}
}

// TestWindowsSupervisor_PreCancelledStartDoesNotLaunch verifies cancellation blocks process creation.
func TestWindowsSupervisor_PreCancelledStartDoesNotLaunch(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := supervisor.Start(ctx, ProcessSpec{Executable: "missing.exe"}); err == nil {
		t.Fatal("Start succeeded with a pre-cancelled context")
	}
}

// TestWindowsSupervisor_RejectsUnknownDescendantRoot verifies tracking cannot target an unmanaged process.
func TestWindowsSupervisor_RejectsUnknownDescendantRoot(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	tracker, ok := supervisor.(DescendantTracker)
	if !ok {
		t.Fatal("Windows supervisor does not expose descendant tracking")
	}
	if err := tracker.TrackDescendant(context.Background(), 123456, 123457); err == nil {
		t.Fatal("tracking accepted an unknown root process")
	}
}
