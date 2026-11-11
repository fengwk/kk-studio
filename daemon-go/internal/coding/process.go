package coding

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// ExecuteProcess runs the supplied shell command under native process-tree supervision.
func ExecuteProcess(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext, command, workdir string) (runtime.CapabilityResult, error) {
	if strings.TrimSpace(command) == "" {
		return errorResult(call.InvocationID, errors.New("command must be a non-empty string")), nil
	}
	if !filepath.IsAbs(workdir) {
		return errorResult(call.InvocationID, errors.New("workdir must be absolute")), nil
	}
	if err := ctx.Err(); err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if capability.Processes == nil {
		return errorResult(call.InvocationID, errors.New("process supervisor is unavailable")), nil
	}
	shell := "bash"
	if capability.Config.BashExecutable != nil && *capability.Config.BashExecutable != "" {
		shell = *capability.Config.BashExecutable
	}
	process, err := capability.Processes.Start(ctx, native.ProcessSpec{Executable: shell, Args: []string{"-lc", `cd -- "$1" || exit 1; shift; ` + command, "bash", workdir}, Directory: workdir})
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	_ = process.Stdin().Close()
	var output bytes.Buffer
	var outputMu sync.Mutex
	readErrors := make(chan error, 2)
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
					outputMu.Lock()
					_, _ = output.Write(buffer[:n])
					outputMu.Unlock()
				}
				if readErr != nil {
					if !errors.Is(readErr, io.EOF) {
						readErrors <- readErr
					}
					return
				}
			}
		}(stream)
	}
	exit, waitErr := process.Wait(ctx)
	if ctx.Err() != nil {
		termCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		termErr := process.Terminate(termCtx, 5*time.Second)
		cancel()
		if termErr != nil {
			return errorResult(call.InvocationID, fmt.Errorf("process scope did not converge; processes may still be running: %w", termErr)), nil
		}
	}
	readers.Wait()
	select {
	case readErr := <-readErrors:
		return errorResult(call.InvocationID, fmt.Errorf("the process output stream failed while reading: %w", readErr)), nil
	default:
	}
	outputMu.Lock()
	text := output.String()
	outputMu.Unlock()
	if ctx.Err() != nil {
		text += "\n[Operation cancelled by the caller. Captured output above may be incomplete; side effects already performed are not rolled back.]"
	}
	if waitErr != nil {
		return errorResult(call.InvocationID, waitErr), nil
	}
	details := map[string]any{"process": map[string]any{"outcome": "EXITED", "exitCode": exit.Code}}
	if exit.Signal != "" {
		details = map[string]any{"process": map[string]any{"outcome": "EXITED", "signal": exit.Signal}}
		text += fmt.Sprintf("\n[Command was terminated by signal %s.]", exit.Signal)
	} else if exit.Code != 0 {
		text += fmt.Sprintf("\n[Command exited with code %d.]", exit.Code)
	}
	result := textResult(call.InvocationID, text)
	result.Details = mustJSON(details)
	return result, nil
}
