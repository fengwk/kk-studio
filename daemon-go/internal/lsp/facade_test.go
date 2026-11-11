package lsp

import (
	"context"
	"encoding/json"
	"path/filepath"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// LSP-T-001 ensures argument rejection precedes any server launch and confirms exit once.
func TestCapabilities_ValidateBeforeStart(t *testing.T) {
	supervisor := &fakeSupervisor{}
	facade := NewFacade(supervisor, PoolOptions{})
	confirmed := 0
	missing := filepath.Join(t.TempDir(), "missing-source.java")
	workspaceArgs, err := json.Marshal(map[string]string{"path": missing, "query": " "})
	if err != nil {
		t.Fatal(err)
	}
	decompileArgs, err := json.Marshal(map[string]string{"path": missing, "target": "x"})
	if err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		id   string
		args string
		want string
	}{{"lsp.goto-definition", `{"line":1}`, "path is required and must be a string"}, {"lsp.goto-definition", `{"path":"relative","line":1}`, "path must be an absolute path (a relative or non-absolute value was given)"}, {"lsp.workspace-symbols", string(workspaceArgs), "path does not exist: " + missing}, {"lsp.java-decompile", string(decompileArgs), "path does not exist: " + missing}}
	for _, tc := range cases {
		call := runtime.CapabilityCall{InvocationID: "id", CapabilityID: tc.id, Arguments: json.RawMessage(tc.args)}
		result, err := facade.Capabilities()[tc.id](context.Background(), call, runtime.CapabilityContext{ConfirmExecutionExit: func() { confirmed++ }})
		if err != nil {
			t.Fatal(err)
		}
		if !result.Error || len(result.Contents) != 1 || result.Contents[0].Text != tc.want {
			t.Fatalf("%s returned %#v, want %q", tc.id, result, tc.want)
		}
	}
	if supervisor.starts != 0 {
		t.Fatalf("validation launched %d servers", supervisor.starts)
	}
	if confirmed != len(cases) {
		t.Fatalf("confirmed %d exits, want %d", confirmed, len(cases))
	}
}
