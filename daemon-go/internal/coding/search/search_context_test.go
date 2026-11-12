package search

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"
)

// COD-T-198
func TestNoFilesReadAfterLimit(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "a.txt"), []byte("x\nx\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "b.txt"), []byte("x\n"), 0600); err != nil {
		t.Fatal(err)
	}
	var readPaths []string
	reader := func(ctx context.Context, path string) ([]byte, func(), bool, error) {
		readPaths = append(readPaths, filepath.Base(path))
		return readPooledContext(ctx, path)
	}
	outcome, err := grepContextWithReader(context.Background(), root, "x", true, false, "", 1, reader)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(readPaths, []string{"a.txt"}) {
		t.Fatalf("read paths=%v", readPaths)
	}
	if !reflect.DeepEqual(outcome.Results, []string{"a.txt:1:x"}) || !outcome.LimitReached || outcome.Incomplete {
		t.Fatalf("outcome=%#v", outcome)
	}
}

// COD-F-010
func TestGrepExactLimitDoesNotReportLimitReached(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "a.txt"), []byte("x\nx\n"), 0600); err != nil {
		t.Fatal(err)
	}
	outcome, err := GrepContext(context.Background(), root, "x", true, false, "", 2)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(outcome.Results, []string{"a.txt:1:x", "a.txt:2:x"}) || outcome.LimitReached || outcome.Incomplete {
		t.Fatalf("outcome=%#v", outcome)
	}
}

// COD-N-014
func TestFindContextReturnsPartialOnDeadlineAndCancellationError(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "a.txt"), []byte("x"), 0600); err != nil {
		t.Fatal(err)
	}
	deadline, cancelDeadline := context.WithDeadline(context.Background(), time.Unix(1, 0))
	defer cancelDeadline()
	outcome, err := FindContext(deadline, root, "", 0)
	if err != nil || !outcome.Incomplete || outcome.StopReason != "TIMED_OUT" || len(outcome.Results) != 0 {
		t.Fatalf("outcome=%#v err=%v", outcome, err)
	}
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := FindContext(cancelled, root, "", 0); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancellation error=%v", err)
	}
}

// COD-T-197
func TestSearchTimeoutPreservesCurrentFilePartialAndStopsScheduling(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "a.txt"), nil, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "b.txt"), nil, 0600); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var readPaths []string
	reader := func(_ context.Context, path string) ([]byte, func(), bool, error) {
		readPaths = append(readPaths, filepath.Base(path))
		return []byte("match line\n"), func() {}, true, nil
	}
	outcome, err := grepContextWithReader(ctx, root, "match", true, false, "", 10, reader)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(outcome.Results, []string{"a.txt:1:match line"}) || outcome.StopReason != "TIMED_OUT" || !outcome.Incomplete {
		t.Fatalf("outcome=%#v", outcome)
	}
	if !reflect.DeepEqual(readPaths, []string{"a.txt"}) {
		t.Fatalf("read paths=%v", readPaths)
	}
}
