package runtime

import (
	"errors"
	"math"
	"reflect"
	"sync"
)

type UpdateProgress struct {
	TargetVersion   string
	DownloadedBytes int64  `json:"downloadedBytes"`
	TotalBytes      *int64 `json:"totalBytes"`
}

type ConfigurationError struct {
	Version int64
	Code    string
	Field   string
}

// StatusTracker publishes a monotonically revised runtime status and update progress. Only the facts
// the runtime reports are retained: the applied configuration, its failure, and update progress.
type StatusTracker struct {
	mu                 sync.Mutex
	revision           int64
	appliedVersion     *int64
	proxyURL           *string
	progress           *UpdateProgress
	updating           bool
	configurationError *ConfigurationError
	updateTarget       string
}

func NewStatusTracker() *StatusTracker { return &StatusTracker{revision: 1} }

// Revision returns the current revision the runtime reports with every status frame.
func (s *StatusTracker) Revision() int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.revision
}

func (s *StatusTracker) ApplyConfiguration(version int64, proxyURL *string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.configurationError == nil && s.appliedVersion != nil && *s.appliedVersion == version && equalOptionalString(s.proxyURL, proxyURL) {
		return nil
	}
	if err := s.advanceLocked(); err != nil {
		return err
	}
	s.appliedVersion = &version
	s.proxyURL = cloneString(proxyURL)
	s.configurationError = nil
	return nil
}

func (s *StatusTracker) FailConfiguration(failure ConfigurationError) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.configurationError != nil && *s.configurationError == failure {
		return false, nil
	}
	if err := s.advanceLocked(); err != nil {
		return false, err
	}
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

func (s *StatusTracker) BeginUpdate(targetVersion string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.updating && s.updateTarget == targetVersion {
		return nil
	}
	if err := s.advanceLocked(); err != nil {
		return err
	}
	s.updating = true
	s.updateTarget = targetVersion
	s.progress = &UpdateProgress{TargetVersion: targetVersion}
	return nil
}
func (s *StatusTracker) EndUpdate() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.updating {
		return nil
	}
	if err := s.advanceLocked(); err != nil {
		return err
	}
	s.updating = false
	s.updateTarget = ""
	s.progress = nil
	return nil
}
func (s *StatusTracker) UpdateProgress(p UpdateProgress) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.updating {
		return errors.New("no update is active")
	}
	if p.TargetVersion == "" {
		p.TargetVersion = s.updateTarget
	}
	if p.TargetVersion != s.updateTarget {
		return errors.New("update progress target version does not match the active update")
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
	if reflect.DeepEqual(s.progress, &cp) {
		return nil
	}
	if err := s.advanceLocked(); err != nil {
		return err
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
func (s *StatusTracker) advanceLocked() error {
	if s.revision == math.MaxInt64 {
		return errors.New("runtime status revision exceeds the Java long range")
	}
	s.revision++
	return nil
}

func cloneString(value *string) *string {
	if value == nil {
		return nil
	}
	copy := *value
	return &copy
}

func equalOptionalString(left, right *string) bool {
	return left == nil && right == nil || left != nil && right != nil && *left == *right
}
