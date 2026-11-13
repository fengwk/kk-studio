package update

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/binary"
	jsonv2 "encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf16"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
)

const (
	maxInstallerBytes = 1 << 20
	jobReadyTimeout   = 15 * time.Second
	installerTimeout  = 60 * time.Second
)

var (
	canonicalEnvironmentID = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
	errInstallerCleanup    = errors.New("installer preparation cleanup failed")
)

// JobLauncher registers an operating-system job that outlives the daemon service process.
type JobLauncher func(context.Context, string, []string) error

// InstallerRunnerOptions provides host facts and narrow side-effect boundaries for the managed updater.
type InstallerRunnerOptions struct {
	Platform     string
	Architecture string
	Home         string
	TempDir      string
	Environment  map[string]string
	HTTPClient   *http.Client
	Launch       JobLauncher
	Stop         func(context.Context) error
	Log          func(string)
}

// InstallerRunner downloads the pinned upstream installer and hands it to a separate user-owned OS job.
type InstallerRunner struct {
	options InstallerRunnerOptions
}

// NewInstallerRunner creates a runner using the current host and standard library process/network facilities.
func NewInstallerRunner(options InstallerRunnerOptions) *InstallerRunner {
	if options.Platform == "" {
		options.Platform = runtime.GOOS
	}
	if options.Architecture == "" {
		options.Architecture = runtime.GOARCH
	}
	if options.Home == "" {
		options.Home, _ = os.UserHomeDir()
	}
	if options.TempDir == "" {
		options.TempDir = os.TempDir()
	}
	if options.Environment == nil {
		options.Environment = environmentMap(os.Environ())
	}
	if options.HTTPClient == nil {
		options.HTTPClient = http.DefaultClient
	}
	if options.Launch == nil {
		options.Launch = launchJob
	}
	return &InstallerRunner{options: options}
}

func (r *InstallerRunner) Configured() bool {
	return r != nil && r.options.Home != "" && r.options.TempDir != "" && r.options.Launch != nil
}

func (r *InstallerRunner) BusyReason() string { return "" }

func (r *InstallerRunner) StopForUpdate(ctx context.Context) error {
	if r.options.Stop == nil {
		return nil
	}
	return r.options.Stop(ctx)
}

// HandleUpdate prepares an independently running official installer and reports only safe fixed diagnostics.
func (r *InstallerRunner) HandleUpdate(ctx context.Context, command Command, daemonConfig config.DaemonConfig, report func(Progress)) (Outcome, error) {
	outcome, err := r.prepare(ctx, command, daemonConfig, report)
	if err == nil {
		return outcome, nil
	}
	message := "official installer preparation failed; check daemon logs and network"
	if errors.Is(err, errInstallerCleanup) {
		message = "official installer preparation and cleanup failed; check local files"
	}
	if r.options.Log != nil {
		r.options.Log(message)
	}
	return Outcome{Phase: PhaseFailed, Message: message}, nil
}

