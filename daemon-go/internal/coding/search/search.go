// Package search is the in-process M0 search prototype. File discovery and
// content matching are parallel; all externally visible output is path sorted.
package search

import (
	"bytes"
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

// Find returns paths relative to root, ordered like rg --sort path.
func Find(root, glob string, limit int) ([]string, error) {
	return findWith(root, glob, limit, min(runtime.NumCPU(), 8))
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
	caseFoldRegex := literal && ignoreCase && !ascii(pattern)
	if !literal || caseFoldRegex {
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
	results := parallel(files, func(rel string) []string {
		data, release, e := readPooled(filepath.Join(root, filepath.FromSlash(rel)))
		if e != nil {
			return nil
		}
		defer release()
		return grepFile(data, filepath.ToSlash(rel), pattern, literal, ignoreCase, re, required)
	})
	flat := flatten(results)
	if limit > 0 && len(flat) > limit {
		flat = flat[:limit]
	}
	return flat, nil
}

func findWith(root, glob string, limit, workers int) ([]string, error) {
	if limit < 0 {
		return nil, errors.New("limit cannot be negative")
	}
	abs, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	info, err := os.Stat(abs)
	if err != nil {
		return nil, err
	}
	if !info.IsDir() {
		return nil, errors.New("root is not a directory")
	}
	if glob != "" {
		if _, err = doublestar.Match(glob, "probe"); err != nil {
			return nil, fmt.Errorf("invalid glob: %w", err)
		}
	}
	matcher, err := newIgnoreMatcher(abs)
	if err != nil {
		return nil, err
	}
	// An explicitly selected ignored directory is the search boundary, not an ignored descendant.
	if matcher.Ignored(abs, true) {
		matcher = &ignoreMatcher{root: abs, matcher: ignorealt.New()}
		if b, e := os.ReadFile(filepath.Join(abs, ".gitignore")); e == nil {
			matcher.matcher.AddPatterns("", b)
		}
	}
	var files []string
	var filesMu sync.Mutex
	err = fastwalk.Walk(&fastwalk.Config{NumWorkers: workers, ToSlash: true}, abs, func(path string, d fs.DirEntry, walkErr error) error {
		if walkErr != nil || d == nil {
			return nil
		}
		if path == abs {
			return nil
		}
		rel, _ := filepath.Rel(abs, filepath.FromSlash(path))
		rel = filepath.ToSlash(rel)
		if d.Type()&os.ModeSymlink != 0 {
			return nil
		}
		if filepath.Base(rel) == ".git" {
			if d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		fullPath := filepath.Join(abs, filepath.FromSlash(rel))
		if matcher.Ignored(fullPath, d.IsDir()) {
			if d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if d.IsDir() {
			if b, e := os.ReadFile(filepath.Join(fullPath, ".gitignore")); e == nil {
				matcher.matcher.AddPatterns(rel, b)
			}
			return nil
		}
		if glob == "" || globMatch(glob, rel) {
			filesMu.Lock()
			files = append(files, rel)
			filesMu.Unlock()
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	sort.Slice(files, func(i, j int) bool { return pathLess(files[i], files[j]) })
	if limit > 0 && len(files) > limit {
		files = files[:limit]
	}
	return files, nil
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

func newIgnoreMatcher(searchRoot string) (*ignoreMatcher, error) {
	baseRoot := searchRoot
	for p := searchRoot; ; p = filepath.Dir(p) {
		if _, err := os.Lstat(filepath.Join(p, ".git")); err == nil {
			baseRoot = p
			break
		}
		if filepath.Dir(p) == p {
			break
		}
	}
	m := &ignoreMatcher{root: baseRoot, matcher: ignorealt.New()}
	// Git's info/exclude has lower precedence than the repository's .gitignore files.
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
	for p := searchRoot; ; p = filepath.Dir(p) {
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
	for p := root; ; p = filepath.Dir(p) {
		if b, e := os.ReadFile(filepath.Join(p, ".git")); e == nil {
			s := strings.TrimSpace(string(b))
			if strings.HasPrefix(s, "gitdir:") {
				gitDir := strings.TrimSpace(strings.TrimPrefix(s, "gitdir:"))
				if !filepath.IsAbs(gitDir) {
					gitDir = filepath.Join(p, gitDir)
				}
				return filepath.Clean(gitDir)
			}
		}
		if st, e := os.Stat(filepath.Join(p, ".git")); e == nil && st.IsDir() {
			return filepath.Join(p, ".git")
		}
		if filepath.Dir(p) == p {
			break
		}
	}
	return ""
}
func (m *ignoreMatcher) Ignored(path string, isDir bool) bool {
	rel, err := filepath.Rel(m.root, path)
	return err == nil && m.matcher.Match(filepath.ToSlash(rel), isDir)
}

func binary(b []byte) bool {
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
