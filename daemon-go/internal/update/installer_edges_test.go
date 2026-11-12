package update

import (
	"context"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
	"unicode/utf16"
)

// RUN-T-204/218/219 exercises platform-specific job registration without launching an installer in tests.
func TestInstallerRunnerBuildsDarwinAndWindowsJobs(t *testing.T) {
	for _, platform := range []string{"darwin", "windows"} {
		t.Run(platform, func(t *testing.T) {
			home, _, temp := installerFixture(t)
			var executable string
			var args []string
			runner := NewInstallerRunner(InstallerRunnerOptions{
				Platform: platform, Architecture: "amd64", Home: home, TempDir: temp,
				HTTPClient: &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) { return installerResponse(http.StatusOK, "script"), nil })},
				Launch: func(ctx context.Context, file string, arguments []string) error {
					executable, args = file, append([]string(nil), arguments...)
					if err := ctx.Err(); err != nil {
						return err
					}
					entries, err := os.ReadDir(temp)
					if err != nil || len(entries) != 1 {
						return errors.New("update work directory not found")
					}
					return os.WriteFile(filepath.Join(temp, entries[0].Name(), "ready"), nil, 0600)
				},
			})
			outcome, err := runner.prepare(context.Background(), Command{OperationID: installerTestEnvironmentID, TargetVersion: "2.0.0"}, installerConfig(home), nil)
			if err != nil || outcome.Phase != PhasePrepared {
				t.Fatalf("prepared result=%+v err=%v", outcome, err)
			}
			entries, err := os.ReadDir(temp)
			if err != nil || len(entries) != 1 {
				t.Fatalf("job workspace entries=%v err=%v", entries, err)
			}
			work := filepath.Join(temp, entries[0].Name())
			defer os.RemoveAll(work)
			if err := outcome.ReleasePermit(); err != nil {
				t.Fatal(err)
			}
			stop := outcome.WatchFailure(func(string) { t.Fatal("watcher reported a failure without a result") })
			stop()
			if platform == "darwin" {
				if executable != "launchctl" || !containsArg(args, "submit") || !containsArg(args, "-l") {
					t.Fatalf("launchctl handoff executable=%q args=%v", executable, args)
				}
			} else {
				if executable != "powershell.exe" || !containsArg(args, "-EncodedCommand") {
					t.Fatalf("scheduled task handoff executable=%q args=%v", executable, args)
				}
				encoded := args[len(args)-1]
				registrationBytes, err := decodePowerShell(encoded)
				if err != nil || !strings.Contains(decodeUTF16(registrationBytes), "Register-ScheduledTask") || !strings.Contains(decodeUTF16(registrationBytes), "Start-ScheduledTask") {
					t.Fatalf("scheduled task registration script decode failed: %v", err)
				}
			}
		})
	}
}

// RUN-T-207 keeps update adapters configured only with usable host boundaries and sanitizes launcher failures.
func TestInstallerRunnerBoundariesAndSafeLauncherErrors(t *testing.T) {
	var nilRunner *InstallerRunner
	if nilRunner.Configured() {
		t.Fatal("nil runner reports configured")
	}
	runner := NewInstallerRunner(InstallerRunnerOptions{Home: t.TempDir(), TempDir: t.TempDir()})
	if !runner.Configured() || runner.BusyReason() != "" {
		t.Fatal("default runner boundaries were not configured")
	}
	if err := runner.StopForUpdate(context.Background()); err != nil {
		t.Fatalf("empty stop hook error = %v", err)
	}
	stopErr := errors.New("IDE shutdown failed")
	runner.options.Stop = func(context.Context) error { return stopErr }
	if err := runner.StopForUpdate(context.Background()); !errors.Is(err, stopErr) {
		t.Fatalf("stop hook error = %v", err)
	}

	home, _, temp := installerFixture(t)
	logs := []string{}
	failed := NewInstallerRunner(InstallerRunnerOptions{
		Platform: "linux", Architecture: "amd64", Home: home, TempDir: temp,
		HTTPClient: &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) { return installerResponse(http.StatusOK, "script"), nil })},
		Launch:     func(context.Context, string, []string) error { return errors.New("secret launch diagnostic") },
		Log:        func(message string) { logs = append(logs, message) },
	})
	outcome, err := failed.HandleUpdate(context.Background(), Command{OperationID: installerTestEnvironmentID, TargetVersion: "2.0.0"}, installerConfig(home), nil)
	if err != nil || outcome.Phase != PhaseFailed || strings.Contains(outcome.Message, "secret") || len(logs) != 1 || strings.Contains(logs[0], "secret") {
		t.Fatalf("unsafe failure outcome=%+v logs=%v err=%v", outcome, logs, err)
	}
	entries, err := os.ReadDir(temp)
	if err != nil || len(entries) != 0 {
		t.Fatalf("failed launcher left an unregistered workspace: entries=%v err=%v", entries, err)
	}
}

