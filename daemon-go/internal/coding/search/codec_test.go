package search

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

// COD-T-265: undecodable files are listed by absolute path, binary files are skipped silently
// and decoded text is matched.
func TestGrepContextReportsUndecodableFiles(t *testing.T) {
	// grep lists real paths; temp directories sit behind symlinks (macOS) or short names (Windows).
	root, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	for name, data := range map[string][]byte{
		"text.txt":   []byte("target\n"),
		"gbk.txt":    {'x', 0xd6, 0xd0, 't', 'a', 'r', 'g', 'e', 't'},
		"binary.dat": {'x', 0, 'y', 't', 'a', 'r', 'g', 'e', 't'},
	} {
		if err := os.WriteFile(filepath.Join(root, name), data, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	outcome, err := GrepContext(context.Background(), root, "target", true, false, "", 10)
	if err != nil {
		t.Fatalf("GrepContext: %v", err)
	}
	if len(outcome.Results) != 1 || outcome.Results[0] != "text.txt:1:target" {
		t.Fatalf("results=%q", outcome.Results)
	}
	if len(outcome.Undecodable) != 1 || outcome.Undecodable[0] != filepath.ToSlash(filepath.Join(root, "gbk.txt")) || outcome.UndecodableCount != 1 {
		t.Fatalf("undecodable=%q count=%d", outcome.Undecodable, outcome.UndecodableCount)
	}
	if len(outcome.Skipped) != 0 {
		t.Fatalf("skipped=%q", outcome.Skipped)
	}
}

// COD-T-266: a file given directly as path reports the codec failure and is never searched.
func TestGrepContextReportsUndecodableDirectFile(t *testing.T) {
	dir, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "gbk.txt")
	if err := os.WriteFile(path, []byte{'x', 0xd6, 0xd0}, 0o600); err != nil {
		t.Fatal(err)
	}
	_, err = GrepContext(context.Background(), path, "x", true, false, "", 10)
	var undecodable *UndecodableError
	if !errors.As(err, &undecodable) || undecodable.Path != path {
		t.Fatalf("err=%v", err)
	}
	if !errors.Is(err, undecodable.Err) || !errors.Is(undecodable, undecodable.Err) {
		t.Fatalf("Unwrap err=%v", err)
	}
	if err.Error() == "" {
		t.Fatal("the codec failure is not reported")
	}
	if _, err := Grep(path, "x", true, false, "", 10); err != nil {
		t.Fatalf("Grep: %v", err)
	}
}

// COD-T-267: lone CR line breaks give the same line numbers and line text as read.
func TestGrepLoneCRLineNumbersMatchRead(t *testing.T) {
	root := t.TempDir()
	path := filepath.Join(root, "cr.txt")
	if err := os.WriteFile(path, []byte("one\rtwo\r\nthree\nfour"), 0o600); err != nil {
		t.Fatal(err)
	}
	for pattern, want := range map[string]string{
		"one":   "cr.txt:1:one",
		"two":   "cr.txt:2:two",
		"three": "cr.txt:3:three",
		"four":  "cr.txt:4:four",
	} {
		outcome, err := GrepContext(context.Background(), root, pattern, true, false, "", 10)
		if err != nil {
			t.Fatalf("%s: %v", pattern, err)
		}
		if len(outcome.Results) != 1 || outcome.Results[0] != want {
			t.Fatalf("%s: results=%q want %q", pattern, outcome.Results, want)
		}
	}
	outcome, err := GrepContext(context.Background(), root, "one\rtwo\nthree", true, false, "", 10)
	if err != nil {
		t.Fatalf("multiline: %v", err)
	}
	want := []string{"cr.txt:1:one", "cr.txt:2:two", "cr.txt:3:three"}
	if len(outcome.Results) != len(want) {
		t.Fatalf("multiline results=%q", outcome.Results)
	}
	for index := range want {
		if outcome.Results[index] != want[index] {
			t.Fatalf("multiline results=%q want %q", outcome.Results, want)
		}
	}
}
