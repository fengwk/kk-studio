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
	process := &fakeProcess{stdout: io.NopCloser(strings.NewReader(output)), stderr: io.NopCloser(strings.NewReader("")), wait: func(context.Context) (native.ProcessExit, error) { return testProcessExit(0), nil }}
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

// COD-F-016
func TestProcessUnknownExitStatusDoesNotAssumeZero(t *testing.T) {
	dataDir := t.TempDir()
	process := &fakeProcess{stdout: io.NopCloser(strings.NewReader("output")), stderr: io.NopCloser(strings.NewReader("")), wait: func(context.Context) (native.ProcessExit, error) { return native.ProcessExit{}, nil }}
	result, err := ExecuteProcess(context.Background(), runtime.CapabilityCall{InvocationID: "unknown-exit"}, runtime.CapabilityContext{Config: config.DaemonConfig{DataDir: dataDir}, Processes: fakeSupervisor{process}}, "true", dataDir)
	if err != nil || result.Error || result.Contents[0].Text != "output" {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	var details map[string]any
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	processDetails := details["process"].(map[string]any)
	if processDetails["outcome"] != "EXITED" || processDetails["exitCode"] != nil || processDetails["signal"] != nil {
		t.Fatalf("unknown status was reported as known: %v", processDetails)
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

// COD-T-145
func TestProcessCapabilityRejectsMissingCommand(t *testing.T) {
	call := runtime.CapabilityCall{InvocationID: "missing", Arguments: json.RawMessage(`{"command":" "}`)}
	result, err := processCapability(context.Background(), call, runtime.CapabilityContext{})
	if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "command must be a non-empty string") {
		t.Fatalf("result=%#v err=%v", result, err)
	}
}

// COD-T-144
func TestProcessCapabilityRejectsInvalidWorkdirBeforeStarting(t *testing.T) {
	arguments, err := json.Marshal(map[string]string{"command": "true", "workdir": filepath.Join(t.TempDir(), "missing")})
	if err != nil {
		t.Fatal(err)
	}
	call := runtime.CapabilityCall{InvocationID: "bad-workdir", Arguments: arguments}
	result, err := processCapability(context.Background(), call, runtime.CapabilityContext{})
	if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "workdir is not a directory") {
		t.Fatalf("result=%#v err=%v", result, err)
	}
}

// COD-T-149
func TestProcessProgressFailureDoesNotStopOutputCapture(t *testing.T) {
	dataDir := t.TempDir()
	output := strings.Repeat("progress-failure\n", 6000)
	process := &fakeProcess{stdout: io.NopCloser(strings.NewReader(output)), stderr: io.NopCloser(strings.NewReader("")), wait: func(context.Context) (native.ProcessExit, error) { return testProcessExit(0), nil }}
	progressCalls := 0
	result, err := ExecuteProcess(context.Background(), runtime.CapabilityCall{InvocationID: "progress-failure"}, runtime.CapabilityContext{Config: config.DaemonConfig{DataDir: dataDir}, Processes: fakeSupervisor{process}, Progress: func(runtime.CapabilityResult) error {
		progressCalls++
		return errors.New("progress transport unavailable")
	}}, "printf output", dataDir)
	if err != nil || result.Error {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	var details struct {
		TextOutput struct {
			Path string `json:"path"`
		} `json:"textOutput"`
	}
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	captured, err := os.ReadFile(details.TextOutput.Path)
	if err != nil || string(captured) != output {
		t.Fatalf("captured=%d want=%d err=%v", len(captured), len(output), err)
	}
	if progressCalls != 1 {
		t.Fatalf("progress calls=%d want=1", progressCalls)
	}
}

// COD-T-148
func TestProcessEmitterAppendOffsetsAndSnapshot(t *testing.T) {
	var partials []runtime.CapabilityResult
	emitter := &processEmitter{callID: "partial", progress: func(result runtime.CapabilityResult) error {
		partials = append(partials, result)
		return nil
	}, stopped: func() bool { return false }}
	emitter.Write([]byte("prefix"))
	emitter.Flush()
	emitter.Snapshot("bounded tail")
	if len(partials) != 2 {
		t.Fatalf("partials=%d", len(partials))
	}
	var appendDetails, snapshotDetails map[string]any
	if err := json.Unmarshal(partials[0].Details, &appendDetails); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(partials[1].Details, &snapshotDetails); err != nil {
		t.Fatal(err)
	}
	if appendDetails["mode"] != "APPEND" || appendDetails["startOffset"] != float64(0) || appendDetails["endOffset"] != float64(6) || partials[0].Contents[0].Text != "prefix" {
		t.Fatalf("append=%v content=%q", appendDetails, partials[0].Contents[0].Text)
	}
	if snapshotDetails["mode"] != "SNAPSHOT" || snapshotDetails["startOffset"] != float64(6) || snapshotDetails["endOffset"] != float64(6) || partials[1].Contents[0].Text != "bounded tail" {
		t.Fatalf("snapshot=%v content=%q", snapshotDetails, partials[1].Contents[0].Text)
	}
}

// COD-T-150
func TestProcessConvergenceFailureDoesNotConfirmExecutionExit(t *testing.T) {
	process := &fakeProcess{stdout: io.NopCloser(strings.NewReader("")), stderr: io.NopCloser(strings.NewReader("")), wait: func(context.Context) (native.ProcessExit, error) { return testProcessExit(0), nil }, terminateErr: errors.New("scope still active")}
	confirmed := 0
	result, err := ExecuteProcess(context.Background(), runtime.CapabilityCall{InvocationID: "not-converged"}, runtime.CapabilityContext{Config: config.DaemonConfig{DataDir: t.TempDir()}, Processes: fakeSupervisor{process}, ConfirmExecutionExit: func() { confirmed++ }}, "true", t.TempDir())
	if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "process scope did not converge; processes may still be running") {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	if confirmed != 0 {
		t.Fatalf("execution exit confirmations=%d want=0", confirmed)
	}
}

// COD-T-151
func TestProcessReadErrorPreservesCapturedPrefix(t *testing.T) {
	dataDir := t.TempDir()
	process := &fakeProcess{
		stdout: &failingReadCloser{data: []byte("captured prefix")},
		stderr: io.NopCloser(strings.NewReader("")),
		wait:   func(context.Context) (native.ProcessExit, error) { return testProcessExit(0), nil },
	}
	result, err := ExecuteProcess(context.Background(), runtime.CapabilityCall{InvocationID: "read-error"}, runtime.CapabilityContext{Config: config.DaemonConfig{DataDir: dataDir}, Processes: fakeSupervisor{process}}, "true", dataDir)
	if err != nil || !result.Error {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	text := result.Contents[0].Text
	if !strings.HasPrefix(text, "captured prefix") || !strings.Contains(text, "the process output stream failed while reading.") {
		t.Fatalf("text=%q", text)
	}
}

func testProcessExit(code int) native.ProcessExit {
	return native.ProcessExit{Code: &code}
}
