package search

import (
	"context"
	"errors"
	"os"
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

// COD-F-010
func TestFindContextRejectsInvalidRootsPatternsAndLimits(t *testing.T) {
	root := t.TempDir()
	file := filepath.Join(root, "plain.txt")
	if err := os.WriteFile(file, []byte("content"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := FindContext(context.Background(), file, "", 0); err == nil || err.Error() != "root is not a directory" {
		t.Fatalf("file root error=%v", err)
	}
	if _, err := FindContext(context.Background(), filepath.Join(root, "missing"), "", 0); err == nil {
		t.Fatal("missing root accepted")
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
