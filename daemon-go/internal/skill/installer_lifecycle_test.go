package skill

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// TestSkillInstallHonorsTicketSizeWithoutAnIndependentDownloadLimit verifies ticket metadata controls size validation.
func TestSkillInstallHonorsTicketSizeWithoutAnIndependentDownloadLimit(t *testing.T) {
	archive := zipBytes(t, map[string]string{"demo/SKILL.md": "skill"})
	digest := sha256.Sum256(archive)
	d := &fakeDownloader{
		data: archive,
		ticket: DownloadTicket{
			Size:   maxExpandedBytes + 1,
			SHA256: hex.EncodeToString(digest[:]),
			Opaque: "ticket-token",
		},
	}
	installer := Installer{DataDir: t.TempDir(), Downloader: d}
	_, _, err := installer.Install(context.Background(), installInput())
	if err == nil || !strings.Contains(err.Error(), "size does not match its ticket") {
		t.Fatalf("Install error=%v, want ticket-size mismatch after download", err)
	}
	if d.requests != 1 || d.downloads != 1 {
		t.Fatalf("requests/downloads=%d/%d, want 1/1", d.requests, d.downloads)
	}
}

// TestSkillInstallEnforcesManifestSizeAndNoRetryKeepsRequiredFailuresBounded.
func TestSkillInstallEnforcesManifestSizeAndNoRetryKeepsRequiredFailuresBounded(t *testing.T) {
	archive := zipBytes(t, map[string]string{"demo/SKILL.md": strings.Repeat("x", maxSkillManifestBytes+1)})
	d := &fakeDownloader{data: archive}
	root := t.TempDir()
	installer := Installer{DataDir: root, Downloader: d}
	if _, _, err := installer.Install(context.Background(), installInput()); err == nil {
		t.Fatal("Install accepted a SKILL.md larger than 64 KiB")
	}
	if d.requests != 1 || d.downloads != 1 {
		t.Fatalf("requests/downloads=%d/%d, want one ticket and one download", d.requests, d.downloads)
	}
	if _, err := os.Stat(expectedTarget(root, archive)); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("installation target exists after manifest rejection: %v", err)
	}
}

// RUN-N-002 cleanup tests verify startup removes abandoned staging but preserves immutable installations.
func TestInstallerCleanupStagingRemovesOnlyInterruptedInstallDirectories(t *testing.T) {
	root := t.TempDir()
	skills := filepath.Join(root, "skills")
	abandoned := filepath.Join(skills, "pkg", "1.2", ".staging-interrupted")
	if err := os.MkdirAll(abandoned, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(abandoned, "partial"), []byte("incomplete"), 0600); err != nil {
		t.Fatal(err)
	}
	published := filepath.Join(skills, "pkg", "1.2", strings.Repeat("a", 12), "demo", "SKILL.md")
	if err := os.MkdirAll(filepath.Dir(published), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(published, []byte("complete"), 0600); err != nil {
		t.Fatal(err)
	}
	stagingFile := filepath.Join(skills, "pkg", "1.2", ".staging-not-a-directory")
	if err := os.WriteFile(stagingFile, []byte("keep"), 0600); err != nil {
		t.Fatal(err)
	}
	installer := Installer{DataDir: root}
	if err := installer.CleanupStaging(context.Background()); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(abandoned); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("abandoned stage still exists: %v", err)
	}
	for _, path := range []string{published, stagingFile} {
		if _, err := os.Stat(path); err != nil {
			t.Fatalf("cleanup removed retained path %q: %v", path, err)
		}
	}
}

