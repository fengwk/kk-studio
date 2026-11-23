package lsp

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// The request dispatch must follow sync notifications, and the send gate must be
// released before the server answers so another document update can proceed.
func TestDocument_DefinitionDispatchAndConcurrentSync(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sample.go")
	if err := os.WriteFile(path, []byte("first\n"), 0600); err != nil {
		t.Fatal(err)
	}
	events := make(chan fakeRequest, 16)
	supervisor := &fakeSupervisor{blockMethod: "textDocument/definition", events: events}
	client, err := Launch(context.Background(), ServerConfig{ID: "go", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err = client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	takeFakeEvent(t, events, "initialize")
	takeFakeEvent(t, events, "initialized")
	if err = client.Sync(path); err != nil {
		t.Fatal(err)
	}
	takeFakeEvent(t, events, "textDocument/didOpen")
	if err = os.WriteFile(path, []byte("before query\n"), 0600); err != nil {
		t.Fatal(err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	definitionDone := make(chan error, 1)
	go func() {
		_, callErr := client.Definition(ctx, path, 1, 1)
		definitionDone <- callErr
	}()
	takeFakeEvent(t, events, "textDocument/didChange")
	takeFakeEvent(t, events, "textDocument/didSave")
	takeFakeEvent(t, events, "textDocument/definition")

	if err = os.WriteFile(path, []byte("second\n"), 0600); err != nil {
		t.Fatal(err)
	}
	syncDone := make(chan error, 1)
	go func() { syncDone <- client.Sync(path) }()
	select {
	case err = <-syncDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("document sync waited for the definition response")
	}
	takeFakeEvent(t, events, "textDocument/didChange")
	takeFakeEvent(t, events, "textDocument/didSave")

	cancel()
	select {
	case err = <-definitionDone:
		if err == nil {
			t.Fatal("blocked definition request unexpectedly succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("definition request did not observe cancellation")
	}
	if err = client.Stop(time.Millisecond); err != nil {
		t.Fatal(err)
	}
}

// A failed document notification must not make the local cache claim it was synchronized.
func TestDocument_SyncFailureDoesNotAdvanceState(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sample.go")
	if err := os.WriteFile(path, []byte("first\n"), 0600); err != nil {
		t.Fatal(err)
	}
	client := &Client{server: ServerConfig{ID: "go"}, documents: map[string]document{}, capabilities: ServerCapabilities{DefinitionProvider: true}}
	client.closed.Store(true)
	if err := client.Sync(path); err == nil {
		t.Fatal("Sync succeeded after the client stopped")
	}
	abs, err := filepath.Abs(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := client.documents[abs]; ok {
		t.Fatal("failed didOpen advanced the synchronized document cache")
	}
	if _, err := client.Definition(context.Background(), path, 1, 1); err == nil {
		t.Fatal("definition succeeded when document synchronization failed")
	}

	client.documents[abs] = document{text: "first\n", version: 1}
	if err := os.WriteFile(path, []byte("second\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := client.Sync(path); err == nil {
		t.Fatal("Sync succeeded after the client stopped")
	}
	if got := client.documents[abs]; got.text != "first\n" || got.version != 1 {
		t.Fatalf("failed didChange advanced the synchronized document cache: %#v", got)
	}
}
