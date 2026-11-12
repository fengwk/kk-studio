package coding

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

type testWriteCloser struct{ bytes.Buffer }

func (testWriteCloser) Close() error { return nil }

type fakeProcess struct {
	stdout, stderr io.ReadCloser
	wait           func(context.Context) (native.ProcessExit, error)
	mu             sync.Mutex
	terminated     int
	terminateErr   error
}

func (p *fakeProcess) PID() int                                             { return 1 }
func (p *fakeProcess) Stdin() io.WriteCloser                                { return &testWriteCloser{} }
func (p *fakeProcess) Stdout() io.ReadCloser                                { return p.stdout }
func (p *fakeProcess) Stderr() io.ReadCloser                                { return p.stderr }
func (p *fakeProcess) Wait(ctx context.Context) (native.ProcessExit, error) { return p.wait(ctx) }
func (p *fakeProcess) Terminate(context.Context, time.Duration) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.terminated++
	return p.terminateErr
}

type fakeSupervisor struct{ process *fakeProcess }

type failingReadCloser struct{ data []byte }

func (r *failingReadCloser) Read(p []byte) (int, error) {
	if len(r.data) == 0 {
		return 0, errors.New("read failure")
	}
	n := copy(p, r.data)
	r.data = r.data[n:]
	return n, errors.New("read failure")
}
func (*failingReadCloser) Close() error { return nil }

func (s fakeSupervisor) Start(context.Context, native.ProcessSpec) (native.ManagedProcess, error) {
	return s.process, nil
}
func (fakeSupervisor) Close(context.Context) error { return nil }

// COD-T-128
func TestProcessMergesOutputSpillsAndEmitsProgress(t *testing.T) {
	dataDir := t.TempDir()
	output := strings.Repeat("x", 100*1024)
	process := &fakeProcess{stdout: io.NopCloser(strings.NewReader(output)), stderr: io.NopCloser(strings.NewReader("")), wait: func(context.Context) (native.ProcessExit, error) { return native.ProcessExit{Code: 0}, nil }}
	var progress []runtime.CapabilityResult
	confirmed := 0
	result, err := ExecuteProcess(context.Background(), runtime.CapabilityCall{InvocationID: "call"}, runtime.CapabilityContext{Config: config.DaemonConfig{DataDir: dataDir}, Processes: fakeSupervisor{process}, Progress: func(r runtime.CapabilityResult) error { progress = append(progress, r); return nil }, ConfirmExecutionExit: func() { confirmed++ }}, "printf output", dataDir)
	if err != nil || result.Error {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	var details struct {
		TextOutput struct {
			Path       string `json:"path"`
			TotalBytes int64  `json:"totalBytes"`
		} `json:"textOutput"`
		Process struct {
			Outcome  string `json:"outcome"`
			ExitCode int    `json:"exitCode"`
		} `json:"process"`
	}
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	captured, err := os.ReadFile(details.TextOutput.Path)
	if err != nil {
		t.Fatal(err)
	}
	if string(captured) != output || details.TextOutput.TotalBytes != int64(len(output)) || details.Process.Outcome != "EXITED" || details.Process.ExitCode != 0 {
		t.Fatalf("details=%s captured=%d", result.Details, len(captured))
	}
	if confirmed != 1 {
		t.Fatalf("execution exit confirmations=%d want=1", confirmed)
	}
	if len(progress) == 0 {
		t.Fatal("expected live output partial")
	}
	var partial struct {
		Kind     string `json:"kind"`
		Mode     string `json:"mode"`
		Start    int64  `json:"startOffset"`
		End      int64  `json:"endOffset"`
		Observed int64  `json:"observedBytes"`
	}
	if err := json.Unmarshal(progress[0].Details, &partial); err != nil {
		t.Fatal(err)
	}
	if partial.Kind != "process.output" || partial.Mode != "APPEND" || partial.Start != 0 || partial.End <= 0 || partial.Observed < int64(len(progress[0].Contents[0].Text)) {
		t.Fatalf("partial=%#v", partial)
	}
}

// COD-T-131
func TestProcessTimeoutTerminatesAndReportsTimeoutOutcome(t *testing.T) {
	dataDir := t.TempDir()
	started := make(chan struct{})
	process := &fakeProcess{stdout: io.NopCloser(strings.NewReader("started\n")), stderr: io.NopCloser(strings.NewReader("")), wait: func(ctx context.Context) (native.ProcessExit, error) {
		close(started)
		<-ctx.Done()
		return native.ProcessExit{}, ctx.Err()
	}}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
	defer cancel()
	result, err := ExecuteProcess(ctx, runtime.CapabilityCall{InvocationID: "timeout", TimeoutMillis: 10}, runtime.CapabilityContext{Config: config.DaemonConfig{DataDir: dataDir}, Processes: fakeSupervisor{process}}, "sleep 1", dataDir)
	if err != nil || !result.Error {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	<-started
	var details struct {
		Process struct {
			Outcome  string `json:"outcome"`
			ExitCode *int   `json:"exitCode"`
		} `json:"process"`
	}
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	if details.Process.Outcome != "TIMED_OUT" || details.Process.ExitCode != nil || !strings.Contains(result.Contents[0].Text, "[Command timed out after 10 ms and was terminated.") {
		t.Fatalf("result=%#v", result)
	}
	process.mu.Lock()
	terminated := process.terminated
	process.mu.Unlock()
	if terminated != 1 {
		t.Fatalf("terminate calls=%d", terminated)
	}
}

// COD-T-134
func TestProcessAlreadyCancelledHasTerminalDetails(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	confirmed := 0
	result, err := ExecuteProcess(ctx, runtime.CapabilityCall{InvocationID: "cancel"}, runtime.CapabilityContext{ConfirmExecutionExit: func() { confirmed++ }}, "true", filepath.Clean(t.TempDir()))
	if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "[Operation cancelled by the caller.") {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	var details struct {
		Process struct {
			Outcome  string `json:"outcome"`
			ExitCode *int   `json:"exitCode"`
		} `json:"process"`
	}
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	if details.Process.Outcome != "CANCELLED" || details.Process.ExitCode != nil {
		t.Fatalf("details=%s", result.Details)
	}
	if confirmed != 1 {
		t.Fatalf("execution exit confirmations=%d want=1", confirmed)
	}
}

// COD-T-151
func TestProcessReadErrorPreservesCapturedPrefix(t *testing.T) {
	dataDir := t.TempDir()
	process := &fakeProcess{
		stdout: &failingReadCloser{data: []byte("captured prefix")},
		stderr: io.NopCloser(strings.NewReader("")),
		wait:   func(context.Context) (native.ProcessExit, error) { return native.ProcessExit{Code: 0}, nil },
	}
	result, err := ExecuteProcess(context.Background(), runtime.CapabilityCall{InvocationID: "read-error"}, runtime.CapabilityContext{Config: config.DaemonConfig{DataDir: dataDir}, Processes: fakeSupervisor{process}}, "true", dataDir)
	if err != nil || !result.Error {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	text := result.Contents[0].Text
	if !strings.HasPrefix(text, "captured prefix") || !strings.Contains(text, "[the process output stream failed while reading]") {
		t.Fatalf("text=%q", text)
	}
}
