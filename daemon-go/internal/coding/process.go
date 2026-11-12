package coding

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

const (
	livePartialBytes  = 64 * 1024
	liveFlushInterval = 150 * time.Millisecond
)

// ExecuteProcess runs the supplied shell command under native process-tree supervision.
func ExecuteProcess(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext, command, workdir string) (runtime.CapabilityResult, error) {
	executionExited := true
	defer func() {
		if executionExited && capability.ConfirmExecutionExit != nil {
			capability.ConfirmExecutionExit()
		}
	}()
	if strings.TrimSpace(command) == "" {
		return errorResult(call.InvocationID, errors.New("command must be a non-empty string")), nil
	}
	if !filepath.IsAbs(workdir) {
		return errorResult(call.InvocationID, errors.New("workdir must be absolute")), nil
	}
	if err := ctx.Err(); err != nil {
		return terminatedBeforeStart(call, err)
	}
	if capability.Processes == nil {
		return errorResult(call.InvocationID, errors.New("process supervisor is unavailable")), nil
	}
	store, err := TextOutputStoreForDataDir(capability.Config.DataDir)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	spool, err := NewOutputSpool(store, call.InvocationID, defaultCaptureBudget)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	defer spool.Close()
	shell := "bash"
	if capability.Config.BashExecutable != nil && *capability.Config.BashExecutable != "" {
		shell = *capability.Config.BashExecutable
	}
	process, err := capability.Processes.Start(ctx, native.ProcessSpec{Executable: shell, Args: []string{"-lc", `cd -- "$1" || exit 1; shift; ` + command, "bash", workdir}, Directory: workdir})
	if err != nil {
		executionExited = !errors.Is(err, native.ErrScopeNotConverged)
		return errorResult(call.InvocationID, err), nil
	}
	executionExited = false
	_ = process.Stdin().Close()
	emitter := &processEmitter{callID: call.InvocationID, progress: capability.Progress, stopped: func() bool { return ctx.Err() != nil }, lastFlush: time.Now()}
	stopProgress := make(chan struct{})
	var progressWait sync.WaitGroup
	if capability.Progress != nil {
		progressWait.Add(1)
		go func() {
			defer progressWait.Done()
			ticker := time.NewTicker(liveFlushInterval)
			defer ticker.Stop()
			for {
				select {
				case <-ticker.C:
					emitter.Flush()
				case <-stopProgress:
					return
				}
			}
		}()
	}
	defer func() { close(stopProgress); progressWait.Wait() }()
	readErrors := make(chan error, 2)
	var terminationOnce sync.Once
	var terminationErr error
	terminate := func() error {
		terminationOnce.Do(func() {
			termCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			terminationErr = process.Terminate(termCtx, 5*time.Second)
		})
		return terminationErr
	}
	var truncationReported atomic.Bool
	var readers sync.WaitGroup
	for _, stream := range []io.ReadCloser{process.Stdout(), process.Stderr()} {
		readers.Add(1)
		go func(r io.ReadCloser) {
			defer readers.Done()
			defer r.Close()
			buffer := make([]byte, 32*1024)
			for {
				n, readErr := r.Read(buffer)
				if n > 0 {
					spool.Write(buffer[:n])
					emitter.Write(buffer[:n])
					if spool.IsCaptureTruncated() && truncationReported.CompareAndSwap(false, true) {
						emitter.Snapshot(spool.CaptureTruncatedTailPreview())
					}
				}
				if readErr != nil {
					if !errors.Is(readErr, io.EOF) {
						readErrors <- readErr
						_ = terminate()
					}
					return
				}
			}
		}(stream)
	}
	exit, waitErr := process.Wait(ctx)
	terminationResult := terminate()
	readers.Wait()
	emitter.Flush()
	if terminationResult == nil && (waitErr == nil || ctx.Err() != nil) {
		executionExited = true
	}
	select {
	case <-readErrors:
		result := spool.Finish(true, "[the process output stream failed while reading]")
		result.Error = true
		var details map[string]any
		if err := json.Unmarshal(result.Details, &details); err != nil {
			details = make(map[string]any)
		}
		details["process"] = map[string]string{"outcome": "EXITED"}
		result.Details = mustJSON(details)
		return result, nil
	default:
	}
	if terminationResult != nil {
		return errorResult(call.InvocationID, errors.New("process scope did not converge; processes may still be running")), nil
	}
	if waitErr != nil && ctx.Err() == nil {
		return errorResult(call.InvocationID, waitErr), nil
	}
	outcome := "EXITED"
	naturalExit := ctx.Err() == nil && waitErr == nil
	var exitCode *int
	note := ""
	failed := false
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		outcome = "TIMED_OUT"
		failed = true
		note = fmt.Sprintf("[Command timed out after %d ms and was terminated. Captured output above may be incomplete; side effects already performed are not rolled back.]", call.TimeoutMillis)
	} else if errors.Is(ctx.Err(), context.Canceled) {
		outcome = "CANCELLED"
		failed = true
		note = "[Operation cancelled by the caller. Captured output above may be incomplete; side effects already performed are not rolled back.]"
	} else if waitErr != nil {
		failed = true
	} else if exit.Signal != "" {
		failed = true
		note = fmt.Sprintf("[Command was terminated by signal %s.]", exit.Signal)
	} else {
		code := exit.Code
		exitCode = &code
		if code != 0 {
			failed = true
			note = fmt.Sprintf("[Command exited with code %d.]", code)
		}
	}
	result := spool.Finish(failed, note)
	var details map[string]any
	if err := json.Unmarshal(result.Details, &details); err != nil {
		details = make(map[string]any)
	}
	processDetails := map[string]any{"outcome": outcome}
	if naturalExit && exitCode != nil {
		processDetails["exitCode"] = *exitCode
	}
	if naturalExit && exit.Signal != "" {
		processDetails["signal"] = exit.Signal
	}
	details["process"] = processDetails
	result.Details = mustJSON(details)
	return result, nil
}

