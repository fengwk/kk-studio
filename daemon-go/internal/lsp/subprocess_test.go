package lsp

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/native"
)

const subprocessHelperMode = "KK_LSP_SUBPROCESS_HELPER"

// LSP-T-069 through LSP-T-072 use the Go test binary as an isolated supervised child.
func TestLSPSubprocessHelper(t *testing.T) {
	switch os.Getenv(subprocessHelperMode) {
	case "env":
		result := mustJSON(map[string]string{
			"coverage":  os.Getenv("GOCOVERDIR"),
			"inherited": os.Getenv("KK_LSP_INHERITED"),
			"override":  os.Getenv("KK_LSP_OVERRIDE"),
		})
		_ = os.WriteFile(os.Getenv("KK_LSP_RESULT_FILE"), []byte(result), 0600)
	case "lsp":
		serveSubprocessLSP()
	}
}

func mustJSON(value any) string {
	data, err := json.Marshal(value)
	if err != nil {
		panic(err)
	}
	return string(data)
}

func serveSubprocessLSP() {
	codec := newLSPCodec()
	reader := bufio.NewReader(os.Stdin)
	for {
		var raw json.RawMessage
		if err := codec.ReadObject(reader, &raw); err != nil {
			return
		}
		var request struct {
			ID     json.RawMessage `json:"id"`
			Method string          `json:"method"`
		}
		if json.Unmarshal(raw, &request) != nil {
			return
		}
		if len(request.ID) == 0 {
			if request.Method == "exit" {
				return
			}
			continue
		}
		var result any
		switch request.Method {
		case "initialize":
			result = map[string]any{"capabilities": map[string]any{
				"definitionProvider":      true,
				"workspaceSymbolProvider": true,
				"positionEncoding":        "utf-16",
				"experimental":            map[string]string{"inherited": os.Getenv("KK_LSP_INHERITED")},
			}}
		case "textDocument/definition":
			result = []any{}
		case "shutdown":
			result = nil
		}
		if err := codec.WriteObject(os.Stdout, map[string]any{"jsonrpc": "2.0", "id": json.RawMessage(request.ID), "result": result}); err != nil {
			return
		}
	}
}

type execHelperSupervisor struct {
	mu        sync.Mutex
	processes []*execHelperProcess
}

func (s *execHelperSupervisor) Start(ctx context.Context, spec native.ProcessSpec) (native.ManagedProcess, error) {
	cmd := exec.CommandContext(ctx, spec.Executable, spec.Args...)
	cmd.Dir = spec.Directory
	if spec.Env != nil {
		cmd.Env = mergeHelperEnvironment(os.Environ(), spec.Env)
	}
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		_ = stdin.Close()
		return nil, err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		_ = stdin.Close()
		_ = stdout.Close()
		return nil, err
	}
	if err = cmd.Start(); err != nil {
		_ = stdin.Close()
		_ = stdout.Close()
		_ = stderr.Close()
		return nil, err
	}
	process := &execHelperProcess{cmd: cmd, stdin: stdin, stdout: stdout, stderr: stderr, done: make(chan struct{})}
	go process.waitForExit()
	s.mu.Lock()
	s.processes = append(s.processes, process)
	s.mu.Unlock()
	return process, nil
}

func mergeHelperEnvironment(parent, overrides []string) []string {
	values := make(map[string]string, len(parent)+len(overrides))
	for _, item := range append(append([]string(nil), parent...), overrides...) {
		key, value, ok := strings.Cut(item, "=")
		if ok {
			values[key] = value
		}
	}
	delete(values, "GOCOVERDIR")
	merged := make([]string, 0, len(values))
	for key, value := range values {
		merged = append(merged, key+"="+value)
	}
	return merged
}

type execHelperProcess struct {
	cmd        *exec.Cmd
	stdin      io.WriteCloser
	stdout     io.ReadCloser
	stderr     io.ReadCloser
	stdinOnce  sync.Once
	waitResult native.ProcessExit
	waitErr    error
	done       chan struct{}
}

func (p *execHelperProcess) PID() int              { return p.cmd.Process.Pid }
func (p *execHelperProcess) Stdin() io.WriteCloser { return p.stdin }
func (p *execHelperProcess) Stdout() io.ReadCloser { return p.stdout }
func (p *execHelperProcess) Stderr() io.ReadCloser { return p.stderr }
func (p *execHelperProcess) waitForExit() {
	err := p.cmd.Wait()
	if err != nil {
		var exitErr *exec.ExitError
		if errors.As(err, &exitErr) {
			p.waitResult.Code = exitErr.ExitCode()
		} else {
			p.waitErr = err
		}
	}
	close(p.done)
}
func (p *execHelperProcess) Wait(ctx context.Context) (native.ProcessExit, error) {
	select {
	case <-p.done:
		return p.waitResult, p.waitErr
	case <-ctx.Done():
		return native.ProcessExit{}, ctx.Err()
	}
}
func (p *execHelperProcess) Terminate(ctx context.Context, grace time.Duration) error {
	p.stdinOnce.Do(func() { _ = p.stdin.Close() })
	if grace <= 0 {
		select {
		case <-p.done:
			return p.waitErr
		default:
			if err := p.cmd.Process.Kill(); err != nil && !errors.Is(err, os.ErrProcessDone) {
				return err
			}
		}
	} else {
		timer := time.NewTimer(grace)
		defer timer.Stop()
		select {
		case <-p.done:
			return p.waitErr
		case <-ctx.Done():
			_ = p.cmd.Process.Kill()
		case <-timer.C:
			_ = p.cmd.Process.Kill()
		}
	}
	<-p.done
	return p.waitErr
}
func (s *execHelperSupervisor) Close(ctx context.Context) error {
	s.mu.Lock()
	processes := append([]*execHelperProcess(nil), s.processes...)
	s.mu.Unlock()
	var failures []error
	for _, process := range processes {
		if err := process.Terminate(ctx, time.Second); err != nil {
			failures = append(failures, err)
		}
	}
	return errors.Join(failures...)
}

