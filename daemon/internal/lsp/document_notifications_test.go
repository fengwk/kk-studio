package lsp

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/textcodec"
)

// Verifies document synchronization notification payloads and ordering.
func TestDocument_NotificationSequenceAndContent(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sample.go")
	if err := os.WriteFile(path, []byte("first\n"), 0600); err != nil {
		t.Fatal(err)
	}
	events := make(chan fakeRequest, 16)
	supervisor := &fakeSupervisor{events: events}
	client, err := Launch(context.Background(), ServerConfig{ID: "go", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err := client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	for _, method := range []string{"initialize", "initialized"} {
		takeFakeEvent(t, events, method)
	}
	if err := client.Sync(path); err != nil {
		t.Fatal(err)
	}
	opened := takeFakeEvent(t, events, "textDocument/didOpen")
	var openParams struct {
		TextDocument struct {
			URI        string `json:"uri"`
			LanguageID string `json:"languageId"`
			Version    int    `json:"version"`
			Text       string `json:"text"`
		} `json:"textDocument"`
	}
	if err := json.Unmarshal(opened.Params, &openParams); err != nil {
		t.Fatal(err)
	}
	if openParams.TextDocument.URI != pathURI(path) || openParams.TextDocument.LanguageID != "go" || openParams.TextDocument.Version != 1 || openParams.TextDocument.Text != "first\n" {
		t.Fatalf("didOpen params = %#v", openParams)
	}
	if err := os.WriteFile(path, []byte("second\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := client.Sync(path); err != nil {
		t.Fatal(err)
	}
	changed := takeFakeEvent(t, events, "textDocument/didChange")
	saved := takeFakeEvent(t, events, "textDocument/didSave")
	var changeParams struct {
		TextDocument struct {
			URI     string `json:"uri"`
			Version int    `json:"version"`
		} `json:"textDocument"`
		ContentChanges []struct {
			Text string `json:"text"`
		} `json:"contentChanges"`
	}
	if err := json.Unmarshal(changed.Params, &changeParams); err != nil {
		t.Fatal(err)
	}
	if changeParams.TextDocument.URI != pathURI(path) || changeParams.TextDocument.Version != 2 || len(changeParams.ContentChanges) != 1 || changeParams.ContentChanges[0].Text != "second\n" {
		t.Fatalf("didChange params = %#v", changeParams)
	}
	var saveParams struct {
		TextDocument struct {
			URI string `json:"uri"`
		} `json:"textDocument"`
	}
	if err := json.Unmarshal(saved.Params, &saveParams); err != nil || saveParams.TextDocument.URI != pathURI(path) {
		t.Fatalf("didSave params = %#v err=%v", saveParams, err)
	}
	if err := client.Stop(time.Millisecond); err != nil {
		t.Fatal(err)
	}
}

// A missing definition source closes its overlay but never dispatches a stale definition.
// A second deletion, without a callback or direct read, is caught before workspace/symbol.
func TestDocument_DeletedOverlayClosesAndReopens(t *testing.T) {
	root := t.TempDir()
	path := filepath.Join(root, "sample.go")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	events := make(chan fakeRequest, 16)
	client, err := Launch(ctx, ServerConfig{ID: "go", Command: []string{"fake"}}, root, "/fake", &fakeSupervisor{events: events})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := client.Stop(time.Second); err != nil {
			t.Error(err)
		}
	})
	if err := client.Initialize(ctx, time.Second); err != nil {
		t.Fatal(err)
	}
	takeFakeEvent(t, events, "initialize")
	takeFakeEvent(t, events, "initialized")

	open := func() {
		t.Helper()
		const text = "package sample\n"
		if err := os.WriteFile(path, []byte(text), 0600); err != nil {
			t.Fatal(err)
		}
		if err := client.Sync(path); err != nil {
			t.Fatal(err)
		}
		var p DidOpenTextDocumentParams
		if err := json.Unmarshal(takeFakeEvent(t, events, "textDocument/didOpen").Params, &p); err != nil || p.TextDocument.URI != pathURI(path) || p.TextDocument.Version != 1 || p.TextDocument.Text != text {
			t.Fatalf("didOpen params = %#v, err = %v", p, err)
		}
	}
	remove := func() {
		t.Helper()
		if err := os.Remove(path); err != nil {
			t.Fatal(err)
		}
	}
	checkClose := func() {
		t.Helper()
		var p DidCloseTextDocumentParams
		if err := json.Unmarshal(takeFakeEvent(t, events, "textDocument/didClose").Params, &p); err != nil || p.TextDocument.URI != pathURI(path) {
			t.Fatalf("didClose params = %#v, err = %v", p, err)
		}
	}

	open()
	remove()
	// An existing directory cannot be read as text, but that is not ENOENT.
	if err := os.Mkdir(path, 0700); err != nil {
		t.Fatal(err)
	}
	if _, err := client.Definition(ctx, path, 1, 1); err == nil || errors.Is(err, os.ErrNotExist) {
		t.Fatalf("definition of a directory = %v, want a non-ENOENT read failure", err)
	}
	remove()
	if _, err := client.Definition(ctx, path, 1, 1); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("definition of deleted file = %v, want ENOENT", err)
	}
	checkClose()
	open()
	remove()
	if _, err := client.WorkspaceSymbols(ctx, "sample", 10); err != nil {
		t.Fatal(err)
	}
	checkClose()
	takeFakeEvent(t, events, "workspace/symbol")
	open()
}

