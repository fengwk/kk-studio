// Package search is the in-process M0 search prototype. File discovery and
// content matching are parallel; all externally visible output is path sorted.
package search

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strings"
	"sync"

	ignorealt "github.com/Sriram-PR/go-ignore"
	"github.com/bmatcuk/doublestar/v4"
	"github.com/charlievieth/fastwalk"
)

// SearchOutcome contains deterministic results and the reason an incomplete scan stopped.
type SearchOutcome struct {
	Results            []string
	Skipped            []string
	IgnoreSkipped      []string
	IgnoreSkippedCount int
	Incomplete         bool
	StopReason         string
	LimitReached       bool
}

// Find returns paths relative to root, ordered like rg --sort path.
func Find(root, glob string, limit int) ([]string, error) {
	outcome, err := FindContext(context.Background(), root, glob, limit)
	return outcome.Results, err
}

// FindContext returns available paths when a deadline expires and a cancellation error when canceled.
func FindContext(ctx context.Context, root, glob string, limit int) (SearchOutcome, error) {
	discovered, err := findWithContext(ctx, root, glob, limit, min(runtime.NumCPU(), 8))
	if err != nil {
		return SearchOutcome{}, err
	}
	outcome := SearchOutcome{Results: discovered.files, IgnoreSkipped: discovered.ignored, IgnoreSkippedCount: discovered.ignoredCount, LimitReached: discovered.limitReached, Incomplete: discovered.timedOut}
	if discovered.timedOut {
		outcome.StopReason = "TIMED_OUT"
	} else if discovered.limitReached {
		outcome.StopReason = "LIMIT"
	}
	return outcome, nil
}

// Grep returns path:line:content records. literal controls fixed-string mode.
func Grep(root, pattern string, literal, ignoreCase bool, glob string, limit int) ([]string, error) {
	if limit < 0 {
		return nil, errors.New("limit cannot be negative")
	}
	files, err := Find(root, glob, 0)
	if err != nil {
		return nil, err
	}
	var re *regexp.Regexp
	var required []byte
	multiline := crossLinePattern(pattern, literal, ignoreCase)
	caseFoldRegex := literal && ignoreCase && !ascii(pattern)
	if !literal || caseFoldRegex || multiline {
		body := pattern
		if literal {
			body = regexp.QuoteMeta(pattern)
		}
		flags := "(?m)"
		if ignoreCase {
			flags = "(?im)"
		}
		re, err = regexp.Compile(flags + "(?:" + body + ")")
		if err != nil {
			return nil, fmt.Errorf("invalid regex: %w", err)
		}
		if !ignoreCase && !literal {
			required = requiredLiteralPattern(pattern)
		}
	}
	root, err = filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	root, err = filepath.EvalSymlinks(root)
	if err != nil {
		return nil, err
	}
	rootInfo, err := os.Stat(root)
	if err != nil {
		return nil, err
	}
	directFile := !rootInfo.IsDir()
	results := parallel(files, func(rel string) []string {
		data, release, e := readPooled(searchFilePath(root, rel, directFile))
		if e != nil {
			return nil
		}
		defer release()
		display := filepath.ToSlash(rel)
		if directFile {
			display = filepath.ToSlash(root)
		}
		if multiline {
			matches, _ := grepMultilineFile(data, display, re, 0)
			return matches
		}
		return grepFile(data, display, pattern, literal, ignoreCase, re, required)
	})
	flat := flatten(results)
	if limit > 0 && len(flat) > limit {
		flat = flat[:limit]
	}
	return flat, nil
}

// GrepContext processes files in sorted order and never opens a later file after reaching limit.
func GrepContext(ctx context.Context, root, pattern string, literal, ignoreCase bool, glob string, limit int) (SearchOutcome, error) {
	return grepContextWithReader(ctx, root, pattern, literal, ignoreCase, glob, limit, readPooledContext)
}

