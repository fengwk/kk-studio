package ide

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
)

const (
	startupTimeout       = 30 * time.Second
	healthRequestTimeout = 2 * time.Second
	loginRequestTimeout  = 5 * time.Second
	managementTimeout    = 15 * time.Second
	managementBodyLimit  = 4 << 20
	managementBase       = "/__kk/ide/views"
	bridgeExtensionName  = "kk-studio-ide-bridge"
	bridgeDigestFile     = ".kk-studio-digest"
)

// IDEPaths names the private persistent paths owned by the IDE service.
type IDEPaths struct {
	IDEDir        string
	CodeServerDir string
	UserDataDir   string
	ExtensionsDir string
	ConfigFile    string
	ViewsDir      string
}

// CodeServerSession identifies one ready, authenticated code-server generation.
type CodeServerSession struct {
	Host            string
	Port            int
	Cookie          string
	PID             int
	Generation      uint64
	ManagementToken string
}

// ManagedViewState is the native extension's externally visible view lifecycle state.
type ManagedViewState string

const (
	ManagedViewOpen          ManagedViewState = "OPEN"
	ManagedViewClosing       ManagedViewState = "CLOSING"
	ManagedViewCleanupFailed ManagedViewState = "CLEANUP_FAILED"
	ManagedViewClosed        ManagedViewState = "CLOSED"
)

// ManagedViewCloseMode selects normal editor confirmation or an explicit cleanup mode.
type ManagedViewCloseMode string

const (
	ManagedViewCloseNormal  ManagedViewCloseMode = "normal"
	ManagedViewCloseDiscard ManagedViewCloseMode = "discard"
	ManagedViewCloseExpired ManagedViewCloseMode = "expired"
)

// ManagedViewInspection is a safe snapshot returned by the code-server management extension.
type ManagedViewInspection struct {
	ViewID        string           `json:"viewId"`
	BindingEpoch  string           `json:"bindingEpoch"`
	WorkspacePath string           `json:"workspacePath"`
	State         ManagedViewState `json:"state"`
	Failures      []string         `json:"failures,omitempty"`
}

// ManagedViewCloseResult includes the cleanup outcome of a managed view close.
type ManagedViewCloseResult struct {
	ManagedViewInspection
	Mode    ManagedViewCloseMode `json:"-"`
	Cleanup struct {
		Complete                      bool `json:"complete"`
		HTTPReleased                  int  `json:"httpReleased,omitempty"`
		WebsocketsReleased            int  `json:"websocketsReleased,omitempty"`
		ManagementConnectionsReleased int  `json:"managementConnectionsReleased,omitempty"`
		ExtensionHostsReleased        int  `json:"extensionHostsReleased,omitempty"`
		TerminalsReleased             int  `json:"terminalsReleased,omitempty"`
	} `json:"cleanup"`
}

// BridgeCredentials are available only to the supervised extension host.
type BridgeCredentials struct {
	URL   string
	Token string
}

// CodeServerManager owns one private code-server process and its management API.
type CodeServerManager interface {
	EnsureStarted(context.Context) (*CodeServerSession, error)
	Session() *CodeServerSession
	Diagnostics() string
	Stop(context.Context) error
	Close(context.Context) error
	RegisterView(context.Context, string, int64, string) (*ManagedViewInspection, error)
	InspectView(context.Context, string, int64) (*ManagedViewInspection, error)
	CloseView(context.Context, string, int64, ManagedViewCloseMode) (*ManagedViewCloseResult, error)
	ListViews(context.Context) ([]ManagedViewInspection, error)
	OnUnexpectedExit(func(uint64))
}

// CodeServerLaunch is the fixed bundled Node executable and entrypoint.
type CodeServerLaunch struct {
	Executable string
	Args       []string
}

// IDEPathsFor derives private IDE storage below the configured daemon data directory.
func IDEPathsFor(cfg config.DaemonConfig) IDEPaths {
	ideDir := filepath.Join(cfg.DataDir, "ide")
	codeServerDir := filepath.Join(ideDir, "code-server")
	return IDEPaths{
		IDEDir: ideDir, CodeServerDir: codeServerDir,
		UserDataDir:   filepath.Join(codeServerDir, "user-data"),
		ExtensionsDir: filepath.Join(codeServerDir, "extensions"),
		ConfigFile:    filepath.Join(codeServerDir, "config.yaml"),
		ViewsDir:      filepath.Join(ideDir, "views"),
	}
}