// TestInstallerCleanupStagingHonorsContextAndMissingRoot checks cancellation and the no-skills first-start case.
func TestInstallerCleanupStagingHonorsContextAndMissingRoot(t *testing.T) {
	installer := Installer{DataDir: t.TempDir()}
	if err := installer.CleanupStaging(context.Background()); err != nil {
		t.Fatalf("cleanup of missing skills directory failed: %v", err)
	}
	stage := filepath.Join(installer.DataDir, "skills", "pkg", "v1", ".staging-crash")
	if err := os.MkdirAll(stage, 0700); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := installer.CleanupStaging(ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("cleanup error=%v, want context cancellation", err)
	}
	if _, err := os.Stat(stage); err != nil {
		t.Fatalf("cancelled cleanup removed stage: %v", err)
	}
	if err := os.RemoveAll(filepath.Join(installer.DataDir, "skills")); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(installer.DataDir, "skills"), []byte("not a directory"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := installer.CleanupStaging(context.Background()); err == nil {
		t.Fatal("cleanup accepted a non-directory skills path")
	}
}

// TestExtractZipEntryLimitAndDirectoryEntries verifies bounded extraction while allowing explicit directory records.
func TestExtractZipEntryLimitAndDirectoryEntries(t *testing.T) {
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	for n := 0; n <= maxArchiveFiles; n++ {
		if _, err := writer.Create(fmt.Sprintf("files/%05d", n)); err != nil {
			t.Fatal(err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := extractZip(buffer.Bytes(), t.TempDir()); err == nil || !strings.Contains(err.Error(), "too many entries") {
		t.Fatalf("extractZip error=%v, want entry-count rejection", err)
	}

	var directory bytes.Buffer
	writer = zip.NewWriter(&directory)
	if _, err := writer.Create("demo/"); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	if err := extractZip(directory.Bytes(), root); err != nil {
		t.Fatalf("extractZip rejected a directory entry: %v", err)
	}
	if info, err := os.Stat(filepath.Join(root, "demo")); err != nil || !info.IsDir() {
		t.Fatalf("directory entry not extracted: info=%v err=%v", info, err)
	}
}

// TestExtractZipRejectsUnsupportedFileTypes rejects ZIP entries that are neither regular files nor directories.
func TestExtractZipRejectsUnsupportedFileTypes(t *testing.T) {
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	header := &zip.FileHeader{Name: "demo/pipe", Method: zip.Store}
	header.SetMode(os.ModeNamedPipe | 0600)
	if _, err := writer.CreateHeader(header); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := extractZip(buffer.Bytes(), t.TempDir()); err == nil || !strings.Contains(err.Error(), "unsupported entry") {
		t.Fatalf("extractZip error=%v, want unsupported-type rejection", err)
	}
}

// TestExtractZipRejectsCorruptPayloadAndFilesystemConflicts reports unreadable ZIP entries without leaving partial files.
func TestExtractZipRejectsCorruptPayloadAndFilesystemConflicts(t *testing.T) {
	for _, tc := range []struct {
		name   string
		method uint16
		size   uint64
		root   func(string) error
	}{
		{name: "unsupported compression", method: 99, size: 1},
		{name: "truncated stored payload", method: zip.Store, size: 2},
		{name: "parent path is a file", method: zip.Store, size: 1, root: func(root string) error {
			return os.WriteFile(root, []byte("file"), 0600)
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var buffer bytes.Buffer
			writer := zip.NewWriter(&buffer)
			_, err := writer.CreateRaw(&zip.FileHeader{
				Name:               "demo/file.txt",
				Method:             tc.method,
				CompressedSize64:   1,
				UncompressedSize64: tc.size,
			})
			if err != nil {
				t.Fatal(err)
			}
			if err := writer.Close(); err != nil {
				t.Fatal(err)
			}
			root := filepath.Join(t.TempDir(), "destination")
			if tc.root != nil {
				if err := tc.root(root); err != nil {
					t.Fatal(err)
				}
			} else if err := os.MkdirAll(root, 0700); err != nil {
				t.Fatal(err)
			}
			if err := extractZip(buffer.Bytes(), root); err == nil {
				t.Fatal("extractZip accepted an unreadable entry or invalid destination")
			}
		})
	}

	var duplicate bytes.Buffer
	writer := zip.NewWriter(&duplicate)
	for n := 0; n < 2; n++ {
		entry, err := writer.Create("demo/repeated.txt")
		if err != nil {
			t.Fatal(err)
		}
		if _, err := entry.Write([]byte("content")); err != nil {
			t.Fatal(err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := extractZip(duplicate.Bytes(), t.TempDir()); err == nil {
		t.Fatal("extractZip overwrote an earlier ZIP entry")
	}
}

type serialDownloader struct {
	data           []byte
	ticket         DownloadTicket
	mu             sync.Mutex
	requests       int
	downloads      int
	secondRequest  chan struct{}
	firstDownload  chan struct{}
	secondDownload chan struct{}
	releaseFirst   chan struct{}
	releaseOnce    sync.Once
}

func (d *serialDownloader) RequestTicket(context.Context, string) (DownloadTicket, error) {
	d.mu.Lock()
	d.requests++
	if d.requests == 2 {
		close(d.secondRequest)
	}
	d.mu.Unlock()
	return d.ticket, nil
}

func (d *serialDownloader) Download(ctx context.Context, ticket DownloadTicket) ([]byte, error) {
	d.mu.Lock()
	d.downloads++
	n := d.downloads
	d.mu.Unlock()
	if ticket.Opaque != d.ticket.Opaque {
		return nil, errors.New("ticket changed")
	}
	if n == 1 {
		close(d.firstDownload)
		select {
		case <-d.releaseFirst:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	} else {
		close(d.secondDownload)
	}
	return d.data, nil
}

// RUN-T-238 checks that overlapping installs share the package-version critical section.
func TestSkillInstallSerializesSamePackage(t *testing.T) {
	archive := zipBytes(t, map[string]string{"demo/SKILL.md": "skill"})
	digest := sha256.Sum256(archive)
	d := &serialDownloader{
		data:           archive,
		ticket:         DownloadTicket{Size: int64(len(archive)), SHA256: hex.EncodeToString(digest[:]), Opaque: "ticket"},
		secondRequest:  make(chan struct{}),
		firstDownload:  make(chan struct{}),
		secondDownload: make(chan struct{}),
		releaseFirst:   make(chan struct{}),
	}
	installer := &Installer{DataDir: t.TempDir(), Downloader: d}
	defer d.releaseOnce.Do(func() { close(d.releaseFirst) })
	results := make(chan error, 2)
	go func() {
		_, _, err := installer.Install(context.Background(), installInput())
		results <- err
	}()
	select {
	case <-d.firstDownload:
	case <-time.After(5 * time.Second):
		t.Fatal("first download did not start")
	}
	go func() {
		_, _, err := installer.Install(context.Background(), installInput())
		results <- err
	}()
	select {
	case <-d.secondRequest:
	case <-time.After(5 * time.Second):
		t.Fatal("second ticket request did not start")
	}
	select {
	case <-d.secondDownload:
		t.Fatal("second package download overlapped the first")
	case <-time.After(50 * time.Millisecond):
	}
	d.releaseOnce.Do(func() { close(d.releaseFirst) })
	for n := 0; n < 2; n++ {
		select {
		case err := <-results:
			if err != nil {
				t.Fatalf("Install failed: %v", err)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("concurrent install did not finish")
		}
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.requests != 2 || d.downloads != 1 {
		t.Fatalf("requests/downloads=%d/%d, want 2/1", d.requests, d.downloads)
	}
}