func grepContextWithReader(ctx context.Context, root, pattern string, literal, ignoreCase bool, glob string, limit int, read func(context.Context, string) ([]byte, func(), bool, error)) (SearchOutcome, error) {
	if limit < 0 {
		return SearchOutcome{}, errors.New("limit cannot be negative")
	}
	discovered, err := findWithContext(ctx, root, glob, 0, min(runtime.NumCPU(), 8))
	if err != nil {
		return SearchOutcome{}, err
	}
	var re *regexp.Regexp
	var required []byte
	multiline := crossLinePattern(pattern, literal, ignoreCase)
	caseFoldRegex := literal && ignoreCase && !ascii(pattern)
	if !literal || caseFoldRegex || multiline {
		body := pattern
		if literal {
			body = regexp.QuoteMeta(pattern)
		}
		flags := "(?m)"
		if ignoreCase {
			flags = "(?im)"
		}
		re, err = regexp.Compile(flags + "(?:" + body + ")")
		if err != nil {
			return SearchOutcome{}, fmt.Errorf("invalid regex: %w", err)
		}
		if !ignoreCase && !literal {
			required = requiredLiteralPattern(pattern)
		}
	}
	absoluteRoot, err := filepath.Abs(root)
	if err != nil {
		return SearchOutcome{}, err
	}
	absoluteRoot, err = filepath.EvalSymlinks(absoluteRoot)
	if err != nil {
		return SearchOutcome{}, err
	}
	rootInfo, err := os.Stat(absoluteRoot)
	if err != nil {
		return SearchOutcome{}, err
	}
	directFile := !rootInfo.IsDir()
	outcome := SearchOutcome{IgnoreSkipped: discovered.ignored, IgnoreSkippedCount: discovered.ignoredCount, Incomplete: discovered.timedOut}
	if discovered.timedOut {
		outcome.StopReason = "TIMED_OUT"
	}
	multilineMatchCount := 0
	for _, rel := range discovered.files {
		if ctxErr := ctx.Err(); ctxErr != nil {
			if errors.Is(ctxErr, context.DeadlineExceeded) {
				outcome.Incomplete = true
				outcome.StopReason = "TIMED_OUT"
				break
			}
			return outcome, ctxErr
		}
		filePath := searchFilePath(absoluteRoot, rel, directFile)
		display := filepath.ToSlash(rel)
		if directFile {
			display = filepath.ToSlash(absoluteRoot)
		}
		if multiline {
			info, statErr := os.Stat(filePath)
			if statErr != nil || info.Size() > 64*1024*1024 {
				outcome.Skipped = append(outcome.Skipped, display)
				continue
			}
		}
		data, release, readTimedOut, readErr := read(ctx, filePath)
		if readErr != nil {
			if errors.Is(readErr, context.Canceled) {
				return outcome, readErr
			}
			outcome.Skipped = append(outcome.Skipped, display)
			continue
		}
		if directFile && binary(data) {
			release()
			return outcome, errors.New("directly specified file is binary")
		}
		var matches []string
		limitReached := false
		if multiline {
			maxMatches := 0
			if limit > 0 {
				maxMatches = limit - multilineMatchCount
				if maxMatches <= 0 {
					maxMatches = -1
				}
			}
			var matchCount int
			matches, matchCount = grepMultilineFile(data, display, re, maxMatches)
			if limit > 0 && matchCount > max(0, limit-multilineMatchCount) {
				limitReached = true
				multilineMatchCount = limit
			} else {
				multilineMatchCount += matchCount
			}
		} else {
			matches = grepFile(data, display, pattern, literal, ignoreCase, re, required)
			limitReached = limit > 0 && len(matches) >= limit-len(outcome.Results)
			if limitReached {
				matches = matches[:limit-len(outcome.Results)]
			}
		}
		release()
		outcome.Results = append(outcome.Results, matches...)
		if errors.Is(ctx.Err(), context.Canceled) {
			return outcome, context.Canceled
		}
		if readTimedOut || errors.Is(ctx.Err(), context.DeadlineExceeded) {
			outcome.Incomplete = true
			outcome.StopReason = "TIMED_OUT"
			break
		}
		if limitReached || !multiline && limit > 0 && len(outcome.Results) >= limit {
			outcome.LimitReached = true
			if outcome.StopReason == "" {
				outcome.StopReason = "LIMIT"
			}
			break
		}
	}
	return outcome, nil
}