// LSP-T-069 checks child environment isolation without mutating the parent environment.
func TestSubprocessSupervisor_Environment(t *testing.T) {
	temp := t.TempDir()
	keys := []string{"GOCOVERDIR", "KK_LSP_INHERITED", "KK_LSP_OVERRIDE"}
	previous := make(map[string]string, len(keys))
	present := make(map[string]bool, len(keys))
	for _, key := range keys {
		previous[key], present[key] = os.LookupEnv(key)
	}
	t.Cleanup(func() {
		for _, key := range keys {
			if present[key] {
				_ = os.Setenv(key, previous[key])
			} else {
				_ = os.Unsetenv(key)
			}
		}
	})
	inheritedCoverage := temp + string(os.PathSeparator) + "inherited"
	explicitCoverage := temp + string(os.PathSeparator) + "explicit"
	if err := os.Setenv("GOCOVERDIR", inheritedCoverage); err != nil {
		t.Fatal(err)
	}
	if err := os.Setenv("KK_LSP_INHERITED", "inherited"); err != nil {
		t.Fatal(err)
	}
	if err := os.Setenv("KK_LSP_OVERRIDE", "parent"); err != nil {
		t.Fatal(err)
	}
	for _, explicit := range []bool{false, true} {
		supervisor := &execHelperSupervisor{}
		resultFile := filepath.Join(temp, "result")
		env := []string{subprocessHelperMode + "=env", "KK_LSP_OVERRIDE=child", "KK_LSP_RESULT_FILE=" + resultFile}
		if explicit {
			env = append(env, "GOCOVERDIR="+explicitCoverage)
		}
		process, err := supervisor.Start(context.Background(), native.ProcessSpec{Executable: os.Args[0], Args: []string{"-test.run=^TestLSPSubprocessHelper$"}, Directory: temp, Env: env})
		if err != nil {
			t.Fatal(err)
		}
		if _, err = process.Wait(context.Background()); err != nil {
			t.Fatal(err)
		}
		if err = supervisor.Close(context.Background()); err != nil {
			t.Fatal(err)
		}
		data, err := os.ReadFile(resultFile)
		if err != nil {
			t.Fatal(err)
		}
		var result map[string]string
		if err = json.Unmarshal(data, &result); err != nil {
			t.Fatal(err)
		}
		if result["coverage"] != "" || result["inherited"] != "inherited" || result["override"] != "child" {
			t.Fatalf("child environment = %#v", result)
		}
		if got, _ := os.LookupEnv("KK_LSP_OVERRIDE"); got != "parent" {
			t.Fatalf("parent override changed to %q", got)
		}
		if _, err = os.Stat(inheritedCoverage); !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("inherited coverage path exists: %v", err)
		}
		if _, err = os.Stat(explicitCoverage); !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("explicit coverage path exists: %v", err)
		}
	}
}

// LSP-T-070 and LSP-T-072 verify a helper LSP process exits and inherits environment on stop.
func TestSubprocessSupervisor_LSPLifecycle(t *testing.T) {
	const inherited = "go-lsp-helper-env"
	previous, wasSet := os.LookupEnv("KK_LSP_INHERITED")
	if err := os.Setenv(subprocessHelperMode, "lsp"); err != nil {
		t.Fatal(err)
	}
	if err := os.Setenv("KK_LSP_INHERITED", inherited); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = os.Unsetenv(subprocessHelperMode)
		if wasSet {
			_ = os.Setenv("KK_LSP_INHERITED", previous)
		} else {
			_ = os.Unsetenv("KK_LSP_INHERITED")
		}
	})
	supervisor := &execHelperSupervisor{}
	client, err := Launch(context.Background(), ServerConfig{ID: "helper", Command: []string{os.Args[0], "-test.run=^TestLSPSubprocessHelper$"}}, t.TempDir(), os.Args[0], supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err = client.Initialize(context.Background(), 5*time.Second); err != nil {
		t.Fatal(err)
	}
	var capabilities map[string]json.RawMessage
	client.mu.RLock()
	capabilities = client.capabilities
	client.mu.RUnlock()
	var experimental map[string]string
	if err = json.Unmarshal(capabilities["experimental"], &experimental); err != nil || experimental["inherited"] != inherited {
		t.Fatalf("inherited environment capability=%v err=%v", experimental, err)
	}
	if err = client.Stop(time.Second); err != nil {
		t.Fatalf("stop error=%#v cause=%v", err, errors.Unwrap(err))
	}
	if _, err = supervisor.processes[0].Wait(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err = supervisor.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
}

// LSP-T-071 ensures a failed spawn does not leave work for supervisor close to hang on.
func TestSubprocessSupervisor_FailedSpawnSettles(t *testing.T) {
	supervisor := &execHelperSupervisor{}
	missing := os.TempDir() + string(os.PathSeparator) + "missing-lsp-helper-executable"
	_, err := Launch(context.Background(), ServerConfig{ID: "missing", Command: []string{missing}}, t.TempDir(), missing, supervisor)
	var bootstrap *LspBootstrapError
	if !errors.As(err, &bootstrap) || !bootstrap.Converged || !strings.Contains(bootstrap.Error(), "cannot be started") {
		t.Fatalf("failed spawn error = %#v", err)
	}
	closed := make(chan error, 1)
	go func() { closed <- supervisor.Close(context.Background()) }()
	select {
	case err = <-closed:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("supervisor close hung after failed spawn")
	}
}