// RUN-T-205 verifies timeout, content-size, and non-redirect behavior at the HTTP boundary.
func TestInstallerDownloadContextAndRedirectPolicy(t *testing.T) {
	home, _, temp := installerFixture(t)
	requests := 0
	client := &http.Client{Transport: roundTripFunc(func(request *http.Request) (*http.Response, error) {
		requests++
		if request.URL.Scheme != "https" {
			return nil, errors.New("unexpected scheme")
		}
		response := installerResponse(http.StatusFound, "redirect")
		response.Header.Set("Location", "https://other.example/installer.sh")
		return response, nil
	})}
	runner := NewInstallerRunner(InstallerRunnerOptions{Platform: "linux", Architecture: "amd64", Home: home, TempDir: temp, HTTPClient: client, Launch: func(context.Context, string, []string) error { t.Fatal("redirected script was launched"); return nil }})
	outcome, err := runner.HandleUpdate(context.Background(), Command{OperationID: installerTestEnvironmentID, TargetVersion: "1.0.0"}, installerConfig(home), nil)
	if err != nil || outcome.Phase != PhaseFailed || requests != 1 {
		t.Fatalf("redirect handling outcome=%+v err=%v requests=%d", outcome, err, requests)
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := runner.prepare(ctx, Command{OperationID: installerTestEnvironmentID, TargetVersion: "1.0.0"}, installerConfig(home), nil); err == nil {
		t.Fatal("cancelled preparation succeeded")
	}
	if err := waitForReady(ctx, filepath.Join(temp, "missing-ready")); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled ready wait error = %v", err)
	}
}

// RUN-T-211/213 reads only bounded diagnostics and stops after a matching successful operation.
func TestInstallerDiagnosticReadBoundsAndSuccess(t *testing.T) {
	path := filepath.Join(t.TempDir(), "last-operation.json")
	if _, err := readBoundedFile(path, 4097); err == nil {
		t.Fatal("missing diagnostic file was read")
	}
	if err := os.Mkdir(path, 0700); err != nil {
		t.Fatal(err)
	}
	if _, err := readBoundedFile(path, 4097); err == nil {
		t.Fatal("directory diagnostic was read")
	}
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(`{"operationId":"`+installerTestEnvironmentID+`","result":"succeeded"}`), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(path, path+".link"); err == nil {
		if _, err := readBoundedFile(path+".link", 4097); err == nil {
			t.Fatal("symlinked diagnostic was read")
		}
	}
	if err := os.WriteFile(path, []byte(strings.Repeat("x", 4098)), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := readBoundedFile(path, 4097); err == nil {
		t.Fatal("oversized diagnostic was read")
	}
	if err := os.WriteFile(path, []byte(`{"operationId":"`+installerTestEnvironmentID+`","result":"succeeded"}`), 0600); err != nil {
		t.Fatal(err)
	}
	reported := make(chan string, 1)
	stop := watchFailure(path, installerTestEnvironmentID, func(message string) { reported <- message })
	select {
	case message := <-reported:
		t.Fatalf("successful operation reported failure: %q", message)
	case <-time.After(1100 * time.Millisecond):
	}
	stop()
}

// RUN-T-208 checks exact managed paths and Windows case-insensitive path identity.
func TestManagedUpdatePathValidation(t *testing.T) {
	home := t.TempDir()
	value := installerConfig(home)
	if err := requireManagedInstallation(InstallerConfig{EnvironmentID: value.EnvironmentID, ConfigPath: strings.ToUpper(value.ConfigPath), TokenPath: value.TokenPath, DistributionDir: value.DistributionDir}, home, "windows"); err != nil {
		t.Fatalf("Windows case-insensitive managed paths = %v", err)
	}
	if err := requireManagedInstallation(InstallerConfig{EnvironmentID: value.EnvironmentID, ConfigPath: value.ConfigPath, TokenPath: value.TokenPath, DistributionDir: value.DistributionDir}, "relative-home", "linux"); err == nil {
		t.Fatal("relative home directory was accepted")
	}
	if installerURL("windows", "1.0.0") != "https://raw.githubusercontent.com/fengwk/kk-studio/v1.0.0/scripts/daemon/install.ps1" {
		t.Fatal("Windows installer URL used the wrong script")
	}
	if runtime.GOOS != "windows" {
		if executable, err := exec.LookPath("true"); err == nil {
			if err := launchJob(context.Background(), executable, nil); err != nil {
				t.Fatalf("successful OS launcher result = %v", err)
			}
		}
		if executable, err := exec.LookPath("false"); err == nil {
			if err := launchJob(context.Background(), executable, nil); err == nil {
				t.Fatal("failing OS launcher returned success")
			}
		}
	}
}

// RUN-T-218 keeps the packaged Unix runner syntactically valid as a standalone Bash job.
func TestUnixRunnerScriptParses(t *testing.T) {
	bash, err := exec.LookPath("bash")
	if err != nil {
		t.Skip("bash is unavailable on this host")
	}
	if output, err := exec.Command(bash, "-n", "-c", unixRunner).CombinedOutput(); err != nil {
		t.Fatalf("Unix update runner did not parse: %v: %s", err, output)
	}
}

func decodePowerShell(value string) ([]byte, error) {
	return base64.StdEncoding.DecodeString(value)
}

func decodeUTF16(value []byte) string {
	units := make([]uint16, len(value)/2)
	for index := range units {
		units[index] = binary.LittleEndian.Uint16(value[index*2:])
	}
	return string(utf16.Decode(units))
}