func findWith(root, glob string, limit, workers int) ([]string, error) {
	result, err := findWithContext(context.Background(), root, glob, limit, workers)
	return result.files, err
}

func searchFilePath(root, rel string, directFile bool) string {
	if directFile {
		return root
	}
	return filepath.Join(root, filepath.FromSlash(rel))
}

func isGitMetadataPath(path string) bool {
	for _, part := range strings.Split(filepath.ToSlash(path), "/") {
		if part == ".git" {
			return true
		}
	}
	return false
}

type findResult struct {
	files        []string
	ignored      []string
	ignoredCount int
	timedOut     bool
	limitReached bool
}

func findWithContext(ctx context.Context, root, glob string, limit, workers int) (findResult, error) {
	if limit < 0 {
		return findResult{}, errors.New("limit cannot be negative")
	}
	if err := ctx.Err(); err != nil {
		if errors.Is(err, context.DeadlineExceeded) {
			return findResult{timedOut: true}, nil
		}
		return findResult{}, err
	}
	abs, err := filepath.Abs(root)
	if err != nil {
		return findResult{}, err
	}
	abs, err = filepath.EvalSymlinks(abs)
	if err != nil {
		return findResult{}, err
	}
	info, err := os.Stat(abs)
	if err != nil {
		return findResult{}, err
	}
	if isGitMetadataPath(abs) {
		return findResult{}, nil
	}
	if !info.IsDir() {
		return findResult{files: []string{filepath.ToSlash(abs)}}, nil
	}
	if glob != "" {
		if _, err = doublestar.Match(glob, "probe"); err != nil {
			return findResult{}, fmt.Errorf("invalid glob: %w", err)
		}
	}
	rootScope := nearestGitRoot(abs)
	if rootScope == "" {
		rootScope = abs
	}
	rootMatcher, err := newIgnoreMatcherAt(rootScope, abs)
	if err != nil {
		return findResult{}, err
	}
	// An explicitly selected ignored directory is the search boundary, not an ignored descendant.
	if rootMatcher.Ignored(abs, true) {
		rootScope = abs
		rootMatcher, err = newIgnoreMatcherAt(rootScope, abs)
		if err != nil {
			return findResult{}, err
		}
	}
	type directoryScope struct {
		root    string
		matcher *ignoreMatcher
		files   []string
	}
	// fastwalk calls each directory callback before reading its entries. Files then load their parent state;
	// each directory owns its result slice, and inherited immutable matchers are shared when possible.
	var scopes sync.Map
	scopes.Store(filepath.ToSlash(abs), &directoryScope{root: rootScope, matcher: rootMatcher})
	rootPath := filepath.ToSlash(abs)
	rootPrefix := rootPath
	if !strings.HasSuffix(rootPrefix, "/") {
		rootPrefix += "/"
	}
	var files []string
	var ignoredPaths []string
	var ignoredCount int
	var ignoredMu sync.Mutex
	addIgnored := func(path string) {
		ignoredMu.Lock()
		defer ignoredMu.Unlock()
		ignoredCount++
		ignoredPaths = append(ignoredPaths, path)
		sort.Strings(ignoredPaths)
		if len(ignoredPaths) > 10 {
			ignoredPaths = ignoredPaths[:10]
		}
	}
	err = fastwalk.Walk(&fastwalk.Config{NumWorkers: workers, ToSlash: true}, abs, func(path string, d fs.DirEntry, walkErr error) error {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return ctxErr
		}
		if walkErr != nil || d == nil {
			return nil
		}
		if path == abs || path == rootPath {
			return nil
		}
		rel := strings.TrimPrefix(path, rootPrefix)
		if rel == path {
			return fmt.Errorf("walk path %q is outside search root %q", path, rootPath)
		}
		if d.Type()&os.ModeSymlink != 0 {
			return nil
		}
		if filepath.Base(rel) == ".git" {
			if d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		fullPath := filepath.FromSlash(path)
		parentEnd := strings.LastIndexByte(path, '/')
		parent := path[:parentEnd]
		if parent == "" {
			parent = "/"
		}
		parentValue, ok := scopes.Load(parent)
		if !ok {
			return fmt.Errorf("search scope for parent directory %q is unavailable", parent)
		}
		parentScope := parentValue.(*directoryScope)
		if d.IsDir() {
			isRepository := hasGitMarker(fullPath)
			if parentScope.matcher.Ignored(fullPath, true) {
				addIgnored(filepath.ToSlash(fullPath) + "/")
				return filepath.SkipDir
			}
			scope := parentScope.root
			matcher := parentScope.matcher
			if isRepository {
				scope = fullPath
			}
			localIgnore, readErr := os.ReadFile(filepath.Join(fullPath, ".gitignore"))
			if isRepository || readErr == nil && len(localIgnore) > 0 {
				nextMatcher, matcherErr := newIgnoreMatcherAt(scope, fullPath)
				if matcherErr != nil {
					return matcherErr
				}
				matcher = nextMatcher
			}
			scopes.Store(path, &directoryScope{root: scope, matcher: matcher})
			return nil
		}
		if parentScope.matcher.Ignored(fullPath, false) {
			if glob == "" || globMatch(glob, rel) {
				addIgnored(filepath.ToSlash(fullPath))
			}
			return nil
		}
		if glob == "" || globMatch(glob, rel) {
			parentScope.files = append(parentScope.files, rel)
		}
		return nil
	})
	timedOut := errors.Is(ctx.Err(), context.DeadlineExceeded)
	if errors.Is(ctx.Err(), context.Canceled) {
		return findResult{}, context.Canceled
	}
	if err != nil && !timedOut {
		return findResult{}, err
	}
	scopes.Range(func(_, value any) bool {
		files = append(files, value.(*directoryScope).files...)
		return true
	})
	sort.Slice(files, func(i, j int) bool { return pathLess(files[i], files[j]) })
	limitReached := limit > 0 && len(files) > limit
	if limitReached {
		files = files[:limit]
	}
	return findResult{files: files, ignored: ignoredPaths, ignoredCount: ignoredCount, timedOut: timedOut, limitReached: limitReached}, nil
}

