package lsp

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"
)

// LSP-N-001 pins typed protocol payloads to the existing LSP wire contract.
func TestProtocol_WirePayloadContracts(t *testing.T) {
	root := t.TempDir()
	source := filepath.Join(root, "sample.go")
	if err := os.WriteFile(source, []byte("package sample\n"), 0600); err != nil {
		t.Fatal(err)
	}
	classFile := filepath.Join(root, "Sample.class")
	if err := os.WriteFile(classFile, []byte("class"), 0600); err != nil {
		t.Fatal(err)
	}
	events := make(chan fakeRequest, 32)
	supervisor := &fakeSupervisor{events: events}
	client, err := Launch(context.Background(), ServerConfig{ID: "jdtls", Command: []string{"jdtls"}}, root, "/jdtls", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err := client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}

	rootURI := pathURI(root)
	initialize := takeFakeEvent(t, events, "initialize")
	assertWireJSON(t, initialize.Params, map[string]any{
		"processId": float64(17), "rootPath": root, "rootUri": rootURI,
		"workspaceFolders": []any{map[string]any{"name": "workspace", "uri": rootURI}},
		"capabilities": map[string]any{
			"general":      map[string]any{"positionEncodings": []any{"utf-16", "utf-8"}},
			"workspace":    map[string]any{"workspaceFolders": true, "configuration": false, "applyEdit": false},
			"textDocument": map[string]any{"synchronization": map[string]any{"willSave": false, "willSaveWaitUntil": false, "didSave": true}, "definition": map[string]any{"linkSupport": true}},
		},
		"initializationOptions": map[string]any{"extendedClientCapabilities": map[string]any{"classFileContentsSupport": true}},
	})
	initialized := takeFakeEvent(t, events, "initialized")
	assertWireJSON(t, initialized.Params, map[string]any{})

	if err := client.Sync(source); err != nil {
		t.Fatal(err)
	}
	didOpen := takeFakeEvent(t, events, "textDocument/didOpen")
	assertWireJSON(t, didOpen.Params, map[string]any{"textDocument": map[string]any{
		"uri": pathURI(source), "languageId": "go", "version": float64(1), "text": "package sample\n",
	}})

	if _, err := client.Definition(context.Background(), source, 1, 1); err != nil {
		t.Fatal(err)
	}
	definition := takeFakeEvent(t, events, "textDocument/definition")
	assertWireJSON(t, definition.Params, map[string]any{
		"textDocument": map[string]any{"uri": pathURI(source)},
		"position":     map[string]any{"line": float64(0), "character": float64(0)},
	})

	if _, err := client.WorkspaceSymbols(context.Background(), "Sample", 10); err != nil {
		t.Fatal(err)
	}
	workspaceSymbol := takeFakeEvent(t, events, "workspace/symbol")
	assertWireJSON(t, workspaceSymbol.Params, map[string]any{"query": "Sample"})

	const jdtTarget = "jdt://contents/java.base/java/lang/String.class"
	if _, err := client.JavaDecompile(context.Background(), jdtTarget); err != nil {
		t.Fatal(err)
	}
	classContents := takeFakeEvent(t, events, "java/classFileContents")
	assertWireJSON(t, classContents.Params, map[string]any{"uri": jdtTarget})

	if _, err := client.JavaDecompile(context.Background(), classFile); err != nil {
		t.Fatal(err)
	}
	decompile := takeFakeEvent(t, events, "workspace/executeCommand")
	assertWireJSON(t, decompile.Params, map[string]any{"command": "java.decompile", "arguments": []any{pathURI(classFile)}})

	if err := client.Stop(time.Millisecond); err != nil {
		t.Fatal(err)
	}
}

func TestProtocol_ServerCapabilitySupportShapes(t *testing.T) {
	for _, test := range []struct {
		body string
		want bool
	}{{`{"definitionProvider":false}`, false}, {`{"definitionProvider":null}`, false}, {`{"definitionProvider":{}}`, true}, {`{"definitionProvider":true}`, true}} {
		var capabilities ServerCapabilities
		if err := json.Unmarshal([]byte(test.body), &capabilities); err != nil {
			t.Fatal(err)
		}
		if bool(capabilities.DefinitionProvider) != test.want {
			t.Errorf("definition capability from %s = %v, want %v", test.body, capabilities.DefinitionProvider, test.want)
		}
	}
}

func assertWireJSON(t *testing.T, actual json.RawMessage, expected any) {
	t.Helper()
	var decoded any
	if err := json.Unmarshal(actual, &decoded); err != nil {
		t.Fatalf("decode wire JSON %s: %v", actual, err)
	}
	if !reflect.DeepEqual(decoded, expected) {
		t.Fatalf("wire JSON = %#v, want %#v", decoded, expected)
	}
}
