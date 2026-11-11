package runtime

import (
	"errors"
	"math"
	"reflect"
	"sync"
)

type UpdateProgress struct {
	DownloadedBytes int64  `json:"downloadedBytes"`
	TotalBytes      *int64 `json:"totalBytes"`
}
type RuntimeStatus struct {
	Revision int64          `json:"revision"`
	Status   map[string]any `json:"status"`
}

// StatusTracker publishes monotonically revised runtime status and update progress.
type StatusTracker struct {
	mu       sync.Mutex
	revision int64
	status   map[string]any
	progress *UpdateProgress
	updating bool
}

func NewStatusTracker() *StatusTracker { return &StatusTracker{revision: 1, status: map[string]any{}} }
func (s *StatusTracker) Snapshot() RuntimeStatus {
	s.mu.Lock()
	defer s.mu.Unlock()
	return RuntimeStatus{Revision: s.revision, Status: cloneMap(s.status)}
}
func (s *StatusTracker) Update(values map[string]any) (RuntimeStatus, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if reflect.DeepEqual(s.status, values) {
		return RuntimeStatus{Revision: s.revision, Status: cloneMap(s.status)}, nil
	}
	if s.revision == math.MaxInt64 {
		return RuntimeStatus{}, errors.New("runtime status revision exceeds the Java long range")
	}
	s.revision++
	s.status = cloneMap(values)
	return RuntimeStatus{Revision: s.revision, Status: cloneMap(s.status)}, nil
}
func (s *StatusTracker) BeginUpdate() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.updating = true
	s.progress = nil
}
func (s *StatusTracker) EndUpdate() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.updating = false
	s.progress = nil
}
func (s *StatusTracker) UpdateProgress(p UpdateProgress) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.updating {
		return errors.New("no update is active")
	}
	if p.DownloadedBytes < 0 || p.DownloadedBytes > 9007199254740991 || p.TotalBytes != nil && (*p.TotalBytes < p.DownloadedBytes || *p.TotalBytes > 9007199254740991) {
		return errors.New("invalid update progress")
	}
	if s.progress != nil && p.DownloadedBytes < s.progress.DownloadedBytes {
		return errors.New("update progress cannot go backwards")
	}
	cp := p
	if p.TotalBytes != nil {
		v := *p.TotalBytes
		cp.TotalBytes = &v
	}
	s.progress = &cp
	return nil
}
func (s *StatusTracker) Progress() (*UpdateProgress, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.progress == nil {
		return nil, false
	}
	p := *s.progress
	if p.TotalBytes != nil {
		v := *p.TotalBytes
		p.TotalBytes = &v
	}
	return &p, true
}
func cloneMap(m map[string]any) map[string]any {
	out := make(map[string]any, len(m))
	for k, v := range m {
		out[k] = v
	}
	return out
}
