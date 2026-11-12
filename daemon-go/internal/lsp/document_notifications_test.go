package lsp

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// LSP-T-014 verifies document synchronization notification payloads and ordering.
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
