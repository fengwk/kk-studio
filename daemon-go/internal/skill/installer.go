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
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"unicode"
)

const (
	maxArchiveBytes  = 128 << 20
	maxArchiveFiles  = 10000
	maxExpandedBytes = 512 << 20
	installRecord    = ".kk-studio-install.json"
)

type installArgs struct {
	TransferID string `json:"transferId"`
	Package    string `json:"package"`
	Version    string `json:"version"`
	Skill      string `json:"skill"`
}
type installRecordData struct {
	SHA256 string `json:"sha256"`
}

var packageLocks sync.Map

// Installer installs ticket-verified skill archives into the daemon's versioned skills tree.
type Installer struct {
	DataDir    string
	Downloader ResourceDownloader
}

// Install verifies the request, downloads and safely publishes one versioned skill archive.
func (i *Installer) Install(ctx context.Context, input json.RawMessage) (json.RawMessage, string, error) {
	var a installArgs
	if err := jsonv2.Unmarshal(input, &a, jsonv2.RejectUnknownMembers(true)); err != nil || a.TransferID == "" || !validUUID(a.TransferID) || !safeSegment(a.Package) || !safeSegment(a.Version) || !safeSegment(a.Skill) {
		return nil, "", errors.New("skill.install: invalid arguments")
	}
	if i.Downloader == nil {
		return nil, "", errors.New("skill.install: resource downloader is not configured")
	}
	if !filepath.IsAbs(i.DataDir) {
		return nil, "", errors.New("skill.install: data directory must be absolute")
	}
	key := filepath.Join(i.DataDir, "skills", a.Package)
	lockAny, _ := packageLocks.LoadOrStore(key, &sync.Mutex{})
	lock := lockAny.(*sync.Mutex)
	lock.Lock()
	defer lock.Unlock()
	packageDir := filepath.Join(key, a.Version)
	var ticketDigest string
	if provider, ok := i.Downloader.(ResourceDigestProvider); ok {
		var err error
		ticketDigest, err = provider.TicketSHA256(ctx, a.TransferID)
		if err != nil {
			return nil, "", errors.New("skill.install: resource ticket is unavailable")
		}
		if !validSHA256(ticketDigest) {
			return nil, "", errors.New("skill.install: resource ticket digest is invalid")
		}
		if old, err := installedDigest(packageDir, a.Skill); err == nil && old == ticketDigest {
			return installedResult(packageDir, a.Skill, "Skill package is already installed.")
		}
	}
	archive, err := i.Downloader.Download(ctx, a.TransferID)
	if err != nil {
		return nil, "", errors.New("skill.install: resource download failed")
	}
	if len(archive) == 0 || len(archive) > maxArchiveBytes {
		return nil, "", errors.New("skill.install: archive exceeds the size limit")
	}
	digest := sha256.Sum256(archive)
	digestText := hex.EncodeToString(digest[:])
	if ticketDigest != "" && ticketDigest != digestText {
		return nil, "", errors.New("skill.install: downloaded archive does not match its ticket digest")
	}
	if old, err := installedDigest(packageDir, a.Skill); err == nil && old == digestText {
		return installedResult(packageDir, a.Skill, "Skill package is already installed.")
	}
	parent := filepath.Dir(packageDir)
	if err := os.MkdirAll(parent, 0700); err != nil {
		return nil, "", errors.New("skill.install: cannot create package directory")
	}
	stage, err := os.MkdirTemp(parent, ".staging-")
	if err != nil {
		return nil, "", errors.New("skill.install: cannot create staging directory")
	}
	defer os.RemoveAll(stage)
	if err := extractZip(archive, stage); err != nil {
		return nil, "", err
	}
	skillDir := filepath.Join(stage, a.Skill)
	marker, err := os.Stat(filepath.Join(skillDir, "SKILL.md"))
	if err != nil || !marker.Mode().IsRegular() {
		return nil, "", errors.New("skill.install: archive does not contain the requested skill's SKILL.md")
	}
	meta, _ := jsonv2.Marshal(installRecordData{SHA256: hex.EncodeToString(digest[:])})
	if err := os.WriteFile(filepath.Join(stage, installRecord), meta, 0600); err != nil {
		return nil, "", errors.New("skill.install: cannot write installation record")
	}
	if err := replaceDirectory(stage, packageDir); err != nil {
		return nil, "", errors.New("skill.install: cannot publish installation")
	}
	return installedResult(packageDir, a.Skill, "Skill package installed successfully.")
}

