package search

import (
	"context"
	binaryencoding "encoding/binary"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	goruntime "runtime"
	"strings"
	"testing"
	"time"

	"go.uber.org/goleak"
)

// COD-N-008
func TestFindAndGrepDoNotLaunchRipgrep(t *testing.T) {
	if goruntime.GOOS == "windows" {
		t.Skip("the fake executable uses a POSIX shell script")
	}
	root := t.TempDir()
	binaryDir := filepath.Join(t.TempDir(), "bin")
	if err := os.Mkdir(binaryDir, 0700); err != nil {
		t.Fatal(err)
	}
	marker := filepath.Join(t.TempDir(), "launched")
	rg := filepath.Join(binaryDir, "rg")
	if err := os.WriteFile(rg, []byte(`#!/bin/sh
printf launched > "$SEARCH_RG_MARKER"
`), 0700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", binaryDir+string(os.PathListSeparator)+os.Getenv("PATH"))
	t.Setenv("SEARCH_RG_MARKER", marker)
	if _, err := Find(root, "", 0); err != nil {
		t.Fatal(err)
	}
	if _, err := Grep(root, "needle", true, false, "", 0); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(marker); !os.IsNotExist(err) {
		t.Fatalf("ripgrep was launched: stat error=%v", err)
	}
}

// COD-N-014
func TestSearchContextsDoNotLeakWorkers(t *testing.T) {
	defer goleak.VerifyNone(t)
	root := t.TempDir()
	for _, name := range []string{"a.txt", "b.txt", "c.txt"} {
		if err := os.WriteFile(filepath.Join(root, name), []byte("match\n"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	for i := 0; i < 3; i++ {
		if _, err := FindContext(context.Background(), root, "", 0); err != nil {
			t.Fatal(err)
		}
		if _, err := GrepContext(context.Background(), root, "match", true, false, "", 0); err != nil {
			t.Fatal(err)
		}
	}
}

// COD-N-014
func TestGrepContextReportsResultsAndContextFailures(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "a.txt"), []byte("alpha\nbeta\n"), 0600); err != nil {
		t.Fatal(err)
	}
	outcome, err := GrepContext(context.Background(), root, "beta", true, false, "", 10)
	if err != nil || !reflect.DeepEqual(outcome.Results, []string{"a.txt:2:beta"}) || outcome.Incomplete {
		t.Fatalf("outcome=%#v err=%v", outcome, err)
	}
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := GrepContext(cancelled, root, "beta", true, false, "", 10); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancel error=%v", err)
	}
	if _, err := GrepContext(context.Background(), root, "beta", true, false, "", -1); err == nil {
		t.Fatal("negative limit was accepted")
	}
}

// COD-F-010 COD-F-011 COD-T-165
func TestFindContextRejectsInvalidRootsPatternsAndLimits(t *testing.T) {
	root := t.TempDir()
	file := filepath.Join(root, "plain.txt")
	if err := os.WriteFile(file, []byte("content"), 0600); err != nil {
		t.Fatal(err)
	}
	resolvedFile, err := filepath.EvalSymlinks(file)
	if err != nil {
		t.Fatal(err)
	}
	files, err := FindContext(context.Background(), file, "ignored-glob", 0)
	if err != nil || len(files.Results) != 1 || files.Results[0] != filepath.ToSlash(resolvedFile) {
		t.Fatalf("direct file result=%#v error=%v", files, err)
	}
	if _, err := FindContext(context.Background(), filepath.Join(root, "missing"), "", 0); err == nil {
		t.Fatal("missing root accepted")
	}
	if err := os.Mkdir(filepath.Join(root, ".git"), 0o700); err != nil {
		t.Fatal(err)
	}
	metadata, err := FindContext(context.Background(), filepath.Join(root, ".git"), "", 0)
	if err != nil || len(metadata.Results) != 0 {
		t.Fatalf(".git search root result=%#v error=%v", metadata, err)
	}
	if _, err := FindContext(context.Background(), root, "[", 0); err == nil || !strings.Contains(err.Error(), "invalid glob") {
		t.Fatalf("invalid glob error=%v", err)
	}
	if _, err := FindContext(context.Background(), root, "", -1); err == nil {
		t.Fatal("negative find limit accepted")
	}
	if _, err := Grep(root, "[", false, false, "", 10); err == nil || !strings.Contains(err.Error(), "invalid regex") {
		t.Fatalf("invalid regex error=%v", err)
	}
}

// COD-F-012
func TestGrepContextSupportsRegexAndCaseFoldingModes(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "text.txt"), []byte("Alpha\nalpha\nÉcole\nécole\n"), 0600); err != nil {
		t.Fatal(err)
	}
	for _, test := range []struct {
		name       string
		pattern    string
		literal    bool
		ignoreCase bool
		want       []string
	}{
		{"regex", "^Alpha$", false, false, []string{"text.txt:1:Alpha"}},
		{"regex-ignore-case", "^alpha$", false, true, []string{"text.txt:1:Alpha", "text.txt:2:alpha"}},
		{"literal-ascii-ignore-case", "ALPHA", true, true, []string{"text.txt:1:Alpha", "text.txt:2:alpha"}},
		{"literal-unicode-ignore-case", "ÉCOLE", true, true, []string{"text.txt:3:École", "text.txt:4:école"}},
	} {
		t.Run(test.name, func(t *testing.T) {
			outcome, err := GrepContext(context.Background(), root, test.pattern, test.literal, test.ignoreCase, "", 10)
			if err != nil || !reflect.DeepEqual(outcome.Results, test.want) {
				t.Fatalf("results=%v err=%v want=%v", outcome.Results, err, test.want)
			}
		})
	}
	if _, err := GrepContext(context.Background(), root, "[", false, false, "", 10); err == nil {
		t.Fatal("invalid regex accepted")
	}
}

