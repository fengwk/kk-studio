package runtime

import (
	"errors"
	"sync"
)

var errUnbound = errors.New("environment scope is not bound")
var errNotReady = errors.New("outbound message requires a READY bound connection")

// OutboundGate enforces environment binding and READY admission for control frames.
type OutboundGate struct {
	mu            sync.RWMutex
	environmentID string
	ready         bool
}

func (g *OutboundGate) Bind(environmentID string) error {
	if environmentID == "" {
		return errors.New("environmentId must not be empty")
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.environmentID != "" && g.environmentID != environmentID {
		return errors.New("bound environment cannot change")
	}
	g.environmentID = environmentID
	g.ready = false
	return nil
}
func (g *OutboundGate) Clear() {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.environmentID = ""
	g.ready = false
}
func (g *OutboundGate) MarkReady() { g.mu.Lock(); defer g.mu.Unlock(); g.ready = true }
func (g *OutboundGate) BoundEnvironment() (string, bool) {
	g.mu.RLock()
	defer g.mu.RUnlock()
	return g.environmentID, g.environmentID != ""
}
func (g *OutboundGate) RequireReady() error {
	g.mu.RLock()
	defer g.mu.RUnlock()
	if g.environmentID == "" {
		return errUnbound
	}
	if !g.ready {
		return errNotReady
	}
	return nil
}
func (g *OutboundGate) RequireBound() error {
	g.mu.RLock()
	defer g.mu.RUnlock()
	if g.environmentID == "" {
		return errUnbound
	}
	return nil
}

// RequireReadyFrame applies the READY gate to update receipts and other explicitly READY-only frames.
func (g *OutboundGate) RequireReadyFrame() error { return g.RequireReady() }

// RequireControlFrame allows bound-scoped work, errors, heartbeat, and status after SETTINGS.
func (g *OutboundGate) RequireControlFrame() error { return g.RequireBound() }
