package runtime

import (
	"math"
	"strings"
	"testing"
)

// RUN-T-121 checks revision changes only when status content changes and progress remains monotonic.
func TestStatusRevisionAndProgress(t *testing.T) {
	s := NewStatusTracker()
	a, err := s.Update(map[string]any{"state": "ready"})
	if err != nil {
		t.Fatal(err)
	}
	b, err := s.Update(map[string]any{"state": "ready"})
	if err != nil {
		t.Fatal(err)
	}
	if a.Revision != 2 || b.Revision != 2 {
		t.Fatalf("revisions %d, %d", a.Revision, b.Revision)
	}
	if err := s.BeginUpdate("2.0.0"); err != nil {
		t.Fatal(err)
	}
	total := int64(12)
	if err := s.UpdateProgress(UpdateProgress{DownloadedBytes: 4, TotalBytes: &total}); err != nil {
		t.Fatal(err)
	}
	progress, ok := s.Progress()
	if !ok || progress.TargetVersion != "2.0.0" || progress.DownloadedBytes != 4 {
		t.Fatalf("update progress = %+v, present=%t", progress, ok)
	}
	if err := s.UpdateProgress(UpdateProgress{DownloadedBytes: 3, TotalBytes: &total}); err == nil {
		t.Fatal("expected backwards progress rejection")
	}
	if err := s.EndUpdate(); err != nil {
		t.Fatal(err)
	}
	if _, ok := s.Progress(); ok {
		t.Fatal("ending update retained progress")
	}
}

// RUN-T-019 keeps the last applied configuration visible through failure and clears the error on repair.
func TestStatusConfigurationFailurePreservesAppliedState(t *testing.T) {
	s := NewStatusTracker()
	if err := s.ApplyConfiguration(1, nil); err != nil {
		t.Fatal(err)
	}
	if changed, err := s.FailConfiguration(ConfigurationError{Version: 2, Code: "VALIDATION_FAILED", Field: "bashExecutable"}); err != nil || !changed {
		t.Fatalf("configuration failure status changed=%t err=%v", changed, err)
	}
	failure, ok := s.ConfigurationError()
	if !ok || failure.Version != 2 || failure.Field != "bashExecutable" {
		t.Fatalf("failure status = %+v, present=%t", failure, ok)
	}
	if err := s.ApplyConfiguration(3, nil); err != nil {
		t.Fatal(err)
	}
	if _, ok := s.ConfigurationError(); ok {
		t.Fatal("successful configuration did not clear failure status")
	}
	status := s.Snapshot()
	if status.Status["appliedVersion"] != int64(3) {
		t.Fatalf("successful repair did not advance applied version: %+v", status.Status)
	}
}

// RUN-T-122/123 exercises invalid progress, duplicate transitions, immutable snapshots, and revision exhaustion.
func TestStatusRejectsInvalidProgressAndRevisionOverflow(t *testing.T) {
	s := NewStatusTracker()
	if err := s.UpdateProgress(UpdateProgress{}); err == nil || err.Error() != "no update is active" {
		t.Fatalf("inactive progress error = %v", err)
	}
	if err := s.BeginUpdate("2.0.0"); err != nil {
		t.Fatal(err)
	}
	initialRevision := s.Snapshot().Revision
	if err := s.BeginUpdate("2.0.0"); err != nil || s.Snapshot().Revision != initialRevision {
		t.Fatalf("duplicate begin changed revision: err=%v status=%+v", err, s.Snapshot())
	}
	for _, progress := range []UpdateProgress{
		{TargetVersion: "3.0.0"},
		{DownloadedBytes: -1},
		{DownloadedBytes: 9007199254740992},
		{DownloadedBytes: 2, TotalBytes: int64Ptr(1)},
		{DownloadedBytes: 1, TotalBytes: int64Ptr(9007199254740992)},
	} {
		if err := s.UpdateProgress(progress); err == nil {
			t.Fatalf("invalid progress was accepted: %+v", progress)
		}
	}
	valid := UpdateProgress{DownloadedBytes: 2, TotalBytes: int64Ptr(4)}
	if err := s.UpdateProgress(valid); err != nil {
		t.Fatal(err)
	}
	revision := s.Snapshot().Revision
	if err := s.UpdateProgress(valid); err != nil || s.Snapshot().Revision != revision {
		t.Fatalf("duplicate progress advanced revision: err=%v", err)
	}
	valid.TotalBytes = int64Ptr(8)
	stored, ok := s.Progress()
	if !ok || *stored.TotalBytes != 4 {
		t.Fatalf("progress did not retain an immutable total: %+v", stored)
	}
	if err := s.EndUpdate(); err != nil {
		t.Fatal(err)
	}
	if err := s.EndUpdate(); err != nil {
		t.Fatal(err)
	}

	for _, transition := range []func(*StatusTracker) error{
		func(tracker *StatusTracker) error {
			_, err := tracker.Update(map[string]any{"changed": true})
			return err
		},
		func(tracker *StatusTracker) error { return tracker.ApplyConfiguration(1, nil) },
		func(tracker *StatusTracker) error {
			_, err := tracker.FailConfiguration(ConfigurationError{Version: 2, Code: "FAILED"})
			return err
		},
		func(tracker *StatusTracker) error { return tracker.BeginUpdate("3.0.0") },
		func(tracker *StatusTracker) error {
			tracker.updating = true
			return tracker.EndUpdate()
		},
		func(tracker *StatusTracker) error {
			tracker.updating = true
			tracker.updateTarget = "3.0.0"
			return tracker.UpdateProgress(UpdateProgress{DownloadedBytes: 1})
		},
	} {
		tracker := NewStatusTracker()
		tracker.mu.Lock()
		tracker.revision = math.MaxInt64
		tracker.mu.Unlock()
		if err := transition(tracker); err == nil || !strings.Contains(err.Error(), "revision exceeds") {
			t.Fatalf("revision overflow error = %v", err)
		}
	}
	tracker := NewStatusTracker()
	tracker.revision = math.MaxInt64
	if _, err := tracker.Update(map[string]any{}); err != nil {
		t.Fatalf("unchanged status at max revision = %v", err)
	}
	tracker = NewStatusTracker()
	failure := ConfigurationError{Version: 2, Code: "VALIDATION_FAILED"}
	if changed, err := tracker.FailConfiguration(failure); err != nil || !changed {
		t.Fatalf("first configuration failure changed=%t err=%v", changed, err)
	}
	revision = tracker.Snapshot().Revision
	if changed, err := tracker.FailConfiguration(failure); err != nil || changed || tracker.Snapshot().Revision != revision {
		t.Fatalf("duplicate configuration failure changed=%t err=%v status=%+v", changed, err, tracker.Snapshot())
	}
	if err := tracker.ApplyConfiguration(0, nil); err != nil {
		t.Fatal(err)
	}
	revision = tracker.Snapshot().Revision
	if err := tracker.ApplyConfiguration(0, nil); err != nil || tracker.Snapshot().Revision != revision {
		t.Fatalf("idempotent configuration advanced revision: err=%v status=%+v", err, tracker.Snapshot())
	}
}
