// Package capability declares daemon capabilities together with their embedded JSON input schemas.
package capability

import (
	"embed"
	"encoding/json"
	"fmt"
	"io/fs"
	"path/filepath"
	"sort"
	"strings"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

//go:embed schemas/*.schema.json
var schemaFiles embed.FS

// Declaration binds one executable capability to its protocol-facing input schema.
type Declaration struct {
	ID          string
	InputSchema json.RawMessage
	Execute     runtime.CapabilityExecutor
}

// Declare returns a complete schema-backed declaration set for the supplied executors.
func Declare(executors map[string]runtime.CapabilityExecutor) ([]Declaration, error) {
	schemas, err := Schemas()
	if err != nil {
		return nil, err
	}
	if len(schemas) != len(executors) {
		return nil, fmt.Errorf("capability schema and executor sets differ")
	}
	declarations := make([]Declaration, 0, len(executors))
	for id, execute := range executors {
		schema, ok := schemas[id]
		if !ok || execute == nil {
			return nil, fmt.Errorf("capability %q has no valid schema or executor", id)
		}
		declarations = append(declarations, Declaration{ID: id, InputSchema: schema, Execute: execute})
	}
	sort.Slice(declarations, func(i, j int) bool { return declarations[i].ID < declarations[j].ID })
	return declarations, nil
}

// Schemas returns every embedded schema keyed by its protocol capability identifier.
func Schemas() (map[string]json.RawMessage, error) {
	entries, err := fs.ReadDir(schemaFiles, "schemas")
	if err != nil {
		return nil, fmt.Errorf("read capability schemas: %w", err)
	}
	schemas := make(map[string]json.RawMessage, len(entries))
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".schema.json") {
			continue
		}
		data, err := schemaFiles.ReadFile(filepath.Join("schemas", entry.Name()))
		if err != nil {
			return nil, fmt.Errorf("read capability schema %s: %w", entry.Name(), err)
		}
		if !json.Valid(data) {
			return nil, fmt.Errorf("capability schema %s is invalid JSON", entry.Name())
		}
		name := strings.TrimSuffix(entry.Name(), ".schema.json")
		schemas[name] = json.RawMessage(data)
	}
	return schemas, nil
}
