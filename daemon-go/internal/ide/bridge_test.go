package ide

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"
)

// IDE-T-013 verifies loopback bridge authorization and route validation.
func TestBridgeAuthAndRoutes(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	request := func(path, token string) int {
		req, err := http.NewRequest(http.MethodGet, bridge.URL+path, nil)
		if err != nil {
			t.Fatal(err)
		}
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		response, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		return response.StatusCode
	}
	if status := request("/bridge/stream", ""); status != http.StatusUnauthorized {
		t.Fatalf("missing token status = %d", status)
	}
	if status := request("/missing", bridge.Secret); status != http.StatusNotFound {
		t.Fatalf("unknown route status = %d", status)
	}
	if status := request("/bridge/stream", bridge.Secret); status != http.StatusBadRequest {
		t.Fatalf("missing workspace status = %d", status)
	}
	bridge.Assign("11111111-1111-4111-8111-111111111111", "/managed/view.code-workspace", 1, "/managed")
	invalidHost := "/bridge/stream?workspacePath=%2Fmanaged%2Fview.code-workspace&hostId=UPPER&folder=%2Fmanaged"
	if status := request(invalidHost, bridge.Secret); status != http.StatusBadRequest {
		t.Fatalf("invalid host status = %d", status)
	}
}

// IDE-T-019 verifies NDJSON command delivery, command results, and PTY callbacks.
func TestBridgeCommandResultAndPTY(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	viewID := "11111111-1111-4111-8111-111111111111"
	hostID := "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	workspace := "/managed/view.code-workspace"
	bridge.Assign(viewID, workspace, 1, "/managed")
	streamCtx, stopStream := context.WithCancel(context.Background())
	defer stopStream()
	params := url.Values{"workspacePath": {workspace}, "hostId": {hostID}, "folder": {"/managed"}}
	req, _ := http.NewRequestWithContext(streamCtx, http.MethodGet, bridge.URL+"/bridge/stream?"+params.Encode(), nil)
	req.Header.Set("Authorization", "Bearer "+bridge.Secret)
	response, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("stream status = %d", response.StatusCode)
	}
	reader := bufio.NewReader(response.Body)
	if _, err := reader.ReadString('\n'); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	pending := make(chan BridgeResult, 1)
	go func() {
		result, _ := bridge.Request(ctx, viewID, BridgeCommand{Op: "inspect"}, BridgeRequestOptions{})
		pending <- result
	}()
	var command struct {
		ID string `json:"id"`
		Op string `json:"op"`
	}
	if err := json.NewDecoder(reader).Decode(&command); err != nil {
		t.Fatal(err)
	}
	if command.Op != "inspect" || command.ID == "" {
		t.Fatalf("command = %#v", command)
	}
	body, _ := json.Marshal(map[string]any{"id": command.ID, "ok": true, "clean": true})
	resultResponse := postBridge(t, bridge, "/bridge/result", body)
	if resultResponse != http.StatusNoContent {
		t.Fatalf("result status = %d", resultResponse)
	}
	select {
	case result := <-pending:
		if !result.OK || result.Clean == nil || !*result.Clean {
			t.Fatalf("result = %#v", result)
		}
	case <-ctx.Done():
		t.Fatal("command did not resolve")
	}
	pty := make(chan string, 1)
	bridge.OnPTY(func(id string, pid int) error { pty <- fmt.Sprintf("%s:%d", id, pid); return nil })
	body, _ = json.Marshal(map[string]any{"workspacePath": workspace, "pid": 42})
	if status := postBridge(t, bridge, "/bridge/pty", body); status != http.StatusNoContent {
		t.Fatalf("pty status = %d", status)
	}
	if got := <-pty; got != viewID+":42" {
		t.Fatalf("pty callback = %q", got)
	}
}

// IDE-T-018 verifies malformed result and PTY payloads are rejected without binding an unknown workspace.
func TestValidateResultAndPTY(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	if status := postBridge(t, bridge, "/bridge/result", []byte("not-json")); status != http.StatusBadRequest {
		t.Fatalf("invalid result status = %d", status)
	}
	if status := postBridge(t, bridge, "/bridge/pty", []byte(`{"workspacePath":"/unknown","pid":4}`)); status != http.StatusForbidden {
		t.Fatalf("unknown PTY workspace status = %d", status)
	}
	bridge.Assign("11111111-1111-4111-8111-111111111111", "/managed/view.code-workspace", 1, "/managed")
	if status := postBridge(t, bridge, "/bridge/pty", []byte(`{"workspacePath":"/managed/view.code-workspace","pid":0}`)); status != http.StatusBadRequest {
		t.Fatalf("invalid PTY pid status = %d", status)
	}
}

