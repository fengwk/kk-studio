package lsp

import (
	"context"
	"encoding/json"
	"testing"
	"time"
)

// LSP-T-073 checks required initialize capability fields and the initialized notification.
func TestProtocol_InitializeContract(t *testing.T) {
	paramsCh := make(chan json.RawMessage, 1)
	supervisor := &fakeSupervisor{initializeParams: paramsCh}
	client, err := Launch(context.Background(), ServerConfig{ID: "jdtls", Command: []string{"jdtls"}}, t.TempDir(), "/jdtls", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err := client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	var params struct {
		ProcessID        int    `json:"processId"`
		RootURI          string `json:"rootUri"`
		WorkspaceFolders []struct {
			Name string `json:"name"`
			URI  string `json:"uri"`
		} `json:"workspaceFolders"`
		Capabilities struct {
			General struct {
				PositionEncodings []string `json:"positionEncodings"`
			} `json:"general"`
			Workspace struct {
				Folders       bool `json:"workspaceFolders"`
				Configuration bool `json:"configuration"`
				ApplyEdit     bool `json:"applyEdit"`
			} `json:"workspace"`
			TextDocument struct {
				Synchronization struct {
					WillSave          bool `json:"willSave"`
					WillSaveWaitUntil bool `json:"willSaveWaitUntil"`
					DidSave           bool `json:"didSave"`
				} `json:"synchronization"`
				Definition struct {
					LinkSupport bool `json:"linkSupport"`
				} `json:"definition"`
			} `json:"textDocument"`
		} `json:"capabilities"`
		InitializationOptions struct {
			ExtendedClientCapabilities struct {
				ClassFileContentsSupport bool `json:"classFileContentsSupport"`
			} `json:"extendedClientCapabilities"`
		} `json:"initializationOptions"`
	}
	select {
	case raw := <-paramsCh:
		if err := json.Unmarshal(raw, &params); err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("initialize parameters were not observed")
	}
	if params.ProcessID <= 0 || params.RootURI == "" || len(params.WorkspaceFolders) != 1 || params.WorkspaceFolders[0].Name != "workspace" || params.WorkspaceFolders[0].URI != params.RootURI {
		t.Fatalf("initialize root/process fields = %#v", params)
	}
	if len(params.Capabilities.General.PositionEncodings) != 2 || params.Capabilities.General.PositionEncodings[0] != "utf-16" || params.Capabilities.General.PositionEncodings[1] != "utf-8" || !params.Capabilities.Workspace.Folders || params.Capabilities.Workspace.Configuration || params.Capabilities.Workspace.ApplyEdit || params.Capabilities.TextDocument.Synchronization.WillSave || params.Capabilities.TextDocument.Synchronization.WillSaveWaitUntil || !params.Capabilities.TextDocument.Synchronization.DidSave || !params.Capabilities.TextDocument.Definition.LinkSupport {
		t.Fatalf("initialize capability fields = %#v", params.Capabilities)
	}
	if !params.InitializationOptions.ExtendedClientCapabilities.ClassFileContentsSupport {
		t.Fatalf("jdtls initialization options = %#v", params.InitializationOptions)
	}
	waitForFakeRequest(t, supervisor.process, "initialized")
	if err := client.Stop(time.Millisecond); err != nil {
		t.Fatal(err)
	}
}
