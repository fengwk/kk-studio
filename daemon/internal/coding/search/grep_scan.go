package search

import (
	"context"
	"errors"
	"os"
	"path/filepath"

	"github.com/fengwk/kk-studio/daemon/internal/textcodec"
)

// maxMultilineFileBytes bounds the files a cross-line pattern scans as one buffer.
const maxMultilineFileBytes = 64 * 1024 * 1024

// grepScan is the running state of one grep invocation over the discovered files.
type grepScan struct {
	ctx        context.Context
	spec       patternSpec
	read       fileReader
	root       string
	directFile bool
	limit      int
	// matched counts the matches already charged against limit in multiline mode, where one
	// match can print several lines.
	matched int
	outcome SearchOutcome
}

// scanFile appends the matches of one file and reports whether the scan must stop.
func (s *grepScan) scanFile(rel string) (bool, error) {
	if err := s.ctx.Err(); err != nil {
		return s.stopAtContext(err)
	}
	path, display := s.fileAndDisplay(rel)
	if s.spec.multiline && !withinMultilineSize(path) {
		s.outcome.Skipped = append(s.outcome.Skipped, display)
		return false, nil
	}
	data, release, readTimedOut, err := s.read(s.ctx, path)
	if err != nil {
		if errors.Is(err, context.Canceled) {
			return true, err
		}
		s.outcome.Skipped = append(s.outcome.Skipped, display)
		return false, nil
	}
	decoded, _, _, decodeErr := textcodec.Decode(path, data)
	if decodeErr != nil {
		release()
		return s.skipRejected(path, decodeErr)
	}
	matches, limitReached := s.matches(decoded, display)
	release()
	s.outcome.Results = append(s.outcome.Results, matches...)
	return s.finishFile(readTimedOut, limitReached)
}

// stopAtContext ends the scan because the caller's context ended before the next file was read.
func (s *grepScan) stopAtContext(ctxErr error) (bool, error) {
	if errors.Is(ctxErr, context.DeadlineExceeded) {
		s.markTimedOut()
		return true, nil
	}
	return true, ctxErr
}

// fileAndDisplay returns the file to read and the path reported for it.
func (s *grepScan) fileAndDisplay(rel string) (path, display string) {
	if s.directFile {
		return s.root, filepath.ToSlash(s.root)
	}
	return filepath.Join(s.root, filepath.FromSlash(rel)), filepath.ToSlash(rel)
}

// skipRejected records a file the codec refused; a direct path reports the failure.
func (s *grepScan) skipRejected(path string, decodeErr error) (bool, error) {
	if s.directFile {
		return true, &RejectedError{Path: path, Err: decodeErr}
	}
	// A binary file is skipped silently; a declaration the codec cannot use is reported.
	if errors.Is(decodeErr, textcodec.ErrBinary) {
		return false, nil
	}
	s.outcome.Rejected = append(s.outcome.Rejected, filepath.ToSlash(path))
	s.outcome.RejectedCount++
	return false, nil
}

// matches returns the records of one decoded file and reports whether they reached the limit.
func (s *grepScan) matches(data []byte, display string) ([]string, bool) {
	if !s.spec.multiline {
		return s.clipLineMatches(grepFile(data, display, s.spec))
	}
	maxMatches := 0
	if s.limit > 0 {
		if maxMatches = s.limit - s.matched; maxMatches <= 0 {
			maxMatches = -1
		}
	}
	matches, matchCount := grepMultilineFile(data, display, s.spec.re, maxMatches)
	if s.limit > 0 && matchCount >= s.limit-s.matched {
		s.matched = s.limit
		return matches, true
	}
	s.matched += matchCount
	return matches, false
}

// clipLineMatches charges line matches against limit, where every record is one match.
func (s *grepScan) clipLineMatches(matches []string) ([]string, bool) {
	if s.limit <= 0 {
		return matches, false
	}
	remaining := s.limit - len(s.outcome.Results)
	if len(matches) < remaining {
		return matches, false
	}
	return matches[:remaining], true
}

// finishFile reports whether the scan must stop once a file has been scanned.
func (s *grepScan) finishFile(readTimedOut, limitReached bool) (bool, error) {
	if errors.Is(s.ctx.Err(), context.Canceled) {
		return true, context.Canceled
	}
	if readTimedOut || errors.Is(s.ctx.Err(), context.DeadlineExceeded) {
		s.markTimedOut()
		return true, nil
	}
	if limitReached {
		s.outcome.LimitReached = true
		if s.outcome.StopReason == "" {
			s.outcome.StopReason = "LIMIT"
		}
		return true, nil
	}
	return !s.spec.multiline && s.limit > 0 && len(s.outcome.Results) >= s.limit, nil
}

func (s *grepScan) markTimedOut() {
	s.outcome.Incomplete = true
	s.outcome.StopReason = "TIMED_OUT"
}

// withinMultilineSize reports whether a file may be scanned as one buffer.
func withinMultilineSize(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Size() <= maxMultilineFileBytes
}
