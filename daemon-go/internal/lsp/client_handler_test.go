package lsp

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/sourcegraph/jsonrpc2"
)

// LSP-T-083 checks server-request responses and client notification handling.
func TestProtocol_ServerRequests(t *testing.T) {
	methods := []struct {
		method string
		params any
		check  func(*testing.T, json.RawMessage)
	}{{"workspace/configuration", map[string]any{"items": []any{map[string]any{}, map[string]any{}}}, func(t *testing.T, raw json.RawMessage) {
		var got []map[string]any
		if err := json.Unmarshal(raw, &got); err != nil || len(got) != 2 {
			t.Fatalf("configuration result = %s, %v", raw, err)
		}
	}}, {"workspace/workspaceFolders", nil, func(t *testing.T, raw json.RawMessage) {
		var got []map[string]string
		if err := json.Unmarshal(raw, &got); err != nil || len(got) != 1 || got[0]["name"] != "workspace" {
			t.Fatalf("workspace folders = %s, %v", raw, err)
		}
	}}, {"workspace/applyEdit", nil, func(t *testing.T, raw json.RawMessage) {
		var got map[string]bool
		if err := json.Unmarshal(raw, &got); err != nil || got["applied"] {
			t.Fatalf("apply edit result = %s, %v", raw, err)
		}
	}}, {"client/registerCapability", nil, func(t *testing.T, raw json.RawMessage) {
		if string(raw) != "null" {
			t.Fatalf("register result = %s", raw)
		}
	}}}
	for _, item := range methods {
		t.Run(item.method, func(t *testing.T) {
			serverSide, clientSide := net.Pipe()
			client := &Client{root: t.TempDir()}
			conn := jsonrpc2.NewConn(context.Background(), jsonrpc2.NewBufferedStream(clientSide, newLSPCodec()), rpcHandler(client.handleRequest))
			t.Cleanup(func() { _ = conn.Close(); _ = serverSide.Close() })
			params, err := json.Marshal(item.params)
			if err != nil {
				t.Fatal(err)
			}
			rawParams := json.RawMessage(params)
			request := &jsonrpc2.Request{ID: jsonrpc2.ID{Num: 900}, Method: item.method, Params: &rawParams}
			done := make(chan struct{})
			go func() { client.handleRequest(context.Background(), conn, request); close(done) }()
			var response json.RawMessage
			if err = newLSPCodec().ReadObject(bufio.NewReader(serverSide), &response); err != nil {
				t.Fatal(err)
			}
			<-done
			var envelope struct {
				Result json.RawMessage `json:"result"`
			}
			if err = json.Unmarshal(response, &envelope); err != nil {
				t.Fatal(err)
			}
			item.check(t, envelope.Result)
		})
	}
	serverSide, clientSide := net.Pipe()
	client := &Client{root: t.TempDir()}
	conn := jsonrpc2.NewConn(context.Background(), jsonrpc2.NewBufferedStream(clientSide, newLSPCodec()), rpcHandler(client.handleRequest))
	defer func() { _ = conn.Close(); _ = serverSide.Close() }()
	serverSide.SetReadDeadline(time.Now().Add(5 * time.Millisecond))
	notification := &jsonrpc2.Request{Method: "window/logMessage", Notif: true}
	rpcHandler(client.handleRequest).Handle(context.Background(), conn, notification)
	var buf [1]byte
	if _, err := serverSide.Read(buf[:]); err != io.EOF {
		if netErr, ok := err.(net.Error); !ok || !netErr.Timeout() {
			t.Fatalf("notification response read error = %v", err)
		}
	}
}

// LSP-T-078 and LSP-T-097 verify static capabilities and JDTLS-specific support.
func TestClient_StaticAndJDTLSSupport(t *testing.T) {
	plain := &Client{capabilities: map[string]json.RawMessage{"definitionProvider": json.RawMessage("true")}}
	if !plain.Supports("textDocument/definition") || plain.Supports("textDocument/unknown") {
		t.Fatal("plain server capability selection is incorrect")
	}
	java := &Client{jdtls: true}
	if !java.Supports("workspace/executeCommand") {
		t.Fatal("JDTLS-specific capability was not enabled")
	}
}

// LSP-F-025 verifies exit reports are stable and distinguish live clients.
func TestClient_ExitErrorAndAliveStates(t *testing.T) {
	client := &Client{server: ServerConfig{ID: "fake"}, exited: make(chan native.ProcessExit, 1), exitErr: make(chan error, 1)}
	if got := client.exitError(); got.Error() != "LSP client stopped" {
		t.Fatalf("live exit error = %v", got)
	}
	client.exited <- native.ProcessExit{Code: 7}
	if client.IsAlive() {
		t.Fatal("client with a reported exit is alive")
	}
	if got := client.exitError(); got.Error() != "LSP server 'fake' exited (exit code 7)" {
		t.Fatalf("exit error = %v", got)
	}
	stopped := &Client{exited: make(chan native.ProcessExit, 1), exitErr: make(chan error, 1)}
	stopped.exitErr <- errors.New("wait failed")
	if stopped.IsAlive() {
		t.Fatal("client whose process wait failed is alive")
	}
}
