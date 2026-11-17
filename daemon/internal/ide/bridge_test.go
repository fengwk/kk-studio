package ide

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// Verifies loopback bridge authorization and route validation.
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
	for _, path := range []string{
		"/bridge/stream?workspacePath=%2Fmanaged%2Fview.code-workspace&hostId=&folder=%2Fmanaged",
		"/bridge/stream?workspacePath=%2Fmanaged%2Fview.code-workspace&folder=%2Fmanaged",
		"/bridge/stream?workspacePath=%2Fmanaged%2Fview.code-workspace&hostId=UPPER&folder=%2Fmanaged",
		"/bridge/stream?workspacePath=%2Fmanaged%2Fview.code-workspace&hostId=bad&folder=%2Fmanaged",
	} {
		if status := request(path, bridge.Secret); status != http.StatusBadRequest {
			t.Errorf("invalid host request %q status = %d", path, status)
		}
	}
}

// Verifies a timed-out directory transition remains reconcilable by a same-epoch target host.
func TestDirectoryTransitionTimeoutReconcilesTarget(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	bridge.commandTimeout = 10 * time.Millisecond
	const viewID = "11111111-1111-4111-8111-111111111111"
	const oldHost = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	const targetHost = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
	workspace, err := filepath.Abs(filepath.Join(t.TempDir(), "view.code-workspace"))
	if err != nil {
		t.Fatal(err)
	}
	directory := filepath.Dir(workspace)
	targetDirectory := filepath.Join(directory, "target")
	bridge.Assign(viewID, workspace, 1, directory)
	hostCtx, cancelHost := context.WithCancel(context.Background())
	defer cancelHost()
	response, reader := openManualBridgeStream(t, hostCtx, bridge, workspace, oldHost, directory)
	defer response.Body.Close()
	if _, err := reader.ReadString('\n'); err != nil {
		t.Fatal(err)
	}
	commandResult := make(chan BridgeResult, 1)
	go func() {
		result, _ := bridge.Request(context.Background(), viewID, BridgeCommand{Op: "changeDirectory", Directory: targetDirectory}, BridgeRequestOptions{ExpectedEpoch: 1, ExpectedDirectory: targetDirectory})
		commandResult <- result
	}()
	if _, err := reader.ReadString('\n'); err != nil {
		t.Fatal(err)
	}
	select {
	case result := <-commandResult:
		if result.OK || result.Code != "OUTCOME_UNKNOWN" {
			t.Fatalf("timed-out result=%+v", result)
		}
	case <-time.After(time.Second):
		t.Fatal("directory command did not time out")
	}
	if _, err := bridge.Request(context.Background(), viewID, BridgeCommand{Op: "changeDirectory", Directory: directory}, BridgeRequestOptions{ExpectedEpoch: 1, ExpectedDirectory: directory}); FailureCode(err) != "BUSY" {
		t.Fatalf("second directory command error=%v, want BUSY", err)
	}
	params := bridgeTestStreamParams(bridge, workspace, targetHost, targetDirectory)
	request, err := http.NewRequest(http.MethodGet, bridge.URL+"/bridge/stream?"+params.Encode(), nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+bridge.Secret)
	denied, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer denied.Body.Close()
	if denied.StatusCode != http.StatusOK {
		t.Fatalf("target folder status=%d, want 200", denied.StatusCode)
	}
	bridge.mu.Lock()
	confirmedDirectory := bridge.views[viewID].directory
	bridge.mu.Unlock()
	if confirmedDirectory != targetDirectory {
		t.Fatalf("reconciled directory=%q, want %q", confirmedDirectory, targetDirectory)
	}
}

