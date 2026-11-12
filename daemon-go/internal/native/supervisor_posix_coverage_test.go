//go:build linux || darwin

package native

import (
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// These cases exercise helper readiness errors without waiting on process timing.
func TestPOSIXAwaitScopeFailsClosed(t *testing.T) {
	tests := []struct {
		name  string
		setup func(*testing.T, *posixProcess) context.Context
	}{
		{
			name: "untrusted scope",
			setup: func(t *testing.T, p *posixProcess) context.Context {
				if err := publishText(p.stateDir, scopeFile, "999999"); err != nil {
					t.Fatal(err)
				}
				return context.Background()
			},
		},
		{
			name: "helper is not the session leader",
			setup: func(t *testing.T, p *posixProcess) context.Context {
				p.sid = os.Getpid()
				if err := publishText(p.stateDir, scopeFile, strconv.Itoa(p.sid)); err != nil {
					t.Fatal(err)
				}
				p.pid = p.sid + 1
				return context.Background()
			},
		},
		{
			name: "invalid scope record",
			setup: func(t *testing.T, p *posixProcess) context.Context {
				if err := os.Mkdir(filepath.Join(p.stateDir, scopeFile), 0700); err != nil {
					t.Fatal(err)
				}
				return context.Background()
			},
		},
		{
			name: "helper exits with phase",
			setup: func(t *testing.T, p *posixProcess) context.Context {
				if err := publishText(p.stateDir, errorFile, "helper-posix-failure"); err != nil {
					t.Fatal(err)
				}
				close(p.done)
				return context.Background()
			},
		},
		{
			name: "helper exits without phase",
			setup: func(t *testing.T, p *posixProcess) context.Context {
				close(p.done)
				return context.Background()
			},
		},
		{
			name: "cancelled while awaiting scope",
			setup: func(t *testing.T, p *posixProcess) context.Context {
				ctx, cancel := context.WithCancel(context.Background())
				cancel()
				return ctx
			},
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			dir := stateTestDir(t)
			p := &posixProcess{pid: 42, sid: 42, stateDir: dir, done: make(chan struct{})}
			ctx := test.setup(t, p)
			if err := p.awaitScope(ctx); err == nil {
				t.Fatal("awaitScope unexpectedly accepted invalid readiness state")
			}
		})
	}
}

type testErrorWriteCloser struct{ err error }

func (w testErrorWriteCloser) Write(value []byte) (int, error) { return len(value), nil }
func (w testErrorWriteCloser) Close() error                    { return w.err }

func posixTestProcess(t *testing.T) *posixProcess {
	t.Helper()
	owner, err := os.Open(os.DevNull)
	if err != nil {
		t.Fatal(err)
	}
	return &posixProcess{
		supervisor:      &PosixSupervisor{processes: make(map[int]*posixProcess)},
		pid:             os.Getpid(),
		sid:             os.Getpid(),
		stateDir:        stateTestDir(t),
		owner:           owner,
		stdin:           testErrorWriteCloser{},
		stdout:          io.NopCloser(strings.NewReader("")),
		stderr:          io.NopCloser(strings.NewReader("")),
		trackedSessions: make(map[int]trackedSession),
		done:            make(chan struct{}),
	}
}

func TestPOSIXTerminateInjectedFailures(t *testing.T) {
	t.Run("context cancellation", func(t *testing.T) {
		p := posixTestProcess(t)
		p.convergeScope = func(context.Context, int, time.Duration) error { return nil }
		ctx, cancel := context.WithCancel(context.Background())
		cancel()
		err := p.Terminate(ctx, 0)
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("Terminate error = %v, want cancellation", err)
		}
		_ = p.owner.Close()
	})

	t.Run("tracked session identity changed", func(t *testing.T) {
		p := posixTestProcess(t)
		close(p.done)
		p.convergeScope = func(context.Context, int, time.Duration) error { return nil }
		members, err := allProcessMembers()
		if err != nil {
			t.Fatal(err)
		}
		var own sessionMember
		for _, member := range members {
			if member.PID == os.Getpid() {
				own = member
				break
			}
		}
		if own.PID == 0 || own.Identity == "" {
			t.Fatal("current process identity was not present in its session")
		}
		p.trackedSessions[own.Session] = trackedSession{ChildPID: own.PID, Session: own.Session, Identity: "reused-pid"}
		err = p.Terminate(context.Background(), 0)
		var scopeErr *ProcessScopeNotConvergedError
		if !errors.As(err, &scopeErr) || !strings.Contains(scopeErr.Cause.Error(), "tracked process identity changed") {
			t.Fatalf("Terminate error = %v, want tracked identity failure", err)
		}
		_ = p.owner.Close()
	})

	t.Run("stream close failure", func(t *testing.T) {
		p := posixTestProcess(t)
		close(p.done)
		p.convergeScope = func(context.Context, int, time.Duration) error { return nil }
		closeErr := errors.New("stdin close failed")
		p.stdin = testErrorWriteCloser{err: closeErr}
		err := p.Terminate(context.Background(), 0)
		if !errors.Is(err, closeErr) {
			t.Fatalf("Terminate error = %v, want stream close failure", err)
		}
		_ = p.owner.Close()
	})

	t.Run("settled process is idempotent", func(t *testing.T) {
		p := &posixProcess{settled: true}
		if err := p.Terminate(context.Background(), 0); err != nil {
			t.Fatalf("Terminate on settled process = %v", err)
		}
	})
}
