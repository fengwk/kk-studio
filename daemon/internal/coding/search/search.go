// Package search is the in-process file search used by fs.find-files and fs.grep. File discovery and
// content matching are parallel; every externally visible output is path sorted.
package search

import (
	"context"
	"errors"
	"fmt"
	"regexp"
)

// SearchOutcome contains deterministic results and the reason an incomplete scan stopped.
type SearchOutcome struct {
	Results            []string
	Skipped            []string
	Rejected           []string
	RejectedCount      int
	IgnoreSkipped      []string
	IgnoreSkippedCount int
	Incomplete         bool
	StopReason         string
	LimitReached       bool
}

// RejectedError reports a file given directly as path whose declared text encoding the codec
// cannot use.
type RejectedError struct {
	Path string
	Err  error
}

func (e *RejectedError) Error() string { return e.Err.Error() }

// Unwrap returns the codec failure.
func (e *RejectedError) Unwrap() error { return e.Err }

// FindContext returns available paths when a deadline expires and a cancellation error when canceled.
func FindContext(ctx context.Context, root, glob string, limit int) (SearchOutcome, error) {
	discovered, err := findWithContext(ctx, root, glob, limit)
	if err != nil {
		return SearchOutcome{}, err
	}
	outcome := SearchOutcome{
		Results:            discovered.files,
		Skipped:            discovered.skipped,
		IgnoreSkipped:      discovered.ignored,
		IgnoreSkippedCount: discovered.ignoredCount,
		LimitReached:       discovered.limitReached,
		Incomplete:         discovered.timedOut,
	}
	switch {
	case discovered.timedOut:
		outcome.StopReason = "TIMED_OUT"
	case discovered.limitReached:
		outcome.StopReason = "LIMIT"
	}
	return outcome, nil
}

// GrepContext returns path:line:content records. literal controls fixed-string mode.
func GrepContext(ctx context.Context, root, pattern string, literal, ignoreCase bool, glob string, limit int) (SearchOutcome, error) {
	return grepContextWithReader(ctx, root, pattern, literal, ignoreCase, glob, limit, readPooledContext)
}

// fileReader returns the bytes of one file together with the release for them, and reports a
// read that stopped at the deadline.
type fileReader func(ctx context.Context, path string) (data []byte, release func(), timedOut bool, err error)

// grepContextWithReader scans the discovered files in order and never opens a later file after
// reaching limit.
func grepContextWithReader(ctx context.Context, root, pattern string, literal, ignoreCase bool, glob string, limit int, read fileReader) (SearchOutcome, error) {
	if limit < 0 {
		return SearchOutcome{}, errors.New("limit cannot be negative")
	}
	discovered, err := findWithContext(ctx, root, glob, 0)
	if err != nil {
		return SearchOutcome{}, err
	}
	spec, err := compilePattern(pattern, literal, ignoreCase)
	if err != nil {
		return SearchOutcome{}, err
	}
	root, directFile, err := resolveGrepRoot(root)
	if err != nil {
		return SearchOutcome{}, err
	}
	scan := &grepScan{
		ctx:        ctx,
		spec:       spec,
		read:       read,
		root:       root,
		directFile: directFile,
		limit:      limit,
		outcome:    SearchOutcome{IgnoreSkipped: discovered.ignored, IgnoreSkippedCount: discovered.ignoredCount, Incomplete: discovered.timedOut},
	}
	if discovered.timedOut {
		scan.outcome.StopReason = "TIMED_OUT"
	}
	for _, rel := range discovered.files {
		stop, scanErr := scan.scanFile(rel)
		if scanErr != nil {
			return scan.outcome, scanErr
		}
		if stop {
			break
		}
	}
	return scan.outcome, nil
}

// patternSpec is one compiled grep pattern: the regular expression plus the literal fragment that
// lets a scan skip text without running RE2.
type patternSpec struct {
	text       string
	literal    bool
	ignoreCase bool
	multiline  bool
	re         *regexp.Regexp
	required   []byte
}

// compilePattern builds the matcher for one pattern. A case-sensitive literal that cannot span
// lines needs no regular expression at all.
func compilePattern(pattern string, literal, ignoreCase bool) (patternSpec, error) {
	spec := patternSpec{text: pattern, literal: literal, ignoreCase: ignoreCase, multiline: crossLinePattern(pattern, literal, ignoreCase)}
	caseFoldRegex := literal && ignoreCase && !ascii(pattern)
	if spec.multiline || !literal || caseFoldRegex {
		body := pattern
		if literal {
			body = regexp.QuoteMeta(pattern)
		}
		flags := "(?m)"
		if ignoreCase {
			flags = "(?im)"
		}
		re, err := regexp.Compile(flags + "(?:" + body + ")")
		if err != nil {
			return patternSpec{}, fmt.Errorf("invalid regex: %w", err)
		}
		spec.re = re
		if !literal && !ignoreCase {
			spec.required = requiredLiteralPattern(pattern)
		}
	}
	return spec, nil
}