// ResolveCodeServerLaunch resolves only the bundled Node distribution; system Node is not used.
func ResolveCodeServerLaunch(distributionDir string) (CodeServerLaunch, error) {
	runtimeDir := filepath.Join(distributionDir, "runtime", "code-server")
	nodeName := "node"
	if runtime.GOOS == "windows" {
		nodeName = "node.exe"
	}
	executable := filepath.Join(runtimeDir, "lib", nodeName)
	entry := filepath.Join(runtimeDir, "out", "node", "entry.js")
	if _, err := os.Stat(executable); err != nil {
		return CodeServerLaunch{}, NewError("code-server runtime is not installed", "UNAVAILABLE")
	}
	if _, err := os.Stat(entry); err != nil {
		return CodeServerLaunch{}, NewError("code-server runtime is not installed", "UNAVAILABLE")
	}
	return CodeServerLaunch{Executable: executable, Args: []string{entry}}, nil
}

type codeServerManager struct {
	cfg                        config.DaemonConfig
	paths                      IDEPaths
	processes                  native.Supervisor
	bridge                     BridgeCredentials
	client                     *http.Client
	mu                         sync.Mutex
	session                    *CodeServerSession
	process                    native.ManagedProcess
	processExit                chan struct{}
	processExitedDuringStartup bool
	starting                   chan struct{}
	startErr                   error
	startCancel                context.CancelFunc
	stopping                   chan struct{}
	stopErr                    error
	closed                     bool
	intentional                bool
	ipcDirectory               string
	generation                 uint64
	listeners                  []func(uint64)
	diagnostics                diagnosticTail
}

// NewCodeServerManager creates a lazy, restartable manager backed by the native supervisor.
func NewCodeServerManager(cfg config.DaemonConfig, processes native.Supervisor, bridge BridgeCredentials) CodeServerManager {
	return &codeServerManager{
		cfg: cfg, paths: IDEPathsFor(cfg), processes: processes, bridge: bridge,
		client: &http.Client{Transport: &http.Transport{Proxy: nil, DialContext: (&net.Dialer{Timeout: 2 * time.Second}).DialContext}, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }},
	}
}

// EnsureStarted shares a startup attempt; canceling a waiter does not cancel the service-owned start.
func (m *codeServerManager) EnsureStarted(ctx context.Context) (*CodeServerSession, error) {
	for {
		if err := ctx.Err(); err != nil {
			return nil, ErrCancelled
		}
		m.mu.Lock()
		if m.closed {
			m.mu.Unlock()
			return nil, NewError("ide service is closed", "UNAVAILABLE")
		}
		if m.stopping != nil {
			done := m.stopping
			m.mu.Unlock()
			select {
			case <-ctx.Done():
				return nil, ErrCancelled
			case <-done:
				m.mu.Lock()
				err := m.stopErr
				m.mu.Unlock()
				if err != nil {
					return nil, err
				}
				continue
			}
		}
		if m.session != nil {
			session := cloneSession(m.session)
			m.mu.Unlock()
			return session, nil
		}
		if m.starting == nil {
			startCtx, cancel := context.WithTimeout(context.Background(), startupTimeout)
			m.starting = make(chan struct{})
			m.startCancel = cancel
			m.startErr = nil
			go m.runStart(startCtx)
		}
		done := m.starting
		m.mu.Unlock()
		select {
		case <-ctx.Done():
			return nil, ErrCancelled
		case <-done:
			m.mu.Lock()
			err, session := m.startErr, cloneSession(m.session)
			m.mu.Unlock()
			if err != nil {
				return nil, err
			}
			if session != nil {
				return session, nil
			}
		}
	}
}

