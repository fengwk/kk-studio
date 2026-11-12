package lsp

import (
	"encoding/json"
	"path/filepath"
	"reflect"
	"testing"
)

// LSP-T-074 and LSP-T-075 exercise Location and LocationLink result formatting.
func TestFormatLocations(t *testing.T) {
	got := formatLocations(mustDefinitionResult(t, `[{"uri":"file:///tmp/a.go","range":{"start":{"line":0,"character":2}}},{"targetUri":"jdt://contents/java.base/java.lang/String.class","targetRange":{"start":{"line":1,"character":0}},"targetSelectionRange":{"start":{"line":2,"character":3}}}]`))
	want := []string{filepath.FromSlash("/tmp/a.go") + ":1:3", "jdt://contents/java.base/java.lang/String.class:3:4"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("locations = %v, want %v", got, want)
	}
	empty := formatLocations(mustDefinitionResult(t, `null`))
	if !reflect.DeepEqual(empty, []string{"No results found"}) {
		t.Fatalf("empty = %v", empty)
	}
}

// LSP-T-077 and LSP-T-104 cover symbol kinds, unknown metadata and output limits.
func TestFormatSymbols(t *testing.T) {
	result := mustWorkspaceSymbolsResult(t, `[{"name":"A","kind":5,"location":{"uri":"file:///tmp/a.java"}},{"name":"B","kind":"custom"}]`)
	got, err := formatSymbols(result, 1)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"A (Class) - file:///tmp/a.java"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("symbols = %v, want %v", got, want)
	}
	all, err := formatSymbols(result, 50)
	if err != nil {
		t.Fatal(err)
	}
	want = []string{"A (Class) - file:///tmp/a.java", "B (custom) - unknown"}
	if !reflect.DeepEqual(all, want) {
		t.Fatalf("symbols = %v, want %v", all, want)
	}
	zero, err := formatSymbols(result, 0)
	if err != nil || !reflect.DeepEqual(zero, []string{"No symbols found"}) {
		t.Fatalf("zero = %v, %v", zero, err)
	}
}

func mustDefinitionResult(t *testing.T, raw string) DefinitionResult {
	t.Helper()
	var result DefinitionResult
	if err := json.Unmarshal([]byte(raw), &result); err != nil {
		t.Fatal(err)
	}
	return result
}

func mustWorkspaceSymbolsResult(t *testing.T, raw string) WorkspaceSymbolsResult {
	t.Helper()
	var result WorkspaceSymbolsResult
	if err := json.Unmarshal([]byte(raw), &result); err != nil {
		t.Fatal(err)
	}
	return result
}
