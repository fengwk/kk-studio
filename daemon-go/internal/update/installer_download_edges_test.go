package update

import (
	"context"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

// RUN-T-205 checks that transport, response-reader, and private-storage errors fail closed.
func TestInstallerDownloadTransportReaderAndStorageFailures(t *testing.T) {
	for _, tc := range []struct {
		name      string
		transport roundTripFunc
	}{
		{"network", func(*http.Request) (*http.Response, error) { return nil, errors.New("network secret") }},
		{"reader", func(*http.Request) (*http.Response, error) {
			return &http.Response{StatusCode: http.StatusOK, Body: failingReadCloser{}, ContentLength: -1}, nil
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			runner := NewInstallerRunner(InstallerRunnerOptions{HTTPClient: &http.Client{Transport: tc.transport}})
			if err := runner.downloadInstaller(context.Background(), "https://example.test/install.sh", filepath.Join(t.TempDir(), "installer"), nil, nil); err == nil {
				t.Fatal("failed response was accepted")
			}
		})
	}
	destination := filepath.Join(t.TempDir(), "exists")
	if err := os.WriteFile(destination, []byte("existing"), 0600); err != nil {
		t.Fatal(err)
	}
	runner := NewInstallerRunner(InstallerRunnerOptions{HTTPClient: &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) { return installerResponse(http.StatusOK, "installer"), nil })}})
	if err := runner.downloadInstaller(context.Background(), "https://example.test/install.sh", destination, nil, nil); err == nil {
		t.Fatal("existing destination was overwritten")
	}
}

type failingReadCloser struct{}

func (failingReadCloser) Read([]byte) (int, error) { return 0, errors.New("body read failed") }

func (failingReadCloser) Close() error { return nil }

var _ io.ReadCloser = failingReadCloser{}