func (m *codeServerManager) runStart(ctx context.Context) {
	session, err := m.start(ctx)
	m.mu.Lock()
	if err == nil && m.processExitedDuringStartup {
		err = NewError("code-server exited during startup", "UNAVAILABLE")
	}
	if err == nil {
		m.session = session
	}
	m.startErr = err
	cancel := m.startCancel
	m.startCancel = nil
	done := m.starting
	m.starting = nil
	close(done)
	m.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func (m *codeServerManager) start(ctx context.Context) (*CodeServerSession, error) {
	m.mu.Lock()
	previous := m.process
	m.mu.Unlock()
	if previous != nil {
		if err := previous.Terminate(ctx, 5*time.Second); err != nil {
			return nil, NewError("code-server process scope did not converge", "UNAVAILABLE")
		}
		m.mu.Lock()
		if m.process == previous {
			m.process = nil
		}
		m.mu.Unlock()
	}
	launch, err := ResolveCodeServerLaunch(m.cfg.DistributionDir)
	if err != nil {
		return nil, err
	}
	for _, dir := range []string{m.paths.CodeServerDir, m.paths.UserDataDir, m.paths.ExtensionsDir, m.paths.ViewsDir} {
		if err := native.EnsurePrivateDirectory(dir); err != nil {
			return nil, NewError("code-server storage is unavailable", "UNAVAILABLE")
		}
	}
	if err := installBridgeExtension(m.cfg.DistributionDir, m.paths.ExtensionsDir); err != nil {
		return nil, err
	}
	port, err := allocateLoopbackPort()
	if err != nil {
		return nil, NewError("code-server port is unavailable", "UNAVAILABLE")
	}
	password, err := randomToken()
	if err != nil {
		return nil, NewError("code-server startup failed", "UNAVAILABLE")
	}
	managementToken, err := randomToken()
	if err != nil {
		return nil, NewError("code-server startup failed", "UNAVAILABLE")
	}
	configData := []byte(fmt.Sprintf("bind-addr: 127.0.0.1:%d\nauth: password\npassword: %s\ncert: false\n", port, password))
	if err := native.WritePrivateFile(m.paths.ConfigFile, configData); err != nil {
		return nil, NewError("code-server storage is unavailable", "UNAVAILABLE")
	}
	args := append([]string(nil), launch.Args...)
	if runtime.GOOS != "windows" {
		m.mu.Lock()
		ipcDirectory := m.ipcDirectory
		m.mu.Unlock()
		if ipcDirectory == "" {
			ipcDirectory, err = os.MkdirTemp("/tmp", "kk-studio-ide-")
			if err != nil {
				return nil, NewError("code-server IPC storage is unavailable", "UNAVAILABLE")
			}
			if err := os.Chmod(ipcDirectory, 0o700); err != nil {
				_ = os.RemoveAll(ipcDirectory)
				return nil, NewError("code-server IPC storage is unavailable", "UNAVAILABLE")
			}
			m.mu.Lock()
			m.ipcDirectory = ipcDirectory
			m.mu.Unlock()
		}
		args = append(args, "--session-socket", filepath.Join(ipcDirectory, "session.sock"))
	}
	args = append(args, "--config", m.paths.ConfigFile, "--bind-addr", fmt.Sprintf("127.0.0.1:%d", port), "--auth", "password", "--user-data-dir", m.paths.UserDataDir, "--extensions-dir", m.paths.ExtensionsDir, "--disable-telemetry", "--disable-update-check", "--disable-workspace-trust", "--disable-getting-started-override")
	process, err := m.processes.Start(ctx, native.ProcessSpec{
		Executable: launch.Executable, Args: args, Directory: m.paths.CodeServerDir,
		Env: mergeEnvironment(os.Environ(), map[string]string{
			"KK_STUDIO_IDE_BRIDGE_URL":       m.bridge.URL,
			"KK_STUDIO_IDE_BRIDGE_TOKEN":     m.bridge.Token,
			"KK_STUDIO_IDE_MANAGEMENT_TOKEN": managementToken,
		}),
	})
	if err != nil {
		return nil, NewError("code-server could not be started", "UNAVAILABLE")
	}
	processExit := make(chan struct{})
	m.mu.Lock()
	m.process = process
	m.processExit = processExit
	m.processExitedDuringStartup = false
	m.generation++
	generation := m.generation
	m.intentional = false
	m.mu.Unlock()
	go m.drain(process.Stdout())
	go m.drain(process.Stderr())
	go m.watchExit(process, generation, false)
	if err := m.waitForHealth(ctx, port, processExit); err != nil {
		if terminateErr := process.Terminate(context.Background(), 5*time.Second); terminateErr != nil {
			return nil, NewError(fmt.Sprintf("code-server startup failed (%s) and could not be terminated", SafeMessage(err)), "UNAVAILABLE")
		}
		m.mu.Lock()
		if m.process == process {
			m.process = nil
		}
		m.mu.Unlock()
		return nil, err
	}
	cookie, err := m.login(ctx, port, password)
	if err != nil {
		if terminateErr := process.Terminate(context.Background(), 5*time.Second); terminateErr != nil {
			return nil, NewError(fmt.Sprintf("code-server startup failed (%s) and could not be terminated", SafeMessage(err)), "UNAVAILABLE")
		}
		m.mu.Lock()
		if m.process == process {
			m.process = nil
		}
		m.mu.Unlock()
		return nil, err
	}
	return &CodeServerSession{Host: "127.0.0.1", Port: port, Cookie: cookie, PID: process.PID(), Generation: generation, ManagementToken: managementToken}, nil
}

func (m *codeServerManager) waitForHealth(ctx context.Context, port int, processExit <-chan struct{}) error {
	ticker := time.NewTicker(50 * time.Millisecond)
	defer ticker.Stop()
	deadline := time.NewTimer(startupTimeout)
	defer deadline.Stop()
	for {
		requestCtx, cancel := context.WithTimeout(ctx, healthRequestTimeout)
		req, err := http.NewRequestWithContext(requestCtx, http.MethodGet, fmt.Sprintf("http://127.0.0.1:%d/healthz", port), nil)
		if err == nil {
			res, requestErr := m.client.Do(req)
			if requestErr == nil {
				_ = res.Body.Close()
				if res.StatusCode >= 200 && res.StatusCode < 300 {
					cancel()
					return nil
				}
			}
		}
		cancel()
		select {
		case <-ctx.Done():
			return ErrCancelled
		case <-processExit:
			return NewError("code-server exited during startup", "UNAVAILABLE")
		case <-deadline.C:
			return NewError("code-server did not become ready", "UNAVAILABLE")
		case <-ticker.C:
		}
	}
}

func (m *codeServerManager) login(ctx context.Context, port int, password string) (string, error) {
	requestCtx, cancel := context.WithTimeout(ctx, loginRequestTimeout)
	defer cancel()
	form := url.Values{"password": {password}}
	req, err := http.NewRequestWithContext(requestCtx, http.MethodPost, fmt.Sprintf("http://127.0.0.1:%d/login", port), strings.NewReader(form.Encode()))
	if err != nil {
		return "", NewError("code-server login failed", "UNAVAILABLE")
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	res, err := m.client.Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return "", ErrCancelled
		}
		return "", NewError("code-server login failed", "UNAVAILABLE")
	}
	defer res.Body.Close()
	for _, cookie := range res.Cookies() {
		if cookie.Name == "code-server-session" {
			return cookie.Name + "=" + cookie.Value, nil
		}
	}
	return "", NewError("code-server login failed", "UNAVAILABLE")
}

