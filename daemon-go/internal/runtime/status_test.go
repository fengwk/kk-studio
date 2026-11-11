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
	s.BeginUpdate()
	total := int64(12)
	if err := s.UpdateProgress(UpdateProgress{DownloadedBytes: 4, TotalBytes: &total}); err != nil {
		t.Fatal(err)
	}
	if err := s.UpdateProgress(UpdateProgress{DownloadedBytes: 3, TotalBytes: &total}); err == nil {
		t.Fatal("expected backwards progress rejection")
	}
}