func (r *InstallerRunner) prepare(ctx context.Context, command Command, daemonConfig config.DaemonConfig, report func(Progress)) (outcome Outcome, resultErr error) {
	if err := ValidateCommand(command); err != nil {
		return Outcome{}, err
	}
	if !canonicalEnvironmentID.MatchString(daemonConfig.EnvironmentID) {
		return Outcome{}, errors.New("the daemon configuration has no canonical environment identity")
	}
	if err := requireUpdatePlatform(r.options.Platform, r.options.Architecture); err != nil {
		return Outcome{}, err
	}
	installerConfig := InstallerConfig{
		EnvironmentID:   daemonConfig.EnvironmentID,
		ProxyURL:        daemonConfig.ProxyURL,
		ConfigPath:      daemonConfig.ConfigPath,
		TokenPath:       daemonConfig.TokenPath,
		DistributionDir: daemonConfig.DistributionDir,
	}
	if err := requireManagedInstallation(installerConfig, r.options.Home, r.options.Platform); err != nil {
		return Outcome{}, err
	}
	environmentRoot := managedEnvironmentRoot(r.options.Home, daemonConfig.EnvironmentID)
	work, err := os.MkdirTemp(r.options.TempDir, "kk-studio-update-")
	if err != nil {
		return Outcome{}, errors.New("cannot create private update job directory")
	}
	if err = native.EnsurePrivateDirectory(work); err != nil {
		if cleanupErr := os.RemoveAll(work); cleanupErr != nil {
			return Outcome{}, errors.Join(errors.New("cannot protect update job directory"), errInstallerCleanup, cleanupErr)
		}
		return Outcome{}, errors.New("cannot protect update job directory")
	}
	jobRegistered := false
	defer func() {
		if !jobRegistered {
			if cleanupErr := os.RemoveAll(work); cleanupErr != nil {
				resultErr = errors.Join(resultErr, errInstallerCleanup, cleanupErr)
			}
		}
	}()
	installerName := "install.sh"
	if r.options.Platform == "windows" {
		installerName = "install.ps1"
	}
	installer := filepath.Join(work, installerName)
	if err := r.downloadInstaller(ctx, installerURL(r.options.Platform, command.TargetVersion), installer, daemonConfig.ProxyURL, report); err != nil {
		return Outcome{}, err
	}
	unit := "kk-studio-daemon-update-" + command.OperationID
	launchCtx, cancel := context.WithTimeout(ctx, jobReadyTimeout)
	defer cancel()
	if r.options.Platform == "windows" {
		action := windowsActionScript(work, installer, environmentRoot, installerConfig, command.TargetVersion, command.OperationID, unit)
		registration := windowsRegistrationScript(action, unit)
		if err := r.options.Launch(launchCtx, "powershell.exe", []string{"-NoProfile", "-NonInteractive", "-EncodedCommand", encodePowerShell(registration)}); err != nil {
			return Outcome{}, errors.New("operating-system update job could not be started")
		}
		jobRegistered = true
	} else {
		runnerPath := filepath.Join(work, "run.sh")
		if err := native.WritePrivateFile(runnerPath, []byte(unixRunner)); err != nil {
			return Outcome{}, errors.New("cannot prepare private update job")
		}
		invocation := unixInvocation(work, installer, environmentRoot, installerConfig, command.TargetVersion, command.OperationID, r.options.Home, r.options.Environment)
		var executable string
		var args []string
		if r.options.Platform == "darwin" {
			executable = "launchctl"
			args = append([]string{"submit", "-l", unit, "--"}, invocation...)
		} else {
			executable = "systemd-run"
			args = append([]string{"--user", "--collect", "--unit=" + unit}, invocation...)
		}
		if err := r.options.Launch(launchCtx, executable, args); err != nil {
			return Outcome{}, errors.New("operating-system update job could not be started")
		}
		jobRegistered = true
	}
	if err := waitForReady(launchCtx, filepath.Join(work, "ready")); err != nil {
		return Outcome{}, errors.New("operating-system update job did not become ready")
	}
	var permitMu sync.Mutex
	permitReleased := false
	lastOperation := filepath.Join(environmentRoot, "last-operation.json")
	return Outcome{
		Phase: PhasePrepared,
		ReleasePermit: func() error {
			permitMu.Lock()
			defer permitMu.Unlock()
			if permitReleased {
				return nil
			}
			if err := requireRegularFile(filepath.Join(work, "ready")); err != nil {
				return errors.New("update job is no longer ready")
			}
			if err := native.WritePrivateFile(filepath.Join(work, "permit"), nil); err != nil {
				return errors.New("update job permit could not be released")
			}
			permitReleased = true
			return nil
		},
		WatchFailure: func(reportFailure func(string)) func() {
			return watchFailure(lastOperation, command.OperationID, reportFailure)
		},
	}, nil
}

func requireUpdatePlatform(platform, architecture string) error {
	key, err := config.ReleaseKey(platform, architecture)
	if err != nil {
		return err
	}
	if key == "windows-arm64" {
		return errors.New("platform is not covered by the official release matrix")
	}
	return nil
}

func managedEnvironmentRoot(home, environmentID string) string {
	return filepath.Join(home, ".kk-studio", "environments", environmentID)
}

func requireManagedInstallation(value InstallerConfig, home, platform string) error {
	if !filepath.IsAbs(home) {
		return errors.New("online update requires the official managed installation")
	}
	root := managedEnvironmentRoot(home, value.EnvironmentID)
	if !equalPath(platform, value.ConfigPath, filepath.Join(root, "daemon.json")) ||
		!equalPath(platform, value.TokenPath, filepath.Join(root, "daemon.token")) ||
		!equalPath(platform, value.DistributionDir, filepath.Join(root, "lib")) {
		return errors.New("online update requires the official managed installation")
	}
	return nil
}

func equalPath(platform, left, right string) bool {
	if left == "" || right == "" {
		return false
	}
	leftAbs, err := filepath.Abs(left)
	if err != nil {
		return false
	}
	rightAbs, err := filepath.Abs(right)
	if err != nil {
		return false
	}
	leftAbs, rightAbs = filepath.Clean(leftAbs), filepath.Clean(rightAbs)
	if platform == "windows" {
		return strings.EqualFold(leftAbs, rightAbs)
	}
	return leftAbs == rightAbs
}

