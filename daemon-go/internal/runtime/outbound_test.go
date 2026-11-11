package runtime

import (
	"errors"
	"testing"
)

// RUN-T-118 ensures outbound environment-scoped work is blocked before SETTINGS binds scope.
func TestOutboundGateRequiresBindingAndReadiness(t *testing.T) {
	g := &OutboundGate{}
	if !errors.Is(g.RequireReadyFrame(), errUnbound) || !errors.Is(g.RequireControlFrame(), errUnbound) {
		t.Fatal("unbound frames were admitted")
	}
	if err := g.Bind("env"); err != nil {
		t.Fatal(err)
	}
	if err := g.RequireControlFrame(); err != nil {
		t.Fatal(err)
	}
	if !errors.Is(g.RequireReadyFrame(), errNotReady) {
		t.Fatal("work frame admitted before READY")
	}
	g.MarkReady()
	if err := g.RequireReadyFrame(); err != nil {
		t.Fatal(err)
	}
	g.Clear()
	if !errors.Is(g.RequireControlFrame(), errUnbound) {
		t.Fatal("frame admitted after scope cleared")
	}
}
