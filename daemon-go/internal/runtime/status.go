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

type ConfigurationError struct {
	Version int64
	Code    string
	Field   string
}

// StatusTracker publishes monotonically revised runtime status and update progress.
type StatusTracker struct {
	mu                 sync.Mutex
	revision           int64
	status             map[string]any
	progress           *UpdateProgress
	updating           bool
	configurationError *ConfigurationError
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
func (s *StatusTracker) ApplyConfiguration(version int64, proxyURL *string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	updated := cloneMap(s.status)
	updated["appliedVersion"] = version
	if proxyURL == nil {
		updated["proxyUrl"] = nil
	} else {
		updated["proxyUrl"] = *proxyURL
	}
	delete(updated, "configurationError")
	if reflect.DeepEqual(s.status, updated) && s.configurationError == nil {
		return nil
	}
	if s.revision == math.MaxInt64 {
		return errors.New("runtime status revision exceeds the Java long range")
	}
	s.revision++
	s.status = updated
	s.configurationError = nil
	return nil
}

func (s *StatusTracker) FailConfiguration(failure ConfigurationError) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.configurationError != nil && *s.configurationError == failure {
		return false, nil
	}
	updated := cloneMap(s.status)
	failureStatus := map[string]any{"version": failure.Version, "code": failure.Code}
	if failure.Field != "" {
		failureStatus["field"] = failure.Field
	}
	updated["configurationError"] = failureStatus
	if s.revision == math.MaxInt64 {
		return false, errors.New("runtime status revision exceeds the Java long range")
	}
	s.revision++
	s.status = updated
	copy := failure
	s.configurationError = &copy
	return true, nil
}

func (s *StatusTracker) ConfigurationError() (ConfigurationError, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.configurationError == nil {
		return ConfigurationError{}, false
	}
	return *s.configurationError, true
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
