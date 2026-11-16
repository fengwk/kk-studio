package coding

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
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
	// terminateTimeout bounds how long one process scope may take to converge.
	terminateTimeout  = 5 * time.Second
	streamBufferBytes = 32 * 1024
)

// ExecuteProcess runs the supplied shell command under native process-tree supervision.
func ExecuteProcess(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext, command, workdir string) (runtime.CapabilityResult, error) {
	executionExited := true
	var spool *OutputSpool
	defer func() {
		closeProcessOutputBeforeExitConfirmation(func() {
			if spool != nil {
				_ = spool.Close()
			}
		}, executionExited, capability.ConfirmExecutionExit)
	}()
	if strings.TrimSpace(command) == "" {
		return errorResult(call.InvocationID, rejectInput("command must be a non-empty string")), nil
	}
	if !filepath.IsAbs(workdir) {
		return errorResult(call.InvocationID, rejectInput("workdir must be absolute")), nil
	}
	if err := ctx.Err(); err != nil {
		return terminatedBeforeStart(call, err)
	}
	if capability.Processes == nil {
		return errorResult(call.InvocationID, failServiceUnavailable("process supervisor is unavailable")), nil
	}
	store, err := TextOutputStoreForDataDir(capability.Config.DataDir)
	if err != nil {
		return errorResult(call.InvocationID, failServiceUnavailable("temporary output storage is unavailable")), nil
	}
	spool, err = NewOutputSpool(store, call.InvocationID, defaultCaptureBudget)
	if err != nil {
		return errorResult(call.InvocationID, failServiceUnavailable("temporary output storage is unavailable")), nil
	}
	process, err := capability.Processes.Start(ctx, processSpec(capability.Config.BashExecutable, command, workdir))
	if err != nil {
		executionExited = !errors.Is(err, native.ErrScopeNotConverged)
		if executionExited && ctx.Err() != nil {
			return terminatedBeforeStart(call, ctx.Err())
		}
		return errorResult(call.InvocationID, failRun(err.Error(), "FAILED")), nil
	}
	executionExited = false
	run := &processRun{ctx: ctx, process: process, spool: spool, terminate: terminateOnce(process)}
	run.collect(call, capability.Progress)
	executionExited = run.exited()
	if run.readErr != nil && ctx.Err() == nil {
		return streamFailureResult(spool), nil
	}
	if run.terminationErr != nil {
		return errorResult(call.InvocationID, failRun("the process scope did not converge; processes may still be running", "FAILED")), nil
	}
	if run.waitErr != nil && ctx.Err() == nil {
		return errorResult(call.InvocationID, failRun(run.waitErr.Error(), "FAILED")), nil
	}
	return exitResult(call, spool, run), nil
}

// processSpec wraps the command so that it starts in workdir under the configured shell.
func processSpec(bashExecutable *string, command, workdir string) native.ProcessSpec {
	shell := "bash"
	if bashExecutable != nil && *bashExecutable != "" {
		shell = *bashExecutable
	}
	return native.ProcessSpec{Executable: shell, Args: []string{"-lc", `cd -- "$1" || exit 1; shift; ` + command, "bash", workdir}, Directory: workdir}
}

// processRun holds the state of one started process until its output has been read.
type processRun struct {
	ctx       context.Context
	process   native.ManagedProcess
	spool     *OutputSpool
	terminate func() error
	exitedAt  atomic.Bool
	// readErr joins the stream failures that were not caused by the wait itself.
	readErr        error
	terminationErr error
	waitErr        error
	exit           native.ProcessExit
}