func installerURL(platform, version string) string {
	script := "install.sh"
	if platform == "windows" {
		script = "install.ps1"
	}
	return "https://raw.githubusercontent.com/fengwk/kk-studio/v" + version + "/scripts/daemon/" + script
}

func clientForProxy(client *http.Client, proxyURL *string) (*http.Client, error) {
	if client == nil {
		return nil, errors.New("HTTP client is not configured")
	}
	configured := *client
	transport := client.Transport
	if transport == nil {
		transport = http.DefaultTransport
	}
	standardTransport, ok := transport.(*http.Transport)
	if !ok {
		if proxyURL != nil {
			return nil, errors.New("configured proxy requires an HTTP transport")
		}
		return &configured, nil
	}
	transportCopy := standardTransport.Clone()
	transportCopy.Proxy = nil
	if proxyURL != nil {
		proxy, err := url.Parse(*proxyURL)
		if err != nil {
			return nil, errors.New("invalid platform proxy")
		}
		port, portErr := strconv.Atoi(proxy.Port())
		if proxy.Scheme != "http" || proxy.Host == "" || proxy.Hostname() == "" || portErr != nil || port < 1 || port > 65535 || proxy.User != nil || proxy.Path != "" || proxy.RawQuery != "" || proxy.ForceQuery || proxy.Fragment != "" || proxy.RawFragment != "" || proxy.Opaque != "" || strings.ContainsAny(*proxyURL, "?#") {
			return nil, errors.New("invalid platform proxy")
		}
		transportCopy.Proxy = http.ProxyURL(proxy)
	}
	configured.Transport = transportCopy
	return &configured, nil
}

func (r *InstallerRunner) downloadInstaller(ctx context.Context, source, destination string, proxyURL *string, report func(Progress)) error {
	downloadCtx, cancel := context.WithTimeout(ctx, installerTimeout)
	defer cancel()
	request, err := http.NewRequestWithContext(downloadCtx, http.MethodGet, source, nil)
	if err != nil {
		return errors.New("official installer download failed")
	}
	clientValue, err := clientForProxy(r.options.HTTPClient, proxyURL)
	if err != nil {
		return errors.New("official installer download failed")
	}
	clientValue.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	response, err := clientValue.Do(request)
	if err != nil {
		return errors.New("official installer download failed")
	}
	defer clientValue.CloseIdleConnections()
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return errors.New("official installer download failed")
	}
	var total *int64
	if response.ContentLength >= 0 {
		value := response.ContentLength
		total = &value
	}
	var body bytes.Buffer
	buffer := make([]byte, 32*1024)
	for {
		count, readErr := response.Body.Read(buffer)
		if count > 0 {
			if body.Len()+count > maxInstallerBytes {
				return errors.New("official installer exceeds size limit")
			}
			_, _ = body.Write(buffer[:count])
			if report != nil {
				report(Progress{DownloadedBytes: int64(body.Len()), TotalBytes: total})
			}
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			return errors.New("official installer download failed")
		}
	}
	if body.Len() == 0 {
		return errors.New("official installer is empty")
	}
	if err := native.WritePrivateFile(destination, body.Bytes()); err != nil {
		return errors.New("official installer could not be stored privately")
	}
	return nil
}

func requireRegularFile(path string) error {
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 {
		return errors.New("file is not regular")
	}
	return nil
}

func waitForReady(ctx context.Context, path string) error {
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for {
		if err := requireRegularFile(path); err == nil {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
		}
	}
}

func readBoundedFile(path string, limit int64) ([]byte, error) {
	pathInfo, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !pathInfo.Mode().IsRegular() || pathInfo.Mode()&os.ModeSymlink != 0 {
		return nil, errors.New("diagnostic is not a regular file")
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	fileInfo, err := file.Stat()
	if err != nil || !fileInfo.Mode().IsRegular() || !os.SameFile(pathInfo, fileInfo) {
		return nil, errors.New("diagnostic is not a regular file")
	}
	if limit < 0 || fileInfo.Size() > limit {
		return nil, errors.New("diagnostic exceeds size limit")
	}
	return io.ReadAll(io.LimitReader(file, limit))
}

func watchFailure(path, operationID string, report func(string)) func() {
	stop := make(chan struct{})
	done := make(chan struct{})
	var once sync.Once
	go func() {
		defer close(done)
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-stop:
				return
			case <-ticker.C:
				var record struct {
					OperationID string `json:"operationId"`
					Result      string `json:"result"`
				}
				data, err := readBoundedFile(path, 4097)
				if err != nil || len(data) > 4096 || jsonv2.Unmarshal(data, &record) != nil || record.OperationID != operationID {
					continue
				}
				if record.Result == "failed" && report != nil {
					report("official installer failed; check local last-operation.json")
				}
				return
			}
		}
	}()
	return func() {
		once.Do(func() { close(stop) })
		<-done
	}
}