// COD-N-015
func TestGrepReaderErrorsPreserveCancellationAndContinueReadFailures(t *testing.T) {
	root := t.TempDir()
	for _, name := range []string{"a.txt", "b.txt"} {
		if err := os.WriteFile(filepath.Join(root, name), []byte("match\n"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	readFailure := func(context.Context, string) ([]byte, func(), bool, error) {
		return nil, func() {}, false, errors.New("read failure")
	}
	outcome, err := grepContextWithReader(context.Background(), root, "match", true, false, "", 10, func(ctx context.Context, path string) ([]byte, func(), bool, error) {
		if filepath.Base(path) == "a.txt" {
			return readFailure(ctx, path)
		}
		return readPooledContext(ctx, path)
	})
	if err != nil || !reflect.DeepEqual(outcome.Results, []string{"b.txt:1:match"}) {
		t.Fatalf("read failure outcome=%#v err=%v", outcome, err)
	}
	outcome, err = grepContextWithReader(context.Background(), root, "match", true, false, "", 10, func(context.Context, string) ([]byte, func(), bool, error) {
		return nil, func() {}, false, context.Canceled
	})
	if !errors.Is(err, context.Canceled) || len(outcome.Results) != 0 {
		t.Fatalf("cancelled reader outcome=%#v err=%v", outcome, err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	outcome, err = grepContextWithReader(ctx, root, "match", true, false, "", 10, func(context.Context, string) ([]byte, func(), bool, error) {
		cancel()
		return []byte("match\n"), func() {}, false, nil
	})
	if !errors.Is(err, context.Canceled) || len(outcome.Results) != 1 {
		t.Fatalf("cancel during read outcome=%#v err=%v", outcome, err)
	}
}

// COD-N-015
func TestReadPooledContextHandlesOpenCancelDeadlineAndGrowth(t *testing.T) {
	if _, _, _, err := readPooledContext(context.Background(), filepath.Join(t.TempDir(), "missing")); err == nil {
		t.Fatal("missing file was opened")
	}
	file := filepath.Join(t.TempDir(), "large")
	if err := os.WriteFile(file, []byte(strings.Repeat("x", 70*1024)), 0600); err != nil {
		t.Fatal(err)
	}
	data, release, timedOut, err := readPooledContext(context.Background(), file)
	if err != nil || timedOut || len(data) != 70*1024 {
		t.Fatalf("large read bytes=%d timedOut=%v err=%v", len(data), timedOut, err)
	}
	release()
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	if _, _, _, err := readPooledContext(cancelled, file); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled read error=%v", err)
	}
	deadline, cancelDeadline := context.WithDeadline(context.Background(), time.Unix(1, 0))
	defer cancelDeadline()
	if _, _, timedOut, err := readPooledContext(deadline, file); err != nil || !timedOut {
		t.Fatalf("deadline read timedOut=%v err=%v", timedOut, err)
	}
}

// COD-F-010
func TestGrepASCIIInsensitiveLongPatternAndNegativeLimit(t *testing.T) {
	root := t.TempDir()
	pattern := strings.Repeat("A", 300)
	if err := os.WriteFile(filepath.Join(root, "long.txt"), []byte(strings.ToLower(pattern)), 0600); err != nil {
		t.Fatal(err)
	}
	results, err := Grep(root, pattern, true, true, "", 10)
	if err != nil || !reflect.DeepEqual(results, []string{"long.txt:1:" + strings.ToLower(pattern)}) {
		t.Fatalf("results=%v err=%v", results, err)
	}
	if _, err := Grep(root, pattern, true, false, "", -1); err == nil {
		t.Fatal("negative grep limit accepted")
	}
}

// COD-F-012
func TestLineEndHandlesEmptyAndUnterminatedLines(t *testing.T) {
	for _, test := range []struct {
		data  []byte
		start int
		want  int
	}{
		{nil, 0, 0},
		{[]byte("line"), 0, 4},
		{[]byte("line\nnext"), 0, 4},
		{[]byte("line"), 4, 4},
	} {
		if got := lineEnd(test.data, test.start); got != test.want {
			t.Fatalf("lineEnd(%q, %d)=%d want=%d", test.data, test.start, got, test.want)
		}
	}
}

// COD-N-009
func TestRegexUnicodeCodePointsCaseFoldingAndRE2Syntax(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "unicode.txt"), []byte("é\nÉ\n"), 0600); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		pattern string
		want    []string
	}{
		{pattern: ".", want: []string{"unicode.txt:1:é", "unicode.txt:2:É"}},
		{pattern: "[é]", want: []string{"unicode.txt:1:é"}},
		{pattern: "(?i)é", want: []string{"unicode.txt:1:é", "unicode.txt:2:É"}},
	} {
		got, err := GrepContext(context.Background(), root, tc.pattern, false, false, "", 0)
		if err != nil || !reflect.DeepEqual(got.Results, tc.want) {
			t.Fatalf("pattern %q matches=%v err=%v, want %v", tc.pattern, got.Results, err, tc.want)
		}
	}
	if _, err := GrepContext(context.Background(), root, `(?x)é`, false, false, "", 0); err == nil {
		t.Fatal("Go RE2 unexpectedly accepted extended mode")
	}
}

// COD-N-010
func TestRegexCRLFPreservesLineAnchorsAndOutput(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "crlf.txt"), []byte("before\r\nmatch\r\nafter\r\n"), 0600); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		pattern string
		multi   bool
		want    []string
	}{
		{pattern: "^match$", want: []string{"crlf.txt:2:match"}},
		{pattern: `(?m)^match$`, multi: true, want: []string{"crlf.txt:2:match"}},
		{pattern: `(?m)^after$`, multi: true, want: []string{"crlf.txt:3:after"}},
	} {
		got, err := GrepContext(context.Background(), root, tc.pattern, false, tc.multi, "", 0)
		if err != nil || !reflect.DeepEqual(got.Results, tc.want) {
			t.Fatalf("pattern %q multiline=%v matches=%v err=%v, want %v", tc.pattern, tc.multi, got.Results, err, tc.want)
		}
	}
}