// collect reads both output streams until the process exits, then flushes the last progress.
func (r *processRun) collect(call runtime.CapabilityCall, progress runtime.Progress) {
	_ = r.process.Stdin().Close()
	emitter := &processEmitter{callID: call.InvocationID, progress: progress, stopped: func() bool { return r.ctx.Err() != nil }, lastFlush: time.Now()}
	stopProgress := make(chan struct{})
	var progressWait sync.WaitGroup
	if progress != nil {
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
	var truncationReported atomic.Bool
	var readers sync.WaitGroup
	for _, stream := range []io.ReadCloser{r.process.Stdout(), r.process.Stderr()} {
		readers.Add(1)
		go func(stream io.ReadCloser) {
			defer readers.Done()
			defer stream.Close()
			buffer := make([]byte, streamBufferBytes)
			for {
				n, readErr := stream.Read(buffer)
				if n > 0 {
					r.spool.Write(buffer[:n])
					emitter.Write(buffer[:n])
					if r.spool.IsCaptureTruncated() && truncationReported.CompareAndSwap(false, true) {
						emitter.Snapshot(r.spool.CaptureTruncatedTailPreview())
					}
				}
				if readErr != nil {
					if !errors.Is(readErr, io.EOF) {
						readErrors <- readErr
						_ = r.terminate()
					}
					return
				}
			}
		}(stream)
	}
	r.exit, r.waitErr = r.process.Wait(r.ctx)
	r.terminationErr = r.terminate()
	readers.Wait()
	emitter.Flush()
	if r.terminationErr == nil && (r.waitErr == nil || r.ctx.Err() != nil) {
		r.exitedAt.Store(true)
	}
	r.readErr = joinStreamErrors(readErrors, r.waitErr, r.terminationErr)
}

// joinStreamErrors keeps the stream failures that no normal wait explains.
func joinStreamErrors(readErrors chan error, waitErr, terminationErr error) error {
	var joined error
	for len(readErrors) > 0 {
		streamErr := <-readErrors
		streamClosedByWait := errors.Is(streamErr, os.ErrClosed) && waitErr == nil && terminationErr == nil
		if !streamClosedByWait {
			joined = errors.Join(joined, streamErr)
		}
	}
	return joined
}

// exited reports whether no process of the scope survives.
func (r *processRun) exited() bool { return r.exitedAt.Load() }

// terminateOnce returns a terminate function that runs the scope termination at most once.
func terminateOnce(process native.ManagedProcess) func() error {
	var once sync.Once
	var err error
	return func() error {
		once.Do(func() {
			termCtx, cancel := context.WithTimeout(context.Background(), terminateTimeout)
			defer cancel()
			err = process.Terminate(termCtx, terminateTimeout)
		})
		return err
	}
}

// streamFailureResult reports output that could not be read even though the process exited.
func streamFailureResult(spool *OutputSpool) runtime.CapabilityResult {
	result := spool.Finish(true, "")
	result.Error = true
	result.Contents[0].Text += "\n\n" + guideText("the process output stream failed while reading", "FAILED", verifyEffectNextAction)
	var details map[string]any
	if err := json.Unmarshal(result.Details, &details); err != nil {
		details = make(map[string]any)
	}
	details["code"] = "FAILED"
	details["process"] = map[string]string{"outcome": "EXITED"}
	result.Details = mustJSON(details)
	return result
}

// exitResult reports how the process ended: its outcome note, exit code or signal.
func exitResult(call runtime.CapabilityCall, spool *OutputSpool, run *processRun) runtime.CapabilityResult {
	outcome := run.outcome(call)
	result := spool.Finish(outcome.failed, outcome.note)
	var details map[string]any
	if err := json.Unmarshal(result.Details, &details); err != nil {
		details = make(map[string]any)
	}
	processDetails := map[string]any{"outcome": outcome.outcome}
	if outcome.failed {
		code := "FAILED"
		if outcome.outcome == "CANCELLED" {
			code = "CANCELLED"
		} else if outcome.outcome == "TIMED_OUT" {
			code = "TIMEOUT"
		}
		details["code"] = code
	}
	naturalExit := run.ctx.Err() == nil && run.waitErr == nil
	if naturalExit && outcome.exitCode != nil {
		processDetails["exitCode"] = *outcome.exitCode
	}
	if naturalExit && run.exit.Signal != nil {
		processDetails["signal"] = *run.exit.Signal
	}
	details["process"] = processDetails
	result.Details = mustJSON(details)
	return result
}

// processOutcome is the reported ending of one process.
type processOutcome struct {
	outcome  string
	note     string
	failed   bool
	exitCode *int
}

// outcome derives the reported ending from the context, the wait error and the exit status.
func (r *processRun) outcome(call runtime.CapabilityCall) processOutcome {
	outcome := processOutcome{outcome: "EXITED"}
	switch {
	case errors.Is(r.ctx.Err(), context.DeadlineExceeded):
		outcome.outcome = "TIMED_OUT"
		outcome.failed = true
		outcome.note = fmt.Sprintf("[Command timed out after %d ms and was terminated. Captured output above may be incomplete; side effects already performed are not rolled back.]", call.TimeoutMillis)
	case errors.Is(r.ctx.Err(), context.Canceled):
		outcome.outcome = "CANCELLED"
		outcome.failed = true
		outcome.note = "[Operation cancelled by the caller. Captured output above may be incomplete; side effects already performed are not rolled back.]"
	case r.waitErr != nil:
		outcome.failed = true
	case r.exit.Signal != nil:
		outcome.failed = true
		outcome.note = fmt.Sprintf("[Command was terminated by signal %s.]", *r.exit.Signal)
	case r.exit.Code != nil:
		code := *r.exit.Code
		outcome.exitCode = &code
		if code != 0 {
			outcome.failed = true
			outcome.note = fmt.Sprintf("[Command exited with code %d.]", code)
		}
	}
	return outcome
}

func closeProcessOutputBeforeExitConfirmation(closeOutput func(), executionExited bool, confirm func()) {
	closeOutput()
	if executionExited && confirm != nil {
		confirm()
	}
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
	code := "CANCELLED"
	if outcome == "TIMED_OUT" {
		code = "TIMEOUT"
	}
	result.Details = mustJSON(map[string]any{"code": code, "process": map[string]string{"outcome": outcome}})
	return result, nil
}
