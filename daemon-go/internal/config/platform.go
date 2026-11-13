package config

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

// ReleaseKey maps the supported OS/architecture pairs to packaged release names.
func ReleaseKey(goos, goarch string) (string, error) {
	var osName string
	switch goos {
	case "linux":
		osName = "linux"
	case "darwin":
		osName = "macos"
	case "windows":
		osName = "windows"
	default:
		return "", errors.New("unsupported operating system")
	}
	var arch string
	switch goarch {
	case "amd64":
		arch = "amd64"
	case "arm64":
		arch = "arm64"
	default:
		return "", errors.New("unsupported architecture")
	}
	return osName + "-" + arch, nil
}

// ResolveExecutable returns an absolute executable path from an explicit path or PATH.
func ResolveExecutable(command string) (string, error) {
	if command == "" {
		return "", errors.New("executable is empty")
	}
	resolved, err := exec.LookPath(command)
	if err != nil {
		return "", errors.New("executable cannot be resolved")
	}
	absolute, err := filepath.Abs(resolved)
	if err != nil {
		return "", errors.New("executable cannot be resolved")
	}
	st, err := os.Stat(absolute)
	if err != nil || !st.Mode().IsRegular() {
		return "", errors.New("executable is not a regular file")
	}
	if runtime.GOOS != "windows" && st.Mode().Perm()&0111 == 0 {
		return "", errors.New("file is not executable")
	}
	return absolute, nil
}

// ResolveBash validates the configured Bash candidate for hot configuration application.
func ResolveBash(command *string) (string, error) {
	value := "bash"
	if command != nil {
		value = *command
	}
	return ResolveExecutable(value)
}

// DistributionDir resolves the executable's distribution root, collapsing a final dist directory.
func DistributionDir(executablePath string) string {
	dir := filepath.Dir(executablePath)
	if strings.EqualFold(filepath.Base(dir), "dist") {
		return filepath.Dir(dir)
	}
	return dir
}
