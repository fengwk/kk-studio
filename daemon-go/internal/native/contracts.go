package native

import (
	"context"
	"errors"
	"io"
	"time"
)

// ProcessSpec describes one process and its working environment.
type ProcessSpec struct {
	Executable string
	Args       []string
	Directory  string
	Env        []string
}

// ProcessExit is the direct process exit status; nil fields mean the status is unknown.
type ProcessExit struct {
	Code   *int
	Signal *string
}

// ManagedProcess exposes pipes and permits convergent process-tree termination.
type ManagedProcess interface {
	PID() int
	Stdin() io.WriteCloser
	Stdout() io.ReadCloser
	Stderr() io.ReadCloser
	Wait(context.Context) (ProcessExit, error)
	Terminate(context.Context, time.Duration) error
}

// Supervisor starts and terminates process trees. Start failures other than
// ErrScopeNotConverged guarantee that no process from the attempt remains alive.
type Supervisor interface {
	Start(context.Context, ProcessSpec) (ManagedProcess, error)
	Close(context.Context) error
}

// DescendantTracker tracks an IDE PTY process that intentionally starts a separate POSIX session.
type DescendantTracker interface {
	TrackDescendant(context.Context, int, int) error
}

// ProcessScopeNotConvergedError means process scope termination could not be proven.
type ProcessScopeNotConvergedError struct {
	Cause error
}

func (e *ProcessScopeNotConvergedError) Error() string { return ErrScopeNotConverged.Error() }
func (e *ProcessScopeNotConvergedError) Unwrap() error {
	return errors.Join(ErrScopeNotConverged, e.Cause)
}

// ErrScopeNotConverged is the compatibility sentinel for a failed convergence proof.
var ErrScopeNotConverged = errors.New("process scope termination not converged")
