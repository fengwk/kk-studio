package lsp

import (
	"context"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
)

const (
	typescriptLanguageServerVersion = "4.3.4"
	typescriptVersion               = "5.8.3"
	typescriptProfileID             = "typescript-language-server-4.3.4_typescript-5.8.3"
)

//go:embed typescript/package.json
var typescriptPackageJSON []byte

//go:embed typescript/package-lock.json
var typescriptPackageLock []byte

// TypeScriptLibrary installs one verified Linux/amd64 TS profile into a caller-owned,
// same-user library directory. Installed versions are immutable; sessions remain owned by
// their Environment through the existing Facade and keyed by canonical workspace root.
type TypeScriptLibrary struct {
	Directory string
	NPM       string
}

// Install publishes the fixed TypeScript profile without replacing an installed version.
func (l TypeScriptLibrary) Install(ctx context.Context) (installed string, retErr error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}
	if runtime.GOOS != "linux" || runtime.GOARCH != "amd64" {
		return "", errors.New("TypeScript LSP profile is verified only on linux/amd64")
	}
	if !filepath.IsAbs(l.Directory) {
		return "", errors.New("TypeScript LSP library directory must be absolute")
	}
	if l.NPM == "" {
		l.NPM = "npm"
	}
	if _, err := exec.LookPath(l.NPM); err != nil {
		return "", errors.New("TypeScript LSP installation requires npm")
	}
	if err := native.EnsurePrivateDirectory(l.Directory); err != nil {
		return "", fmt.Errorf("TypeScript LSP library directory is unavailable: %w", err)
	}
	root, err := filepath.EvalSymlinks(l.Directory)
	if err != nil {
		return "", fmt.Errorf("TypeScript LSP library directory cannot be canonicalized: %w", err)
	}
	root, err = filepath.Abs(root)
	if err != nil {
		return "", fmt.Errorf("TypeScript LSP library directory cannot be resolved: %w", err)
	}
	lock, err := acquireTypeScriptLibraryLock(ctx, root)
	if err != nil {
		return "", err
	}
	defer func() {
		retErr = errors.Join(retErr, lock.Close())
	}()

	target := filepath.Join(root, typescriptProfileID)
	if err := validateTypeScriptInstall(target); err == nil {
		return target, nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return "", err
	}
	stage, err := os.MkdirTemp(root, ".typescript-staging-")
	if err != nil {
		return "", errors.New("TypeScript LSP staging directory cannot be created")
	}
	defer func() {
		if err := os.RemoveAll(stage); err != nil {
			retErr = errors.Join(retErr, fmt.Errorf("TypeScript LSP staging directory cleanup failed: %w", err))
		}
	}()
	if err := os.WriteFile(filepath.Join(stage, "package.json"), typescriptPackageJSON, 0600); err != nil {
		return "", errors.New("TypeScript LSP package manifest cannot be staged")
	}
	if err := os.WriteFile(filepath.Join(stage, "package-lock.json"), typescriptPackageLock, 0600); err != nil {
		return "", errors.New("TypeScript LSP package lock cannot be staged")
	}
	cacheDir := filepath.Join(stage, ".npm-cache")
	cmd := exec.CommandContext(ctx, l.NPM, "ci", "--ignore-scripts", "--no-audit", "--no-fund", "--no-progress", "--cache="+cacheDir, "--logs-max=0")
	cmd.Dir = stage
	if err := cmd.Run(); err != nil {
		return "", errors.New("TypeScript LSP dependencies could not be installed from the pinned lockfile")
	}
	if err := os.RemoveAll(cacheDir); err != nil {
		return "", errors.New("temporary TypeScript LSP npm cache could not be removed")
	}
	if err := validateTypeScriptInstall(stage); err != nil {
		return "", fmt.Errorf("TypeScript LSP installation failed validation: %w", err)
	}
	if err := os.Rename(stage, target); err != nil {
		return "", errors.New("TypeScript LSP installation could not be atomically published")
	}
	return target, nil
}