func (m *codeServerManager) watchExit(process native.ManagedProcess, generation uint64, intentional bool) {
	_, err := process.Wait(context.Background())
	m.mu.Lock()
	if m.process != process {
		m.mu.Unlock()
		return
	}
	if m.processExit != nil {
		close(m.processExit)
		m.processExit = nil
	}
	m.session = nil
	if m.starting != nil {
		m.processExitedDuringStartup = true
	}
	listeners := append([]func(uint64){}, m.listeners...)
	shouldNotify := err == nil && !intentional && !m.intentional && m.starting == nil && !m.closed
	m.mu.Unlock()
	if shouldNotify {
		for _, listener := range listeners {
			func() {
				defer func() { _ = recover() }()
				listener(generation)
			}()
		}
	}
}

func (m *codeServerManager) Session() *CodeServerSession {
	m.mu.Lock()
	defer m.mu.Unlock()
	return cloneSession(m.session)
}

func (m *codeServerManager) Diagnostics() string {
	m.diagnostics.mu.Lock()
	defer m.diagnostics.mu.Unlock()
	return m.diagnostics.value.String()
}

func (m *codeServerManager) Stop(ctx context.Context) error  { return m.stop(ctx, false) }
func (m *codeServerManager) Close(ctx context.Context) error { return m.stop(ctx, true) }

