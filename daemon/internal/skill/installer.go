package skill

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	jsonv2 "encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"unicode"

	"github.com/fengwk/kk-studio/daemon/internal/coding/keylock"
	"github.com/fengwk/kk-studio/daemon/internal/uuid"
)

const (
	maxArchiveFiles       = 10000
	maxExpandedBytes      = 512 << 20
	maxSkillManifestBytes = 64 << 10
	installDigestFile     = ".kk-studio-skill-sha256"
)

type installArgs struct {
	TransferID string `json:"transferId"`
	Package    string `json:"package"`
	Version    string `json:"version"`
	Skill      string `json:"skill"`
}

var packageLocks keylock.Locks

// Installer installs ticket-verified skill archives into the daemon's versioned skills tree.
type Installer struct {
	DataDir    string
	Downloader ResourceDownloader
}

// CleanupStaging removes only abandoned staging directories left by interrupted installations.
// Call it once during daemon startup, before accepting skill.install invocations.
func (i *Installer) CleanupStaging(ctx context.Context) error {
	root := filepath.Join(i.DataDir, "skills")
	info, err := os.Lstat(root)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("skill.install: cannot inspect skills directory: %w", err)
	}
	if !info.IsDir() {
		return errors.New("skill.install: skills path is not a directory")
	}
	return filepath.WalkDir(root, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		if entry.IsDir() && strings.HasPrefix(entry.Name(), ".staging-") {
			if err := os.RemoveAll(path); err != nil {
				return fmt.Errorf("skill.install: cannot remove abandoned staging directory: %w", err)
			}
			return filepath.SkipDir
		}
		return nil
	})
}

// Install verifies the request and ticket, then publishes one immutable content-addressed skill archive.
func (i *Installer) Install(ctx context.Context, input json.RawMessage) (json.RawMessage, string, error) {
	a, err := decodeInstallArgs(input)
	if err != nil {
		return nil, "", err
	}
	if i.Downloader == nil {
		return nil, "", errors.New("skill.install: resource downloader is not configured")
	}
	if !filepath.IsAbs(i.DataDir) {
		return nil, "", errors.New("skill.install: data directory must be absolute")
	}
	ticket, err := i.ticket(ctx, a.TransferID)
	if err != nil {
		return nil, "", err
	}
	packageVersionDir := filepath.Join(i.DataDir, "skills", a.Package, a.Version)
	unlock := packageLocks.Lock(packageVersionDir)
	defer unlock()
	target := filepath.Join(packageVersionDir, ticket.SHA256[:12])
	if err := requireInstalledSkill(target, a.Skill, ticket.SHA256); err == nil {
		return installedResult(target, a.Skill, "Skill package is already installed.")
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, "", err
	}
	archive, err := i.download(ctx, ticket)
	if err != nil {
		return nil, "", err
	}
	stage, err := i.stage(packageVersionDir, archive, ticket, a.Skill)
	if err != nil {
		return nil, "", err
	}
	defer os.RemoveAll(stage)
	if err := os.Rename(stage, target); err != nil {
		if targetErr := requireInstalledSkill(target, a.Skill, ticket.SHA256); targetErr == nil {
			return installedResult(target, a.Skill, "Skill package is already installed.")
		}
		return nil, "", errors.New("skill.install: cannot publish installation")
	}
	return installedResult(target, a.Skill, "Skill package installed successfully.")
}

// decodeInstallArgs decodes the install request and rejects incomplete or unsafe arguments.
func decodeInstallArgs(input json.RawMessage) (installArgs, error) {
	var a installArgs
	if err := jsonv2.Unmarshal(input, &a, jsonv2.RejectUnknownMembers(true)); err != nil || a.TransferID == "" || !uuid.Canonical(a.TransferID) || !safeSegment(a.Package) || !safeSegment(a.Version) || !safeSegment(a.Skill) {
		return a, errors.New("skill.install: invalid arguments")
	}
	return a, nil
}

