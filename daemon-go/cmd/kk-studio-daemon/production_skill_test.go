package main

import (
	"context"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	daemonruntime "github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// COD-T-250 verifies skill.install also confirms its execution exit when the
// capability fails before any protected execution scope exists.
func TestSkillCapabilityConfirmsExecutionExitOnFailure(t *testing.T) {
	confirmed := 0
	capability := skillCapability(
		func() config.DaemonConfig { return config.DaemonConfig{} },
		&networkRuntimeStore{},
		nil,
	)
	result, err := capability(
		context.Background(),
		daemonruntime.CapabilityCall{InvocationID: "skill"},
		daemonruntime.CapabilityContext{ConfirmExecutionExit: func() { confirmed++ }},
	)
	if err != nil {
		t.Fatal(err)
	}
	if !result.Error {
		t.Fatalf("skill install without network unexpectedly succeeded: %#v", result)
	}
	if confirmed != 1 {
		t.Fatalf("execution exit confirmations = %d, want 1", confirmed)
	}
}
