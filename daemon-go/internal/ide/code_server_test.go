package ide

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
)

// IDE-T-031 verifies process creation, health, and login failures never expose a usable session.
func TestCodeServerStartupFailureBoundaries(t *testing.T) {
	for _, test := range []struct {
		name      string
		configure func(*fakeSupervisor)
	}{
		{"process start", func(supervisor *fakeSupervisor) { supervisor.startErr = errors.New("native start failed") }},
		{"login status", func(supervisor *fakeSupervisor) { supervisor.loginStatus = http.StatusUnauthorized }},
		{"missing cookie", func(supervisor *fakeSupervisor) { supervisor.omitLoginCookie = true }},
	} {
		t.Run(test.name, func(t *testing.T) {
			root := t.TempDir()
			installFakeDistribution(t, root)
			supervisor := &fakeSupervisor{}
			test.configure(supervisor)
			manager := NewCodeServerManager(fakeIDEConfig(root), supervisor, BridgeCredentials{})
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			if _, err := manager.EnsureStarted(ctx); FailureCode(err) != "UNAVAILABLE" {
				t.Fatalf("startup error=%v", err)
			}
			if manager.Session() != nil {
				t.Fatal("failed startup retained a session")
			}
			if err := manager.Close(context.Background()); err != nil {
				t.Fatal(err)
			}
		})
	}
}