// COD-N-010: crossing behavior is inferred only from syntax that can cross a newline.
func TestCrossLinePatternDetection(t *testing.T) {
	for _, tc := range []struct {
		name    string
		pattern string
		literal bool
		want    bool
	}{
		{"actual-newline", "foo\nbar", false, true},
		{"newline-escape", `foo\nbar`, false, true},
		{"backslash-and-n", `foo\\nbar`, false, false},
		{"whitespace-class", `foo\sbar`, false, false},
		{"dotall", `(?s).`, false, true},
		{"literal-newline", "foo\nbar", true, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := crossLinePattern(tc.pattern, tc.literal, false); got != tc.want {
				t.Fatalf("crossLinePattern(%q, literal=%v)=%v want=%v", tc.pattern, tc.literal, got, tc.want)
			}
		})
	}
}

// COD-T-174 COD-T-240: a cross-line match prints every covered line, while limit counts matches.
func TestMultilineGrepOutputAndMatchLimit(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "text.txt"), []byte("a\nb\na\nb\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	outcome, err := GrepContext(context.Background(), root, `a\nb`, false, false, "", 1)
	want := []string{"text.txt:1:a", "text.txt:2:b"}
	if err != nil || !reflect.DeepEqual(outcome.Results, want) || !outcome.LimitReached || outcome.StopReason != "LIMIT" {
		t.Fatalf("outcome=%#v err=%v want results=%v and match limit", outcome, err, want)
	}
	literal, err := GrepContext(context.Background(), root, "a\nb", true, false, "", 0)
	if err != nil || !reflect.DeepEqual(literal.Results, []string{"text.txt:1:a", "text.txt:2:b", "text.txt:3:a", "text.txt:4:b"}) {
		t.Fatalf("literal multiline results=%v err=%v", literal.Results, err)
	}
}