// Verifies native close prompts have no bridge command timer and remain answerable.
func TestNativeClosePromptHasNoTimeout(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	const viewID = "11111111-1111-4111-8111-111111111111"
	workspace := filepath.Join(t.TempDir(), "view.code-workspace")
	bridge.Assign(viewID, workspace, 1, filepath.Dir(workspace))
	stream := &bridgeStream{messages: make(chan []byte, 1), retired: make(chan struct{})}
	bridge.mu.Lock()
	view := bridge.views[viewID]
	view.hostID, view.stream = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", stream
	bridge.mu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	requestResult := make(chan BridgeResult, 1)
	go func() {
		result, _ := bridge.Request(ctx, viewID, BridgeCommand{Op: "closeView"}, BridgeRequestOptions{ExpectedEpoch: 1})
		requestResult <- result
	}()
	var command struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(<-stream.messages, &command); err != nil || command.ID == "" {
		t.Fatalf("close command=%+v err=%v", command, err)
	}
	bridge.mu.Lock()
	pending := bridge.pending[command.ID]
	noTimer := pending != nil && pending.timer == nil
	bridge.mu.Unlock()
	if !noTimer {
		t.Fatal("native close prompt unexpectedly has a command timeout")
	}
	bridge.resolvePending(command.ID, BridgeResult{OK: true})
	select {
	case result := <-requestResult:
		if !result.OK {
			t.Fatalf("native close result=%+v", result)
		}
	case <-ctx.Done():
		t.Fatal("native close prompt did not remain answerable")
	}
}

// Verifies explicit discard close requests are bounded even though normal native prompts are not.
func TestDiscardClosePromptHasTimeout(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	bridge.commandTimeout = 10 * time.Millisecond
	const viewID = "11111111-1111-4111-8111-111111111111"
	workspace := filepath.Join(t.TempDir(), "view.code-workspace")
	bridge.Assign(viewID, workspace, 1, filepath.Dir(workspace))
	stream := &bridgeStream{messages: make(chan []byte, 1), retired: make(chan struct{})}
	bridge.mu.Lock()
	bridge.views[viewID].hostID, bridge.views[viewID].stream = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", stream
	bridge.mu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	requestResult := make(chan BridgeResult, 1)
	go func() {
		result, _ := bridge.Request(ctx, viewID, BridgeCommand{Op: "closeView", Discard: true}, BridgeRequestOptions{ExpectedEpoch: 1})
		requestResult <- result
	}()
	var command struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(<-stream.messages, &command); err != nil || command.ID == "" {
		t.Fatalf("discard close command=%+v err=%v", command, err)
	}
	select {
	case result := <-requestResult:
		if result.OK || result.Message != "ide window did not respond" {
			t.Fatalf("discard close timeout=%+v", result)
		}
	case <-ctx.Done():
		t.Fatal("discard close request did not time out")
	}
}

