package search

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

// COD-T-162: slash-containing globs are interpreted relative to the search root.
func TestFindSlashGlobIsRelativeToSearchRoot(t *testing.T) {
	root := t.TempDir()
	put(t, root, "src/a.ts", "")
	put(t, root, "nested/src/b.ts", "")
	put(t, root, "a.ts", "")
	outcome, err := FindContext(context.Background(), root, "src/*.ts", 0)
	if err != nil || !reflect.DeepEqual(outcome.Results, []string{"src/a.ts"}) {
		t.Fatalf("results=%v err=%v", outcome.Results, err)
	}
}

// COD-T-177: Go RE2 \w retains ASCII-only character-class semantics.
func TestRegexWordClassASCIISemantics(t *testing.T) {
	root := t.TempDir()
	put(t, root, "unicode.txt", "中文\nline\n")
	outcome, err := GrepContext(context.Background(), root, `\w+`, false, false, "", 0)
	if err != nil || !reflect.DeepEqual(outcome.Results, []string{"unicode.txt:2:line"}) {
		t.Fatalf("results=%v err=%v", outcome.Results, err)
	}
}

// COD-T-179, COD-T-180: binary files are ignored throughout directory searches, even before a NUL match.
func TestGrepDirectoryBinaryFilesAreSkipped(t *testing.T) {
	for _, data := range [][]byte{{0, 1}, []byte("match\n\x00tail\n")} {
		root := t.TempDir()
		if err := os.WriteFile(filepath.Join(root, "binary.dat"), data, 0o600); err != nil {
			t.Fatal(err)
		}
		outcome, err := GrepContext(context.Background(), root, "match", true, false, "", 0)
		if err != nil || len(outcome.Results) != 0 {
			t.Fatalf("binary %x results=%v err=%v", data, outcome.Results, err)
		}
	}
}

// COD-T-183: hidden files are searchable while Git metadata is never exposed.
func TestGrepSearchesHiddenFilesAndSkipsGitMetadata(t *testing.T) {
	root := t.TempDir()
	put(t, root, ".git/config", "needle\n")
	put(t, root, ".hidden.txt", "needle\n")
	outcome, err := GrepContext(context.Background(), root, "needle", true, false, "", 0)
	if err != nil || !reflect.DeepEqual(outcome.Results, []string{".hidden.txt:1:needle"}) {
		t.Fatalf("results=%v err=%v", outcome.Results, err)
	}
}

// COD-T-199: a file read failure is reported as skipped, not as an empty search.
func TestSearchRecordsUnreadablePathsAsSkipped(t *testing.T) {
	root := t.TempDir()
	put(t, root, "a.txt", "match\n")
	put(t, root, "b.txt", "match\n")
	outcome, err := grepContextWithReader(context.Background(), root, "match", true, false, "", 10, func(ctx context.Context, path string) ([]byte, func(), bool, error) {
		if filepath.Base(path) == "a.txt" {
			return nil, func() {}, false, errors.New("fixture read failure")
		}
		return readPooledContext(ctx, path)
	})
	if err != nil || !reflect.DeepEqual(outcome.Skipped, []string{"a.txt"}) || !reflect.DeepEqual(outcome.Results, []string{"b.txt:1:match"}) {
		t.Fatalf("outcome=%#v err=%v", outcome, err)
	}
}
