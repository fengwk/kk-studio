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
	// Env is filtered, deduplicated, and then applied as an overlay on the daemon
	// environment unless ReplaceEnv makes it the complete environment instead.
	Env []string
	// NullStdin connects the process to the null device, so it reads end-of-file
	// immediately and never consumes the daemon's own standard input.
	NullStdin bool
	// ReplaceEnv makes Env the complete environment instead of an overlay.
	ReplaceEnv bool
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

// UpdateSupervisor is a Supervisor whose process admission can be suspended reversibly.
// Suspend refuses new Start calls and terminates every tracked process, including one that a
// concurrent Start was still publishing. Call Resume only after Suspend returns (even on error).
// Resume is a no-op once the supervisor is closed; Close stays terminal.
type UpdateSupervisor interface {
	Supervisor
	Suspend(context.Context) error
	Resume()
}

// ErrSuspended is matched with errors.Is when Start is refused because the supervisor is suspended.
var ErrSuspended = errors.New("the process supervisor is suspended")

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

// ErrScopeNotConverged is the sentinel matched with errors.Is when a convergence proof fails.
var ErrScopeNotConverged = errors.New("process scope termination not converged")
