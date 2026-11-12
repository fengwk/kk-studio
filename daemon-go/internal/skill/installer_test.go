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
	data           []byte
	ticket         DownloadTicket
	requests       int
	downloads      int
	requestErr     error
	downloadErr    error
	beforeDownload func(DownloadTicket)
}

func (f *fakeDownloader) RequestTicket(context.Context, string) (DownloadTicket, error) {
	f.requests++
	if f.requestErr != nil {
		return DownloadTicket{}, f.requestErr
	}
	if f.ticket.Size == 0 {
		digest := sha256.Sum256(f.data)
		f.ticket = DownloadTicket{Size: int64(len(f.data)), SHA256: hex.EncodeToString(digest[:]), Opaque: "ticket-token"}
	}
	return f.ticket, nil
}
func (f *fakeDownloader) Download(_ context.Context, ticket DownloadTicket) ([]byte, error) {
	f.downloads++
	if ticket.Opaque != f.ticket.Opaque {
		return nil, errors.New("ticket was not passed unchanged")
	}
	if f.beforeDownload != nil {
		f.beforeDownload(ticket)
	}
	return f.data, f.downloadErr
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
		if _, err := io.WriteString(x, content); err != nil {
			t.Fatal(err)
		}
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}
func installInput() json.RawMessage {
	return json.RawMessage(`{"transferId":"123e4567-e89b-42d3-a456-426614174000","package":"pkg","version":"1.2","skill":"demo"}`)
}
func expectedTarget(root string, archive []byte) string {
	digest := sha256.Sum256(archive)
	return filepath.Join(root, "skills", "pkg", "1.2", hex.EncodeToString(digest[:])[:12])
}

// RUN-N-002 and RUN-T-228 verify ticket-bound extraction and publication under the content-addressed path.
func TestSkillInstall_TicketHashAndSafeVersionedPublish(t *testing.T) {
	archive := zipBytes(t, map[string]string{"demo/SKILL.md": "skill", "demo/readme.txt": "content"})
	d := &fakeDownloader{data: archive}
	root := t.TempDir()
	i := Installer{DataDir: root, Downloader: d}
	result, _, err := i.Install(context.Background(), installInput())
	if err != nil {
		t.Fatal(err)
	}
	var got struct {
		SkillDir string `json:"skill_dir"`
	}
	if err := json.Unmarshal(result, &got); err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(expectedTarget(root, archive), "demo")
	if got.SkillDir != want {
		t.Fatalf("skill_dir=%q, want %q", got.SkillDir, want)
	}
	wantResult, err := json.Marshal(struct {
		SkillDir string `json:"skill_dir"`
	}{want})
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(result, wantResult) {
		t.Fatalf("success result = %s, want %s", result, wantResult)
	}
	if _, err := os.Stat(filepath.Join(got.SkillDir, "SKILL.md")); err != nil {
		t.Fatal(err)
	}
	if d.requests != 1 || d.downloads != 1 {
		t.Fatalf("requests/downloads=%d/%d", d.requests, d.downloads)
	}
}

// RUN-T-229 checks that an existing digest target requests metadata but skips archive transfer.
func TestInstallerReusesExistingContentAddress(t *testing.T) {
	archive := zipBytes(t, map[string]string{"demo/SKILL.md": "skill"})
	d := &fakeDownloader{data: archive}
	i := Installer{DataDir: t.TempDir(), Downloader: d}
	for n := 0; n < 2; n++ {
		if _, _, err := i.Install(context.Background(), installInput()); err != nil {
			t.Fatal(err)
		}
	}
	if d.requests != 2 || d.downloads != 1 {
		t.Fatalf("requests/downloads=%d/%d, want 2/1", d.requests, d.downloads)
	}
}

// RUN-T-233 rejects archive traversal and leaves the content-addressed target unpublished.
func TestInstallerRejectsTraversal(t *testing.T) {
	archive := zipBytes(t, map[string]string{"../escaped": "x", "demo/SKILL.md": "ok"})
	d := &fakeDownloader{data: archive}
	root := t.TempDir()
	i := Installer{DataDir: root, Downloader: d}
	if _, _, err := i.Install(context.Background(), installInput()); err == nil {
		t.Fatal("expected unsafe archive rejection")
	}
	if _, err := os.Stat(expectedTarget(root, archive)); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("target unexpectedly published: %v", err)
	}
}

// RUN-T-231 rejects bytes that do not match the ticket's SHA-256 before publishing anything.
func TestInstallerRejectsTicketDigestMismatch(t *testing.T) {
	archive := zipBytes(t, map[string]string{"demo/SKILL.md": "skill"})
	d := &fakeDownloader{data: archive, ticket: DownloadTicket{Size: int64(len(archive)), SHA256: "0000000000000000000000000000000000000000000000000000000000000000", Opaque: "ticket-token"}}
	root := t.TempDir()
	i := Installer{DataDir: root, Downloader: d}
	if _, _, err := i.Install(context.Background(), installInput()); err == nil {
		t.Fatal("expected ticket digest mismatch")
	}
	if _, err := os.Stat(expectedTarget(root, archive)); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("target unexpectedly published: %v", err)
	}
}

// RUN-N-002 verifies installation failures are returned as errors rather than successful text-only results.
func TestSkillInstall_FailureIsReturned(t *testing.T) {
	archive := zipBytes(t, map[string]string{"demo/SKILL.md": "skill"})
	digest := sha256.Sum256(archive)
	cases := []struct {
		name string
		d    *fakeDownloader
	}{
		{name: "ticket request", d: &fakeDownloader{data: archive, requestErr: errors.New("offline")}},
		{name: "download", d: &fakeDownloader{data: archive, downloadErr: errors.New("offline")}},
		{name: "ticket size mismatch", d: &fakeDownloader{data: archive, ticket: DownloadTicket{Size: int64(len(archive)) + 1, SHA256: hex.EncodeToString(digest[:]), Opaque: "ticket-token"}}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			result, message, err := (&Installer{DataDir: t.TempDir(), Downloader: tc.d}).Install(context.Background(), installInput())
			if err == nil || result != nil || message != "" {
				t.Fatalf("Install result=%q message=%q err=%v; want error-only failure", result, message, err)
			}
			if tc.d.requests != 1 || tc.d.downloads > 1 {
				t.Fatalf("requests/downloads=%d/%d, want one ticket request and at most one download", tc.d.requests, tc.d.downloads)
			}
		})
	}
}

// RUN-T-236 reuses a concurrently published valid target when the staging rename loses the race.
func TestInstallerReusesTargetCreatedDuringDownload(t *testing.T) {
	archive := zipBytes(t, map[string]string{"demo/SKILL.md": "ticket contents"})
	root := t.TempDir()
	target := expectedTarget(root, archive)
	d := &fakeDownloader{data: archive}
	d.beforeDownload = func(ticket DownloadTicket) {
		dir := filepath.Join(target, "demo")
		if err := os.MkdirAll(dir, 0700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, "SKILL.md"), []byte("racer"), 0600); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(target, installDigestFile), []byte(ticket.SHA256), 0600); err != nil {
			t.Fatal(err)
		}
	}
	i := Installer{DataDir: root, Downloader: d}
	result, _, err := i.Install(context.Background(), installInput())
	if err != nil {
		t.Fatal(err)
	}
	var got struct {
		SkillDir string `json:"skill_dir"`
	}
	if err := json.Unmarshal(result, &got); err != nil {
		t.Fatal(err)
	}
	body, err := os.ReadFile(filepath.Join(got.SkillDir, "SKILL.md"))
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != "racer" {
		t.Fatalf("race winner was overwritten: %q", body)
	}
}