// ticket requests the resource ticket and checks the metadata the archive is verified against.
func (i *Installer) ticket(ctx context.Context, transferID string) (DownloadTicket, error) {
	ticket, err := i.Downloader.RequestTicket(ctx, transferID)
	if err != nil {
		return DownloadTicket{}, errors.New("skill.install: resource ticket is unavailable")
	}
	if ticket.Size <= 0 || !validSHA256(ticket.SHA256) {
		return DownloadTicket{}, errors.New("skill.install: resource ticket metadata is invalid")
	}
	return ticket, nil
}

// download retrieves the archive and checks it byte count and digest against the ticket.
func (i *Installer) download(ctx context.Context, ticket DownloadTicket) ([]byte, error) {
	archive, err := i.Downloader.Download(ctx, ticket)
	if err != nil {
		return nil, errors.New("skill.install: resource download failed")
	}
	if int64(len(archive)) != ticket.Size {
		return nil, errors.New("skill.install: downloaded archive size does not match its ticket")
	}
	digest := sha256.Sum256(archive)
	if hex.EncodeToString(digest[:]) != ticket.SHA256 {
		return nil, errors.New("skill.install: downloaded archive does not match its ticket digest")
	}
	return archive, nil
}

// stage extracts the archive into a staging directory and validates it as an installation.
func (i *Installer) stage(packageVersionDir string, archive []byte, ticket DownloadTicket, skillName string) (string, error) {
	if err := os.MkdirAll(packageVersionDir, 0700); err != nil {
		return "", errors.New("skill.install: cannot create package directory")
	}
	stage, err := os.MkdirTemp(packageVersionDir, ".staging-")
	if err != nil {
		return "", errors.New("skill.install: cannot create staging directory")
	}
	if err := extractZip(archive, stage); err != nil {
		_ = os.RemoveAll(stage)
		return "", err
	}
	if err := requireSkillMarker(stage, skillName); err != nil {
		_ = os.RemoveAll(stage)
		return "", errors.New("skill.install: archive does not contain the requested skill's SKILL.md")
	}
	if err := os.WriteFile(filepath.Join(stage, installDigestFile), []byte(ticket.SHA256), 0600); err != nil {
		_ = os.RemoveAll(stage)
		return "", errors.New("skill.install: cannot write archive digest")
	}
	if err := requireInstalledSkill(stage, skillName, ticket.SHA256); err != nil {
		_ = os.RemoveAll(stage)
		return "", errors.New("skill.install: staging directory failed validation")
	}
	return stage, nil
}

func installedResult(target, skillName, message string) (json.RawMessage, string, error) {
	absolute, err := filepath.Abs(filepath.Join(target, skillName))
	if err != nil {
		return nil, "", errors.New("skill.install: cannot resolve installed path")
	}
	b, err := jsonv2.Marshal(struct {
		SkillDir string `json:"skill_dir"`
	}{absolute})
	if err != nil {
		return nil, "", errors.New("skill.install: cannot encode result")
	}
	return b, message, nil
}

func requireInstalledSkill(target, skillName, digest string) error {
	st, err := os.Lstat(target)
	if err != nil {
		return err
	}
	if !st.IsDir() {
		return errors.New("skill.install: content-addressed target is not a directory")
	}
	if err := requireSkillMarker(target, skillName); err != nil {
		return errors.New("skill.install: existing content-addressed target is incomplete")
	}
	markerInfo, err := os.Lstat(filepath.Join(target, installDigestFile))
	if err != nil || !markerInfo.Mode().IsRegular() {
		return errors.New("skill.install: existing content-addressed target has no digest marker")
	}
	storedDigest, err := os.ReadFile(filepath.Join(target, installDigestFile))
	if err != nil || string(storedDigest) != digest {
		return errors.New("skill.install: content-addressed digest prefix collision")
	}
	return nil
}

