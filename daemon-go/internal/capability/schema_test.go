package capability

import (
	"context"
	"encoding/json"
	"reflect"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// TestSchemas_DeclareEveryCapability verifies the embedded contract set and the 1.0.0 schema changes.
func TestSchemas_DeclareEveryCapability(t *testing.T) {
	schemas, err := Schemas()
	if err != nil {
		t.Fatal(err)
	}
	want := []string{
		"fs.edit", "fs.edit.preview", "fs.find", "fs.grep", "fs.read", "fs.write", "fs.write.preview",
		"lsp.goto-definition", "lsp.java-decompile", "lsp.workspace-symbols", "process.exec", "skill.install",
	}
	if len(schemas) != len(want) {
		t.Fatalf("got %d schemas, want %d", len(schemas), len(want))
	}
	executors := make(map[string]runtime.CapabilityExecutor, len(want))
	for _, id := range want {
		if _, ok := schemas[id]; !ok {
			t.Errorf("missing schema %q", id)
		}
		executors[id] = func(context.Context, runtime.CapabilityCall, runtime.CapabilityContext) (runtime.CapabilityResult, error) {
			return runtime.CapabilityResult{}, nil
		}
	}
	declarations, err := Declare(executors)
	if err != nil {
		t.Fatal(err)
	}
	if len(declarations) != len(want) || declarations[0].ID != "fs.edit" {
		t.Fatalf("declaration set=%v", declarations)
	}
	if _, err := Declare(map[string]runtime.CapabilityExecutor{"missing": func(context.Context, runtime.CapabilityCall, runtime.CapabilityContext) (runtime.CapabilityResult, error) {
		return runtime.CapabilityResult{}, nil
	}}); err == nil {
		t.Fatal("undeclared executor was accepted")
	}

	var grep struct {
		Properties map[string]json.RawMessage `json:"properties"`
	}
	if err := json.Unmarshal(schemas["fs.grep"], &grep); err != nil {
		t.Fatal(err)
	}
	if _, ok := grep.Properties["include"]; ok {
		t.Fatal("fs.grep schema still declares include")
	}
	if _, ok := grep.Properties["multiline"]; ok {
		t.Fatal("fs.grep schema still declares multiline")
	}
	if _, ok := grep.Properties["glob"]; !ok {
		t.Fatal("fs.grep schema does not declare glob")
	}

	var position struct {
		Description string `json:"description"`
	}
	var definition struct {
		Properties map[string]json.RawMessage `json:"properties"`
	}
	if err := json.Unmarshal(schemas["lsp.goto-definition"], &definition); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(definition.Properties["character"], &position); err != nil {
		t.Fatal(err)
	}
	if position.Description != "1-based character offset at the target position. Defaults to 1." {
		t.Fatalf("character description=%q", position.Description)
	}

	var install struct {
		Required []string `json:"required"`
	}
	if err := json.Unmarshal(schemas["skill.install"], &install); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(install.Required, []string{"transferId", "package", "version", "skill"}) {
		t.Fatalf("skill.install required=%v", install.Required)
	}
}
