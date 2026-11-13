package coding

import (
	"context"
	"errors"
	"io"
	"strings"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

func TestProcessOutputClosesBeforeExitConfirmation(t *testing.T) {
	var events []string
	closeProcessOutputBeforeExitConfirmation(
		func() { events = append(events, "close") },
		true,
		func() { events = append(events, "confirm") },
	)
	if got := strings.Join(events, ","); got != "close,confirm" {
		t.Fatalf("lifecycle=%q", got)
	}
	events = nil
	closeProcessOutputBeforeExitConfirmation(
		func() { events = append(events, "close") },
		false,
		func() { events = append(events, "confirm") },
	)
	if got := strings.Join(events, ","); got != "close" {
		t.Fatalf("unconverged lifecycle=%q", got)
	}
}

type startErrorSupervisor struct{ err error }

func (s startErrorSupervisor) Start(context.Context, native.ProcessSpec) (native.ManagedProcess, error) {
	return nil, s.err
}
func (startErrorSupervisor) Close(context.Context) error { return nil }

// COD-F-016
func TestExecuteProcessClassifiesStartAndWaitFailures(t *testing.T) {
	workdir := t.TempDir()
	for _, test := range []struct {
		name        string
		err         error
		wantConfirm bool
	}{
		{"start-failed", errors.New("start failed"), true},
		{"scope-not-converged", native.ErrScopeNotConverged, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			confirmed := 0
			capability := runtime.CapabilityContext{Config: config.DaemonConfig{DataDir: t.TempDir()}, Processes: startErrorSupervisor{test.err}, ConfirmExecutionExit: func() { confirmed++ }}
			result, err := ExecuteProcess(context.Background(), runtime.CapabilityCall{InvocationID: test.name}, capability, "true", workdir)
			if err != nil || !result.Error || !strings.Contains(string(result.Details), `"code":"FAILED"`) || !strings.Contains(result.Contents[0].Text, "Whether the tool took effect") {
				t.Fatalf("result=%#v err=%v", result, err)
			}
			want := 0
			if test.wantConfirm {
				want = 1
			}
			if confirmed != want {
				t.Fatalf("confirmed=%d want=%d", confirmed, want)
			}
		})
	}
	process := &fakeProcess{stdout: io.NopCloser(strings.NewReader("partial")), stderr: io.NopCloser(strings.NewReader("")), wait: func(context.Context) (native.ProcessExit, error) {
		return native.ProcessExit{}, errors.New("wait failed")
	}}
	capability := runtime.CapabilityContext{Config: config.DaemonConfig{DataDir: t.TempDir()}, Processes: fakeSupervisor{process}}
	result, err := ExecuteProcess(context.Background(), runtime.CapabilityCall{InvocationID: "wait-failed"}, capability, "true", workdir)
	if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "wait failed") {
		t.Fatalf("wait result=%#v err=%v", result, err)
	}
}

// COD-F-016
func TestExecuteProcessReportsSignalAndUsesConfiguredShell(t *testing.T) {
	workdir := t.TempDir()
	signal := "SIGTERM"
	process := &fakeProcess{stdout: io.NopCloser(strings.NewReader("finished")), stderr: io.NopCloser(strings.NewReader("")), wait: func(context.Context) (native.ProcessExit, error) { return native.ProcessExit{Signal: &signal}, nil }}
	capability := runtime.CapabilityContext{Config: config.DaemonConfig{DataDir: t.TempDir(), BashExecutable: stringPointer("/bin/custom-bash")}, Processes: fakeSupervisor{process}}
	result, err := ExecuteProcess(context.Background(), runtime.CapabilityCall{InvocationID: "signal"}, capability, "true", workdir)
	if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "finished") || !strings.Contains(result.Contents[0].Text, "[Command was terminated by signal SIGTERM.]") || !strings.Contains(string(result.Details), `"outcome":"EXITED"`) || !strings.Contains(string(result.Details), `"signal":"SIGTERM"`) {
		t.Fatalf("signal result=%#v err=%v", result, err)
	}
}

func stringPointer(value string) *string { return &value }

// COD-F-016
func TestExecuteProcessReportsNonzeroExitCode(t *testing.T) {
	workdir := t.TempDir()
	process := &fakeProcess{stdout: io.NopCloser(strings.NewReader("failed")), stderr: io.NopCloser(strings.NewReader("")), wait: func(context.Context) (native.ProcessExit, error) { return testProcessExit(7), nil }}
	capability := runtime.CapabilityContext{Config: config.DaemonConfig{DataDir: t.TempDir()}, Processes: fakeSupervisor{process}}
	result, err := ExecuteProcess(context.Background(), runtime.CapabilityCall{InvocationID: "exit-seven"}, capability, "false", workdir)
	if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "failed") || !strings.Contains(result.Contents[0].Text, "[Command exited with code 7.]") || !strings.Contains(string(result.Details), `"outcome":"EXITED"`) || !strings.Contains(string(result.Details), `"exitCode":7`) {
		t.Fatalf("exit result=%#v err=%v", result, err)
	}
}

// COD-F-017
func TestProcessSnapshotDisablesEmitterAfterProgressError(t *testing.T) {
	calls := 0
	emitter := &processEmitter{callID: "snapshot-error", progress: func(runtime.CapabilityResult) error { calls++; return errors.New("progress unavailable") }, stopped: func() bool { return false }, lastFlush: time.Now()}
	emitter.Snapshot("snapshot")
	emitter.Snapshot("ignored")
	if calls != 1 || !emitter.disabled {
		t.Fatalf("progress calls=%d disabled=%v", calls, emitter.disabled)
	}
}

// COD-F-017
func TestProcessSnapshotSkipsStoppedAndEmptyUpdates(t *testing.T) {
	calls := 0
	emitter := &processEmitter{callID: "stopped", progress: func(runtime.CapabilityResult) error { calls++; return nil }, stopped: func() bool { return true }, lastFlush: time.Now()}
	emitter.Snapshot("ignored")
	emitter.stopped = func() bool { return false }
	emitter.Snapshot("")
	if calls != 0 {
		t.Fatalf("progress calls=%d", calls)
	}
}

// COD-F-017
func TestProcessSnapshotNoOpsWithoutProgressEmitter(t *testing.T) {
	emitter := &processEmitter{callID: "no-progress", lastFlush: time.Now()}
	emitter.Snapshot("snapshot")
	if emitter.nextOffset != 0 || len(emitter.pending) != 0 {
		t.Fatalf("emitter state=%#v", emitter)
	}
}
