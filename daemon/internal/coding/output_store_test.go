package coding

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"testing/synctest"
	"time"
	"unicode/utf8"
)

func TestSpillFailureFallsBackToBoundedPreview(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	spool, err := NewOutputSpool(store, "spill-failure", defaultCaptureBudget)
	if err != nil {
		t.Fatal(err)
	}
	spool.Write(bytes.Repeat([]byte("x"), inlineOutputBytes+1))
	if spool.file == nil || spool.staging == "" {
		t.Fatal("expected spill file")
	}
	if err := spool.file.Close(); err != nil {
		t.Fatal(err)
	}
	spool.Write([]byte("extra"))
	result := spool.Finish(false, "")
	var details struct {
		TextOutput struct {
			CaptureFailed bool    `json:"captureFailed"`
			Path          *string `json:"path"`
		} `json:"textOutput"`
	}
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(result.Contents[0].Text, "could not be saved to local storage") || !details.TextOutput.CaptureFailed || details.TextOutput.Path != nil {
		t.Fatalf("result=%#v details=%#v", result, details)
	}
	if err := spool.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestAppendFinalNoteToPublishedOutput(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	spool, err := NewOutputSpool(store, "note", defaultCaptureBudget)
	if err != nil {
		t.Fatal(err)
	}
	spool.Write(bytes.Repeat([]byte("x"), inlineOutputBytes+1))
	result := spool.Finish(false, "[Command exited with code 2.]")
	var details struct {
		TextOutput struct {
			Path string `json:"path"`
		} `json:"textOutput"`
	}
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	wantFooter := fmt.Sprintf("[Full output: %d bytes, 1 lines. Saved to: %s\nUse read with offset/limit to page through the file, or grep to search it.]\n[Command exited with code 2.]", inlineOutputBytes+1, details.TextOutput.Path)
	if !strings.HasSuffix(result.Contents[0].Text, wantFooter) {
		t.Fatalf("preview=%q want suffix=%q", result.Contents[0].Text, wantFooter)
	}
}

func TestPublishUnregisteredPartFile(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	workspace := filepath.Join(store.Root(), "12345678-1234-4234-8234-123456789abc")
	if err := os.Mkdir(workspace, 0700); err != nil {
		t.Fatal(err)
	}
	part := filepath.Join(workspace, "unregistered.part")
	if err := os.WriteFile(part, []byte("output"), 0600); err != nil {
		t.Fatal(err)
	}
	published, err := store.Publish(part)
	if err != nil || published != strings.TrimSuffix(part, ".part")+".log" {
		t.Fatalf("published=%q err=%v", published, err)
	}
	if data, err := os.ReadFile(published); err != nil || string(data) != "output" {
		t.Fatalf("data=%q err=%v", data, err)
	}
}

// Backdate two staging workspaces to simulate long-running and interrupted captures without waiting.
func TestPublishedOutputRetentionStartsAtPublish(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		dataDir := t.TempDir()
		store, err := OpenTextOutputStore(dataDir)
		if err != nil {
			t.Fatal(err)
		}
		stage := func(id string) string {
			t.Helper()
			file, path, err := store.CreateStagingFile(id)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := file.WriteString("captured output"); err != nil {
				t.Fatal(err)
			}
			if err := file.Close(); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(filepath.Join(filepath.Dir(path), "created-at"), []byte("1"), 0600); err != nil {
				t.Fatal(err)
			}
			return path
		}
		staged, abandoned := stage("long-running"), stage("interrupted")
		time.Sleep(500 * time.Millisecond) // Publish between whole-second boundaries.
		const ttl = time.Minute
		if removed, err := store.Sweep(ttl); err != nil || removed != 0 {
			t.Fatalf("sweep while staged: removed=%d err=%v", removed, err)
		}
		published, err := store.Publish(staged)
		if err != nil {
			t.Fatal(err)
		}

		// Reopening loses the abandoned write lease, but must preserve the published file's TTL.
		reopened, err := OpenTextOutputStore(dataDir)
		if err != nil {
			t.Fatal(err)
		}
		if removed, err := reopened.Sweep(ttl); err != nil || removed != 1 {
			t.Errorf("sweep after reopening: removed=%d err=%v; want only abandoned workspace removed", removed, err)
		}
		if _, err := os.Stat(abandoned); !errors.Is(err, os.ErrNotExist) {
			t.Errorf("abandoned staging file: %v", err)
		}
		// A second-resolution marker must still retain the output for at least the full TTL.
		time.Sleep(ttl - time.Nanosecond)
		if removed, err := reopened.Sweep(ttl); err != nil || removed != 0 {
			t.Errorf("sweep before TTL: removed=%d err=%v", removed, err)
		}
		if data, err := os.ReadFile(published); err != nil || string(data) != "captured output" {
			t.Fatalf("published output=%q err=%v", data, err)
		}
		time.Sleep(time.Second + time.Nanosecond)
		if removed, err := reopened.Sweep(ttl); err != nil || removed != 1 {
			t.Fatalf("sweep after TTL: removed=%d err=%v", removed, err)
		}
	})
}

