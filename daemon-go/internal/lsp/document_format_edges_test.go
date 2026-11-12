package lsp

import (
	"context"
	"encoding/json"
	"reflect"
	"testing"
	"time"
)

// LSP-T-075 and LSP-T-077 exercise malformed and incomplete definition/symbol results.
func TestDocument_FormattingEdges(t *testing.T) {
	locations, err := formatLocations(json.RawMessage(`[{"targetUri":"jdt://contents/module/Type.class","targetRange":{"start":{"line":2,"character":4}}},null,{"uri":"custom://host/path"},{"uri":""}]`))
	if err != nil {
		t.Fatal(err)
	}
	wantLocations := []string{"jdt://contents/module/Type.class:3:4", "custom://host/path", "unknown"}
	if !reflect.DeepEqual(locations, wantLocations) {
		t.Fatalf("locations = %v, want %v", locations, wantLocations)
	}
	if _, err := formatLocations(json.RawMessage(`[`)); err == nil {
		t.Fatal("malformed location list unexpectedly succeeded")
	}

	symbols, err := formatSymbols(json.RawMessage(`[{"kind":99,"location":{"uri":"file:///tmp/Type.java"}},null,{"name":"Named","kind":1}]`), 10)
	if err != nil {
		t.Fatal(err)
	}
	wantSymbols := []string{"unknown (unknown) - file:///tmp/Type.java", "Named (File) - unknown"}
	if !reflect.DeepEqual(symbols, wantSymbols) {
		t.Fatalf("symbols = %v, want %v", symbols, wantSymbols)
	}
	if _, err := formatSymbols(json.RawMessage(`{}`), 10); err == nil {
		t.Fatal("non-array symbols unexpectedly succeeded")
	}
}

// LSP-T-097 rejects JDT targets on other servers and maps an empty decompile response.
func TestDocument_JavaDecompileFailures(t *testing.T) {
	plain := &Client{server: ServerConfig{ID: "plain"}}
	if _, err := plain.JavaDecompile(context.Background(), "jdt://contents/java.base/java/lang/String.class"); err == nil || err.Error() != "lsp_java_decompile is only supported by jdtls." {
		t.Fatalf("non-JDT decompile error = %v", err)
	}

	supervisor := &fakeSupervisor{emptyMethod: "java/classFileContents"}
	client, err := Launch(context.Background(), ServerConfig{ID: "jdtls", Command: []string{"jdtls"}}, t.TempDir(), "jdtls", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err = client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	const target = "jdt://contents/java.base/java/lang/String.class"
	if _, err = client.JavaDecompile(context.Background(), target); err == nil || err.Error() != "Could not load or decompile class for target: "+target {
		t.Fatalf("empty decompile error = %v", err)
	}
	if err = client.Stop(time.Millisecond); err != nil {
		t.Fatal(err)
	}
}