type processEmitter struct {
	mu         sync.Mutex
	callID     string
	progress   runtime.Progress
	stopped    func() bool
	pending    []byte
	observed   int64
	nextOffset int64
	lastFlush  time.Time
	disabled   bool
}

func (e *processEmitter) Write(data []byte) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.observed += int64(len(data))
	e.pending = append(e.pending, data...)
	if len(e.pending) >= livePartialBytes || time.Since(e.lastFlush) >= liveFlushInterval {
		e.flushLocked()
	}
}

func (e *processEmitter) Flush() {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.flushLocked()
}

func (e *processEmitter) Snapshot(text string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.flushLocked()
	if e.disabled || e.progress == nil || e.stopped() || text == "" {
		return
	}
	partial := runtime.CapabilityResult{CallID: e.callID, Details: mustJSON(map[string]any{"kind": "process.output", "mode": "SNAPSHOT", "startOffset": e.nextOffset, "endOffset": e.nextOffset, "observedBytes": e.observed}), Contents: []runtime.ResultContent{{Type: "text", Text: text}}}
	if err := e.progress(partial); err != nil {
		e.disabled = true
	}
}

func (e *processEmitter) flushLocked() {
	for len(e.pending) > 0 {
		take := alignUTF8(e.pending, min(len(e.pending), livePartialBytes))
		if take == 0 {
			return
		}
		text := strings.ToValidUTF8(string(e.pending[:take]), "�")
		start, end := e.nextOffset, e.nextOffset+int64(take)
		e.pending = append(e.pending[:0], e.pending[take:]...)
		e.nextOffset = end
		if !e.disabled && e.progress != nil && !e.stopped() {
			partial := runtime.CapabilityResult{CallID: e.callID, Details: mustJSON(map[string]any{"kind": "process.output", "mode": "APPEND", "startOffset": start, "endOffset": end, "observedBytes": e.observed}), Contents: []runtime.ResultContent{{Type: "text", Text: text}}}
			if err := e.progress(partial); err != nil {
				e.disabled = true
			}
		}
	}
	e.lastFlush = time.Now()
}

func terminatedBeforeStart(call runtime.CapabilityCall, reason error) (runtime.CapabilityResult, error) {
	outcome, note := "CANCELLED", "[Operation cancelled by the caller. Captured output above may be incomplete; side effects already performed are not rolled back.]"
	if errors.Is(reason, context.DeadlineExceeded) {
		outcome = "TIMED_OUT"
		note = fmt.Sprintf("[Command timed out after %d ms and was terminated. Captured output above may be incomplete; side effects already performed are not rolled back.]", call.TimeoutMillis)
	}
	result := textResult(call.InvocationID, note)
	result.Error = true
	result.Details = mustJSON(map[string]any{"process": map[string]string{"outcome": outcome}})
	return result, nil
}
