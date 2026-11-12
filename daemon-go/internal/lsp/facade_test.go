package lsp

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"

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
	}{{"lsp.goto-definition", `{"line":1}`, "path is required and must be a string"}, {"lsp.goto-definition", `{"path":"   ","line":1}`, "path is required"}, {"lsp.goto-definition", `{"path":"relative","line":1}`, "path must be an absolute path (a relative or non-absolute value was given)"}, {"lsp.workspace-symbols", string(workspaceArgs), "path does not exist: " + filepath.ToSlash(missing)}, {"lsp.java-decompile", string(decompileArgs), "path does not exist: " + filepath.ToSlash(missing)}}
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

// LSP-T-001 validates every capability's field constraints before acquiring a server.
func TestCapabilities_ValidateCapabilityArguments(t *testing.T) {
	source := filepath.Join(t.TempDir(), "source.java")
	if err := os.WriteFile(source, []byte("class Source {}\n"), 0600); err != nil {
		t.Fatal(err)
	}
	marshal := func(value any) string {
		data, err := json.Marshal(value)
		if err != nil {
			t.Fatal(err)
		}
		return string(data)
	}
	cases := []struct {
		id, args, want string
	}{{"lsp.goto-definition", marshal(map[string]any{"path": source}), "line is required and must be a positive integer"}, {"lsp.goto-definition", marshal(map[string]any{"path": source, "line": 1.5}), "line is required and must be a positive integer"}, {"lsp.goto-definition", marshal(map[string]any{"path": source, "line": 1, "character": -1}), "character must be a non-negative integer"}, {"lsp.goto-definition", marshal(map[string]any{"path": source, "line": 1, "character": "x"}), "character must be a non-negative integer"}, {"lsp.workspace-symbols", marshal(map[string]any{"path": source}), "query is required and must be a string"}, {"lsp.workspace-symbols", marshal(map[string]any{"path": source, "query": " "}), "query must not be blank"}, {"lsp.workspace-symbols", marshal(map[string]any{"path": source, "query": "x", "limit": -1}), "limit must be a non-negative integer"}, {"lsp.workspace-symbols", marshal(map[string]any{"path": source, "query": "x", "limit": 501}), "limit must be <= 500"}, {"lsp.workspace-symbols", marshal(map[string]any{"path": source, "query": "x", "limit": 1.5}), "limit must be a non-negative integer"}, {"lsp.java-decompile", marshal(map[string]any{"path": source}), "target is required and must be a string"}, {"lsp.java-decompile", marshal(map[string]any{"path": source, "target": "  "}), "target must not be blank"}, {"lsp.goto-definition", "[]", "tool arguments must be an object"}}
	facade := NewFacade(&fakeSupervisor{}, PoolOptions{})
	confirmed := 0
	for _, tc := range cases {
		result, err := facade.Capabilities()[tc.id](context.Background(), runtime.CapabilityCall{InvocationID: "args", CapabilityID: tc.id, Arguments: json.RawMessage(tc.args)}, runtime.CapabilityContext{ConfirmExecutionExit: func() { confirmed++ }})
		if err != nil || !result.Error || result.Contents[0].Text != tc.want {
			t.Errorf("%s arguments %s: result=%#v err=%v, want %q", tc.id, tc.args, result, err, tc.want)
		}
	}
	if confirmed != len(cases) {
		t.Fatalf("confirmed %d exits, want %d", confirmed, len(cases))
	}
}

// LSP-T-006 preserves the configured-environment error for a valid request without a service.
func TestCapabilities_Unconfigured(t *testing.T) {
	source := filepath.Join(t.TempDir(), "source.go")
	if err := os.WriteFile(source, []byte("package source\n"), 0600); err != nil {
		t.Fatal(err)
	}
	arguments, err := json.Marshal(map[string]any{"path": source, "line": 1})
	if err != nil {
		t.Fatal(err)
	}
	confirmed := 0
	facade := NewFacade(&fakeSupervisor{}, PoolOptions{})
	result, err := facade.Capabilities()["lsp.goto-definition"](context.Background(), runtime.CapabilityCall{InvocationID: "unconfigured", CapabilityID: "lsp.goto-definition", Arguments: arguments}, runtime.CapabilityContext{ConfirmExecutionExit: func() { confirmed++ }})
	if err != nil || !result.Error || result.Contents[0].Text != "LSP is not configured for this environment" || confirmed != 1 {
		t.Fatalf("unconfigured result=%#v err=%v confirmed=%d", result, err, confirmed)
	}
}

// LSP-T-009 executes all three capabilities through one pooled JDTLS client.
func TestCapabilities_SharedClient(t *testing.T) {
	root := t.TempDir()
	binary := filepath.Join(root, "jdtls")
	if err := os.WriteFile(binary, []byte("fake"), 0700); err != nil {
		t.Fatal(err)
	}
	source := filepath.Join(root, "Source.java")
	if err := os.WriteFile(source, []byte("class Source {}\n"), 0600); err != nil {
		t.Fatal(err)
	}
	supervisor := &fakeSupervisor{}
	facade := NewFacade(supervisor, PoolOptions{IdleTimeout: time.Hour})
	if err := facade.Update([]config.LSPServerConfig{{ID: "jdtls", Command: []string{binary}, Extensions: []string{".java"}}}); err != nil {
		t.Fatal(err)
	}
	calls := []struct {
		id        string
		arguments map[string]any
		want      string
	}{{"lsp.goto-definition", map[string]any{"path": source, "line": 1}, filepath.FromSlash("/tmp/target.go") + ":3:4"}, {"lsp.workspace-symbols", map[string]any{"path": source, "query": "Source"}, "No symbols found"}, {"lsp.java-decompile", map[string]any{"path": source, "target": "jdt://contents/java.base/java/lang/String.class"}, "class contents"}}
	confirmed := 0
	for _, item := range calls {
		arguments, err := json.Marshal(item.arguments)
		if err != nil {
			t.Fatal(err)
		}
		result, err := facade.Capabilities()[item.id](context.Background(), runtime.CapabilityCall{InvocationID: item.id, CapabilityID: item.id, Arguments: arguments}, runtime.CapabilityContext{ConfirmExecutionExit: func() { confirmed++ }})
		if err != nil || result.Error || len(result.Contents) != 1 || result.Contents[0].Text != item.want {
			t.Errorf("%s result=%#v err=%v, want %q", item.id, result, err, item.want)
		}
	}
	if supervisor.starts != 1 || confirmed != len(calls) || facade.ActiveClientCount() != 1 {
		t.Fatalf("starts=%d confirmations=%d clients=%d", supervisor.starts, confirmed, facade.ActiveClientCount())
	}
	if err := facade.Close(); err != nil {
		t.Fatal(err)
	}
}
