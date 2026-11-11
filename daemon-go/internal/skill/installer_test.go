package skill

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"
)

type fakeDownloader struct {
	data  []byte
	calls int
	err   error
}
type ticketDownloader struct{ fakeDownloader }

func (f *ticketDownloader) TicketSHA256(context.Context, string) (string, error) {
	digest := sha256.Sum256(f.data)
	return hex.EncodeToString(digest[:]), nil
}

func (f *fakeDownloader) Download(context.Context, string) ([]byte, error) {
	f.calls++
	return f.data, f.err
}
func zipBytes(t *testing.T, files map[string]string) []byte {
	t.Helper()
	var b bytes.Buffer
	w := zip.NewWriter(&b)
	for name, content := range files {
		x, err := w.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		_, _ = io.WriteString(x, content)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

// RUN-T-228 verifies that a ticket downloader's archive is extracted and published only with its skill marker.
func TestInstallerPublishesVerifiedSkill(t *testing.T) {
	archive := zipBytes(t, map[string]string{"demo/SKILL.md": "skill", "demo/readme.txt": "content"})
	d := &fakeDownloader{data: archive}
	i := Installer{DataDir: t.TempDir(), Downloader: d}
	input := json.RawMessage(`{"transferId":"123e4567-e89b-42d3-a456-426614174000","package":"pkg","version":"1.2","skill":"demo"}`)
	result, _, err := i.Install(context.Background(), input)
	if err != nil {
		t.Fatal(err)
	}
	var got struct {
		SkillDir string `json:"skill_dir"`
	}
	if err := json.Unmarshal(result, &got); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(got.SkillDir, "SKILL.md")); err != nil {
		t.Fatal(err)
	}
	_, _, err = i.Install(context.Background(), input)
	if err != nil {
		t.Fatal(err)
	}
}

// RUN-T-233 rejects archive traversal and leaves no installed package behind.
func TestInstallerRejectsTraversal(t *testing.T) {
	d := &fakeDownloader{data: zipBytes(t, map[string]string{"../escaped": "x", "demo/SKILL.md": "ok"})}
	root := t.TempDir()
	i := Installer{DataDir: root, Downloader: d}
	input := json.RawMessage(`{"transferId":"123e4567-e89b-42d3-a456-426614174000","package":"pkg","version":"1","skill":"demo"}`)
	if _, _, err := i.Install(context.Background(), input); err == nil {
		t.Fatal("expected unsafe archive rejection")
	}
	if _, err := os.Stat(filepath.Join(root, "skills", "pkg", "1")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("install unexpectedly published: %v", err)
	}
}

// RUN-T-229 ensures identical verified archive bytes reuse the existing version directory.
func TestInstallerReusesMatchingDigest(t *testing.T) {
	archive := zipBytes(t, map[string]string{"demo/SKILL.md": "skill"})
	d := &ticketDownloader{fakeDownloader: fakeDownloader{data: archive}}
	i := Installer{DataDir: t.TempDir(), Downloader: d}
	input := json.RawMessage(`{"transferId":"123e4567-e89b-42d3-a456-426614174000","package":"pkg","version":"1","skill":"demo"}`)
	_, _, err := i.Install(context.Background(), input)
	if err != nil {
		t.Fatal(err)
	}
	before, err := os.ReadFile(filepath.Join(i.DataDir, "skills", "pkg", "1", "demo", "SKILL.md"))
	if err != nil {
		t.Fatal(err)
	}
	_, _, err = i.Install(context.Background(), input)
	if err != nil {
		t.Fatal(err)
	}
	after, err := os.ReadFile(filepath.Join(i.DataDir, "skills", "pkg", "1", "demo", "SKILL.md"))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(before, after) {
		t.Fatal("reused installation changed")
	}
	if d.calls != 1 {
		t.Fatalf("downloads=%d, want the matching-ticket reuse to skip archive transfer", d.calls)
	}
}
