package lsp

import (
	"encoding/json"
	"reflect"
	"testing"
)

// LSP-T-074 and LSP-T-075 exercise Location and LocationLink result formatting.
func TestFormatLocations(t *testing.T) {
	var raw json.RawMessage = []byte(`[{"uri":"file:///tmp/a.go","range":{"start":{"line":0,"character":2}}},{"targetUri":"jdt://contents/java.base/java.lang/String.class","targetRange":{"start":{"line":1,"character":0}},"targetSelectionRange":{"start":{"line":2,"character":3}}}]`)
	got, err := formatLocations(raw)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"/tmp/a.go:1:2", "jdt://contents/java.base/java.lang/String.class:3:3"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("locations = %v, want %v", got, want)
	}
	empty, err := formatLocations(json.RawMessage("null"))
	if err != nil || !reflect.DeepEqual(empty, []string{"No results found"}) {
		t.Fatalf("empty = %v, %v", empty, err)
	}
}

// LSP-T-077 and LSP-T-104 cover symbol kinds, unknown metadata and output limits.
func TestFormatSymbols(t *testing.T) {
	raw := json.RawMessage(`[{"name":"A","kind":5,"location":{"uri":"file:///tmp/a.java"}},{"name":"B","kind":"custom"}]`)
	got, err := formatSymbols(raw, 1)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"A (Class) - file:///tmp/a.java"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("symbols = %v, want %v", got, want)
	}
	all, err := formatSymbols(raw, 50)
	if err != nil {
		t.Fatal(err)
	}
	want = []string{"A (Class) - file:///tmp/a.java", "B (custom) - unknown"}
	if !reflect.DeepEqual(all, want) {
		t.Fatalf("symbols = %v, want %v", all, want)
	}
	zero, err := formatSymbols(raw, 0)
	if err != nil || !reflect.DeepEqual(zero, []string{"No symbols found"}) {
		t.Fatalf("zero = %v, %v", zero, err)
	}
}
