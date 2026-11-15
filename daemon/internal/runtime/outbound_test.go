package runtime

import (
	"errors"
	"testing"
)

// Ensures outbound environment-scoped work is blocked before SETTINGS binds scope.
func TestOutboundGateRequiresBindingAndReadiness(t *testing.T) {
	g := &OutboundGate{}
	if !errors.Is(g.RequireReady(), errUnbound) || !errors.Is(g.RequireBound(), errUnbound) {
		t.Fatal("unbound frames were admitted")
	}
	if err := g.Bind("env"); err != nil {
		t.Fatal(err)
	}
	if err := g.RequireBound(); err != nil {
		t.Fatal(err)
	}
	if !errors.Is(g.RequireReady(), errNotReady) {
		t.Fatal("work frame admitted before READY")
	}
	g.MarkReady()
	if err := g.RequireReady(); err != nil {
		t.Fatal(err)
	}
	g.Clear()
	if !errors.Is(g.RequireBound(), errUnbound) {
		t.Fatal("frame admitted after scope cleared")
	}
	if err := g.Bind(""); err == nil {
		t.Fatal("empty environment ID was bound")
	}
	if _, bound := g.BoundEnvironment(); bound {
		t.Fatal("clear gate retained an environment identity")
	}
	if err := g.Bind("first"); err != nil {
		t.Fatal(err)
	}
	if err := g.Bind("first"); err != nil {
		t.Fatalf("idempotent bind error = %v", err)
	}
	if environmentID, bound := g.BoundEnvironment(); !bound || environmentID != "first" {
		t.Fatalf("bound environment = %q, present=%t", environmentID, bound)
	}
	if err := g.Bind("second"); err == nil || err.Error() != "bound environment cannot change" {
		t.Fatalf("rebinding error = %v", err)
	}
}