func installedResult(packageDir, skillName, message string) (json.RawMessage, string, error) {
	absolute, err := filepath.Abs(filepath.Join(packageDir, skillName))
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

func installedDigest(dir, skillName string) (string, error) {
	var r installRecordData
	b, err := os.ReadFile(filepath.Join(dir, installRecord))
	if err != nil {
		return "", err
	}
	if jsonv2.Unmarshal(b, &r) != nil || len(r.SHA256) != 64 {
		return "", errors.New("invalid installation record")
	}
	st, err := os.Stat(filepath.Join(dir, skillName, "SKILL.md"))
	if err != nil || !st.Mode().IsRegular() {
		return "", errors.New("skill missing")
	}
	return r.SHA256, nil
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
func validUUID(s string) bool {
	if len(s) != 36 || strings.ToLower(s) != s {
		return false
	}
	for n, c := range s {
		if n == 8 || n == 13 || n == 18 || n == 23 {
			if c != '-' {
				return false
			}
			continue
		}
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return false
		}
	}
	return true
}
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
		name := f.Name
		if name == "" || strings.Contains(name, `\`) || strings.HasPrefix(name, "/") || filepath.IsAbs(name) || strings.Contains(name, ":") {
			return errors.New("skill.install: archive contains an unsafe path")
		}
		clean := filepath.Clean(filepath.FromSlash(name))
		if clean == "." || clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
			return errors.New("skill.install: archive contains an unsafe path")
		}
		for _, part := range strings.Split(name, "/") {
			if part == ".." || part == "." || part == "" && !strings.HasSuffix(name, "/") {
				return errors.New("skill.install: archive contains an unsafe path")
			}
		}
		mode := f.Mode()
		if mode&os.ModeSymlink != 0 || mode&os.ModeType != 0 && !mode.IsDir() {
			return errors.New("skill.install: archive contains an unsupported entry")
		}
		if f.UncompressedSize64 > uint64(maxExpandedBytes) || total+int64(f.UncompressedSize64) > maxExpandedBytes {
			return errors.New("skill.install: expanded archive exceeds the size limit")
		}
		target := filepath.Join(root, clean)
		rel, err := filepath.Rel(root, target)
		if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
			return errors.New("skill.install: archive contains an unsafe path")
		}
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0700); err != nil {
				return errors.New("skill.install: cannot extract archive")
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0700); err != nil {
			return errors.New("skill.install: cannot extract archive")
		}
		src, err := f.Open()
		if err != nil {
			return errors.New("skill.install: cannot extract archive")
		}
		out, err := os.OpenFile(target, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if err != nil {
			src.Close()
			return errors.New("skill.install: cannot extract archive")
		}
		n, copyErr := io.Copy(out, io.LimitReader(src, maxExpandedBytes-total+1))
		closeErr := out.Close()
		src.Close()
		if copyErr != nil || closeErr != nil || n != int64(f.UncompressedSize64) {
			return errors.New("skill.install: cannot extract archive")
		}
		total += n
	}
	return nil
}
func replaceDirectory(stage, target string) error {
	if _, err := os.Lstat(target); errors.Is(err, os.ErrNotExist) {
		return os.Rename(stage, target)
	} else if err != nil {
		return err
	}
	backup := target + ".backup"
	_ = os.RemoveAll(backup)
	if err := os.Rename(target, backup); err != nil {
		return err
	}
	if err := os.Rename(stage, target); err != nil {
		restoreErr := os.Rename(backup, target)
		if restoreErr != nil {
			return errors.Join(err, restoreErr)
		}
		return err
	}
	return os.RemoveAll(backup)
}