// Both query types must update dependency overlays before sending the request and must not query stale text on failure.
func TestDocument_QueriesRefreshOpenOverlays(t *testing.T) {
	for _, method := range []string{"workspace/symbol", "textDocument/definition"} {
		t.Run(method, func(t *testing.T) {
			root := t.TempDir()
			source, dependency := filepath.Join(root, "source.go"), filepath.Join(root, "dependency.go")
			write := func(path, text string) {
				t.Helper()
				if err := os.WriteFile(path, []byte(text), 0600); err != nil {
					t.Fatal(err)
				}
			}
			write(source, "package sample\n")
			write(dependency, "package sample\nfunc before() {}\n")
			write(filepath.Join(root, "unopened.go"), "\x00")
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			events := make(chan fakeRequest, 16)
			client, err := Launch(ctx, ServerConfig{ID: "go", Command: []string{"fake"}}, root, "/fake", &fakeSupervisor{events: events})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := client.Stop(time.Second); err != nil {
					t.Error(err)
				}
			})
			if err := client.Initialize(ctx, time.Second); err != nil {
				t.Fatal(err)
			}
			takeFakeEvent(t, events, "initialize")
			takeFakeEvent(t, events, "initialized")
			for _, path := range []string{source, dependency} {
				if err := client.Sync(path); err != nil {
					t.Fatal(err)
				}
				takeFakeEvent(t, events, "textDocument/didOpen")
			}
			query := func() error {
				if method == "workspace/symbol" {
					_, err := client.WorkspaceSymbols(ctx, "after", 10)
					return err
				}
				_, err := client.Definition(ctx, source, 1, 1)
				return err
			}
			write(dependency, "\x00")
			if err := query(); !errors.Is(err, textcodec.ErrBinary) {
				t.Fatalf("query with an invalid open dependency = %v", err)
			}
			const updated = "package sample\nfunc after() {}\n"
			write(dependency, updated)
			if err := query(); err != nil {
				t.Fatal(err)
			}
			var change DidChangeTextDocumentParams
			if err := json.Unmarshal(takeFakeEvent(t, events, "textDocument/didChange").Params, &change); err != nil ||
				change.TextDocument.URI != pathURI(dependency) || change.TextDocument.Version != 2 ||
				len(change.ContentChanges) != 1 || change.ContentChanges[0].Text != updated {
				t.Fatalf("didChange params = %#v, err = %v", change, err)
			}
			takeFakeEvent(t, events, "textDocument/didSave")
			takeFakeEvent(t, events, method)
		})
	}
}

func takeFakeEvent(t *testing.T, events <-chan fakeRequest, method string) fakeRequest {
	t.Helper()
	select {
	case event := <-events:
		if event.Method != method {
			t.Fatalf("event method=%q, want %q", event.Method, method)
		}
		return event
	case <-time.After(time.Second):
		t.Fatalf("event %q not observed", method)
		return fakeRequest{}
	}
}
