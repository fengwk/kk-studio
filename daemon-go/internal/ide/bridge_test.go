package ide

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
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

// IDE-T-018 verifies result payload structure and directory fencing before settling an extension command.
func TestBridgeResultRouteFencing(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	const viewID = "11111111-1111-4111-8111-111111111111"
	bridge.Assign(viewID, "/managed/view.code-workspace", 1, "/managed/old")
	if status := postBridge(t, bridge, "/bridge/result", []byte(`null`)); status != http.StatusBadRequest {
		t.Fatalf("null result status=%d", status)
	}
	if status := postBridge(t, bridge, "/bridge/result", []byte(`{}`)); status != http.StatusBadRequest {
		t.Fatalf("missing ID status=%d", status)
	}
	if status := postBridge(t, bridge, "/bridge/result", []byte(`{"id":3}`)); status != http.StatusBadRequest {
		t.Fatalf("non-string ID status=%d", status)
	}
	if status := postBridge(t, bridge, "/bridge/result", []byte(`{"id":"unknown","ok":true}`)); status != http.StatusNoContent {
		t.Fatalf("unknown ID status=%d", status)
	}
	stream := &bridgeStream{hostID: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", messages: make(chan []byte), retired: make(chan struct{})}
	bridge.mu.Lock()
	bridge.views[viewID].hostID = stream.hostID
	bridge.views[viewID].stream = stream
	result := make(chan BridgeResult, 1)
	bridge.pending["change"] = &bridgePending{viewID: viewID, epoch: 1, op: "changeDirectory", expectedDirectory: "/managed/new", stream: stream, result: result}
	bridge.mu.Unlock()
	if status := postBridge(t, bridge, "/bridge/result", []byte(`{"id":"change","ok":true,"directory":"/managed/wrong"}`)); status != http.StatusNoContent {
		t.Fatalf("directory mismatch status=%d", status)
	}
	if got := <-result; got.OK || got.Message != "directory change was not applied" {
		t.Fatalf("mismatched directory result=%+v", got)
	}
	bridge.mu.Lock()
	stale := make(chan BridgeResult, 1)
	bridge.pending["stale"] = &bridgePending{viewID: viewID, epoch: 1, op: "inspect", stream: stream, result: stale}
	bridge.views[viewID].stream = &bridgeStream{hostID: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", messages: make(chan []byte), retired: make(chan struct{})}
	bridge.mu.Unlock()
	if status := postBridge(t, bridge, "/bridge/result", []byte(`{"id":"stale","ok":true}`)); status != http.StatusNoContent {
		t.Fatalf("stale result status=%d", status)
	}
	select {
	case <-stale:
		t.Fatal("result from a replaced host settled a pending command")
	default:
	}
}

// IDE-T-018 verifies PTY validation and callback errors never disclose implementation failures.
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
	bridge.OnPTY(func(string, int) error { return fmt.Errorf("internal path should not cross boundary") })
	if status := postBridge(t, bridge, "/bridge/pty", []byte(`{"workspacePath":"/managed/view.code-workspace","pid":42}`)); status != http.StatusInternalServerError {
		t.Fatalf("PTY callback failure status=%d", status)
	}
}

// IDE-T-014/022 verifies stream query, retired-host, and host-capacity admission rules.
func TestBridgeStreamAdmissionBoundaries(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	const viewID = "11111111-1111-4111-8111-111111111111"
	const workspace = "/managed/view.code-workspace"
	bridge.Assign(viewID, workspace, 1, "/managed")
	status := func(values url.Values) int {
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
	if status(url.Values{"workspacePath": {"/unassigned"}, "hostId": {"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}, "folder": {"/managed"}}) != http.StatusForbidden {
		t.Fatal("unassigned workspace accepted")
	}
	if status(url.Values{"workspacePath": {workspace}, "hostId": {"bad"}, "folder": {"/managed"}}) != http.StatusBadRequest {
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
	if status(url.Values{"workspacePath": {workspace}, "hostId": {"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}, "folder": {"/managed"}}) != http.StatusGone {
		t.Fatal("retired host reconnected")
	}
	pending := make(chan BridgeResult, 1)
	bridge.mu.Lock()
	bridge.pending["same-host-transition"] = &bridgePending{viewID: viewID, epoch: 1, op: "changeDirectory", hostID: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", stream: bridge.views[viewID].stream, expectedDirectory: "/managed/next", result: pending}
	bridge.mu.Unlock()
	if status(url.Values{"workspacePath": {workspace}, "hostId": {"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}, "folder": {"/managed"}}) != http.StatusGone {
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
	if status(url.Values{"workspacePath": {workspace}, "hostId": {"cccccccc-cccc-4ccc-8ccc-cccccccccccc"}, "folder": {"/managed"}}) != http.StatusServiceUnavailable {
		t.Fatal("host limit was not enforced")
	}
}

// IDE-T-014 verifies the bridge rejects non-loopback, unauthenticated, unknown, and post-close stream requests.
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
	key := bridge.pathKey(workspace)
	if err := bridge.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	bridge.mu.Lock()
	bridge.byKey[key] = viewID
	bridge.mu.Unlock()
	stream := "/bridge/stream?workspacePath=" + url.QueryEscape(workspace) + "&hostId=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa&folder=%2Fmanaged"
	if got := request("127.0.0.1:1234", stream, "Bearer "+bridge.Secret).Code; got != http.StatusServiceUnavailable {
		t.Fatalf("closed bridge status=%d", got)
	}
}

// IDE-T-014 verifies canonical path and loopback predicates reject unsafe bridge identities.
func TestBridgePathAndLoopbackPredicates(t *testing.T) {
	bridge := &Bridge{}
	if bridge.pathKey("") != "" {
		t.Fatal("empty path was canonicalized")
	}
	if bridge.pathKey("relative/path") == "" {
		t.Fatal("relative path did not resolve")
	}
	for _, test := range []struct {
		path, expected string
		want           bool
	}{
		{"/managed", "/managed", true}, {"/managed", "/other", false}, {"relative", "/managed", false}, {"", "/managed", false},
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
		{[]string{"/managed"}, "/managed", true}, {[]string{"/managed", "/managed"}, "/managed", false}, {[]string{"relative"}, "/managed", false},
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