// A retention-marker error must leave an unpublished capture available for cleanup.
func TestPublishDoesNotExposeLogWhenRetentionMarkerFails(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	file, path, err := store.CreateStagingFile("publish-failure")
	if err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(filepath.Dir(path), "created-at.part"), 0700); err != nil {
		t.Fatal(err)
	}
	if target, err := store.Publish(path); target != "" || err == nil {
		t.Fatalf("target=%q err=%v; want publish failure", target, err)
	}
	if _, err := os.Stat(strings.TrimSuffix(path, ".part") + ".log"); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("log stat err=%v; want no published file", err)
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatal(err)
	}
	if err := store.DeleteStaging(path); err != nil {
		t.Fatal(err)
	}
}

func TestRepeatedAcquireLeasesShareWorkspaceCount(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	file, path, err := store.CreateStagingFile("shared")
	if err != nil {
		t.Fatal(err)
	}
	_ = file.Close()
	path, err = store.Publish(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(filepath.Dir(path), "created-at"), []byte("1"), 0600); err != nil {
		t.Fatal(err)
	}
	releaseFirst := store.Acquire(path)
	releaseSecond := store.Acquire(path)
	releaseFirst()
	if removed, err := store.Sweep(time.Second); err != nil || removed != 0 {
		t.Fatalf("removed=%d err=%v", removed, err)
	}
	releaseSecond()
	if removed, err := store.Sweep(time.Second); err != nil || removed != 1 {
		t.Fatalf("removed=%d err=%v", removed, err)
	}
}

func TestAcquireLeaseNoOpsForUnownedPath(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	release := store.Acquire(filepath.Join(t.TempDir(), "outside.log"))
	release()
	if len(store.leases) != 0 {
		t.Fatalf("leases=%v", store.leases)
	}
}

func TestSweepRejectsInvalidTTLAndSkipsMalformedWorkspace(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Sweep(0); err == nil {
		t.Fatal("expected invalid TTL error")
	}
	if err := os.Mkdir(filepath.Join(store.Root(), "not-a-uuid"), 0700); err != nil {
		t.Fatal(err)
	}
	removed, err := store.Sweep(time.Second)
	if err != nil || removed != 0 {
		t.Fatalf("removed=%d err=%v", removed, err)
	}
}

func TestUTF8PreviewBoundaries(t *testing.T) {
	for _, tc := range []struct {
		value string
		cut   int
		full  int
	}{
		{value: "a¢z", cut: 2, full: 3},
		{value: "a€z", cut: 3, full: 4},
		{value: "a𐍈z", cut: 4, full: 5},
	} {
		data := []byte(tc.value)
		if end := alignUTF8(data, tc.cut); end != 1 {
			t.Fatalf("incomplete value=%q aligned=%d want=1", tc.value, end)
		}
		if end := alignUTF8(data, tc.full); end != tc.full || !utf8.Valid(data[:end]) {
			t.Fatalf("complete value=%q aligned=%d want=%d", tc.value, end, tc.full)
		}
	}
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	spool, err := NewOutputSpool(store, "preview", 10)
	if err != nil {
		t.Fatal(err)
	}
	spool.Write([]byte(strings.Repeat("a", 100)))
	spool.Write([]byte{0x80, 0x80})
	spool.Write([]byte(strings.Repeat("b", previewTailBytes-2)))
	tail := spool.CaptureTruncatedTailPreview()
	if tail != strings.Repeat("b", previewTailBytes-2) || strings.ContainsRune(tail, '\ufffd') {
		t.Fatalf("truncated tail preview=%q", tail)
	}
	if err := spool.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestAcquireLeaseReleaseIsIdempotent(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	file, path, err := store.CreateStagingFile("lease")
	if err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	path, err = store.Publish(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(filepath.Dir(path), "created-at"), []byte("1"), 0o600); err != nil {
		t.Fatal(err)
	}
	first, second := store.Acquire(path), store.Acquire(path)
	first()
	first()
	if removed, err := store.Sweep(time.Second); err != nil || removed != 0 {
		t.Fatalf("workspace removed after duplicate release: removed=%d err=%v", removed, err)
	}
	second()
	if removed, err := store.Sweep(time.Second); err != nil || removed != 1 {
		t.Fatalf("workspace not removed after all leases released: removed=%d err=%v", removed, err)
	}
}

// Deleting staging twice is safe and removes its temporary workspace.
func TestDeleteStagingIsIdempotent(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	file, path, err := store.CreateStagingFile("twice")
	if err != nil {
		t.Fatal(err)
	}
	_ = file.Close()
	if err := store.DeleteStaging(path); err != nil {
		t.Fatal(err)
	}
	if err := store.DeleteStaging(path); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Dir(path)); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("workspace stat error=%v", err)
	}
}

