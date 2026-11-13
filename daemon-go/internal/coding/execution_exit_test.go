package coding

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// COD-T-248 verifies every in-process filesystem capability confirms its execution
// exit; without this the platform keeps the invocation's exit fact unconfirmed and
// the Thread remains EXIT_PENDING after a completed tool turn.
func TestFilesystemCapabilitiesConfirmExecutionExit(t *testing.T) {
	for _, id := range []string{
		"fs.read",
		"fs.write",
		"fs.write.preview",
		"fs.edit",
		"fs.edit.preview",
		"fs.find",
		"fs.grep",
	} {
		t.Run(id, func(t *testing.T) {
			confirmed := 0
			result, err := Provider{}.Capabilities()[id](
				context.Background(),
				runtime.CapabilityCall{InvocationID: id, CapabilityID: id, Arguments: json.RawMessage(`{}`)},
				runtime.CapabilityContext{ConfirmExecutionExit: func() { confirmed++ }},
			)
			if err != nil {
				t.Fatal(err)
			}
			if result.CallID != id {
				t.Fatalf("result call ID = %q, want %q", result.CallID, id)
			}
			if confirmed != 1 {
				t.Fatalf("execution exit confirmations = %d, want 1", confirmed)
			}
		})
	}
}

// COD-T-249 covers the successful result path, where confirmation must also run
// only after the capability has finished producing its result.
func TestReadCapabilityConfirmsExecutionExitAfterSuccessfulResult(t *testing.T) {
	path := filepath.Join(t.TempDir(), "read.txt")
	if err := os.WriteFile(path, []byte("content\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	arguments, err := json.Marshal(map[string]any{"path": path})
	if err != nil {
		t.Fatal(err)
	}
	confirmed := 0
	result, err := Provider{}.Capabilities()["fs.read"](
		context.Background(),
		runtime.CapabilityCall{InvocationID: "read", CapabilityID: "fs.read", Arguments: arguments},
		runtime.CapabilityContext{ConfirmExecutionExit: func() { confirmed++ }},
	)
	if err != nil {
		t.Fatal(err)
	}
	if result.Error {
		t.Fatalf("read failed: %#v", result)
	}
	if confirmed != 1 {
		t.Fatalf("execution exit confirmations = %d, want 1", confirmed)
	}
}
