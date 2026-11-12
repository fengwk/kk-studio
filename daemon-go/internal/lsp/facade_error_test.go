package lsp

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// LSP-T-011 through LSP-T-014 verify cancellation, timeout, and safe capability error mapping.
func TestCapabilities_ErrorMapping(t *testing.T) {
	source := filepath.Join(t.TempDir(), "main.go")
	if err := os.WriteFile(source, []byte("package main\n"), 0600); err != nil {
		t.Fatal(err)
	}
	arguments, err := json.Marshal(map[string]any{"path": source, "line": 1})
	if err != nil {
		t.Fatal(err)
	}
	call := runtime.CapabilityCall{InvocationID: "mapping", CapabilityID: "lsp.goto-definition", Arguments: arguments}

	t.Run("pre-cancel", func(t *testing.T) {
		facade := NewFacade(&fakeSupervisor{}, PoolOptions{})
		ctx, cancel := context.WithCancel(context.Background())
		cancel()
		confirmed := 0
		for _, id := range []string{"lsp.goto-definition", "lsp.workspace-symbols", "lsp.java-decompile"} {
			call.CapabilityID = id
			result, err := facade.Capabilities()[id](ctx, call, runtime.CapabilityContext{ConfirmExecutionExit: func() { confirmed++ }})
			if err != nil || !result.Error || result.Contents[0].Text != "Operation cancelled" {
				t.Fatalf("%s result=%#v err=%v", id, result, err)
			}
		}
		if confirmed != 3 {
			t.Fatalf("confirmations=%d, want 3", confirmed)
		}
	})

	t.Run("pre-timeout", func(t *testing.T) {
		facade := NewFacade(&fakeSupervisor{}, PoolOptions{})
		ctx, cancel := context.WithDeadline(context.Background(), time.Now().Add(-time.Second))
		defer cancel()
		for _, id := range []string{"lsp.goto-definition", "lsp.workspace-symbols", "lsp.java-decompile"} {
			call.CapabilityID = id
			result, err := facade.Capabilities()[id](ctx, call, runtime.CapabilityContext{})
			if err != nil || !result.Error || result.Contents[0].Text != "Operation timed out" {
				t.Fatalf("%s result=%#v err=%v", id, result, err)
			}
		}
	})

	t.Run("bootstrap failure preserves safe text", func(t *testing.T) {
		call.CapabilityID = "lsp.goto-definition"
		call.Arguments = arguments
		root := t.TempDir()
		executable := filepath.Join(root, "fake-lsp")
		if err := os.WriteFile(executable, []byte("fake"), 0700); err != nil {
			t.Fatal(err)
		}
		supervisor := &fakeSupervisor{startErr: errors.New("secret spawn details")}
		facade := NewFacade(supervisor, PoolOptions{})
		if err := facade.Update([]config.LSPServerConfig{{ID: "go", Command: []string{executable}, Extensions: []string{".go"}}}); err != nil {
			t.Fatal(err)
		}
		confirmed := 0
		result, err := facade.Capabilities()[call.CapabilityID](context.Background(), call, runtime.CapabilityContext{ConfirmExecutionExit: func() { confirmed++ }})
		want := "LSP server 'go' cannot be started: " + executable
		if err != nil || !result.Error || result.Contents[0].Text != want || confirmed != 1 {
			t.Fatalf("result=%#v err=%v confirmations=%d", result, err, confirmed)
		}
		if err := facade.Close(); err != nil {
			t.Fatal(err)
		}
	})

	t.Run("in-flight cancel", func(t *testing.T) {
		call.CapabilityID = "lsp.goto-definition"
		call.Arguments = arguments
		supervisor := &fakeSupervisor{blockMethod: "textDocument/definition", startEntered: make(chan struct{}, 1)}
		facade := NewFacade(supervisor, PoolOptions{IdleTimeout: time.Hour})
		executable := filepath.Join(t.TempDir(), "fake-lsp")
		if err := os.WriteFile(executable, []byte("fake"), 0700); err != nil {
			t.Fatal(err)
		}
		if err := facade.Update([]config.LSPServerConfig{{ID: "go", Command: []string{executable}, Extensions: []string{".go"}}}); err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		confirmed := 0
		resultCh := make(chan runtime.CapabilityResult, 1)
		errCh := make(chan error, 1)
		go func() {
			result, err := facade.Capabilities()[call.CapabilityID](ctx, call, runtime.CapabilityContext{ConfirmExecutionExit: func() { confirmed++ }})
			resultCh <- result
			errCh <- err
		}()
		select {
		case <-supervisor.startEntered:
		case <-time.After(time.Second):
			t.Fatal("server start was not observed")
		}
		waitForFakeRequest(t, supervisor.process, "textDocument/definition")
		cancel()
		result, err := <-resultCh, <-errCh
		if err != nil || !result.Error || result.Contents[0].Text != "Operation cancelled" || confirmed != 0 {
			t.Fatalf("result=%#v err=%v confirmations=%d", result, err, confirmed)
		}
		if err := facade.Close(); err != nil {
			t.Fatal(err)
		}
	})

	t.Run("in-flight timeout", func(t *testing.T) {
		root := t.TempDir()
		executable := filepath.Join(root, "fake-lsp")
		if err := os.WriteFile(executable, []byte("fake"), 0700); err != nil {
			t.Fatal(err)
		}
		source := filepath.Join(root, "main.go")
		if err := os.WriteFile(source, []byte("package main\n"), 0600); err != nil {
			t.Fatal(err)
		}
		supervisor := &fakeSupervisor{blockMethod: "textDocument/definition"}
		facade := NewFacade(supervisor, PoolOptions{IdleTimeout: time.Hour})
		if err := facade.Update([]config.LSPServerConfig{{ID: "go", Command: []string{executable}, Extensions: []string{".go"}}}); err != nil {
			t.Fatal(err)
		}
		arguments, err := json.Marshal(map[string]any{"path": source, "line": 1})
		if err != nil {
			t.Fatal(err)
		}
		call.Arguments = arguments
		call.TimeoutMillis = 20
		confirmed := 0
		result, err := facade.Capabilities()[call.CapabilityID](context.Background(), call, runtime.CapabilityContext{ConfirmExecutionExit: func() { confirmed++ }})
		if err != nil || !result.Error || result.Contents[0].Text != "Operation timed out" || confirmed != 0 {
			t.Fatalf("result=%#v err=%v confirmations=%d", result, err, confirmed)
		}
		if err := facade.Close(); err != nil {
			t.Fatal(err)
		}
	})
}