// COD-T-174: a multiline scan skips files over the bounded whole-file size.
func TestMultilineGrepSkipsOversizeFile(t *testing.T) {
	root := t.TempDir()
	file, err := os.Create(filepath.Join(root, "large.txt"))
	if err != nil {
		t.Fatal(err)
	}
	if err := file.Truncate(64*1024*1024 + 1); err != nil {
		file.Close()
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	outcome, err := GrepContext(context.Background(), root, `foo\nbar`, false, false, "", 0)
	if err != nil || !reflect.DeepEqual(outcome.Skipped, []string{"large.txt"}) {
		t.Fatalf("outcome=%#v err=%v", outcome, err)
	}
}

// COD-T-174: multiline matching normalizes CRLF without emitting carriage returns.
func TestMultilineGrepCRLF(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "crlf.txt"), []byte("foo\r\nbar\r\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	outcome, err := GrepContext(context.Background(), root, `foo\nbar`, false, false, "", 0)
	want := []string{"crlf.txt:1:foo", "crlf.txt:2:bar"}
	if err != nil || !reflect.DeepEqual(outcome.Results, want) {
		t.Fatalf("results=%v err=%v want=%v", outcome.Results, err, want)
	}
}

// COD-T-174: a deadline during a file read retains completed multiline results.
func TestMultilineGrepTimeoutPreservesPartialResults(t *testing.T) {
	root := t.TempDir()
	for _, name := range []string{"a.txt", "b.txt"} {
		if err := os.WriteFile(filepath.Join(root, name), []byte("ignored"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	outcome, err := grepContextWithReader(context.Background(), root, `a\nb`, false, false, "", 10, func(_ context.Context, path string) ([]byte, func(), bool, error) {
		if filepath.Base(path) == "a.txt" {
			return []byte("a\nb\n"), func() {}, false, nil
		}
		return []byte("a\nb\n"), func() {}, true, nil
	})
	want := []string{"a.txt:1:a", "a.txt:2:b", "b.txt:1:a", "b.txt:2:b"}
	if err != nil || !outcome.Incomplete || outcome.StopReason != "TIMED_OUT" || !reflect.DeepEqual(outcome.Results, want) {
		t.Fatalf("outcome=%#v err=%v want=%v", outcome, err, want)
	}
}

// COD-N-018: ignored directories and glob-matching ignored files are bounded hint entries.
func TestFindCollectsIgnoreSkippedPaths(t *testing.T) {
	root := t.TempDir()
	put(t, root, ".gitignore", "node_modules/\nsecret.txt\n")
	put(t, root, "node_modules/inside.txt", "hidden")
	put(t, root, "secret.txt", "hidden")
	put(t, root, "visible.txt", "visible")
	outcome, err := FindContext(context.Background(), root, "*.txt", 0)
	if err != nil {
		t.Fatal(err)
	}
	resolvedRoot, err := filepath.EvalSymlinks(root)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{filepath.ToSlash(filepath.Join(resolvedRoot, "node_modules")) + "/", filepath.ToSlash(filepath.Join(resolvedRoot, "secret.txt"))}
	if !reflect.DeepEqual(outcome.IgnoreSkipped, want) || outcome.IgnoreSkippedCount != 2 || !reflect.DeepEqual(outcome.Results, []string{"visible.txt"}) {
		t.Fatalf("outcome=%#v want ignored=%v", outcome, want)
	}
}

// COD-N-018: only structurally valid Git directories and gitdir pointers establish a repository root.
func TestGitMarkerRequiresValidRepository(t *testing.T) {
	for _, test := range []struct {
		name      string
		setup     func(string) error
		wantValid bool
	}{
		{name: "plain-file", setup: func(root string) error {
			return os.WriteFile(filepath.Join(root, ".git"), []byte("not a gitdir"), 0o600)
		}},
		{name: "broken-pointer", setup: func(root string) error {
			return os.WriteFile(filepath.Join(root, ".git"), []byte("gitdir: missing\n"), 0o600)
		}},
		{name: "missing-head", setup: func(root string) error { return os.MkdirAll(filepath.Join(root, ".git"), 0o700) }},
		{name: "missing-objects", setup: func(root string) error {
			gitDir := filepath.Join(root, ".git")
			if err := os.MkdirAll(gitDir, 0o700); err != nil {
				return err
			}
			if err := os.WriteFile(filepath.Join(gitDir, "HEAD"), []byte("ref: refs/heads/main\n"), 0o600); err != nil {
				return err
			}
			return os.Mkdir(filepath.Join(gitDir, "refs"), 0o700)
		}},
		{name: "valid-gitdir-file", wantValid: true, setup: func(root string) error {
			gitDir := filepath.Join(root, "git-data")
			for _, name := range []string{"objects", "refs"} {
				if err := os.MkdirAll(filepath.Join(gitDir, name), 0o700); err != nil {
					return err
				}
			}
			if err := os.WriteFile(filepath.Join(gitDir, "HEAD"), []byte("ref: refs/heads/main\n"), 0o600); err != nil {
				return err
			}
			return os.WriteFile(filepath.Join(root, ".git"), []byte("gitdir: git-data\n"), 0o600)
		}},
	} {
		t.Run(test.name, func(t *testing.T) {
			root := t.TempDir()
			if err := test.setup(root); err != nil {
				t.Fatal(err)
			}
			got := findGitDir(root)
			if (got != "") != test.wantValid {
				t.Fatalf("findGitDir=%q want valid=%v", got, test.wantValid)
			}
		})
	}
}

// COD-N-017: malformed BOM-encoded text is not searched as UTF-16.
func TestGrepUTF16RejectsOddByteCount(t *testing.T) {
	if _, valid := normalizeSearchText([]byte{0xff, 0xfe, 'x'}); valid {
		t.Fatal("odd-length UTF-16 payload accepted")
	}
}

// COD-N-017: UTF-16 BOM files are decoded for matching and preserve Unicode output.
func TestGrepUTF16BOM(t *testing.T) {
	for _, test := range []struct {
		name  string
		order binaryencoding.ByteOrder
		bom   []byte
	}{
		{name: "little-endian", order: binaryencoding.LittleEndian, bom: []byte{0xff, 0xfe}},
		{name: "big-endian", order: binaryencoding.BigEndian, bom: []byte{0xfe, 0xff}},
	} {
		t.Run(test.name, func(t *testing.T) {
			root := t.TempDir()
			file := filepath.Join(root, "utf16.txt")
			data := append([]byte(nil), test.bom...)
			for _, unit := range []uint16{'c', 'a', 'f', 0x00e9, '\n'} {
				var encoded [2]byte
				test.order.PutUint16(encoded[:], unit)
				data = append(data, encoded[:]...)
			}
			if err := os.WriteFile(file, data, 0o600); err != nil {
				t.Fatal(err)
			}
			outcome, err := GrepContext(context.Background(), root, "café", true, false, "", 10)
			if err != nil || !reflect.DeepEqual(outcome.Results, []string{"utf16.txt:1:café"}) {
				t.Fatalf("results=%v err=%v", outcome.Results, err)
			}
		})
	}
}

// COD-N-017: overlong lines are excerpted around the matching code point.
func TestCenteredExcerpt(t *testing.T) {
	line := strings.Repeat("a", 600)
	got := centeredExcerpt(line, 300)
	excerpt := strings.TrimPrefix(got, "... ")
	excerpt = strings.TrimSuffix(excerpt, " ... (line truncated to 500 chars)")
	if len([]rune(excerpt)) != 440 || excerpt != strings.Repeat("a", 440) || !strings.HasPrefix(got, "... ") || !strings.HasSuffix(got, " ... (line truncated to 500 chars)") {
		t.Fatalf("centered excerpt=%q", got)
	}
	if got := centeredExcerpt("short", 2); got != "short" {
		t.Fatalf("short line excerpt=%q", got)
	}
}

// Long Unicode lines are excerpted by code point without splitting a match.
func TestGrepLongUnicodeLineCenteredExcerpt(t *testing.T) {
	root := t.TempDir()
	line := strings.Repeat("界", 300) + "needle" + strings.Repeat("界", 300)
	if err := os.WriteFile(filepath.Join(root, "unicode.txt"), []byte(line), 0o600); err != nil {
		t.Fatal(err)
	}

	got, err := Grep(root, "needle", false, false, "", 0)
	if err != nil {
		t.Fatal(err)
	}
	want := "unicode.txt:1:... " + strings.Repeat("界", 220) + "needle" + strings.Repeat("界", 214) + " ... (line truncated to 500 chars)"
	if !reflect.DeepEqual(got, []string{want}) {
		t.Fatalf("grep=%q want=%q", got, want)
	}
}

// COD-N-011: an invalid nested .git marker is ignored and Git remains the oracle.
func TestInvalidNestedGitMarkerKeepsOuterRepositoryScope(t *testing.T) {
	root := t.TempDir()
	if out, err := exec.Command("git", "init", "-q", root).CombinedOutput(); err != nil {
		t.Fatalf("git init: %s %v", out, err)
	}
	put(t, root, ".gitignore", "ignored.txt\n")
	if err := os.MkdirAll(filepath.Join(root, "nested", ".git"), 0o700); err != nil {
		t.Fatal(err)
	}
	put(t, root, "nested/ignored.txt", "ignored")
	put(t, root, "nested/keep.txt", "keep")
	put(t, root, "broken/.git", "gitdir: missing\n")
	put(t, root, "broken/ignored.txt", "ignored")
	put(t, root, "broken/keep.txt", "keep")
	files, err := FindContext(context.Background(), root, "", 0)
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command("git", "-C", root, "ls-files", "-co", "--exclude-standard")
	output, err := cmd.Output()
	if err != nil {
		t.Fatalf("git ls-files oracle: %v", err)
	}
	var want []string
	for _, path := range strings.Split(strings.TrimSpace(string(output)), "\n") {
		if path != "" {
			want = append(want, filepath.ToSlash(path))
		}
	}
	if !reflect.DeepEqual(files.Results, want) {
		t.Fatalf("Find=%v Git oracle=%v", files.Results, want)
	}
	if nearestGitRoot(filepath.Join(root, "nested")) != root {
		t.Fatalf("invalid nested marker established a repository boundary")
	}
}

// COD-N-012
func TestRegexRequiredLiteralExtraction(t *testing.T) {
	for _, test := range []struct {
		pattern string
		want    string
	}{
		{"literal", "literal"},
		{"(captured)", "captured"},
		{"short.*longest", "longest"},
		{"repeat+", "repea"},
		{"repeat{2,4}", "repea"},
		{"prefix(?:one|other)suffix", "prefix"},
		{"commonA|commonB", "common"},
		{"left|right", ""},
		{"(?i)folded", ""},
		{"[", ""},
	} {
		t.Run(test.pattern, func(t *testing.T) {
			if got := string(requiredLiteralPattern(test.pattern)); got != test.want {
				t.Fatalf("required literal=%q want=%q", got, test.want)
			}
		})
	}
}