// TypeScriptServerConfig returns the configured-server declaration for an already installed
// profile. The caller adds it to its Environment's LSP declarations; no session is shared.
func TypeScriptServerConfig(libraryDirectory string) (config.LSPServerConfig, error) {
	if runtime.GOOS != "linux" || runtime.GOARCH != "amd64" {
		return config.LSPServerConfig{}, errors.New("TypeScript LSP profile is verified only on linux/amd64")
	}
	if !filepath.IsAbs(libraryDirectory) {
		return config.LSPServerConfig{}, errors.New("TypeScript LSP library directory must be absolute")
	}
	target, err := filepath.EvalSymlinks(libraryDirectory)
	if err != nil {
		return config.LSPServerConfig{}, errors.New("TypeScript LSP library directory cannot be resolved")
	}
	profileDir := filepath.Join(target, typescriptProfileID)
	if err := validateTypeScriptInstall(profileDir); err != nil {
		return config.LSPServerConfig{}, err
	}
	node, err := verifiedNodeExecutable()
	if err != nil {
		return config.LSPServerConfig{}, err
	}
	cli := filepath.Join(profileDir, "node_modules", "typescript-language-server", "lib", "cli.mjs")
	return config.LSPServerConfig{
		ID:                typescriptProfileID,
		Command:           []string{node, cli, "--stdio"},
		Extensions:        []string{".ts", ".tsx", ".js", ".jsx", ".mts", ".cts", ".mjs", ".cjs"},
		RootMarkers:       []string{"tsconfig.json", "jsconfig.json", "package.json"},
		FirstMatchMarkers: []string{"tsconfig.json", "jsconfig.json", "package.json"},
	}, nil
}

func verifiedNodeExecutable() (string, error) {
	node, err := exec.LookPath("node")
	if err != nil {
		return "", errors.New("TypeScript LSP requires Node.js 18 or later")
	}
	output, err := exec.Command(node, "--version").Output()
	if err != nil {
		return "", errors.New("TypeScript LSP requires Node.js 18 or later")
	}
	version := strings.TrimPrefix(strings.TrimSpace(string(output)), "v")
	majorText, _, _ := strings.Cut(version, ".")
	major, err := strconv.Atoi(majorText)
	if err != nil || major < 18 {
		return "", errors.New("TypeScript LSP requires Node.js 18 or later")
	}
	absolute, err := filepath.Abs(node)
	if err != nil {
		return "", errors.New("TypeScript LSP Node.js executable cannot be resolved")
	}
	return absolute, nil
}

func validateTypeScriptInstall(directory string) error {
	versions := []struct {
		path, version string
	}{
		{filepath.Join(directory, "node_modules", "typescript-language-server", "package.json"), typescriptLanguageServerVersion},
		{filepath.Join(directory, "node_modules", "typescript", "package.json"), typescriptVersion},
	}
	for _, item := range versions {
		data, err := os.ReadFile(item.path)
		if err != nil {
			return err
		}
		var metadata struct {
			Version string `json:"version"`
		}
		if err := json.Unmarshal(data, &metadata); err != nil || metadata.Version != item.version {
			return errors.New("installed TypeScript LSP package version does not match the fixed profile")
		}
	}
	binary := filepath.Join(directory, "node_modules", ".bin", "typescript-language-server")
	info, err := os.Stat(binary)
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() || info.Mode().Perm()&0111 == 0 {
		return errors.New("installed TypeScript LSP command is not executable")
	}
	cli := filepath.Join(directory, "node_modules", "typescript-language-server", "lib", "cli.mjs")
	info, err = os.Stat(cli)
	if err != nil || !info.Mode().IsRegular() {
		return errors.New("installed TypeScript LSP entrypoint is unavailable")
	}
	return nil
}

func acquireTypeScriptLibraryLock(ctx context.Context, directory string) (native.DirectoryLock, error) {
	for {
		lock, err := native.AcquireDirectoryLock(directory, native.InstallLockFileName)
		if err == nil {
			return lock, nil
		}
		if !errors.Is(err, native.ErrDirectoryLockBusy) {
			return nil, fmt.Errorf("TypeScript LSP library lock could not be acquired: %w", err)
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(100 * time.Millisecond):
		}
	}
}
