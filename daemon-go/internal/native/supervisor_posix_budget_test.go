//go:build linux || darwin

package native

import (
	"testing"
	"time"
)

// NAT-F-008 verifies POSIX timing constants stay aligned with the shared supervisor contract.
func TestPOSIXSupervisorBudgets(t *testing.T) {
	budgets := map[string]struct {
		got  time.Duration
		want time.Duration
	}{
		"default grace":     {got: defaultGracePeriod, want: time.Second},
		"force kill":        {got: posixForceKillBudget, want: time.Second},
		"helper grace":      {got: helperGracePeriod, want: 600 * time.Millisecond},
		"poll interval":     {got: scopePollInterval, want: 5 * time.Millisecond},
		"scope start":       {got: scopeStartBudget, want: 20 * time.Second},
		"scope convergence": {got: scopeConvergeBudget, want: 4 * time.Second},
	}
	for name, budget := range budgets {
		if budget.got != budget.want {
			t.Errorf("%s budget = %s, want %s", name, budget.got, budget.want)
		}
	}
}