func (m *codeServerManager) stop(ctx context.Context, closeManager bool) error {
	m.mu.Lock()
	if closeManager {
		m.closed = true
	}
	if m.stopping != nil {
		done := m.stopping
		m.mu.Unlock()
		select {
		case <-ctx.Done():
			return ErrCancelled
		case <-done:
			m.mu.Lock()
			err := m.stopErr
			m.mu.Unlock()
			return err
		}
	}
	done := make(chan struct{})
	m.stopping = done
	m.stopErr = nil
	m.intentional = true
	cancelStart := m.startCancel
	starting := m.starting
	m.mu.Unlock()
	if cancelStart != nil {
		cancelStart()
	}
	if starting != nil {
		select {
		case <-ctx.Done():
			m.finishStop(done, ErrCancelled)
			return ErrCancelled
		case <-starting:
		}
	}
	m.mu.Lock()
	process := m.process
	m.session = nil
	m.mu.Unlock()
	var err error
	if process != nil {
		err = process.Terminate(ctx, 5*time.Second)
		if err == nil {
			m.mu.Lock()
			if m.process == process {
				m.process = nil
			}
			m.mu.Unlock()
		}
	}
	if err == nil {
		m.mu.Lock()
		ipcDirectory := m.ipcDirectory
		m.mu.Unlock()
		if ipcDirectory != "" {
			if removeErr := os.RemoveAll(ipcDirectory); removeErr != nil {
				err = NewError("code-server IPC storage could not be removed", "UNAVAILABLE")
			} else {
				m.mu.Lock()
				m.ipcDirectory = ""
				m.mu.Unlock()
			}
		}
	}
	m.finishStop(done, err)
	return err
}

func (m *codeServerManager) finishStop(done chan struct{}, err error) {
	m.mu.Lock()
	m.stopErr = err
	if m.stopping == done {
		m.stopping = nil
	}
	close(done)
	m.mu.Unlock()
}

func (m *codeServerManager) OnUnexpectedExit(listener func(uint64)) {
	m.mu.Lock()
	m.listeners = append(m.listeners, listener)
	m.mu.Unlock()
}

