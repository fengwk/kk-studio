//go:build linux || darwin

package native

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"testing"
)

// Injected startup failures verify each preparation stage releases earlier resources and private state.
func TestPOSIXStartInjectedPreparationFailures(t *testing.T) {
	for _, test := range []string{"state directory", "launch publication", "owner pipe", "stdin pipe", "stdout pipe", "stderr pipe", "executable lookup", "helper spawn"} {
		t.Run(test, func(t *testing.T) {
			supervisor, err := NewSupervisor()
			if err != nil {
				t.Fatal(err)
			}
			s := supervisor.(*PosixSupervisor)
			api := nativePOSIXStartAPI()
			var dir string
			baseCreateStateDir := api.createStateDir
			api.createStateDir = func() (string, error) {
				if test == "state directory" {
					return "", errInjectedStartFailure
				}
				dir, err = baseCreateStateDir()
				return dir, err
			}
			if test == "launch publication" {
				api.publishLaunch = func(string, []byte) error { return errInjectedStartFailure }
			}

			pipeCount := 0
			var files []*os.File
			basePipe := api.pipe
			api.pipe = func() (*os.File, *os.File, error) {
				pipeCount++
				if test == "owner pipe" && pipeCount == 1 || test == "stdin pipe" && pipeCount == 2 || test == "stdout pipe" && pipeCount == 3 || test == "stderr pipe" && pipeCount == 4 {
					return nil, nil, errInjectedStartFailure
				}
				reader, writer, err := basePipe()
				if err == nil {
					files = append(files, reader, writer)
				}
				return reader, writer, err
			}
			if test == "executable lookup" {
				api.executable = func() (string, error) { return "", errInjectedStartFailure }
			}
			if test == "helper spawn" {
				api.start = func(*exec.Cmd) error { return errInjectedStartFailure }
			}
			s.startAPI = api

			_, err = s.Start(context.Background(), ProcessSpec{Executable: "/bin/true"})
			if !errors.Is(err, errInjectedStartFailure) {
				t.Fatalf("Start error = %v, want injected failure", err)
			}
			for _, file := range files {
				if _, statErr := file.Stat(); !errors.Is(statErr, os.ErrClosed) {
					t.Errorf("startup file %q was not closed: %v", file.Name(), statErr)
				}
			}
			if dir != "" {
				if _, statErr := os.Stat(dir); !errors.Is(statErr, os.ErrNotExist) {
					t.Errorf("failed start retained state directory %q: %v", dir, statErr)
				}
			}
		})
	}
}

var errInjectedStartFailure = errors.New("injected POSIX start failure")
