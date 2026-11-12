package native

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const (
	stateDirPrefix  = "kk-studio-daemon-process-scope-"
	scopeFile       = "scope"
	permitFile      = "permit"
	exitFile        = "exit"
	errorFile       = "error"
	cleanupFile     = "cleanup"
	launchFile      = "launch"
	diagnosticsFile = "diagnostics"
)

type launchSpec struct {
	Cwd  string   `json:"cwd"`
	Args []string `json:"args"`
}

type exitReport struct {
	ExitCode *int    `json:"exitCode"`
	Signal   *string `json:"signal"`
}

func createStateDir() (string, error) {
	dir, err := os.MkdirTemp(os.TempDir(), stateDirPrefix)
	if err != nil {
		return "", err
	}
	if err := secureNewPrivateDirectory(dir); err != nil {
		_ = os.RemoveAll(dir)
		return "", err
	}
	return dir, nil
}

func publish(dir, name string, data []byte) error {
	if filepath.Base(name) != name {
		return errors.New("invalid state file name")
	}
	return writePrivateFile(filepath.Join(dir, name), data)
}

func publishText(dir, name, value string) error { return publish(dir, name, []byte(value)) }

func publishQuietly(dir, name, value string) {
	_ = publishText(dir, name, value)
}

func readState(dir, name string) ([]byte, error) {
	if err := verifyPrivateDirectory(dir); err != nil {
		return nil, err
	}
	if filepath.Base(name) != name {
		return nil, errors.New("invalid state file name")
	}
	path := filepath.Join(dir, name)
	if err := verifyPrivateFile(path); err != nil {
		return nil, err
	}
	return os.ReadFile(path)
}

func readLaunch(dir string) (*launchSpec, error) {
	if err := verifyPrivateDirectory(dir); err != nil {
		return nil, errors.New("the process scope launch spec is unreadable")
	}
	path := filepath.Join(dir, launchFile)
	if err := verifyPrivateFile(path); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, nil
		}
		return nil, errors.New("the process scope launch spec is unreadable")
	}
	payload, err := os.ReadFile(path)
	if err != nil {
		return nil, errors.New("the process scope launch spec is unreadable")
	}
	_ = os.Remove(path)
	var spec launchSpec
	if err := json.Unmarshal(payload, &spec); err != nil {
		return nil, errors.New("the process scope launch spec is unreadable")
	}
	if spec.Cwd == "" || spec.Args == nil {
		return nil, errors.New("the process scope launch spec is incomplete")
	}
	for _, value := range append([]string{spec.Cwd}, spec.Args...) {
		if strings.ContainsRune(value, '\x00') {
			return nil, errors.New("the process scope launch spec must not contain a NUL character")
		}
	}
	return &spec, nil
}

func readExit(dir string) (*exitReport, error) {
	data, err := readState(dir, exitFile)
	if err != nil {
		return nil, err
	}
	var report exitReport
	if err := json.Unmarshal(data, &report); err != nil || (report.ExitCode == nil && report.Signal == nil) {
		return nil, errors.New("the process scope helper published an unreadable exit code")
	}
	return &report, nil
}

func readCleanup(dir string) (*bool, error) {
	data, err := readState(dir, cleanupFile)
	if err != nil {
		return nil, err
	}
	value := strings.TrimSpace(string(data))
	if value == "true" {
		result := true
		return &result, nil
	}
	if value == "false" {
		result := false
		return &result, nil
	}
	return nil, fmt.Errorf("invalid cleanup evidence")
}

func removeStateDir(dir string) error { return os.RemoveAll(dir) }

func randomSuffix() string {
	var value [16]byte
	if _, err := rand.Read(value[:]); err != nil {
		panic("crypto/rand unavailable: " + err.Error())
	}
	return hex.EncodeToString(value[:])
}