func (m *codeServerManager) RegisterView(ctx context.Context, viewID string, epoch int64, workspace string) (*ManagedViewInspection, error) {
	if _, err := ValidateViewID(viewID); err != nil {
		return nil, err
	}
	if epoch <= 0 {
		return nil, NewError("bindingEpoch must be a positive decimal string", "UNAVAILABLE")
	}
	return m.viewRequest(ctx, http.MethodPost, "/"+url.PathEscape(viewID), map[string]any{"bindingEpoch": strconv.FormatInt(epoch, 10), "workspacePath": workspace})
}
func (m *codeServerManager) InspectView(ctx context.Context, viewID string, epoch int64) (*ManagedViewInspection, error) {
	if _, err := ValidateViewID(viewID); err != nil {
		return nil, err
	}
	return m.viewRequest(ctx, http.MethodGet, "/"+url.PathEscape(viewID)+"?bindingEpoch="+strconv.FormatInt(epoch, 10), nil)
}
func (m *codeServerManager) CloseView(ctx context.Context, viewID string, epoch int64, mode ManagedViewCloseMode) (*ManagedViewCloseResult, error) {
	if _, err := ValidateViewID(viewID); err != nil {
		return nil, err
	}
	body, status, err := m.managementRequest(ctx, http.MethodDelete, "/"+url.PathEscape(viewID), map[string]any{"bindingEpoch": strconv.FormatInt(epoch, 10), "mode": mode})
	if err != nil {
		return nil, err
	}
	if status != http.StatusOK && status != http.StatusInternalServerError {
		return nil, managedFailure(status, body)
	}
	var result ManagedViewCloseResult
	if err := json.Unmarshal(body, &result); err != nil || result.ViewID == "" {
		return nil, NewError("managed code-server returned an invalid close result", "UNAVAILABLE")
	}
	var cleanupShape struct {
		Cleanup *struct {
			Complete *bool `json:"complete"`
		} `json:"cleanup"`
	}
	if err := json.Unmarshal(body, &cleanupShape); err != nil || cleanupShape.Cleanup == nil || cleanupShape.Cleanup.Complete == nil {
		return nil, NewError("managed code-server returned an invalid close result", "UNAVAILABLE")
	}
	result.Cleanup.Complete = *cleanupShape.Cleanup.Complete
	result.Mode = mode
	return &result, nil
}
func (m *codeServerManager) ListViews(ctx context.Context) ([]ManagedViewInspection, error) {
	body, status, err := m.managementRequest(ctx, http.MethodGet, "", nil)
	if err != nil {
		return nil, err
	}
	if status < 200 || status >= 300 {
		return nil, managedFailure(status, body)
	}
	var result struct {
		Views []ManagedViewInspection `json:"views"`
	}
	if err := json.Unmarshal(body, &result); err != nil || result.Views == nil {
		return nil, NewError("managed code-server returned an invalid view list", "UNAVAILABLE")
	}
	return result.Views, nil
}
func (m *codeServerManager) viewRequest(ctx context.Context, method, path string, payload any) (*ManagedViewInspection, error) {
	body, status, err := m.managementRequest(ctx, method, path, payload)
	if err != nil {
		return nil, err
	}
	if status < 200 || status >= 300 {
		return nil, managedFailure(status, body)
	}
	var view ManagedViewInspection
	if err := json.Unmarshal(body, &view); err != nil || view.ViewID == "" {
		return nil, NewError("managed code-server returned an invalid view", "UNAVAILABLE")
	}
	return &view, nil
}
func (m *codeServerManager) managementRequest(ctx context.Context, method, path string, payload any) ([]byte, int, error) {
	session, err := m.EnsureStarted(ctx)
	if err != nil {
		return nil, 0, err
	}
	requestCtx, cancel := context.WithTimeout(ctx, managementTimeout)
	defer cancel()
	var body io.Reader
	if payload != nil {
		encoded, marshalErr := json.Marshal(payload)
		if marshalErr != nil {
			return nil, 0, NewError("managed code-server request failed", "UNAVAILABLE")
		}
		body = strings.NewReader(string(encoded))
	}
	req, err := http.NewRequestWithContext(requestCtx, method, fmt.Sprintf("http://127.0.0.1:%d%s%s", session.Port, managementBase, path), body)
	if err != nil {
		return nil, 0, NewError("managed code-server request failed", "UNAVAILABLE")
	}
	req.Header.Set("X-KK-Studio-Management-Token", session.ManagementToken)
	if payload != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	res, err := m.client.Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return nil, 0, ErrCancelled
		}
		return nil, 0, NewError("managed code-server is unavailable", "UNAVAILABLE")
	}
	defer res.Body.Close()
	data, err := io.ReadAll(io.LimitReader(res.Body, managementBodyLimit+1))
	if err != nil || len(data) > managementBodyLimit {
		return nil, 0, NewError("managed code-server response is too large", "UNAVAILABLE")
	}
	return data, res.StatusCode, nil
}

func managedFailure(status int, body []byte) error {
	var result struct {
		Error string `json:"error"`
	}
	_ = json.Unmarshal(body, &result)
	code := result.Error
	if len(code) == 0 || len(code) > 64 || code[0] < 'A' || code[0] > 'Z' {
		code = "UNAVAILABLE"
	}
	for _, r := range code {
		if !(r == '_' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9') {
			code = "UNAVAILABLE"
			break
		}
	}
	mapped := "UNAVAILABLE"
	switch {
	case code == "DRAFT_STATE_UNKNOWN":
		mapped = "DIRTY"
	case code == "VIEW_CLEANUP_INCOMPLETE" || strings.HasSuffix(code, "_CLEANUP_FAILED"):
		mapped = "CLEANUP_FAILED"
	case code == "VIEW_NOT_FOUND":
		mapped = "VIEW_GONE"
	case code == "VIEW_BINDING_CONFLICT" || code == "VIEW_EPOCH_ROLLBACK" || code == "VIEW_CLOSED":
		mapped = "VIEW_IN_USE"
	case status >= 500:
		mapped = "CLEANUP_FAILED"
	}
	return NewError("managed view rejected ("+code+")", mapped)
}

func (m *codeServerManager) drain(reader io.Reader) {
	if reader == nil {
		return
	}
	buffer := make([]byte, 1024)
	for {
		n, err := reader.Read(buffer)
		if n > 0 {
			m.diagnostics.push(string(buffer[:n]))
		}
		if err != nil {
			return
		}
	}
}

