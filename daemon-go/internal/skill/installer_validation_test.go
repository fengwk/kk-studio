package skill

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

// TestSkillInstallRejectsInvalidRequestsAndTickets prevents malformed identities or untrusted ticket metadata from reaching download.
func TestSkillInstallRejectsInvalidRequestsAndTickets(t *testing.T) {
	archive := zipBytes(t, map[string]string{"demo/SKILL.md": "skill"})
	validDigest := sha256.Sum256(archive)
	validTicket := DownloadTicket{Size: int64(len(archive)), SHA256: hex.EncodeToString(validDigest[:]), Opaque: "opaque"}
	cases := []struct {
		name     string
		input    []byte
		ticket   DownloadTicket
		requests int
	}{
		{name: "malformed JSON", input: []byte("{"), ticket: validTicket},
		{name: "unknown field", input: []byte(`{"transferId":"123e4567-e89b-42d3-a456-426614174000","package":"pkg","version":"1","skill":"demo","extra":true}`), ticket: validTicket},
		{name: "invalid transfer ID", input: []byte(`{"transferId":"bad","package":"pkg","version":"1","skill":"demo"}`), ticket: validTicket},
		{name: "unsafe package segment", input: []byte(`{"transferId":"123e4567-e89b-42d3-a456-426614174000","package":"../pkg","version":"1","skill":"demo"}`), ticket: validTicket},
		{name: "negative ticket size", input: installInput(), ticket: DownloadTicket{Size: -1, SHA256: validTicket.SHA256}, requests: 1},
		{name: "uppercase digest", input: installInput(), ticket: DownloadTicket{Size: validTicket.Size, SHA256: strings.ToUpper(validTicket.SHA256)}, requests: 1},
		{name: "invalid digest", input: installInput(), ticket: DownloadTicket{Size: validTicket.Size, SHA256: strings.Repeat("z", 64)}, requests: 1},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			d := &fakeDownloader{data: archive, ticket: tc.ticket}
			i := Installer{DataDir: t.TempDir(), Downloader: d}
			_, _, err := i.Install(context.Background(), tc.input)
			if err == nil {
				t.Fatal("Install succeeded for invalid request or ticket")
			}
			if d.requests != tc.requests || d.downloads != 0 {
				t.Fatalf("requests/downloads=%d/%d, want %d/0", d.requests, d.downloads, tc.requests)
			}
		})
	}
}

// TestSkillInstallPropagatesTicketDownloadAndArchiveFailures confirms every pre-publication failure is returned without publishing a target.
func TestSkillInstallPropagatesTicketDownloadAndArchiveFailures(t *testing.T) {
	validArchive := zipBytes(t, map[string]string{"demo/SKILL.md": "skill"})
	validDigest := sha256.Sum256(validArchive)
	validTicket := DownloadTicket{Size: int64(len(validArchive)), SHA256: hex.EncodeToString(validDigest[:]), Opaque: "opaque"}
	cases := []struct {
		name   string
		data   []byte
		ticket DownloadTicket
		reqErr error
		dlErr  error
	}{
		{name: "ticket request", data: validArchive, reqErr: errors.New("offline")},
		{name: "download", data: validArchive, ticket: validTicket, dlErr: errors.New("offline")},
		{name: "size mismatch", data: validArchive[:len(validArchive)-1], ticket: validTicket},
		{name: "invalid zip", data: []byte("not a zip"), ticket: DownloadTicket{Size: 9, SHA256: validTicket.SHA256}},
		{name: "missing skill marker", data: zipBytes(t, map[string]string{"other/SKILL.md": "skill"})},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			d := &fakeDownloader{data: tc.data, ticket: tc.ticket, requestErr: tc.reqErr, downloadErr: tc.dlErr}
			if tc.ticket.Size == 0 && tc.ticket.SHA256 == "" && tc.reqErr == nil && tc.dlErr == nil && tc.name == "missing skill marker" {
				d.ticket = DownloadTicket{}
			}
			if tc.name == "size mismatch" {
				d.data = validArchive[:len(validArchive)-1]
				d.ticket = validTicket
			}
			if tc.name == "invalid zip" {
				d.ticket.SHA256 = validTicket.SHA256
			}
			root := t.TempDir()
			i := Installer{DataDir: root, Downloader: d}
			if _, _, err := i.Install(context.Background(), installInput()); err == nil {
				t.Fatal("Install succeeded")
			}
			if _, err := os.Stat(expectedTarget(root, tc.data)); !errors.Is(err, os.ErrNotExist) {
				t.Fatalf("installation target exists after failure: %v", err)
			}
			staging, err := filepath.Glob(filepath.Join(root, "skills", "demo", "1.0.0", ".staging-*"))
			if err != nil {
				t.Fatal(err)
			}
			if len(staging) != 0 {
				t.Fatalf("staging temporary directory leaked after failure: %v", staging)
			}
		})
	}
}

