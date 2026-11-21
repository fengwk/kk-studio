package native

import (
	"context"
	"errors"
	"sync"
)

// errSupervisorClosed reports a terminal supervisor that cannot admit processes again.
var errSupervisorClosed = errors.New("the process supervisor is closed")

// admissionGate serializes process admission with reversible suspension and terminal closure.
// Suspend waits for admitted Starts to complete before taking the process snapshot.
type admissionGate struct {
	mu        sync.Mutex
	closed    bool
	suspended bool
	starting  int
	settled   chan struct{}
}

// admit reserves one in-flight Start or reports why the supervisor refuses it.
func (g *admissionGate) admit() error {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.closed {
		return errSupervisorClosed
	}
	if g.suspended {
		return ErrSuspended
	}
	g.starting++
	return nil
}

// finish releases one admitted Start and wakes a waiting Suspend when none remain in flight.
func (g *admissionGate) finish() {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.starting--
	if g.starting == 0 {
		g.settle()
	}
}

// register publishes an admitted Start even during suspension, so Suspend drains it with the
// other tracked processes. Close is terminal and does not wait for in-flight Starts.
func (g *admissionGate) register(publish func()) error {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.closed {
		return errSupervisorClosed
	}
	publish()
	return nil
}

// suspend refuses new Starts and waits for admitted Starts to finish. A cancelled context leaves
// the supervisor suspended so the caller must Resume before processes are admitted again.
func (g *admissionGate) suspend(ctx context.Context) error {
	g.mu.Lock()
	if g.closed {
		g.mu.Unlock()
		return nil
	}
	g.suspended = true
	for g.starting > 0 {
		settled := g.settlement()
		g.mu.Unlock()
		select {
		case <-settled:
			g.mu.Lock()
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	g.mu.Unlock()
	return nil
}

// resume restores admission unless the supervisor is already closed.
func (g *admissionGate) resume() {
	g.mu.Lock()
	defer g.mu.Unlock()
	if !g.closed {
		g.suspended = false
	}
}

// close terminates admission for the lifetime of the supervisor.
func (g *admissionGate) close() {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.closed = true
}

// settlement returns the channel closed once the last in-flight Start returns.
func (g *admissionGate) settlement() chan struct{} {
	if g.settled == nil {
		g.settled = make(chan struct{})
	}
	return g.settled
}

// settle releases everyone waiting for in-flight Starts.
func (g *admissionGate) settle() {
	if g.settled != nil {
		close(g.settled)
		g.settled = nil
	}
}