type diagnosticTail struct {
	mu    sync.Mutex
	value strings.Builder
}

func (d *diagnosticTail) push(value string) {
	d.mu.Lock()
	defer d.mu.Unlock()
	current := d.value.String() + value
	d.value.Reset()
	d.value.WriteString(diagnosticUTF16Tail(current, 4*1024))
}

func diagnosticUTF16Tail(value string, limit int) string {
	units := 0
	start := len(value)
	for start > 0 {
		r, size := utf8.DecodeLastRuneInString(value[:start])
		runeUnits := 1
		if r > 0xffff {
			runeUnits = 2
		}
		if units+runeUnits > limit {
			break
		}
		units += runeUnits
		start -= size
	}
	return value[start:]
}
func allocateLoopbackPort() (int, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	port := listener.Addr().(*net.TCPAddr).Port
	if err := listener.Close(); err != nil {
		return 0, err
	}
	return port, nil
}
func randomToken() (string, error) {
	value := make([]byte, 32)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(value), nil
}
func mergeEnvironment(in []string, values map[string]string) []string {
	result := make([]string, 0, len(in)+len(values))
	for _, entry := range in {
		key, _, ok := strings.Cut(entry, "=")
		if _, replace := values[key]; ok && !replace {
			result = append(result, entry)
		}
	}
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	for _, key := range keys {
		result = append(result, key+"="+values[key])
	}
	return result
}
func cloneSession(session *CodeServerSession) *CodeServerSession {
	if session == nil {
		return nil
	}
	clone := *session
	return &clone
}

func installBridgeExtension(distributionDir, extensionsDir string) error {
	source := filepath.Join(distributionDir, "ide-extension")
	info, err := os.Stat(source)
	if err != nil || !info.IsDir() {
		return NewError("bundled ide bridge extension is missing", "UNAVAILABLE")
	}
	var files []string
	err = filepath.WalkDir(source, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.Type()&os.ModeSymlink != 0 {
			return errors.New("symlink")
		}
		if entry.Type().IsRegular() {
			files = append(files, path)
		}
		return nil
	})
	if err != nil {
		return NewError("bundled ide bridge extension is unavailable", "UNAVAILABLE")
	}
	sort.Strings(files)
	hash := sha256.New()
	for _, path := range files {
		relative, _ := filepath.Rel(source, path)
		_, _ = io.WriteString(hash, relative)
		data, readErr := os.ReadFile(path)
		if readErr != nil {
			return NewError("bundled ide bridge extension is unavailable", "UNAVAILABLE")
		}
		_, _ = hash.Write(data)
	}
	digest := hex.EncodeToString(hash.Sum(nil))
	target := filepath.Join(extensionsDir, bridgeExtensionName)
	marker := filepath.Join(target, bridgeDigestFile)
	if data, readErr := os.ReadFile(marker); readErr == nil && string(data) == digest {
		return nil
	}
	staging, err := os.MkdirTemp(extensionsDir, bridgeExtensionName+".staging-")
	if err != nil {
		return NewError("code-server storage is unavailable", "UNAVAILABLE")
	}
	defer os.RemoveAll(staging)
	for _, path := range files {
		relative, _ := filepath.Rel(source, path)
		destination := filepath.Join(staging, relative)
		if err := os.MkdirAll(filepath.Dir(destination), 0o700); err != nil {
			return NewError("code-server storage is unavailable", "UNAVAILABLE")
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return NewError("bundled ide bridge extension is unavailable", "UNAVAILABLE")
		}
		if err := os.WriteFile(destination, data, 0o600); err != nil {
			return NewError("code-server storage is unavailable", "UNAVAILABLE")
		}
	}
	if err := os.WriteFile(filepath.Join(staging, bridgeDigestFile), []byte(digest), 0o600); err != nil {
		return NewError("code-server storage is unavailable", "UNAVAILABLE")
	}
	if err := os.RemoveAll(target); err != nil {
		return NewError("code-server storage is unavailable", "UNAVAILABLE")
	}
	if err := os.Rename(staging, target); err != nil {
		return NewError("code-server storage is unavailable", "UNAVAILABLE")
	}
	return nil
}