// Verifies a disconnected host identity remains retired after a replacement is admitted.
func TestLostRegistrationAckRetiresOldHost(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	const viewID = "11111111-1111-4111-8111-111111111111"
	const oldHost = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	const replacementHost = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
	directory, err := CanonicalDirectory(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	workspace := filepath.Join(directory, "view.code-workspace")
	bridge.Assign(viewID, workspace, 1, directory)
	oldCtx, cancelOld := context.WithCancel(context.Background())
	oldResponse, _ := openManualBridgeStream(t, oldCtx, bridge, workspace, oldHost, directory)
	cancelOld()
	_ = oldResponse.Body.Close()
	deadline := time.Now().Add(time.Second)
	for bridge.IsConnected(viewID, 1) && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if bridge.IsConnected(viewID, 1) {
		t.Fatal("disconnected host remained the active stream")
	}
	current, stopCurrent := openTestBridgeStream(t, bridge, viewID, workspace, replacementHost)
	defer stopCurrent()
	defer current.Body.Close()
	params := bridgeTestStreamParams(bridge, workspace, oldHost, directory)
	request, err := http.NewRequest(http.MethodGet, bridge.URL+"/bridge/stream?"+params.Encode(), nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+bridge.Secret)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	_ = response.Body.Close()
	if response.StatusCode != http.StatusGone {
		t.Fatalf("retired host reconnect status=%d", response.StatusCode)
	}
}

// Verifies NDJSON command delivery, command results, and PTY callbacks.
func TestBridgeCommandResultAndPTY(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	viewID := "11111111-1111-4111-8111-111111111111"
	hostID := "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	workspace := filepath.Join(t.TempDir(), "managed", "view.code-workspace")
	directory := filepath.Dir(workspace)
	bridge.Assign(viewID, workspace, 1, directory)
	streamCtx, stopStream := context.WithCancel(context.Background())
	defer stopStream()
	params := bridgeTestStreamParams(bridge, workspace, hostID, directory)
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
	body := bridgeTestResultBody(command.ID, viewID, 1, hostID, map[string]any{"ok": true, "clean": true})
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
	body, _ = json.Marshal(map[string]any{"viewId": viewID, "bindingEpoch": "1", "hostId": hostID, "pid": 42})
	if status := postBridge(t, bridge, "/bridge/pty", body); status != http.StatusNoContent {
		t.Fatalf("pty status = %d", status)
	}
	if got := <-pty; got != viewID+":42" {
		t.Fatalf("pty callback = %q", got)
	}
}

// Verifies malformed result and PTY payloads are rejected without binding an unknown workspace.
func TestValidateResultAndPTY(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	if status := postBridge(t, bridge, "/bridge/result", []byte("not-json")); status != http.StatusBadRequest {
		t.Fatalf("invalid result status = %d", status)
	}
	if status := postBridge(t, bridge, "/bridge/pty", []byte(`{"viewId":"11111111-1111-4111-8111-111111111111","bindingEpoch":"1","hostId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","pid":4}`)); status != http.StatusForbidden {
		t.Fatalf("unknown PTY workspace status = %d", status)
	}
	bridge.Assign("11111111-1111-4111-8111-111111111111", "/managed/view.code-workspace", 1, "/managed")
	if status := postBridge(t, bridge, "/bridge/pty", []byte(`{"viewId":"11111111-1111-4111-8111-111111111111","bindingEpoch":"1","hostId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","pid":0}`)); status != http.StatusBadRequest {
		t.Fatalf("invalid PTY pid status = %d", status)
	}
}

// Verifies result payload structure and directory fencing before settling an extension command.
func TestBridgeResultRouteFencing(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	const viewID = "11111111-1111-4111-8111-111111111111"
	bridge.Assign(viewID, "/managed/view.code-workspace", 1, "/managed/old")
	const hostID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	stream := &bridgeStream{messages: make(chan []byte), retired: make(chan struct{})}
	bridge.mu.Lock()
	bridge.views[viewID].hostID = hostID
	bridge.views[viewID].stream = stream
	bridge.mu.Unlock()
	if status := postBridge(t, bridge, "/bridge/result", []byte(`null`)); status != http.StatusBadRequest {
		t.Fatalf("null result status=%d", status)
	}
	if status := postBridge(t, bridge, "/bridge/result", []byte(`{}`)); status != http.StatusBadRequest {
		t.Fatalf("missing ID status=%d", status)
	}
	if status := postBridge(t, bridge, "/bridge/result", []byte(`{"id":3}`)); status != http.StatusBadRequest {
		t.Fatalf("non-string ID status=%d", status)
	}
	if status := postBridge(t, bridge, "/bridge/result", []byte(`{"id":"missing-identity","ok":true}`)); status != http.StatusBadRequest {
		t.Fatalf("missing result identity status=%d", status)
	}
	if status := postBridge(t, bridge, "/bridge/result", []byte(`{"id":"invalid-documents","documents":"bad"}`)); status != http.StatusBadRequest {
		t.Fatalf("invalid result shape status=%d", status)
	}
	if status := postBridge(t, bridge, "/bridge/result", bridgeTestResultBody("unknown", viewID, 1, hostID, map[string]any{"ok": true})); status != http.StatusNoContent {
		t.Fatalf("unknown ID status=%d", status)
	}
	result := make(chan BridgeResult, 1)
	bridge.mu.Lock()
	bridge.pending["change"] = &bridgePending{viewID: viewID, epoch: 1, op: "changeDirectory", hostID: hostID, expectedDirectory: "/managed/new", stream: stream, result: result}
	bridge.mu.Unlock()
	if status := postBridge(t, bridge, "/bridge/result", bridgeTestResultBody("change", viewID, 1, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", map[string]any{"ok": true, "directory": "/managed/new"})); status != http.StatusNoContent {
		t.Fatalf("wrong-host result status=%d", status)
	}
	select {
	case got := <-result:
		t.Fatalf("wrong host settled pending result: %+v", got)
	default:
	}
	if status := postBridge(t, bridge, "/bridge/result", bridgeTestResultBody("change", viewID, 1, hostID, map[string]any{"ok": true, "directory": "/managed/wrong"})); status != http.StatusNoContent {
		t.Fatalf("directory mismatch status=%d", status)
	}
	if got := <-result; got.OK || got.Message != "directory change was not applied" {
		t.Fatalf("mismatched directory result=%+v", got)
	}
	bridge.mu.Lock()
	stream = &bridgeStream{messages: make(chan []byte), retired: make(chan struct{})}
	bridge.views[viewID].hostID = hostID
	bridge.views[viewID].stream = stream
	initiatorEcho := make(chan BridgeResult, 1)
	bridge.pending["initiator-echo"] = &bridgePending{viewID: viewID, epoch: 1, op: "changeDirectory", hostID: hostID, expectedDirectory: "/managed/new", stream: stream, result: initiatorEcho}
	bridge.mu.Unlock()
	if status := postBridge(t, bridge, "/bridge/result", bridgeTestResultBody("initiator-echo", viewID, 1, hostID, map[string]any{"ok": true, "directory": "/managed/new"})); status != http.StatusNoContent {
		t.Fatalf("initiator echo status=%d", status)
	}
	if got := <-initiatorEcho; got.OK || got.Message != "directory change was not applied" {
		t.Fatalf("initiator self-confirmed directory result=%+v", got)
	}
	bridge.mu.Lock()
	stale := make(chan BridgeResult, 1)
	bridge.pending["stale"] = &bridgePending{viewID: viewID, epoch: 1, op: "inspect", hostID: hostID, stream: stream, result: stale}
	bridge.views[viewID].stream = &bridgeStream{messages: make(chan []byte), retired: make(chan struct{})}
	bridge.mu.Unlock()
	if status := postBridge(t, bridge, "/bridge/result", bridgeTestResultBody("stale", viewID, 1, hostID, map[string]any{"ok": true})); status != http.StatusNoContent {
		t.Fatalf("stale result status=%d", status)
	}
	select {
	case <-stale:
		t.Fatal("result from a replaced host settled a pending command")
	default:
	}
}

// Verifies PTY validation and callback errors never disclose implementation failures.
func TestBridgePTYFailureBoundaries(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	for _, body := range [][]byte{[]byte("{"), []byte(`{"workspacePath":"","pid":1}`), []byte(`{"workspacePath":"/managed/view.code-workspace","pid":1.5}`)} {
		if status := postBridge(t, bridge, "/bridge/pty", body); status != http.StatusBadRequest {
			t.Errorf("invalid PTY body %s status=%d", body, status)
		}
	}
	bridge.Assign("11111111-1111-4111-8111-111111111111", "/managed/view.code-workspace", 1, "/managed")
	stream, cancel := openTestBridgeStream(t, bridge, "11111111-1111-4111-8111-111111111111", "/managed/view.code-workspace", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
	defer cancel()
	defer stream.Body.Close()
	if status := postBridge(t, bridge, "/bridge/pty", []byte(`{"viewId":"11111111-1111-4111-8111-111111111111","bindingEpoch":"1","hostId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","pid":42}`)); status != http.StatusForbidden {
		t.Fatalf("PTY with wrong host status=%d", status)
	}
	if status := postBridge(t, bridge, "/bridge/pty", []byte(`{"viewId":"11111111-1111-4111-8111-111111111111","bindingEpoch":"1","hostId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","pid":42}`)); status != http.StatusNoContent {
		t.Fatalf("PTY event without listeners status=%d", status)
	}
	bridge.OnPTY(func(string, int) error { return fmt.Errorf("internal path should not cross boundary") })
	if status := postBridge(t, bridge, "/bridge/pty", []byte(`{"viewId":"11111111-1111-4111-8111-111111111111","bindingEpoch":"1","hostId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","pid":42}`)); status != http.StatusInternalServerError {
		t.Fatalf("PTY callback failure status=%d", status)
	}
}

// Verifies the current host identity may reconnect after an ordinary stream disconnect.
func TestSameHostReconnectAfterDisconnect(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	const viewID = "11111111-1111-4111-8111-111111111111"
	const hostID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	workspace := filepath.Join(t.TempDir(), "view.code-workspace")
	directory := filepath.Dir(workspace)
	bridge.Assign(viewID, workspace, 1, directory)
	firstCtx, cancelFirst := context.WithCancel(context.Background())
	firstResponse, _ := openManualBridgeStream(t, firstCtx, bridge, workspace, hostID, directory)
	if firstResponse.StatusCode != http.StatusOK {
		cancelFirst()
		firstResponse.Body.Close()
		t.Fatalf("first stream status=%d", firstResponse.StatusCode)
	}
	cancelFirst()
	firstResponse.Body.Close()
	deadline := time.Now().Add(time.Second)
	for {
		bridge.mu.Lock()
		disconnected := bridge.views[viewID].stream == nil
		bridge.mu.Unlock()
		if disconnected {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("first stream did not disconnect")
		}
		time.Sleep(time.Millisecond)
	}
	secondCtx, cancelSecond := context.WithCancel(context.Background())
	defer cancelSecond()
	secondResponse, reader := openManualBridgeStream(t, secondCtx, bridge, workspace, hostID, directory)
	defer secondResponse.Body.Close()
	if secondResponse.StatusCode != http.StatusOK {
		t.Fatalf("same-host reconnect status=%d", secondResponse.StatusCode)
	}
	if _, err := reader.ReadString('\n'); err != nil {
		t.Fatalf("same-host reconnect handshake: %v", err)
	}
}

// Verifies stream query, retired-host, and host-capacity admission rules.
func TestBridgeStreamAdmissionBoundaries(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	const viewID = "11111111-1111-4111-8111-111111111111"
	workspace := filepath.Join(t.TempDir(), "managed", "view.code-workspace")
	directory := filepath.Dir(workspace)
	bridge.Assign(viewID, workspace, 1, directory)
	legacyRequest, err := http.NewRequest(http.MethodGet, bridge.URL+"/bridge/stream?workspacePath="+url.QueryEscape(workspace)+"&hostId=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", nil)
	if err != nil {
		t.Fatal(err)
	}
	legacyRequest.Header.Set("Authorization", "Bearer "+bridge.Secret)
	legacyResponse, err := http.DefaultClient.Do(legacyRequest)
	if err != nil {
		t.Fatal(err)
	}
	legacyResponse.Body.Close()
	if legacyResponse.StatusCode != http.StatusBadRequest {
		t.Fatalf("workspace-path-only stream status=%d, want 400", legacyResponse.StatusCode)
	}
	status := func(values url.Values) int {
		if paths, exists := values["workspacePath"]; exists {
			delete(values, "workspacePath")
			viewID, epoch := bridgeTestIdentity(bridge, paths[0])
			if len(paths) > 1 {
				values["viewId"] = []string{viewID, viewID}
			} else {
				values.Set("viewId", viewID)
			}
			values.Set("bindingEpoch", strconv.FormatInt(epoch, 10))
		}
		request, err := http.NewRequest(http.MethodGet, bridge.URL+"/bridge/stream?"+values.Encode(), nil)
		if err != nil {
			t.Fatal(err)
		}
		request.Header.Set("Authorization", "Bearer "+bridge.Secret)
		response, err := http.DefaultClient.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		return response.StatusCode
	}
	if status(url.Values{"workspacePath": {workspace, workspace}}) != http.StatusBadRequest {
		t.Fatal("duplicate workspace path accepted")
	}
	if status(url.Values{"workspacePath": {workspace}, "hostId": {"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}, "folder": {directory}}) != http.StatusBadRequest {
		t.Fatal("duplicate host ID accepted")
	}
	if status(url.Values{"workspacePath": {"/unassigned"}, "hostId": {"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}, "folder": {"/managed"}}) != http.StatusForbidden {
		t.Fatal("unassigned workspace accepted")
	}
	if status(url.Values{"workspacePath": {workspace}, "hostId": {"bad"}, "folder": {directory}}) != http.StatusBadRequest {
		t.Fatal("invalid host ID accepted")
	}
	if status(url.Values{"workspacePath": {workspace}, "hostId": {"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}, "folder": {"/elsewhere"}}) != http.StatusGone {
		t.Fatal("unconfirmed folder accepted")
	}
	old, stopOld := openTestBridgeStream(t, bridge, viewID, workspace, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
	defer stopOld()
	defer old.Body.Close()
	current, stopCurrent := openTestBridgeStream(t, bridge, viewID, workspace, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
	defer stopCurrent()
	defer current.Body.Close()
	if status(url.Values{"workspacePath": {workspace}, "hostId": {"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}, "folder": {directory}}) != http.StatusGone {
		t.Fatal("retired host reconnected")
	}
	pending := make(chan BridgeResult, 1)
	bridge.mu.Lock()
	bridge.pending["same-host-transition"] = &bridgePending{viewID: viewID, epoch: 1, op: "changeDirectory", hostID: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", stream: bridge.views[viewID].stream, expectedDirectory: filepath.Join(directory, "next"), result: pending}
	bridge.mu.Unlock()
	if status(url.Values{"workspacePath": {workspace}, "hostId": {"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}, "folder": {directory}}) != http.StatusGone {
		t.Fatal("pending transition host reconnected")
	}
	if got := <-pending; got.Message != "directory change was not applied" {
		t.Fatalf("same-host transition=%+v", got)
	}
	bridge.mu.Lock()
	view := bridge.views[viewID]
	view.hosts = make(map[string]struct{}, bridgeMaxViewHosts)
	for index := 0; index < bridgeMaxViewHosts; index++ {
		view.hosts[fmt.Sprintf("%08x-0000-4000-8000-%012x", index+1, index+1)] = struct{}{}
	}
	bridge.mu.Unlock()
	if status(url.Values{"workspacePath": {workspace}, "hostId": {"cccccccc-cccc-4ccc-8ccc-cccccccccccc"}, "folder": {directory}}) != http.StatusServiceUnavailable {
		t.Fatal("host limit was not enforced")
	}
}

// Verifies the bridge rejects non-loopback, unauthenticated, unknown, and post-close stream requests.
func TestBridgeHandlerBoundaryResponses(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	const viewID = "11111111-1111-4111-8111-111111111111"
	const workspace = "/managed/view.code-workspace"
	bridge.Assign(viewID, workspace, 1, "/managed")
	request := func(remote, path, authorization string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodGet, path, nil)
		req.RemoteAddr = remote
		if authorization != "" {
			req.Header.Set("Authorization", authorization)
		}
		writer := httptest.NewRecorder()
		bridge.handle(writer, req)
		return writer
	}
	path := "/unknown"
	if got := request("192.0.2.1:1234", path, "Bearer "+bridge.Secret).Code; got != http.StatusForbidden {
		t.Fatalf("non-loopback status=%d", got)
	}
	if got := request("127.0.0.1:1234", path, "").Code; got != http.StatusUnauthorized {
		t.Fatalf("unauthenticated status=%d", got)
	}
	if got := request("127.0.0.1:1234", path, "Bearer "+bridge.Secret).Code; got != http.StatusNotFound {
		t.Fatalf("unknown route status=%d", got)
	}
	if err := bridge.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	stream := "/bridge/stream?viewId=" + viewID + "&bindingEpoch=1&hostId=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa&folder=%2Fmanaged"
	if got := request("127.0.0.1:1234", stream, "Bearer "+bridge.Secret).Code; got != http.StatusServiceUnavailable {
		t.Fatalf("closed bridge status=%d", got)
	}
}

// Verifies folder matching remains a directory check, not a bridge identity.
func TestBridgeDirectoryAndLoopbackPredicates(t *testing.T) {
	managed := filepath.Join(t.TempDir(), "managed")
	other := filepath.Join(t.TempDir(), "other")
	for _, test := range []struct {
		path, expected string
		want           bool
	}{
		{managed, managed, true}, {managed, other, false}, {"relative", managed, false}, {"", managed, false},
	} {
		if got := validDirectoryExpectation(test.path, test.expected); got != test.want {
			t.Errorf("validDirectoryExpectation(%q,%q)=%v", test.path, test.expected, got)
		}
	}
	for _, test := range []struct {
		folders   []string
		directory string
		want      bool
	}{
		{[]string{managed}, managed, true}, {[]string{managed, managed}, managed, false}, {[]string{"relative"}, managed, false},
	} {
		if got := matchesBridgeDirectory(test.folders, test.directory); got != test.want {
			t.Errorf("matchesBridgeDirectory(%v,%q)=%v", test.folders, test.directory, got)
		}
	}
	for _, test := range []struct {
		address string
		want    bool
	}{
		{"127.0.0.1:1234", true}, {"[::1]:1234", true}, {"192.0.2.1:1234", false}, {"not-an-address", false},
	} {
		if got := isBridgeLoopback(test.address); got != test.want {
			t.Errorf("isBridgeLoopback(%q)=%v", test.address, got)
		}
	}
}

// Confirms a directory transition only after a replacement host reports the target folder.
func TestConfirmDirectoryOnReconnect(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	viewID := "11111111-1111-4111-8111-111111111111"
	workspace := filepath.Join(t.TempDir(), "managed", "view.code-workspace")
	oldDirectory := filepath.Join(filepath.Dir(workspace), "old")
	newDirectory := filepath.Join(filepath.Dir(workspace), "new")
	oldHost := "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	newHost := "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
	bridge.Assign(viewID, workspace, 1, oldDirectory)
	streamCtx, cancelStream := context.WithCancel(context.Background())
	defer cancelStream()
	oldStream, oldReader := openManualBridgeStream(t, streamCtx, bridge, workspace, oldHost, oldDirectory)
	defer oldStream.Body.Close()
	if _, err := oldReader.ReadString('\n'); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	resultCh := make(chan BridgeResult, 1)
	go func() {
		result, _ := bridge.Request(ctx, viewID, BridgeCommand{Op: "changeDirectory", Directory: newDirectory}, BridgeRequestOptions{ExpectedDirectory: newDirectory})
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
	newStream, newReader := openManualBridgeStream(t, streamCtx, bridge, workspace, newHost, newDirectory)
	defer newStream.Body.Close()
	if _, err := newReader.ReadString('\n'); err != nil {
		t.Fatal(err)
	}
	select {
	case result := <-resultCh:
		if !result.OK || result.Directory != newDirectory {
			t.Fatalf("change result = %#v", result)
		}
	case <-ctx.Done():
		t.Fatal("replacement host did not confirm directory change")
	}
}

// Verifies a view's registered stream is disconnected by an epoch-matched revoke.
func TestBindAndRevokeStream(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	viewID := "11111111-1111-4111-8111-111111111111"
	workspace := filepath.Join(t.TempDir(), "managed", "view.code-workspace")
	bridge.Assign(viewID, workspace, 1, filepath.Dir(workspace))
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

// Verifies cancellation resolves a bridge command with the frozen safe message.
func TestBridgeCommandCancellation(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	viewID := "11111111-1111-4111-8111-111111111111"
	workspace := filepath.Join(t.TempDir(), "managed", "view.code-workspace")
	bridge.Assign(viewID, workspace, 1, filepath.Dir(workspace))
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

func bridgeTestIdentity(bridge *Bridge, workspace string) (string, int64) {
	bridge.mu.Lock()
	defer bridge.mu.Unlock()
	for viewID, view := range bridge.views {
		if view.workspacePath == workspace {
			return viewID, view.epoch
		}
	}
	return "ffffffff-ffff-4fff-8fff-ffffffffffff", 1
}

func bridgeTestResultBody(id, viewID string, epoch int64, hostID string, result map[string]any) []byte {
	payload := map[string]any{"id": id, "viewId": viewID, "bindingEpoch": strconv.FormatInt(epoch, 10), "hostId": hostID}
	for key, value := range result {
		payload[key] = value
	}
	body, _ := json.Marshal(payload)
	return body
}

func bridgeTestStreamParams(bridge *Bridge, workspace, hostID, folder string) url.Values {
	viewID, epoch := bridgeTestIdentity(bridge, workspace)
	return url.Values{"viewId": {viewID}, "bindingEpoch": {strconv.FormatInt(epoch, 10)}, "hostId": {hostID}, "folder": {folder}}
}

func openManualBridgeStream(t *testing.T, ctx context.Context, bridge *Bridge, workspace, hostID, folder string) (*http.Response, *bufio.Reader) {
	t.Helper()
	params := bridgeTestStreamParams(bridge, workspace, hostID, folder)
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
	bridge.mu.Lock()
	view := bridge.views[viewID]
	folder := ""
	if view != nil {
		folder = view.directory
	}
	bridge.mu.Unlock()
	params := bridgeTestStreamParams(bridge, workspace, hostID, folder)
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
