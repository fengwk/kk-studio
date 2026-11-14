package search

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"

	ignorealt "github.com/Sriram-PR/go-ignore"
	"github.com/bmatcuk/doublestar/v4"
	"github.com/charlievieth/fastwalk"
)

// maxHintPaths caps the ignored paths a scan reports back to the caller.
const maxHintPaths = 10

// findResult is one discovery pass: the sorted files plus the paths the pass had to leave out.
type findResult struct {
	files        []string
	skipped      []string
	ignored      []string
	ignoredCount int
	timedOut     bool
	limitReached bool
}

type searchWalker func(*fastwalk.Config, string, fs.WalkDirFunc) error

// searchWorkers bounds how many directories fastwalk visits at once.
func searchWorkers() int { return min(runtime.NumCPU(), 8) }

func findWithContext(ctx context.Context, root, glob string, limit int) (findResult, error) {
	return findWithWalker(ctx, root, glob, limit, searchWorkers(), fastwalk.Walk)
}

func findWithWalker(ctx context.Context, root, glob string, limit, workers int, walk searchWalker) (findResult, error) {
	if limit < 0 {
		return findResult{}, errors.New("limit cannot be negative")
	}
	if err := ctx.Err(); err != nil {
		if errors.Is(err, context.DeadlineExceeded) {
			return findResult{timedOut: true}, nil
		}
		return findResult{}, err
	}
	resolved, err := resolveSearchRoot(root)
	if err != nil {
		return findResult{}, err
	}
	if resolved.isGitMetadata {
		return findResult{}, nil
	}
	if !resolved.isDir {
		return findResult{files: []string{filepath.ToSlash(resolved.path)}}, nil
	}
	if glob != "" {
		if _, err = doublestar.Match(glob, "probe"); err != nil {
			return findResult{}, fmt.Errorf("invalid glob: %w", err)
		}
	}
	state, err := newWalkState(resolved.path, glob)
	if err != nil {
		return findResult{}, err
	}
	walkErr := walk(&fastwalk.Config{NumWorkers: workers, ToSlash: true}, resolved.path, state.visit(ctx))
	timedOut := errors.Is(ctx.Err(), context.DeadlineExceeded)
	if errors.Is(ctx.Err(), context.Canceled) {
		return findResult{}, context.Canceled
	}
	if walkErr != nil && !timedOut {
		return findResult{}, walkErr
	}
	result := state.finish(limit)
	result.timedOut = timedOut
	return result, nil
}

// resolveGrepRoot returns the physical search root and reports whether it names a file rather
// than a directory.
func resolveGrepRoot(root string) (string, bool, error) {
	absolute, err := filepath.Abs(root)
	if err != nil {
		return "", false, err
	}
	if absolute, err = filepath.EvalSymlinks(absolute); err != nil {
		return "", false, err
	}
	info, err := os.Stat(absolute)
	if err != nil {
		return "", false, err
	}
	return absolute, !info.IsDir(), nil
}

// resolvedRoot is the physical search root after symlink resolution.
type resolvedRoot struct {
	path          string
	isDir         bool
	isGitMetadata bool
}

func resolveSearchRoot(root string) (resolvedRoot, error) {
	abs, err := filepath.Abs(root)
	if err != nil {
		return resolvedRoot{}, err
	}
	if abs, err = filepath.EvalSymlinks(abs); err != nil {
		return resolvedRoot{}, err
	}
	info, err := os.Stat(abs)
	if err != nil {
		return resolvedRoot{}, err
	}
	return resolvedRoot{path: abs, isDir: info.IsDir(), isGitMetadata: isGitMetadataPath(abs)}, nil
}

// directoryScope is the ignore state one directory hands down to its entries, plus the files the
// walk found directly in it.
type directoryScope struct {
	root    string
	matcher *ignoreMatcher
	files   []string
}

// walkState carries what the walk callback needs across directories and collects the outcome.
//
// fastwalk calls each directory callback before reading its entries, so a file always finds the
// scope of its parent. Every scope owns its file slice, and the immutable matchers are shared
// between directories whenever no local ignore file narrows them.
type walkState struct {
	glob string
	// rootPath is the slashed search root and rootPrefix the same string plus a trailing
	// separator for trimming. rootNative is the root spelling the walk reports, which differs on
	// a platform whose separator is not a slash; both spellings identify the root entry itself.
	rootPath   string
	rootPrefix string
	rootNative string
	scopes     sync.Map
	mu         sync.Mutex
	skipped    []string
	ignored    []string
	ignoredN   int
}

func newWalkState(root, glob string) (*walkState, error) {
	scope := nearestGitRoot(root)
	if scope == "" {
		scope = root
	}
	matcher, err := newIgnoreMatcherAt(scope, root)
	if err != nil {
		return nil, err
	}
	// An explicitly selected ignored directory is the search boundary, not an ignored descendant.
	if matcher.Ignored(root, true) {
		scope = root
		if matcher, err = newIgnoreMatcherAt(scope, root); err != nil {
			return nil, err
		}
	}
	rootPath := filepath.ToSlash(root)
	state := &walkState{glob: glob, rootPath: rootPath, rootPrefix: rootPath, rootNative: root}
	if !strings.HasSuffix(state.rootPrefix, "/") {
		state.rootPrefix += "/"
	}
	state.scopes.Store(rootPath, &directoryScope{root: scope, matcher: matcher})
	return state, nil
}

