package coding

import (
	"encoding/json"
	"errors"
	"os"
	"strings"
	"testing"
)

// COD-T-120: a real /dev/full write failure must never publish a complete output.
func TestOutputWriteFailure(t *testing.T) {
	device, err := os.OpenFile("/dev/full", os.O_WRONLY, 0)
	if errors.Is(err, os.ErrNotExist) || errors.Is(err, os.ErrPermission) {
		t.Skipf("/dev/full is unavailable: %v", err)
	}
	if err != nil {
		t.Fatal(err)
	}

	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	spool, err := NewOutputSpool(store, "write-failure", defaultCaptureBudget)
	if err != nil {
		t.Fatal(err)
	}
	_, staging, err := store.CreateStagingFile("write-failure")
	if err != nil {
		t.Fatal(err)
	}
	spool.file = device
	spool.staging = staging
	spool.spilled = true
	spool.Write([]byte(strings.Repeat("x", inlineOutputBytes+1)))

	result := spool.Finish(false, "")
	var details struct {
		TextOutput struct {
			TotalBytes    int64  `json:"totalBytes"`
			CaptureFailed bool   `json:"captureFailed"`
			Path          string `json:"path"`
		} `json:"textOutput"`
	}
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	if !details.TextOutput.CaptureFailed || details.TextOutput.TotalBytes != inlineOutputBytes+1 || details.TextOutput.Path != "" {
		t.Fatalf("text output details=%s", result.Details)
	}
	if strings.Contains(result.Contents[0].Text, "Full output:") || !strings.Contains(result.Contents[0].Text, "could not be saved to local storage") {
		t.Fatalf("failure preview=%q", result.Contents[0].Text)
	}
	if _, err := os.Stat(staging); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("staging file remains after write failure: %v", err)
	}
	if err := spool.Close(); err != nil {
		t.Fatal(err)
	}
}
