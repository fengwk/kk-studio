//go:build linux

package native

import (
	"errors"
	"strings"
	"testing"
)

// NAT-T-154 verifies a failed prctl prevents Linux supervisor construction.
func TestLinuxSubreaper_PrctlFailure(t *testing.T) {
	injected := errors.New("injected prctl failure")
	called := false
	supervisor, err := newPOSIXSupervisor(func() error {
		called = true
		return injected
	})
	if !called || supervisor != nil || !errors.Is(err, injected) {
		t.Fatalf("constructor result = %#v, %v, called %t", supervisor, err, called)
	}
}

// NAT-T-155 verifies supervisor startup propagates subreaper failure with context.
func TestLinuxSupervisor_SubreaperFailure(t *testing.T) {
	injected := errors.New("injected subreaper failure")
	supervisor, err := newPOSIXSupervisor(func() error { return injected })
	if supervisor != nil || !errors.Is(err, injected) || err == nil || !strings.Contains(err.Error(), "cannot enable process subreaper") {
		t.Fatalf("constructor result = %#v, %v", supervisor, err)
	}
}
