package coding

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// COD-T-111: large output previews omit the middle while retaining both snapshots.
func TestOutputPreviewKeepsHeadAndTailAroundOmittedMiddle(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	spool, err := NewOutputSpool(store, "preview", defaultCaptureBudget)
	if err != nil {
		t.Fatal(err)
	}
	middle := strings.Repeat("M", inlineOutputBytes+100)
	spool.Write([]byte(strings.Repeat("H", previewHeadBytes) + middle + strings.Repeat("T", previewTailBytes)))
	result := spool.Finish(false, "")
	text := result.Contents[0].Text
	if !strings.HasPrefix(text, strings.Repeat("H", previewHeadBytes)) || !strings.Contains(text, "bytes omitted here") || !strings.Contains(text, strings.Repeat("T", previewTailBytes)) || strings.Contains(text, middle) {
		t.Fatalf("large preview lost head/tail or retained middle: len=%d", len(text))
	}
	if err := spool.Close(); err != nil {
		t.Fatal(err)
	}
}

// COD-T-112: staging names derived from untrusted call IDs stay inside the store root.
func TestCreateStagingFileSanitizesUntrustedCallID(t *testing.T) {
	store, err := OpenTextOutputStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	file, staging, err := store.CreateStagingFile("../../evil/name")
	if err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	relative, err := filepath.Rel(store.Root(), staging)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(os.PathSeparator)) || !strings.HasSuffix(staging, ".part") || !strings.Contains(filepath.Base(staging), "evil_name") {
		t.Fatalf("staging path=%q root=%q relative=%q err=%v", staging, store.Root(), relative, err)
	}
	if err := store.DeleteStaging(staging); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(staging); !os.IsNotExist(err) {
		t.Fatalf("staging file was not removed: %v", err)
	}
}
