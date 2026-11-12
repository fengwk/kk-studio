package coding

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type syncFailureOutputFile struct {
	*os.File
	closeCalls int
}

func (f *syncFailureOutputFile) Sync() error {
	return errors.New("fixture fsync failure")
}

func (f *syncFailureOutputFile) Close() error {
	f.closeCalls++
	return f.File.Close()
}

// COD-T-121: fsync failure after budget truncation must not publish full output.
func TestOutputFsyncFailure(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	spool, err := NewOutputSpool(store, "fsync-failure", 50)
	if err != nil {
		t.Fatal(err)
	}
	file, staging, err := store.CreateStagingFile("fsync-failure")
	if err != nil {
		t.Fatal(err)
	}
	failingFile := &syncFailureOutputFile{File: file}
	spool.file = failingFile
	spool.staging = staging
	spool.spilled = true
	spool.Write(bytes.Repeat([]byte("y"), 100))

	result := spool.Finish(false, "")
	var details struct {
		TextOutput struct {
			TotalBytes       int64 `json:"totalBytes"`
			CaptureTruncated bool  `json:"captureTruncated"`
			CaptureFailed    bool  `json:"captureFailed"`
		} `json:"textOutput"`
	}
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	if details.TextOutput.TotalBytes != 100 || !details.TextOutput.CaptureTruncated || !details.TextOutput.CaptureFailed {
		t.Fatalf("text output details=%s", result.Details)
	}
	text := result.Contents[0].Text
	if !strings.Contains(text, "could not be saved to local storage") || strings.Contains(text, "Full output:") {
		t.Fatalf("failure preview=%q", text)
	}
	if failingFile.closeCalls != 1 {
		t.Fatalf("close calls=%d want=1", failingFile.closeCalls)
	}
	if _, err := file.Stat(); !errors.Is(err, os.ErrClosed) {
		t.Fatalf("file stat after sync failure err=%v want os.ErrClosed", err)
	}
	if _, err := os.Stat(staging); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("staging file remains after sync failure: %v", err)
	}
	if err := spool.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Dir(staging)); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("staging workspace remains after close: %v", err)
	}
}