// IDE-T-022 confirms a directory transition only after a replacement host reports the target folder.
func TestConfirmDirectoryOnReconnect(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	viewID := "11111111-1111-4111-8111-111111111111"
	workspace := "/managed/view.code-workspace"
	oldHost := "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	newHost := "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
	bridge.Assign(viewID, workspace, 1, "/managed/old")
	streamCtx, cancelStream := context.WithCancel(context.Background())
	defer cancelStream()
	oldStream, oldReader := openManualBridgeStream(t, streamCtx, bridge, workspace, oldHost, "/managed/old")
	defer oldStream.Body.Close()
	if _, err := oldReader.ReadString('\n'); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	resultCh := make(chan BridgeResult, 1)
	go func() {
		result, _ := bridge.Request(ctx, viewID, BridgeCommand{Op: "changeDirectory", Directory: "/managed/new"}, BridgeRequestOptions{ExpectedDirectory: "/managed/new"})
		resultCh <- result
	}()
	var command struct {
		ID string `json:"id"`
		Op string `json:"op"`
	}
	if err := json.NewDecoder(oldReader).Decode(&command); err != nil {
		t.Fatal(err)
	}
	if command.Op != "changeDirectory" || command.ID == "" {
		t.Fatalf("command = %#v", command)
	}
	newStream, newReader := openManualBridgeStream(t, streamCtx, bridge, workspace, newHost, "/managed/new")
	defer newStream.Body.Close()
	if _, err := newReader.ReadString('\n'); err != nil {
		t.Fatal(err)
	}
	select {
	case result := <-resultCh:
		if !result.OK || result.Directory != "/managed/new" {
			t.Fatalf("change result = %#v", result)
		}
	case <-ctx.Done():
		t.Fatal("replacement host did not confirm directory change")
	}
}

// IDE-T-014 verifies a view's registered stream is disconnected by an epoch-matched revoke.
func TestBindAndRevokeStream(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	viewID := "11111111-1111-4111-8111-111111111111"
	workspace := "/managed/view.code-workspace"
	bridge.Assign(viewID, workspace, 1, "/managed")
	stream, stopStream := openTestBridgeStream(t, bridge, viewID, workspace, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
	defer stopStream()
	defer stream.Body.Close()
	if !bridge.IsConnected(viewID, 1) {
		t.Fatal("view did not connect")
	}
	bridge.Revoke(viewID, 1)
	if bridge.IsConnected(viewID, 1) {
		t.Fatal("revoked stream remains connected")
	}
}

// IDE-T-017 verifies cancellation resolves a bridge command with the frozen safe message.
func TestBridgeCommandCancellation(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	viewID := "11111111-1111-4111-8111-111111111111"
	workspace := "/managed/view.code-workspace"
	bridge.Assign(viewID, workspace, 1, "/managed")
	stream, stopStream := openTestBridgeStream(t, bridge, viewID, workspace, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
	defer stopStream()
	defer stream.Body.Close()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	result, err := bridge.Request(ctx, viewID, BridgeCommand{Op: "inspect"}, BridgeRequestOptions{})
	if err != nil || result.OK || result.Message != "ide request was cancelled" {
		t.Fatalf("result = %#v, err = %v", result, err)
	}
}

func openManualBridgeStream(t *testing.T, ctx context.Context, bridge *Bridge, workspace, hostID, folder string) (*http.Response, *bufio.Reader) {
	t.Helper()
	params := url.Values{"workspacePath": {workspace}, "hostId": {hostID}, "folder": {folder}}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, bridge.URL+"/bridge/stream?"+params.Encode(), nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+bridge.Secret)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK {
		response.Body.Close()
		t.Fatalf("stream status = %d", response.StatusCode)
	}
	return response, bufio.NewReader(response.Body)
}

func openTestBridgeStream(t *testing.T, bridge *Bridge, viewID, workspace, hostID string) (*http.Response, context.CancelFunc) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	params := url.Values{"workspacePath": {workspace}, "hostId": {hostID}, "folder": {"/managed"}}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, bridge.URL+"/bridge/stream?"+params.Encode(), nil)
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+bridge.Secret)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK {
		cancel()
		t.Fatalf("stream status = %d", response.StatusCode)
	}
	if _, err := bufio.NewReader(response.Body).ReadString('\n'); err != nil {
		cancel()
		response.Body.Close()
		t.Fatal(err)
	}
	if !bridge.IsConnected(viewID, 1) {
		cancel()
		response.Body.Close()
		t.Fatal("bridge did not bind the stream")
	}
	return response, cancel
}

func postBridge(t *testing.T, bridge *Bridge, path string, body []byte) int {
	t.Helper()
	request, err := http.NewRequest(http.MethodPost, bridge.URL+path, strings.NewReader(string(body)))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+bridge.Secret)
	request.Header.Set("Content-Type", "application/json")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	return response.StatusCode
}
