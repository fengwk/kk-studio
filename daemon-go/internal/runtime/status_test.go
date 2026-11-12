package runtime

import "testing"

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