func unixInvocation(work, installer, environmentRoot string, value InstallerConfig, targetVersion, operationID, home string, environment map[string]string) []string {
	proxy := ""
	if value.ProxyURL != nil {
		proxy = *value.ProxyURL
	}
	path := environment["PATH"]
	if path == "" {
		path = "/usr/bin:/bin"
	}
	args := []string{"env", "-i", "HOME=" + home, "PATH=" + path,
		"https_proxy=" + proxy, "HTTPS_PROXY=" + proxy, "http_proxy=" + proxy, "HTTP_PROXY=" + proxy,
		"no_proxy=", "NO_PROXY=", "KK_STUDIO_UPDATE_OPERATION_ID=" + operationID}
	for _, name := range []string{"XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS"} {
		if environment[name] != "" {
			args = append(args, name+"="+environment[name])
		}
	}
	if timeout := environment["KK_UPDATE_PERMIT_TIMEOUT_SECONDS"]; permitTimeoutPattern.MatchString(timeout) {
		args = append(args, "KK_UPDATE_PERMIT_TIMEOUT_SECONDS="+timeout)
	}
	return append(args, "bash", filepath.Join(work, "run.sh"), work, installer, environmentRoot, operationID, targetVersion, "update", "--environment-id", value.EnvironmentID, "--version", targetVersion)
}

var permitTimeoutPattern = regexp.MustCompile(`^[0-9]{1,4}$`)

const unixRunner = `#!/bin/bash
set -eu
work=$1; installer=$2; envRoot=$3; operationId=$4; targetVersion=$5
shift 5
timeout=${KK_UPDATE_PERMIT_TIMEOUT_SECONDS:-120}
case "$timeout" in ''|*[!0-9]*) timeout=120 ;; esac
if [ "$timeout" -gt 3600 ]; then timeout=120; fi
record_failure() {
  [[ "$operationId" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || return 0
  [[ "$targetVersion" =~ ^[0-9A-Za-z._+-]{1,64}$ ]] || return 0
  [ -d "$envRoot" ] || return 0
  [ ! -L "$envRoot" ] || return 0
  current="$envRoot/last-operation.json"
  if [ -f "$current" ] && grep -Fq '"operationId":"'"$operationId"'"' "$current" 2>/dev/null; then return 0; fi
  time=$(( $(date +%s) * 1000 ))
  json=$(printf '{"operationId":"%s","type":"update","targetVersion":"%s","phase":"job","result":"failed","time":%s,"errorCode":"installer"}' "$operationId" "$targetVersion" "$time")
  (( ${#json} <= 4096 )) || return 0
  tmp="$envRoot/.last-operation.json.tmp.$$"
  if ! printf '%s\n' "$json" >"$tmp" 2>/dev/null || ! chmod 600 "$tmp" 2>/dev/null || ! mv -f "$tmp" "$current" 2>/dev/null; then
    rm -f "$tmp" 2>/dev/null
    echo 'update job: could not record the installer failure' >&2
    return 1
  fi
}
cleanup() {
  code=$?
  trap - EXIT INT TERM
  if ! rm -rf -- "$work"; then echo 'update job: could not remove the update job directory' >&2; [ "$code" -ne 0 ] || code=1; fi
  exit "$code"
}
trap cleanup EXIT INT TERM
: > "$work/ready"
i=0
until [ -f "$work/permit" ]; do
  if [ "$i" -ge "$timeout" ]; then echo 'update receipt permit timed out' >&2; record_failure || :; exit 1; fi
  sleep 1
  i=$((i + 1))
done
set +e
bash "$installer" "$@"
code=$?
set -e
if [ "$code" -ne 0 ]; then record_failure || :; exit "$code"; fi
`

func psQuote(value string) string { return "'" + strings.ReplaceAll(value, "'", "''") + "'" }

func encodePowerShell(script string) string {
	encoded := utf16.Encode([]rune(script))
	data := make([]byte, len(encoded)*2)
	for index, value := range encoded {
		binary.LittleEndian.PutUint16(data[index*2:], value)
	}
	return base64.StdEncoding.EncodeToString(data)
}