func requireSkillMarker(root, skillName string) error {
	marker, err := os.Lstat(filepath.Join(root, skillName, "SKILL.md"))
	if err != nil || !marker.Mode().IsRegular() {
		return errors.New("skill.install: requested skill marker is missing")
	}
	if marker.Size() > maxSkillManifestBytes {
		return errors.New("skill.install: requested skill marker exceeds 64 KiB")
	}
	return nil
}
func validSHA256(s string) bool {
	if len(s) != sha256.Size*2 || strings.ToLower(s) != s {
		return false
	}
	_, err := hex.DecodeString(s)
	return err == nil
}

func safeSegment(s string) bool {
	if s == "" || s == "." || s == ".." || strings.ContainsAny(s, `/\\:`) {
		return false
	}
	for _, r := range s {
		if unicode.IsControl(r) {
			return false
		}
	}
	return true
}

// extractZip writes every regular file of an archive below root, refusing anything a skill tree
// can not safely hold.
func extractZip(data []byte, root string) error {
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return errors.New("skill.install: archive is not a valid ZIP")
	}
	if len(zr.File) > maxArchiveFiles {
		return errors.New("skill.install: archive contains too many entries")
	}
	var total int64
	for _, f := range zr.File {
		target, err := archiveTarget(root, f)
		if err != nil {
			return err
		}
		if f.UncompressedSize64 > uint64(maxExpandedBytes) || total+int64(f.UncompressedSize64) > maxExpandedBytes {
			return errors.New("skill.install: expanded archive exceeds the size limit")
		}
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0700); err != nil {
				return errors.New("skill.install: cannot extract archive")
			}
			continue
		}
		n, err := extractArchiveFile(f, target, maxExpandedBytes-total+1)
		if err != nil {
			return err
		}
		total += n
	}
	return nil
}

// archiveTarget validates one entry's path and mode and returns where it must be written.
func archiveTarget(root string, f *zip.File) (string, error) {
	name := f.Name
	if name == "" || strings.Contains(name, `\`) || strings.HasPrefix(name, "/") || filepath.IsAbs(name) || strings.Contains(name, ":") {
		return "", errors.New("skill.install: archive contains an unsafe path")
	}
	clean := filepath.Clean(filepath.FromSlash(name))
	if clean == "." || clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
		return "", errors.New("skill.install: archive contains an unsafe path")
	}
	for _, part := range strings.Split(name, "/") {
		if part == ".." || part == "." || part == "" && !strings.HasSuffix(name, "/") {
			return "", errors.New("skill.install: archive contains an unsafe path")
		}
	}
	mode := f.Mode()
	if mode&os.ModeSymlink != 0 || mode&os.ModeType != 0 && !mode.IsDir() {
		return "", errors.New("skill.install: archive contains an unsupported entry")
	}
	target := filepath.Join(root, clean)
	rel, err := filepath.Rel(root, target)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return "", errors.New("skill.install: archive contains an unsafe path")
	}
	return target, nil
}

// extractArchiveFile writes one archive entry and reports how many bytes it wrote. limit keeps a
// zip bomb from expanding past the remaining budget while the entry is being copied.
func extractArchiveFile(f *zip.File, target string, limit int64) (int64, error) {
	if err := os.MkdirAll(filepath.Dir(target), 0700); err != nil {
		return 0, errors.New("skill.install: cannot extract archive")
	}
	src, err := f.Open()
	if err != nil {
		return 0, errors.New("skill.install: cannot extract archive")
	}
	out, err := os.OpenFile(target, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		src.Close()
		return 0, errors.New("skill.install: cannot extract archive")
	}
	n, copyErr := io.Copy(out, io.LimitReader(src, limit))
	closeErr := out.Close()
	src.Close()
	if copyErr != nil || closeErr != nil || n != int64(f.UncompressedSize64) {
		return 0, errors.New("skill.install: cannot extract archive")
	}
	return n, nil
}
