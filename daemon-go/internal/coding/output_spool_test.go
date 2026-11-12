package coding

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

// COD-T-122
func TestOutputLimitsAndInlineThreshold(t *testing.T) {
	if defaultCaptureBudget != 1<<30 {
		t.Fatalf("default capture budget=%d want 1 GiB", defaultCaptureBudget)
	}
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	spool, err := NewOutputSpool(store, "inline", defaultCaptureBudget)
	if err != nil {
		t.Fatal(err)
	}
	want := strings.Repeat("x", inlineOutputBytes)
	spool.Write([]byte(want))
	result := spool.Finish(false, "")
	if result.Contents[0].Text != want || result.Details != nil {
		t.Fatalf("inline result=%#v", result)
	}
	entries, err := os.ReadDir(store.Root())
	if err != nil || len(entries) != 0 {
		t.Fatalf("inline output created staging or published files: entries=%v err=%v", entries, err)
	}
	if err := spool.Close(); err != nil {
		t.Fatal(err)
	}
}

// COD-T-002
func TestOutputLineCountHandlesCRLFOnce(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	spool, _ := NewOutputSpool(store, "lines", defaultCaptureBudget)
	spool.Write([]byte("a\r"))
	spool.Write([]byte("\nb\nlast"))
	if got := spool.totalLines(); got != 3 {
		t.Fatalf("line count=%d want=3", got)
	}
	if got := spool.TotalBytes(); got != 9 {
		t.Fatalf("byte count=%d want=9", got)
	}
	_ = spool.Close()
}

// COD-T-003
func TestCaptureBudgetPublishesCapturedPrefixAndKeepsPreview(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	spool, err := NewOutputSpool(store, "budget", 70*1024)
	if err != nil {
		t.Fatal(err)
	}
	data := bytes.Repeat([]byte("z"), 100*1024)
	spool.Write(data)
	if !spool.IsCaptureTruncated() {
		t.Fatal("expected capture to stop at budget")
	}
	if spool.TotalBytes() != int64(len(data)) || spool.CaptureTruncatedTailPreview() != string(data[len(data)-previewTailBytes:]) {
		t.Fatal("total bytes or truncated tail preview mismatch")
	}
	result := spool.Finish(false, "")
	if !strings.Contains(result.Contents[0].Text, "Capture stopped at the local 71680-byte daemon budget") {
		t.Fatalf("preview=%s", result.Contents[0].Text)
	}
	var details struct {
		TextOutput struct {
			Path          string `json:"path"`
			CapturedBytes int64  `json:"capturedBytes"`
		} `json:"textOutput"`
	}
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	captured, err := os.ReadFile(details.TextOutput.Path)
	if err != nil {
		t.Fatal(err)
	}
	if int64(len(captured)) != details.TextOutput.CapturedBytes || int64(len(captured)) != 70*1024 {
		t.Fatalf("captured bytes=%d metadata=%d", len(captured), details.TextOutput.CapturedBytes)
	}
	if err := spool.Close(); err != nil {
		t.Fatal(err)
	}
}

// COD-T-014: publishing and closing repeated spills must not leave descriptors open on Linux.
func TestPublishedFDClosed(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("/proc/self/fd is available only on Linux")
	}
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	fdCount := func() int {
		entries, err := os.ReadDir("/proc/self/fd")
		if err != nil {
			t.Fatal(err)
		}
		return len(entries)
	}
	before := fdCount()
	for i := 0; i < 5; i++ {
		spool, err := NewOutputSpool(store, fmt.Sprintf("fd-%d", i), defaultCaptureBudget)
		if err != nil {
			t.Fatal(err)
		}
		spool.Write(bytes.Repeat([]byte("x"), inlineOutputBytes+1))
		result := spool.Finish(false, "")
		if result.Error || !strings.Contains(result.Contents[0].Text, "Full output:") {
			t.Fatalf("spill was not published: %#v", result)
		}
		if err := spool.Close(); err != nil {
			t.Fatal(err)
		}
	}
	if after := fdCount(); after > before {
		t.Fatalf("file descriptors increased from %d to %d", before, after)
	}
}

// COD-T-125
func TestOutputLeaseProtectsWorkspaceFromSweep(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	file, path, err := store.CreateStagingFile("lease")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := file.Write([]byte("data")); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	path, err = store.Publish(path)
	if err != nil {
		t.Fatal(err)
	}
	workspace := filepath.Dir(path)
	if err := os.WriteFile(filepath.Join(workspace, "created-at"), []byte("1"), 0o600); err != nil {
		t.Fatal(err)
	}
	release := store.Acquire(path)
	removed, err := store.Sweep(time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if removed != 0 {
		t.Fatalf("removed leased workspace: %d", removed)
	}
	release()
	removed, err = store.Sweep(time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if removed != 1 {
		t.Fatalf("removed=%d want=1", removed)
	}
}
