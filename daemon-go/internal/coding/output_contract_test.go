package coding

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// COD-T-123: crossing the inline threshold atomically publishes the exact full output.
func TestOutputSpillPublishesDurableFullContent(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	spool, err := NewOutputSpool(store, "spill", defaultCaptureBudget)
	if err != nil {
		t.Fatal(err)
	}
	data := bytes.Repeat([]byte("x"), inlineOutputBytes+1)
	spool.Write(data)
	result := spool.Finish(false, "")
	var details struct {
		TextOutput struct {
			Path string `json:"path"`
		} `json:"textOutput"`
	}
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	if result.Error || details.TextOutput.Path == "" || strings.Contains(details.TextOutput.Path, ".part") {
		t.Fatalf("output was not published: result=%#v details=%s", result, result.Details)
	}
	published, err := os.ReadFile(details.TextOutput.Path)
	if err != nil || !bytes.Equal(published, data) {
		t.Fatalf("published bytes=%d want=%d err=%v", len(published), len(data), err)
	}
	if err := spool.Close(); err != nil {
		t.Fatal(err)
	}
}

// COD-T-127: data-directory output storage and TTL cleanup operate on the same root.
func TestOutputStoreDataDirRootIsTTLCleanupRoot(t *testing.T) {
	dataDir := t.TempDir()
	store, err := TextOutputStoreForDataDir(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	wantRoot := filepath.Join(dataDir, "tmp", "workspaces")
	if store.Root() != wantRoot {
		t.Fatalf("root=%q want=%q", store.Root(), wantRoot)
	}
	file, staging, err := store.CreateStagingFile("ttl-root")
	if err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	published, err := store.Publish(staging)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(filepath.Dir(published), "created-at"), []byte("0"), 0o600); err != nil {
		t.Fatal(err)
	}
	removed, err := store.Sweep(time.Second)
	if err != nil || removed != 1 {
		t.Fatalf("sweep removed=%d err=%v want=1", removed, err)
	}
}