// RUN-T-233 and RUN-T-234 reject unsafe archive paths and symlinks without publishing files.
// TestSkillInstallRejectsUnsafeAndSpecialArchiveEntries protects extraction against traversal and non-regular ZIP entries.
func TestSkillInstallRejectsUnsafeAndSpecialArchiveEntries(t *testing.T) {
	for _, name := range []string{"/absolute", `..\\escape`, "C:/drive", "demo//empty", "demo/../escape"} {
		t.Run(name, func(t *testing.T) {
			archive := zipBytes(t, map[string]string{name: "bad", "demo/SKILL.md": "skill"})
			if err := extractZip(archive, t.TempDir()); err == nil {
				t.Fatalf("extractZip accepted unsafe entry %q", name)
			}
		})
	}

	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	header := &zip.FileHeader{Name: "demo/link", Method: zip.Store}
	header.SetMode(os.ModeSymlink | 0777)
	entry, err := writer.CreateHeader(header)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := io.WriteString(entry, "target"); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := extractZip(buffer.Bytes(), t.TempDir()); err == nil {
		t.Fatal("extractZip accepted a symlink entry")
	}
}

// TestSkillInstallRejectsIncompleteExistingTargets prevents corrupt digest paths from being mistaken for installed packages.
func TestSkillInstallRejectsIncompleteExistingTargets(t *testing.T) {
	root := t.TempDir()
	cases := []struct {
		name    string
		prepare func(string) error
	}{
		{name: "not a directory", prepare: func(path string) error { return os.WriteFile(path, []byte("file"), 0600) }},
		{name: "missing skill", prepare: func(path string) error { return os.MkdirAll(path, 0700) }},
		{name: "missing skill marker", prepare: func(path string) error {
			if err := os.MkdirAll(path, 0700); err != nil {
				return err
			}
			return os.WriteFile(filepath.Join(path, installDigestFile), []byte(strings.Repeat("a", 64)), 0600)
		}},
		{name: "missing digest marker", prepare: func(path string) error {
			if err := os.MkdirAll(filepath.Join(path, "demo"), 0700); err != nil {
				return err
			}
			return os.WriteFile(filepath.Join(path, "demo", "SKILL.md"), []byte("skill"), 0600)
		}},
		{name: "digest mismatch", prepare: func(path string) error {
			if err := os.MkdirAll(filepath.Join(path, "demo"), 0700); err != nil {
				return err
			}
			if err := os.WriteFile(filepath.Join(path, "demo", "SKILL.md"), []byte("skill"), 0600); err != nil {
				return err
			}
			return os.WriteFile(filepath.Join(path, installDigestFile), []byte(strings.Repeat("0", 64)), 0600)
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			path := filepath.Join(root, tc.name)
			if err := tc.prepare(path); err != nil {
				t.Fatal(err)
			}
			if err := requireInstalledSkill(path, "demo", strings.Repeat("a", 64)); err == nil {
				t.Fatal("requireInstalledSkill accepted an incomplete or corrupt target")
			}
		})
	}
}