func TestOutputSpoolCloseRemovesUnpublishedSpill(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	spool, err := NewOutputSpool(store, "abandoned", defaultCaptureBudget)
	if err != nil {
		t.Fatal(err)
	}
	spool.Write(bytes.Repeat([]byte("x"), inlineOutputBytes+1))
	staging := spool.staging
	if staging == "" {
		t.Fatal("expected staging path")
	}
	if err := spool.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(staging); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("staging still exists: %v", err)
	}
	if err := spool.Close(); err != nil {
		t.Fatalf("second close: %v", err)
	}
}

// A workspace root that resolves to a non-directory must be rejected.
func TestOpenOutputStoreRejectsNonDirectoryRoot(t *testing.T) {
	dataDir := t.TempDir()
	tmp := filepath.Join(dataDir, "tmp")
	if err := os.Mkdir(tmp, 0700); err != nil {
		t.Fatal(err)
	}
	root := filepath.Join(tmp, "workspaces")
	if err := os.WriteFile(root, []byte("not a directory"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := OpenTextOutputStore(dataDir); err == nil {
		t.Fatal("expected invalid workspace root error")
	}
}

func TestOutputStoreDataDirCacheAndMissingRoots(t *testing.T) {
	if _, err := TextOutputStoreForDataDir(""); err == nil {
		t.Fatal("empty data directory accepted")
	}
	dataDir := t.TempDir()
	first, err := TextOutputStoreForDataDir(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	second, err := TextOutputStoreForDataDir(dataDir)
	if err != nil || first != second {
		t.Fatalf("cached store=%p second=%p err=%v", first, second, err)
	}
	if err := os.RemoveAll(first.Root()); err != nil {
		t.Fatal(err)
	}
	if removed, err := first.Sweep(time.Minute); err != nil || removed != 0 {
		t.Fatalf("missing-root sweep removed=%d err=%v", removed, err)
	}
}

func TestOutputStoreAcquireRejectsUnregisteredWorkspace(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	invalid := filepath.Join(store.Root(), "not-a-uuid", "output.log")
	store.Acquire(invalid)()
	workspace := filepath.Join(store.Root(), "11111111-1111-4111-8111-111111111111")
	if err := os.Mkdir(workspace, 0700); err != nil {
		t.Fatal(err)
	}
	store.Acquire(filepath.Join(workspace, "output.log"))()
	if len(store.leases) != 0 {
		t.Fatalf("unregistered workspace acquired leases: %v", store.leases)
	}
}

func TestOutputSpoolKeepsShortOverlappingPreviewAndIgnoresWritesAfterTruncation(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	spool, err := NewOutputSpool(store, "short-lines", defaultCaptureBudget)
	if err != nil {
		t.Fatal(err)
	}
	spool.Write([]byte(strings.Repeat("x\n", inlineOutputLines+1)))
	result := spool.Finish(false, "")
	if result.Error || !strings.Contains(result.Contents[0].Text, "x\n") || strings.Contains(result.Contents[0].Text, "bytes omitted") {
		t.Fatalf("overlapping preview=%q", result.Contents[0].Text)
	}
	limited, err := NewOutputSpool(store, "truncated", 1)
	if err != nil {
		t.Fatal(err)
	}
	limited.Write(bytes.Repeat([]byte("a"), inlineOutputBytes+1))
	captured := limited.captured
	limited.Write([]byte("later"))
	if limited.captured != captured || !limited.IsCaptureTruncated() || limited.total != int64(inlineOutputBytes+6) {
		t.Fatalf("capture=%d total=%d truncated=%v", limited.captured, limited.total, limited.IsCaptureTruncated())
	}
	_ = limited.Close()
}

func TestOutputSpoolFinishCleansUpAfterClosedStagingFile(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	spool, err := NewOutputSpool(store, "closed-file", defaultCaptureBudget)
	if err != nil {
		t.Fatal(err)
	}
	spool.Write(bytes.Repeat([]byte("x"), inlineOutputBytes+1))
	staging := spool.staging
	if err := spool.file.Close(); err != nil {
		t.Fatal(err)
	}
	result := spool.Finish(false, "")
	if result.Error || !strings.Contains(result.Contents[0].Text, "could not be saved to local storage") {
		t.Fatalf("result=%#v", result)
	}
	if _, err := os.Stat(staging); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("staging file was not removed: %v", err)
	}
}

func TestOpenTextOutputStoreRejectsInvalidParent(t *testing.T) {
	dataDir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dataDir, "tmp"), []byte("file"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := OpenTextOutputStore(dataDir); err == nil {
		t.Fatal("non-directory parent accepted")
	}
}

func TestOutputSpoolReportsInvalidConfigurationAndStorageFailure(t *testing.T) {
	if _, err := NewOutputSpool(nil, "invalid", 1); err == nil {
		t.Fatal("nil output store accepted")
	}
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := NewOutputSpool(store, "invalid", 0); err == nil {
		t.Fatal("nonpositive capture budget accepted")
	}
	rootFile := filepath.Join(t.TempDir(), "not-a-directory")
	if err := os.WriteFile(rootFile, []byte("file"), 0600); err != nil {
		t.Fatal(err)
	}
	store.root = rootFile
	spool, err := NewOutputSpool(store, "failed-call", 1024)
	if err != nil {
		t.Fatal(err)
	}
	spool.Write(bytes.Repeat([]byte("x"), inlineOutputBytes+1))
	result := spool.Finish(false, "")
	if result.Error || !strings.Contains(result.Contents[0].Text, "could not be saved to local storage") {
		t.Fatalf("storage failure result=%#v", result)
	}
	if details := string(result.Details); !strings.Contains(details, `"captureFailed":true`) {
		t.Fatalf("capture details=%s", details)
	}
}

// A staging-file failure removes the workspace and created-at registration.
func TestCreateStagingFileFailureRollsBackWorkspace(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	injected := errors.New("injected staging open failure")
	var workspace string
	store.openStagingFile = func(path string) (*os.File, error) {
		workspace = filepath.Dir(path)
		if _, err := os.Stat(filepath.Join(workspace, "created-at")); err != nil {
			t.Errorf("created-at registration was not written before staging: %v", err)
		}
		return nil, injected
	}
	if _, _, err := store.CreateStagingFile("rollback"); !errors.Is(err, injected) {
		t.Fatalf("CreateStagingFile error=%v, want injected failure", err)
	}
	if workspace == "" {
		t.Fatal("staging failure was not injected")
	}
	if _, err := os.Stat(workspace); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("workspace survived rollback: %v", err)
	}
	entries, err := os.ReadDir(store.Root())
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 0 || len(store.leases) != 0 || len(store.staging) != 0 {
		t.Fatalf("rollback left store state: entries=%v leases=%v staging=%v", entries, store.leases, store.staging)
	}
}

func TestOutputStoreRejectsInvalidPublishPathAndSanitizesName(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Publish(filepath.Join(store.Root(), "not-staging")); err == nil {
		t.Fatal("non-staging path published")
	}
	if got := sanitizeOutputName("!!!"); got != "___" {
		t.Fatalf("sanitized name=%q", got)
	}
	if got := sanitizeOutputName("\\u2603"); got != "_u2603" {
		t.Fatalf("unicode name=%q", got)
	}
}

func TestWriteAllHandlesShortWritesAndNoProgress(t *testing.T) {
	writer := &shortWriter{max: 2}
	if err := writeAll(writer, []byte("abcdef")); err != nil || string(writer.bytes) != "abcdef" {
		t.Fatalf("bytes=%q err=%v", writer.bytes, err)
	}
	if err := writeAll(zeroWriter{}, []byte("x")); err == nil {
		t.Fatal("expected no-progress error")
	}
}

type shortWriter struct {
	max   int
	bytes []byte
}

func (w *shortWriter) Write(data []byte) (int, error) {
	n := min(w.max, len(data))
	w.bytes = append(w.bytes, data[:n]...)
	return n, nil
}

type zeroWriter struct{}

func (zeroWriter) Write([]byte) (int, error) { return 0, nil }