func windowsActionScript(work, installer, environmentRoot string, value InstallerConfig, targetVersion, operationID, unit string) string {
	proxy := ""
	if value.ProxyURL != nil {
		proxy = *value.ProxyURL
	}
	ready, permit := psQuote(filepath.Join(work, "ready")), psQuote(filepath.Join(work, "permit"))
	return fmt.Sprintf(`$ErrorActionPreference = 'Stop'
Get-ChildItem Env: | Where-Object { $_.Name -match '^(http|https|all|no)_proxy$' } | Remove-Item
$env:HTTP_PROXY = %s; $env:HTTPS_PROXY = %s; $env:NO_PROXY = ''; $env:KK_DAEMON_INSTALL_PROXY_ENV = '1'
$env:KK_STUDIO_UPDATE_OPERATION_ID = %s
$permitSeconds = 120
if ($env:KK_UPDATE_PERMIT_TIMEOUT_SECONDS -match '^[0-9]{1,4}$') { $permitSeconds = [int] $env:KK_UPDATE_PERMIT_TIMEOUT_SECONDS }
$failed = $true
try {
  [IO.File]::WriteAllText(%s, '')
  $deadline = (Get-Date).AddSeconds($permitSeconds)
  while (-not (Test-Path -LiteralPath %s)) { if ((Get-Date) -ge $deadline) { throw 'update receipt permit timed out' }; Start-Sleep -Seconds 1 }
  & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File %s update -EnvironmentId %s -Version %s
  if ($LASTEXITCODE -ne 0) { throw 'installer failed; see local last-operation.json' }
  $failed = $false
} finally {
  if ($failed) {
    try {
      $root = %s
      $current = Join-Path $root 'last-operation.json'
      $recorded = $false
      if (Test-Path -LiteralPath $current -PathType Leaf) { try { $old = Get-Content -LiteralPath $current -Raw | ConvertFrom-Json; if ($null -ne $old -and $old.operationId -eq %s) { $recorded = $true } } catch { } }
      if (-not $recorded -and (Test-Path -LiteralPath $root -PathType Container)) {
        $json = '{"operationId":"%s","type":"update","targetVersion":"%s","phase":"job","result":"failed","time":' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + ',"errorCode":"installer"}'
        $tmp = Join-Path $root ('.last-operation.json.tmp.' + [Guid]::NewGuid().ToString('N'))
        [IO.File]::WriteAllText($tmp, $json + [Environment]::NewLine)
        Move-Item -LiteralPath $tmp -Destination $current -Force
      }
    } catch { }
  }
  Unregister-ScheduledTask -TaskName %s -Confirm:$false -ErrorAction Continue
  Remove-Item -LiteralPath %s -Recurse -Force -ErrorAction Continue
}`, psQuote(proxy), psQuote(proxy), psQuote(operationID), ready, permit, psQuote(installer), psQuote(value.EnvironmentID), psQuote(targetVersion), psQuote(environmentRoot), psQuote(operationID), operationID, targetVersion, psQuote(unit), psQuote(work))
}

func windowsRegistrationScript(action, unit string) string {
	encodedAction := encodePowerShell(action)
	return fmt.Sprintf(`$ErrorActionPreference = 'Stop'; $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument %s; $principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().User.Value) -LogonType Interactive -RunLevel Limited; Register-ScheduledTask -TaskName %s -Action $action -Principal $principal | Out-Null; try { Start-ScheduledTask -TaskName %s } catch { Unregister-ScheduledTask -TaskName %s -Confirm:$false; throw }`, psQuote("-NoProfile -NonInteractive -EncodedCommand "+encodedAction), psQuote(unit), psQuote(unit), psQuote(unit))
}

func launchJob(ctx context.Context, executable string, args []string) error {
	ctx, cancel := context.WithTimeout(ctx, jobReadyTimeout)
	defer cancel()
	command := exec.CommandContext(ctx, executable, args...)
	command.Stdout = io.Discard
	command.Stderr = io.Discard
	if err := command.Run(); err != nil {
		return errors.New("operating-system update job could not be started")
	}
	return nil
}

func environmentMap(values []string) map[string]string {
	out := make(map[string]string, len(values))
	for _, value := range values {
		name, content, ok := strings.Cut(value, "=")
		if ok {
			out[name] = content
		}
	}
	return out
}

// InstallerConfig is the minimal frozen local context required to verify official update ownership.
type InstallerConfig struct {
	EnvironmentID   string
	ProxyURL        *string
	ConfigPath      string
	TokenPath       string
	DistributionDir string
}

var _ Runner = (*InstallerRunner)(nil)