// TestSkillInstallRejectsSymlinkedExistingMarker prevents a local symlink from validating a damaged install.
func TestSkillInstallRejectsSymlinkedExistingMarker(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("creating a Windows symlink requires elevated privileges")
	}
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "demo"), 0700); err != nil {
		t.Fatal(err)
	}
	outside := filepath.Join(t.TempDir(), "SKILL.md")
	if err := os.WriteFile(outside, []byte("skill"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, filepath.Join(root, "demo", "SKILL.md")); err != nil {
		t.Fatal(err)
	}
	if err := requireSkillMarker(root, "demo"); err == nil {
		t.Fatal("accepted a symlinked skill marker")
	}
}

// TestSkillInstallerValidationHelpers checks canonical identity and safe path-component predicates directly.
func TestSkillInstallerValidationHelpers(t *testing.T) {
	for _, tc := range []struct {
		value string
		valid bool
	}{
		{value: "123e4567-e89b-42d3-a456-426614174000", valid: true},
		{value: "123E4567-E89B-42D3-A456-426614174000"},
		{value: "123e4567e89b-42d3-a456-426614174000"},
		{value: "x23e4567-e89b-42d3-a456-426614174000"},
		{value: "123e4567-e89b-42d3-a456-42661417400z"},
	} {
		if got := validUUID(tc.value); got != tc.valid {
			t.Errorf("validUUID(%q)=%v, want %v", tc.value, got, tc.valid)
		}
	}
	for _, tc := range []struct {
		value string
		valid bool
	}{
		{value: "pkg", valid: true},
		{value: "hello world", valid: true},
		{value: ""}, {value: "."}, {value: ".."}, {value: "a/b"}, {value: `a\\b`}, {value: "a:b"}, {value: "line\nfeed"},
	} {
		if got := safeSegment(tc.value); got != tc.valid {
			t.Errorf("safeSegment(%q)=%v, want %v", tc.value, got, tc.valid)
		}
	}
	validDigest := strings.Repeat("a", 64)
	for _, tc := range []struct {
		value string
		valid bool
	}{
		{value: validDigest, valid: true},
		{value: strings.ToUpper(validDigest)},
		{value: validDigest[:63]},
		{value: strings.Repeat("z", 64)},
	} {
		if got := validSHA256(tc.value); got != tc.valid {
			t.Errorf("validSHA256(%q)=%v, want %v", tc.value, got, tc.valid)
		}
	}
}

// TestSkillInstallRejectsInvalidConfigurationAndPublicationPaths checks boundary failures before and after staging begins.
func TestSkillInstallRejectsInvalidConfigurationAndPublicationPaths(t *testing.T) {
	archive := zipBytes(t, map[string]string{"demo/SKILL.md": "skill"})
	d := &fakeDownloader{data: archive}
	if _, _, err := (&Installer{DataDir: t.TempDir()}).Install(context.Background(), installInput()); err == nil {
		t.Fatal("Install succeeded without a downloader")
	}
	if _, _, err := (&Installer{DataDir: "relative", Downloader: d}).Install(context.Background(), installInput()); err == nil {
		t.Fatal("Install accepted a relative data directory")
	}
	root := filepath.Join(t.TempDir(), "not-a-directory")
	if err := os.WriteFile(root, []byte("file"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, _, err := (&Installer{DataDir: root, Downloader: d}).Install(context.Background(), installInput()); err == nil {
		t.Fatal("Install published under a non-directory data root")
	}
}

// TestSkillInstallRejectsExpandedArchiveBudget prevents ZIP metadata from exceeding the bounded extraction budget.
func TestSkillInstallRejectsExpandedArchiveBudget(t *testing.T) {
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	_, err := writer.CreateRaw(&zip.FileHeader{
		Name:               "demo/large",
		Method:             zip.Store,
		CompressedSize64:   1,
		UncompressedSize64: uint64(maxExpandedBytes) + 1,
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := extractZip(buffer.Bytes(), t.TempDir()); err == nil {
		t.Fatal("extractZip accepted a declared size above the expansion budget")
	}
}
