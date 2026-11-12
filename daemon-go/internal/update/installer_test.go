package update

import (
	"context"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
)

const installerTestEnvironmentID = "123e4567-e89b-42d3-a456-426614174000"

// RUN-T-202/205 downloads the official installer, reports measured bytes, and hands off a gated OS job.
func TestInstallerRunnerPreparesAndGatesOfficialJob(t *testing.T) {
	home, root, temp := installerFixture(t)
	var mu sync.Mutex
	var requests []*http.Request
	body := "official installer"
	client := &http.Client{Transport: roundTripFunc(func(request *http.Request) (*http.Response, error) {
		mu.Lock()
		requests = append(requests, request.Clone(request.Context()))
		mu.Unlock()
		return installerResponse(http.StatusOK, body), nil
	})}
	var launcher executableCall
	runner := NewInstallerRunner(InstallerRunnerOptions{
		Platform: "linux", Architecture: "amd64", Home: home, TempDir: temp,
		Environment: map[string]string{"PATH": "/usr/bin", "XDG_RUNTIME_DIR": "/run/user/1000", "DBUS_SESSION_BUS_ADDRESS": "unix:path=/run/user/1000/bus", "SECRET_TOKEN": "must-not-pass"},
		HTTPClient:  client,
		Launch: func(ctx context.Context, executable string, args []string) error {
			launcher = executableCall{executable: executable, args: append([]string(nil), args...)}
			if err := ctx.Err(); err != nil {
				return err
			}
			directory := findWorkDirectory(t, args)
			if err := os.WriteFile(filepath.Join(directory, "ready"), nil, 0600); err != nil {
				return err
			}
			return nil
		},
	})
	var progress []Progress
	outcome, err := runner.HandleUpdate(context.Background(), Command{OperationID: installerTestEnvironmentID, TargetVersion: "1.2.3-rc.1"}, installerConfig(home), func(value Progress) { progress = append(progress, value) })
	if err != nil || outcome.Phase != PhasePrepared || outcome.ReleasePermit == nil || outcome.WatchFailure == nil {
		t.Fatalf("prepared outcome=%+v, err=%v", outcome, err)
	}
	mu.Lock()
	if len(requests) != 1 || requests[0].Method != http.MethodGet || requests[0].URL.String() != "https://raw.githubusercontent.com/fengwk/kk-studio/v1.2.3-rc.1/scripts/daemon/install.sh" {
		t.Fatalf("installer requests=%+v", requests)
	}
	mu.Unlock()
	if len(progress) == 0 || progress[len(progress)-1].DownloadedBytes != int64(len(body)) || progress[len(progress)-1].TotalBytes == nil || *progress[len(progress)-1].TotalBytes != int64(len(body)) {
		t.Fatalf("download progress=%+v", progress)
	}
	if launcher.executable != "systemd-run" || !containsArg(launcher.args, "--user") || !containsArg(launcher.args, "KK_STUDIO_UPDATE_OPERATION_ID="+installerTestEnvironmentID) {
		t.Fatalf("OS job launch=%+v", launcher)
	}
	for _, arg := range launcher.args {
		if strings.Contains(arg, "SECRET_TOKEN") || strings.Contains(arg, "must-not-pass") {
			t.Fatalf("service secret environment leaked to update job: %q", arg)
		}
	}
	work := findWorkDirectory(t, launcher.args)
	defer os.RemoveAll(work)
	if err := os.Remove(filepath.Join(work, "ready")); err != nil {
		t.Fatal(err)
	}
	if err := outcome.ReleasePermit(); err == nil {
		t.Fatal("permit was released after the ready marker disappeared")
	}
	if err := os.WriteFile(filepath.Join(work, "ready"), nil, 0600); err != nil {
		t.Fatal(err)
	}
	if err := outcome.ReleasePermit(); err != nil {
		t.Fatal(err)
	}
	if err := outcome.ReleasePermit(); err != nil {
		t.Fatalf("duplicate permit release = %v", err)
	}
	if info, err := os.Stat(filepath.Join(work, "permit")); err != nil || info.Mode().Perm() != 0600 {
		t.Fatalf("permit file mode=%v err=%v", info, err)
	}

	failurePath := filepath.Join(root, "last-operation.json")
	failed := make(chan string, 1)
	stop := outcome.WatchFailure(func(message string) { failed <- message })
	if err := os.WriteFile(failurePath, []byte(`{"operationId":"`+installerTestEnvironmentID+`","result":"failed"}`), 0600); err != nil {
		t.Fatal(err)
	}
	select {
	case message := <-failed:
		if message != "official installer failed; check local last-operation.json" {
			t.Fatalf("watcher message=%q", message)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("watcher did not report the matching installer failure")
	}
	stop()
}

// RUN-T-204/208 rejects untrusted commands and any installation outside the official environment layout.
func TestInstallerRunnerRejectsUnsupportedAndUnmanagedUpdate(t *testing.T) {
	home, _, temp := installerFixture(t)
	for _, tc := range []struct {
		name string
		cmd  Command
		cfg  config.DaemonConfig
		os   string
		arch string
	}{
		{"invalid operation", Command{OperationID: "bad", TargetVersion: "1.2.3"}, installerConfig(home), "linux", "amd64"},
		{"invalid environment", Command{OperationID: installerTestEnvironmentID, TargetVersion: "1.2.3"}, config.DaemonConfig{EnvironmentID: "BAD"}, "linux", "amd64"},
		{"unsupported OS", Command{OperationID: installerTestEnvironmentID, TargetVersion: "1.2.3"}, installerConfig(home), "plan9", "amd64"},
		{"unsupported architecture", Command{OperationID: installerTestEnvironmentID, TargetVersion: "1.2.3"}, installerConfig(home), "linux", "386"},
		{"unpublished Windows ARM", Command{OperationID: installerTestEnvironmentID, TargetVersion: "1.2.3"}, installerConfig(home), "windows", "arm64"},
		{"unmanaged config", Command{OperationID: installerTestEnvironmentID, TargetVersion: "1.2.3"}, func() config.DaemonConfig {
			cfg := installerConfig(home)
			cfg.ConfigPath = filepath.Join(home, "unmanaged.json")
			return cfg
		}(), "linux", "amd64"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			runner := NewInstallerRunner(InstallerRunnerOptions{Platform: tc.os, Architecture: tc.arch, Home: home, TempDir: temp, Launch: func(context.Context, string, []string) error {
				t.Fatal("invalid update reached OS launcher")
				return nil
			}})
			outcome, err := runner.prepare(context.Background(), tc.cmd, tc.cfg, nil)
			if err == nil || outcome.Phase != "" {
				t.Fatalf("invalid preparation outcome=%+v err=%v", outcome, err)
			}
		})
	}
}