func globMatch(pattern, rel string) bool {
	if strings.HasPrefix(pattern, "!") {
		return !globMatch(pattern[1:], rel)
	}
	if !strings.Contains(pattern, "/") {
		return doublestar.MatchUnvalidated("**/"+pattern, rel) || doublestar.MatchUnvalidated(pattern, filepath.Base(rel))
	}
	return doublestar.MatchUnvalidated(pattern, rel)
}

func pathLess(a, b string) bool {
	for ia, ib := 0, 0; ; {
		ea, eb := strings.IndexByte(a[ia:], '/'), strings.IndexByte(b[ib:], '/')
		if ea < 0 {
			ea = len(a) - ia
		}
		if eb < 0 {
			eb = len(b) - ib
		}
		ca, cb := a[ia:ia+ea], b[ib:ib+eb]
		if cmp := strings.Compare(ca, cb); cmp != 0 {
			return cmp < 0
		}
		if ea != eb {
			return ea < eb
		}
		endA, endB := ia+ea == len(a), ib+eb == len(b)
		if endA || endB {
			return endA && !endB
		}
		ia, ib = ia+ea+1, ib+eb+1
	}
}

type ignoreMatcher struct {
	root    string
	matcher *ignorealt.Matcher
}

func nearestGitRoot(path string) string {
	for p := path; ; p = filepath.Dir(p) {
		if hasGitMarker(p) {
			return p
		}
		if filepath.Dir(p) == p {
			return ""
		}
	}
}

func hasGitMarker(directory string) bool {
	return findGitDir(directory) != ""
}