// IDE-T-031 verifies an early process exit aborts startup without leaving a managed process.
func TestCodeServerExitDuringHealthCheck(t *testing.T) {
	root := t.TempDir()
	installFakeDistribution(t, root)
	supervisor := &fakeSupervisor{healthStatus: http.StatusServiceUnavailable}
	manager := NewCodeServerManager(fakeIDEConfig(root), supervisor, BridgeCredentials{})
	result := make(chan error, 1)
	go func() { _, err := manager.EnsureStarted(context.Background()); result <- err }()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		supervisor.mu.Lock()
		process := supervisor.proc
		supervisor.mu.Unlock()
		if process != nil {
			process.exit <- native.ProcessExit{Code: intPointer(7)}
			break
		}
		time.Sleep(time.Millisecond)
	}
	select {
	case err := <-result:
		if FailureCode(err) != "UNAVAILABLE" || SafeMessage(err) != "code-server exited during startup" {
			t.Fatalf("startup exit error=%v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("startup did not observe process exit")
	}
	if err := manager.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
}

// IDE-T-031 verifies an unexpected process exit is reported once and the manager can restart.
func TestCodeServerUnexpectedExitRestart(t *testing.T) {
	root := t.TempDir()
	installFakeDistribution(t, root)
	supervisor := &fakeSupervisor{}
	manager := NewCodeServerManager(fakeIDEConfig(root), supervisor, BridgeCredentials{}).(*codeServerManager)
	exited := make(chan uint64, 1)
	manager.OnUnexpectedExit(func(generation uint64) { exited <- generation })
	first, err := manager.EnsureStarted(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	supervisor.mu.Lock()
	process := supervisor.proc
	supervisor.mu.Unlock()
	process.exit <- native.ProcessExit{Code: intPointer(5)}
	select {
	case generation := <-exited:
		if generation != first.Generation {
			t.Fatalf("exit generation=%d want %d", generation, first.Generation)
		}
	case <-time.After(time.Second):
		t.Fatal("unexpected exit was not reported")
	}
	second, err := manager.EnsureStarted(context.Background())
	if err != nil || second.Generation <= first.Generation {
		t.Fatalf("restart session=%+v err=%v", second, err)
	}
	if err := manager.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
}

// IDE-T-030 verifies derived private paths and the fixed bundled Node entrypoint.
func TestPathsAndLaunchResolution(t *testing.T) {
	root := t.TempDir()
	cfg := config.DaemonConfig{DataDir: filepath.Join(root, "data"), DistributionDir: root}
	paths := IDEPathsFor(cfg)
	if paths.CodeServerDir != filepath.Join(root, "data", "ide", "code-server") || paths.ConfigFile != filepath.Join(paths.CodeServerDir, "config.yaml") {
		t.Fatalf("unexpected paths: %#v", paths)
	}
	nodeName := "node"
	if os.PathSeparator == '\\' {
		nodeName = "node.exe"
	}
	executable := filepath.Join(root, "runtime", "code-server", "lib", nodeName)
	entry := filepath.Join(root, "runtime", "code-server", "out", "node", "entry.js")
	if err := os.MkdirAll(filepath.Dir(executable), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Dir(entry), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(executable, []byte("fixture"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(entry, []byte("// fake code-server"), 0o600); err != nil {
		t.Fatal(err)
	}
	launch, err := ResolveCodeServerLaunch(root)
	if err != nil || launch.Executable != executable || len(launch.Args) != 1 || launch.Args[0] != entry {
		t.Fatalf("launch = %#v, err = %v", launch, err)
	}
	if _, err := ResolveCodeServerLaunch(filepath.Join(root, "missing")); err == nil || err.Error() != "code-server runtime is not installed" {
		t.Fatalf("missing runtime error = %v", err)
	}
	partial := filepath.Join(root, "partial")
	partialNode := filepath.Join(partial, "runtime", "code-server", "lib", nodeName)
	if err := os.MkdirAll(filepath.Dir(partialNode), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(partialNode, []byte("fixture"), 0o700); err != nil {
		t.Fatal(err)
	}
	if _, err := ResolveCodeServerLaunch(partial); err == nil || err.Error() != "code-server runtime is not installed" {
		t.Fatalf("missing entrypoint error=%v", err)
	}
}

// IDE-T-029 verifies a short, private POSIX session socket directory and cleanup after close.
func TestPrivateShortIPC(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Unix session socket is not used on Windows")
	}
	root := t.TempDir()
	installFakeDistribution(t, root)
	supervisor := &fakeSupervisor{}
	manager := NewCodeServerManager(fakeIDEConfig(root), supervisor, BridgeCredentials{})
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if _, err := manager.EnsureStarted(ctx); err != nil {
		t.Fatal(err)
	}
	supervisor.mu.Lock()
	spec := supervisor.specs[0]
	supervisor.mu.Unlock()
	socket := ""
	for index, arg := range spec.Args {
		if arg == "--session-socket" && index+1 < len(spec.Args) {
			socket = spec.Args[index+1]
		}
	}
	if socket == "" || len(socket) > 107 {
		t.Fatalf("session socket path length = %d: %q", len(socket), socket)
	}
	directory := filepath.Dir(socket)
	info, err := os.Stat(directory)
	if err != nil || info.Mode().Perm() != 0o700 {
		t.Fatalf("IPC directory info = %v, err = %v", info, err)
	}
	if err := manager.Close(ctx); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(directory); !os.IsNotExist(err) {
		t.Fatalf("IPC directory remains after close: %v", err)
	}
}

// IDE-T-031 verifies one shared loopback session, bounded diagnostics, and convergent close.
func TestStartDiagnosticsClose(t *testing.T) {
	root := t.TempDir()
	cfg := fakeIDEConfig(root)
	installFakeDistribution(t, root)
	supervisor := &fakeSupervisor{}
	manager := NewCodeServerManager(cfg, supervisor, BridgeCredentials{URL: "http://127.0.0.1:1", Token: "bridge-secret"})
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	first, err := manager.EnsureStarted(ctx)
	if err != nil {
		t.Fatal(err)
	}
	second, err := manager.EnsureStarted(ctx)
	if err != nil || *first != *second || first.Host != "127.0.0.1" || first.Port < 1 || first.Cookie != "code-server-session=fake" {
		t.Fatalf("session = %#v, second = %#v, err = %v", first, second, err)
	}
	if len(manager.Diagnostics()) == 0 || len(manager.Diagnostics()) > 4*1024 {
		t.Fatalf("diagnostics length = %d", len(manager.Diagnostics()))
	}
	if got := supervisor.count(); got != 1 {
		t.Fatalf("process starts = %d", got)
	}
	if err := manager.Stop(ctx); err != nil {
		t.Fatal(err)
	}
	if manager.Session() != nil {
		t.Fatal("session remains after stop")
	}
	if _, err := manager.EnsureStarted(ctx); err != nil {
		t.Fatalf("restart after stop: %v", err)
	}
	if got := supervisor.count(); got != 2 {
		t.Fatalf("process starts after restart = %d", got)
	}
	if err := manager.Close(ctx); err != nil {
		t.Fatal(err)
	}
	if manager.Session() != nil {
		t.Fatal("session remains after close")
	}
	if _, err := manager.EnsureStarted(ctx); err == nil || err.Error() != "ide service is closed" {
		t.Fatalf("start after close error = %v", err)
	}
}

// IDE-T-031 verifies diagnostic bounds count decoded characters rather than UTF-8 bytes.
func TestDiagnosticTailUsesCharacterLimit(t *testing.T) {
	var tail diagnosticTail
	tail.push(strings.Repeat("界", 4*1024+1))
	if got, want := tail.value.String(), strings.Repeat("界", 4*1024); got != want {
		t.Fatalf("diagnostic tail has %d bytes, want %d decoded characters", len(got), 4*1024)
	}
}

func fakeIDEConfig(root string) config.DaemonConfig {
	return config.DaemonConfig{DataDir: root, DistributionDir: root}
}

func installFakeDistribution(t *testing.T, root string) {
	t.Helper()
	nodeName := "node"
	if os.PathSeparator == '\\' {
		nodeName = "node.exe"
	}
	for _, path := range []string{
		filepath.Join(root, "runtime", "code-server", "lib", nodeName),
		filepath.Join(root, "runtime", "code-server", "out", "node", "entry.js"),
		filepath.Join(root, "ide-extension", "bridge.cjs"),
	} {
		if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte("fake"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
}

type fakeSupervisor struct {
	mu               sync.Mutex
	countN           int
	proc             *fakeProcess
	specs            []native.ProcessSpec
	startErr         error
	startHook        func(context.Context)
	healthStatus     int
	loginStatus      int
	omitLoginCookie  bool
	resetLogin       bool
	terminateErr     error
	terminateGate    chan struct{}
	terminateEntered chan struct{}
	terminateOnce    sync.Once
}

func (s *fakeSupervisor) Start(ctx context.Context, spec native.ProcessSpec) (native.ManagedProcess, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.startErr != nil {
		return nil, s.startErr
	}
	if s.startHook != nil {
		s.startHook(ctx)
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	s.countN++
	s.specs = append(s.specs, spec)
	port := 0
	for i, arg := range spec.Args {
		if arg == "--bind-addr" && i+1 < len(spec.Args) {
			_, value, _ := strings.Cut(spec.Args[i+1], ":")
			port, _ = strconv.Atoi(value)
		}
	}
	listener, err := net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if err != nil {
		return nil, err
	}
	stdoutR, stdoutW := io.Pipe()
	stderrR, stderrW := io.Pipe()
	go func() { _, _ = stdoutW.Write([]byte(strings.Repeat("diagnostic", 800))) }()
	password := ""
	configPath := ""
	for i, arg := range spec.Args {
		if arg == "--config" && i+1 < len(spec.Args) {
			configPath = spec.Args[i+1]
		}
	}
	contents, err := os.ReadFile(configPath)
	if err != nil {
		listener.Close()
		return nil, err
	}
	for _, line := range strings.Split(string(contents), "\n") {
		if strings.HasPrefix(line, "password: ") {
			password = strings.TrimPrefix(line, "password: ")
		}
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		status := s.healthStatus
		if status == 0 {
			status = http.StatusOK
		}
		w.WriteHeader(status)
	})
	mux.HandleFunc("/login", func(w http.ResponseWriter, r *http.Request) {
		if s.resetLogin {
			connection, _, err := w.(http.Hijacker).Hijack()
			if err == nil {
				_ = connection.Close()
			}
			return
		}
		_ = r.ParseForm()
		if r.Form.Get("password") != password {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		if s.loginStatus != 0 {
			w.WriteHeader(s.loginStatus)
			return
		}
		if !s.omitLoginCookie {
			http.SetCookie(w, &http.Cookie{Name: "code-server-session", Value: "fake", Path: "/"})
		}
		w.WriteHeader(http.StatusFound)
	})
	server := &http.Server{Handler: mux}
	process := &fakeProcess{pid: 312 + s.countN, stdout: stdoutR, stderr: stderrR, stdoutW: stdoutW, stderrW: stderrW, server: server, listener: listener, exit: make(chan native.ProcessExit, 1), terminateErr: s.terminateErr, terminateGate: s.terminateGate, terminateEntered: s.terminateEntered, terminateOnce: &s.terminateOnce}
	s.proc = process
	go server.Serve(listener)
	return process, nil
}

func (s *fakeSupervisor) Close(context.Context) error { return nil }
func (s *fakeSupervisor) count() int                  { s.mu.Lock(); defer s.mu.Unlock(); return s.countN }

func intPointer(value int) *int { return &value }

type fakeProcess struct {
	pid              int
	stdout, stderr   io.ReadCloser
	stdoutW, stderrW *io.PipeWriter
	server           *http.Server
	listener         net.Listener
	exit             chan native.ProcessExit
	terminateErr     error
	terminateCalls   int
	terminateGate    chan struct{}
	terminateEntered chan struct{}
	terminateOnce    *sync.Once
	once             sync.Once
}

func (p *fakeProcess) PID() int              { return p.pid }
func (p *fakeProcess) Stdin() io.WriteCloser { return fakeWriteCloser{} }

type fakeWriteCloser struct{}

func (fakeWriteCloser) Write(value []byte) (int, error) { return len(value), nil }
func (fakeWriteCloser) Close() error                    { return nil }
func (p *fakeProcess) Stdout() io.ReadCloser            { return p.stdout }
func (p *fakeProcess) Stderr() io.ReadCloser            { return p.stderr }
func (p *fakeProcess) Wait(ctx context.Context) (native.ProcessExit, error) {
	select {
	case result := <-p.exit:
		return result, nil
	case <-ctx.Done():
		return native.ProcessExit{}, ctx.Err()
	}
}
func (p *fakeProcess) Terminate(ctx context.Context, _ time.Duration) error {
	p.terminateCalls++
	if p.terminateEntered != nil && p.terminateOnce != nil {
		p.terminateOnce.Do(func() { close(p.terminateEntered) })
	}
	if p.terminateGate != nil {
		select {
		case <-p.terminateGate:
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	if p.terminateErr != nil {
		return p.terminateErr
	}
	p.once.Do(func() {
		_ = p.server.Close()
		_ = p.listener.Close()
		_ = p.stdoutW.Close()
		_ = p.stderrW.Close()
		p.exit <- native.ProcessExit{Code: intPointer(0)}
	})
	select {
	case <-ctx.Done():
		return ctx.Err()
	default:
		return nil
	}
}