// visit is the fastwalk callback: it sorts every entry into a scope, an ignore hit or a skip.
func (w *walkState) visit(ctx context.Context) fs.WalkDirFunc {
	return func(path string, d fs.DirEntry, walkErr error) error {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return ctxErr
		}
		if walkErr != nil {
			w.addSkipped(path)
			return nil
		}
		if d == nil || path == w.rootPath || path == w.rootNative {
			return nil
		}
		rel := strings.TrimPrefix(path, w.rootPrefix)
		if rel == path {
			return fmt.Errorf("walk path %q is outside search root %q", path, w.rootPath)
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
		parent, err := w.parentScope(path)
		if err != nil {
			return err
		}
		if d.IsDir() {
			return w.enterDir(path, parent)
		}
		w.addFile(path, rel, parent)
		return nil
	}
}

// parentScope returns the scope of the directory that holds path.
func (w *walkState) parentScope(path string) (*directoryScope, error) {
	parent := "/"
	if end := strings.LastIndexByte(path, '/'); end > 0 {
		parent = path[:end]
	}
	value, ok := w.scopes.Load(parent)
	if !ok {
		return nil, fmt.Errorf("search scope for parent directory %q is unavailable", parent)
	}
	return value.(*directoryScope), nil
}

// enterDir registers the ignore scope a directory hands down to its entries.
func (w *walkState) enterDir(path string, parent *directoryScope) error {
	fullPath := filepath.FromSlash(path)
	isRepository := hasGitMarker(fullPath)
	if parent.matcher.Ignored(fullPath, true) {
		w.addIgnored(filepath.ToSlash(fullPath) + "/")
		return filepath.SkipDir
	}
	scope, matcher := parent.root, parent.matcher
	if isRepository {
		scope = fullPath
	}
	localIgnore, readErr := os.ReadFile(filepath.Join(fullPath, ".gitignore"))
	if isRepository || readErr == nil && len(localIgnore) > 0 {
		next, err := newIgnoreMatcherAt(scope, fullPath)
		if err != nil {
			return err
		}
		matcher = next
	}
	w.scopes.Store(path, &directoryScope{root: scope, matcher: matcher})
	return nil
}

// addFile records a file that is either searchable or withheld by an ignore rule.
func (w *walkState) addFile(path, rel string, parent *directoryScope) {
	fullPath := filepath.FromSlash(path)
	if parent.matcher.Ignored(fullPath, false) {
		if w.glob == "" || globMatch(w.glob, rel) {
			w.addIgnored(filepath.ToSlash(fullPath))
		}
		return
	}
	if w.glob == "" || globMatch(w.glob, rel) {
		parent.files = append(parent.files, rel)
	}
}

// addIgnored counts every ignored path and keeps the first ten sorted as a hint.
func (w *walkState) addIgnored(path string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.ignoredN++
	w.ignored = append(w.ignored, path)
	sort.Strings(w.ignored)
	if len(w.ignored) > maxHintPaths {
		w.ignored = w.ignored[:maxHintPaths]
	}
}

// addSkipped records a path the walk could not read, relative to the search root.
func (w *walkState) addSkipped(path string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.skipped = append(w.skipped, filepath.ToSlash(strings.TrimPrefix(path, w.rootPrefix)))
	sort.Slice(w.skipped, func(i, j int) bool { return pathLess(w.skipped[i], w.skipped[j]) })
}

// finish sorts the collected files and applies the limit.
func (w *walkState) finish(limit int) findResult {
	var files []string
	w.scopes.Range(func(_, value any) bool {
		files = append(files, value.(*directoryScope).files...)
		return true
	})
	sort.Slice(files, func(i, j int) bool { return pathLess(files[i], files[j]) })
	limitReached := limit > 0 && len(files) > limit
	if limitReached {
		files = files[:limit]
	}
	return findResult{files: files, skipped: w.skipped, ignored: w.ignored, ignoredCount: w.ignoredN, limitReached: limitReached}
}

func isGitMetadataPath(path string) bool {
	for _, part := range strings.Split(filepath.ToSlash(path), "/") {
		if part == ".git" {
			return true
		}
	}
	return false
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

// pathLess orders paths segment by segment, so "a/b" precedes "a-c" the way rg --sort path does.
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

// ignoreMatcher applies the ignore rules of one repository scope to a directory.
type ignoreMatcher struct {
	root    string
	matcher *ignorealt.Matcher
}

func (m *ignoreMatcher) Ignored(path string, isDir bool) bool {
	rel, err := filepath.Rel(m.root, path)
	return err == nil && m.matcher.Match(filepath.ToSlash(rel), isDir)
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
	for _, p := range directoryChain(baseRoot, directory) {
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

// directoryChain lists directory and its ancestors up to baseRoot, shallowest first, so that the
// rules of an outer directory are added before the ones that override them.
func directoryChain(baseRoot, directory string) []string {
	var chain []string
	for p := directory; ; p = filepath.Dir(p) {
		chain = append(chain, p)
		if p == baseRoot || filepath.Dir(p) == p {
			break
		}
	}
	for left, right := 0, len(chain)-1; left < right; left, right = left+1, right-1 {
		chain[left], chain[right] = chain[right], chain[left]
	}
	return chain
}