// RUN-T-205/206/207 refuses redirects, non-200 responses, empty scripts, and installer downloads over one MiB.
func TestInstallerRunnerRejectsUnsafeDownloads(t *testing.T) {
	home, _, temp := installerFixture(t)
	cases := []struct {
		name   string
		status int
		body   string
		want   string
	}{
		{"redirect", http.StatusFound, "redirect", "official installer download failed"},
		{"server error", http.StatusInternalServerError, "error", "official installer download failed"},
		{"empty", http.StatusOK, "", "official installer is empty"},
		{"too large", http.StatusOK, strings.Repeat("x", maxInstallerBytes+1), "official installer exceeds size limit"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			client := &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) { return installerResponse(tc.status, tc.body), nil })}
			runner := NewInstallerRunner(InstallerRunnerOptions{Platform: "linux", Architecture: "amd64", Home: home, TempDir: temp, HTTPClient: client, Launch: func(context.Context, string, []string) error { t.Fatal("unsafe installer was launched"); return nil }})
			var logs []string
			runner.options.Log = func(message string) { logs = append(logs, message) }
			outcome, err := runner.HandleUpdate(context.Background(), Command{OperationID: installerTestEnvironmentID, TargetVersion: "1.2.3"}, installerConfig(home), nil)
			if err != nil || outcome.Phase != PhaseFailed || outcome.Message != "official installer preparation failed; check daemon logs and network" {
				t.Fatalf("failure outcome=%+v err=%v", outcome, err)
			}
			if !reflect.DeepEqual(logs, []string{"official installer preparation failed; check daemon logs and network"}) {
				t.Fatalf("safe logs=%v", logs)
			}
			entries, err := os.ReadDir(temp)
			if err != nil || len(entries) != 0 {
				t.Fatalf("unregistered job directory was not cleaned: entries=%v err=%v", entries, err)
			}
		})
	}
}