// newIgnoreMatcherAt builds a directory-scoped matcher and never mutates it after publication.
func newIgnoreMatcherAt(baseRoot, directory string) (*ignoreMatcher, error) {
	m := &ignoreMatcher{root: baseRoot, matcher: ignorealt.New()}
	if gitDir := findGitDir(baseRoot); gitDir != "" {
		common := gitDir
		if b, err := os.ReadFile(filepath.Join(gitDir, "commondir")); err == nil {
			value := strings.TrimSpace(string(b))
			if !filepath.IsAbs(value) {
				value = filepath.Join(gitDir, value)
			}
			common = filepath.Clean(value)
		}
		for _, dir := range []string{common, gitDir} {
			if b, err := os.ReadFile(filepath.Join(dir, "info", "exclude")); err == nil {
				m.matcher.AddPatterns("", b)
				break
			}
		}
	}
	var chain []string
	for p := directory; ; p = filepath.Dir(p) {
		chain = append(chain, p)
		if p == baseRoot || filepath.Dir(p) == p {
			break
		}
	}
	for i := len(chain) - 1; i >= 0; i-- {
		p := chain[i]
		if b, err := os.ReadFile(filepath.Join(p, ".gitignore")); err == nil {
			rel, _ := filepath.Rel(baseRoot, p)
			if rel == "." {
				rel = ""
			}
			m.matcher.AddPatterns(filepath.ToSlash(rel), b)
		}
	}
	return m, nil
}

func findGitDir(root string) string {
	marker := filepath.Join(root, ".git")
	info, err := os.Stat(marker)
	if err != nil {
		return ""
	}
	if info.IsDir() {
		if validGitDir(marker) {
			return marker
		}
		return ""
	}
	if !info.Mode().IsRegular() {
		return ""
	}
	b, err := os.ReadFile(marker)
	if err != nil {
		return ""
	}
	value := strings.TrimSpace(string(b))
	if !strings.HasPrefix(value, "gitdir:") {
		return ""
	}
	gitDir := strings.TrimSpace(strings.TrimPrefix(value, "gitdir:"))
	if !filepath.IsAbs(gitDir) {
		gitDir = filepath.Join(root, gitDir)
	}
	gitDir = filepath.Clean(gitDir)
	if validGitDir(gitDir) {
		return gitDir
	}
	return ""
}

func validGitDir(gitDir string) bool {
	if _, err := os.Stat(filepath.Join(gitDir, "HEAD")); err != nil {
		return false
	}
	commonDir := gitDir
	commonFile := filepath.Join(gitDir, "commondir")
	if _, err := os.Lstat(commonFile); err == nil {
		b, err := os.ReadFile(commonFile)
		if err != nil {
			return false
		}
		value := strings.TrimSpace(string(b))
		if value == "" {
			return false
		}
		if !filepath.IsAbs(value) {
			value = filepath.Join(gitDir, value)
		}
		commonDir = filepath.Clean(value)
	} else if !errors.Is(err, fs.ErrNotExist) {
		return false
	}
	for _, name := range []string{"objects", "refs"} {
		info, err := os.Stat(filepath.Join(commonDir, name))
		if err != nil || !info.IsDir() {
			return false
		}
	}
	return true
}
func (m *ignoreMatcher) Ignored(path string, isDir bool) bool {
	rel, err := filepath.Rel(m.root, path)
	return err == nil && m.matcher.Match(filepath.ToSlash(rel), isDir)
}

func binary(b []byte) bool {
	if bytes.HasPrefix(b, []byte{0xff, 0xfe}) || bytes.HasPrefix(b, []byte{0xfe, 0xff}) {
		return false
	}
	n := len(b)
	if n > 8192 {
		n = 8192
	}
	return bytes.IndexByte(b[:n], 0) >= 0
}
func parallel[T any](items []string, fn func(string) T) []T {
	out := make([]T, len(items))
	if len(items) == 0 {
		return out
	}
	workers := min(runtime.NumCPU(), 8)
	if workers > len(items) {
		workers = len(items)
	}
	jobs := make(chan int)
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := range jobs {
				out[j] = fn(items[j])
			}
		}()
	}
	for i := range items {
		jobs <- i
	}
	close(jobs)
	wg.Wait()
	return out
}
func flatten(v [][]string) []string {
	var out []string
	for _, x := range v {
		out = append(out, x...)
	}
	return out
}