// RUN-T-218/219 verifies Unix and Windows handoff arguments never carry inherited secrets.
func TestInstallerJobArgumentConstruction(t *testing.T) {
	proxy := "http://proxy.example:8080"
	value := InstallerConfig{EnvironmentID: installerTestEnvironmentID, ProxyURL: &proxy}
	args := unixInvocation("/tmp/work", "/tmp/work/install.sh", "/home/user/.kk-studio/environments/env", value, "2.0.0", installerTestEnvironmentID, "/home/user", map[string]string{
		"PATH": "/bin", "XDG_RUNTIME_DIR": "/run/user/1", "DBUS_SESSION_BUS_ADDRESS": "unix:path=/run/user/1/bus", "KK_UPDATE_PERMIT_TIMEOUT_SECONDS": "45", "SECRET": "not forwarded",
	})
	for _, expected := range []string{"env", "-i", "https_proxy=" + proxy, "HTTP_PROXY=" + proxy, "no_proxy=", "NO_PROXY=", "XDG_RUNTIME_DIR=/run/user/1", "DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1/bus", "KK_UPDATE_PERMIT_TIMEOUT_SECONDS=45", "update", "--environment-id", installerTestEnvironmentID, "--version", "2.0.0"} {
		if !containsArg(args, expected) {
			t.Errorf("Unix invocation omitted %q: %v", expected, args)
		}
	}
	for _, arg := range args {
		if strings.Contains(arg, "SECRET") || strings.Contains(arg, "not forwarded") {
			t.Fatalf("unrelated service environment leaked: %q", arg)
		}
	}
	if !strings.Contains(psQuote("a'b"), "a''b") || encodePowerShell("abc") == "" {
		t.Fatal("PowerShell quoting or UTF-16 encoding was empty")
	}
	action := windowsActionScript(`C:\work`, `C:\work\install.ps1`, `C:\home\.kk-studio\environments\env`, value, "2.0.0", installerTestEnvironmentID, "kk-studio-daemon-update-"+installerTestEnvironmentID)
	for _, expected := range []string{"HTTP_PROXY", "NO_PROXY = ''", "update receipt permit timed out", "last-operation.json", "install.ps1"} {
		if !strings.Contains(action, expected) {
			t.Errorf("Windows updater action omitted %q", expected)
		}
	}
	registration := windowsRegistrationScript(action, "kk-studio-daemon-update-"+installerTestEnvironmentID)
	if !strings.Contains(registration, "Register-ScheduledTask") || !strings.Contains(registration, "Start-ScheduledTask") {
		t.Fatalf("Windows scheduled task registration incomplete: %s", registration)
	}
}

func installerFixture(t *testing.T) (string, string, string) {
	t.Helper()
	home := t.TempDir()
	root := filepath.Join(home, ".kk-studio", "environments", installerTestEnvironmentID)
	if err := os.MkdirAll(filepath.Join(root, "lib"), 0700); err != nil {
		t.Fatal(err)
	}
	return home, root, t.TempDir()
}

func installerConfig(home string) config.DaemonConfig {
	root := filepath.Join(home, ".kk-studio", "environments", installerTestEnvironmentID)
	return config.DaemonConfig{EnvironmentID: installerTestEnvironmentID, ConfigPath: filepath.Join(root, "daemon.json"), TokenPath: filepath.Join(root, "daemon.token"), DistributionDir: filepath.Join(root, "lib")}
}

type executableCall struct {
	executable string
	args       []string
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(request *http.Request) (*http.Response, error) { return f(request) }

func installerResponse(status int, body string) *http.Response {
	return &http.Response{StatusCode: status, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(body)), ContentLength: int64(len(body))}
}

func findWorkDirectory(t *testing.T, args []string) string {
	t.Helper()
	for index, arg := range args {
		if filepath.Base(arg) == "run.sh" && index+1 < len(args) {
			return args[index+1]
		}
	}
	t.Fatal("Unix job arguments omitted run.sh")
	return ""
}

func containsArg(args []string, expected string) bool {
	for _, arg := range args {
		if arg == expected {
			return true
		}
	}
	return false
}
