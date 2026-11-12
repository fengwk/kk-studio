package ide

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// IDE-T-018 verifies bridge request body limits map read failures to bounded HTTP responses.
func TestBridgeBodyLimits(t *testing.T) {
	tooLarge := httptest.NewRequest(http.MethodPost, "/", strings.NewReader(strings.Repeat("x", bridgeMaxBodyBytes+1)))
	if data, status := bridgeReadBody(httptest.NewRecorder(), tooLarge); status != http.StatusRequestEntityTooLarge || data != nil {
		t.Fatalf("oversize body status=%d bytes=%d", status, len(data))
	}
	broken := httptest.NewRequest(http.MethodPost, "/", nil)
	broken.Body = io.NopCloser(failingBridgeReader{})
	if _, status := bridgeReadBody(httptest.NewRecorder(), broken); status != http.StatusBadRequest {
		t.Fatalf("read failure status=%d", status)
	}
	if bridgeBodyError(http.StatusRequestEntityTooLarge) != "bridge request body too large" || bridgeBodyError(http.StatusBadRequest) != "invalid request body" {
		t.Fatal("bridge body error text changed")
	}
}

type failingBridgeReader struct{}

func (failingBridgeReader) Read([]byte) (int, error) { return 0, errors.New("read failed") }

// IDE-T-018 verifies snapshot payload decoding enforces document, path, and byte limits.
func TestBridgeSnapshotPayloadValidation(t *testing.T) {
	parse := func(value string) (BridgeResult, error) {
		var raw map[string]json.RawMessage
		if err := json.Unmarshal([]byte(value), &raw); err != nil {
			return BridgeResult{}, err
		}
		return bridgeResultFromJSON(raw)
	}
	valid, err := parse(`{"ok":true,"clean":true,"omitted":0,"documents":[{"path":"/workspace/a.txt","text":"saved"}]}`)
	if err != nil || !valid.OK || valid.Clean == nil || !*valid.Clean || len(valid.Documents) != 1 {
		t.Fatalf("valid snapshot=%+v err=%v", valid, err)
	}
	invalid := []string{
		`{"documents":"not-an-array"}`,
		`{"documents":null}`,
		`{"documents":[null]}`,
		`{"documents":[{}]}`,
		`{"documents":[{"path":"relative","text":"x"}]}`,
		`{"documents":[{"path":"/workspace/line\nbreak","text":"x"}]}`,
		`{"documents":[{"path":"/` + strings.Repeat("x", bridgeMaxSnapshotPathChars+1) + `","text":"x"}]}`,
		`{"documents":[{"path":"/workspace/large.txt","text":"` + strings.Repeat("x", bridgeMaxBodyBytes+1) + `"}]}`,
	}
	for index, input := range invalid {
		if _, err := parse(input); err == nil {
			t.Errorf("invalid snapshot %d accepted", index)
		}
	}
	if got, ok := rawBoolPointer(map[string]json.RawMessage{"value": json.RawMessage("true")}, "value"); !ok || got == nil || !*got {
		t.Fatalf("bool pointer=%v,%v", got, ok)
	}
	for _, encoded := range []string{"null", "no", "1"} {
		if _, ok := rawBoolPointer(map[string]json.RawMessage{"value": json.RawMessage(encoded)}, "value"); ok {
			t.Errorf("invalid bool %q accepted", encoded)
		}
	}
	if got, ok := rawIntegerPointer(map[string]json.RawMessage{"value": json.RawMessage("42")}, "value"); !ok || got == nil || *got != 42 {
		t.Fatalf("integer pointer=%v,%v", got, ok)
	}
	for _, encoded := range []string{"null", "-1", "1.5", "9007199254740992", "1e400"} {
		if _, ok := rawIntegerPointer(map[string]json.RawMessage{"value": json.RawMessage(encoded)}, "value"); ok {
			t.Errorf("invalid integer %q accepted", encoded)
		}
	}
}

// IDE-T-017 verifies bridge requests reject stale, cancelled, oversized, and conflicting work before enqueue.
func TestBridgeRequestAdmissionFailures(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	const viewID = "11111111-1111-4111-8111-111111111111"
	bridge.Assign(viewID, "/managed/view.code-workspace", 2, "/managed")
	bridge.mu.Lock()
	view := bridge.views[viewID]
	view.hostID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	view.stream = &bridgeStream{hostID: view.hostID, messages: make(chan []byte), retired: make(chan struct{})}
	stream := view.stream
	bridge.mu.Unlock()
	checks := []struct {
		name    string
		ctx     context.Context
		id      string
		command BridgeCommand
		options BridgeRequestOptions
		want    string
	}{
		{name: "missing connection", ctx: context.Background(), id: "22222222-2222-4222-8222-222222222222", want: "UNAVAILABLE"},
		{name: "stale epoch", ctx: context.Background(), id: viewID, options: BridgeRequestOptions{ExpectedEpoch: 1}, want: "VIEW_GONE"},
		{name: "cancelled", ctx: cancelledBridgeContext(), id: viewID},
		{name: "too many documents", ctx: context.Background(), id: viewID, command: BridgeCommand{Documents: make([]BridgeDocument, bridgeMaxCommandDocuments+1)}, want: "UNAVAILABLE"},
		{name: "invalid directory expectation", ctx: context.Background(), id: viewID, command: BridgeCommand{Op: "changeDirectory", Directory: "relative"}, want: "UNAVAILABLE"},
	}
	bridge.mu.Lock()
	bridge.pending["directory-pending"] = &bridgePending{viewID: viewID, epoch: 2, op: "changeDirectory", stream: stream, result: make(chan BridgeResult, 1)}
	bridge.mu.Unlock()
	checks = append(checks, struct {
		name    string
		ctx     context.Context
		id      string
		command BridgeCommand
		options BridgeRequestOptions
		want    string
	}{name: "duplicate directory transition", ctx: context.Background(), id: viewID, command: BridgeCommand{Op: "changeDirectory", Directory: "/managed/new"}, options: BridgeRequestOptions{ExpectedDirectory: "/managed/new"}, want: "BUSY"})
	for _, check := range checks {
		result, err := bridge.Request(check.ctx, check.id, check.command, check.options)
		if check.want == "" {
			if err != nil || result.Message != "ide request was cancelled" {
				t.Errorf("%s result=%+v err=%v want cancellation", check.name, result, err)
			}
		} else if FailureCode(err) != check.want {
			t.Errorf("%s error=%v want %q", check.name, err, check.want)
		}
	}
}

func cancelledBridgeContext() context.Context {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	return ctx
}

// IDE-T-014 verifies bridge shutdown settles pending work and rejects connection waiters.
func TestBridgeCloseSettlesPendingAndWaiters(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	const viewID = "11111111-1111-4111-8111-111111111111"
	bridge.Assign(viewID, "/managed/view.code-workspace", 1, "/managed")
	pendingResult := make(chan BridgeResult, 1)
	waiterResult := make(chan error, 1)
	bridge.mu.Lock()
	bridge.pending["pending"] = &bridgePending{viewID: viewID, epoch: 1, result: pendingResult}
	bridge.waiters[viewID] = map[chan error]struct{}{waiterResult: {}}
	bridge.mu.Unlock()
	if err := bridge.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	if result := <-pendingResult; result.Message != "ide bridge closed" {
		t.Fatalf("pending result=%+v", result)
	}
	if err := <-waiterResult; FailureCode(err) != "UNAVAILABLE" {
		t.Fatalf("waiter error=%v", err)
	}
	if err := bridge.Close(context.Background()); err != nil {
		t.Fatalf("repeated close: %v", err)
	}
}

// IDE-T-014 verifies connection waiters wake on admission and epoch revocation.
func TestBridgeConnectionWaiterLifecycle(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	const viewID = "11111111-1111-4111-8111-111111111111"
	bridge.Assign(viewID, "/managed/view.code-workspace", 1, "/managed")
	waitForResult := func() <-chan error {
		result := make(chan error, 1)
		go func() { result <- bridge.WaitForConnection(context.Background(), viewID) }()
		deadline := time.Now().Add(time.Second)
		for time.Now().Before(deadline) {
			bridge.mu.Lock()
			waiting := len(bridge.waiters[viewID]) > 0
			bridge.mu.Unlock()
			if waiting {
				return result
			}
			time.Sleep(time.Millisecond)
		}
		t.Fatal("connection waiter was not registered")
		return result
	}
	connected := waitForResult()
	bridge.mu.Lock()
	bridge.notifyConnectedLocked(viewID)
	bridge.mu.Unlock()
	select {
	case err := <-connected:
		if err != nil {
			t.Fatalf("connected waiter err=%v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("connected waiter remained blocked")
	}
	revoked := waitForResult()
	bridge.Revoke(viewID, 1)
	select {
	case err := <-revoked:
		if FailureCode(err) != "VIEW_GONE" {
			t.Fatalf("revoked waiter err=%v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("revoked waiter remained blocked")
	}
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	if err := bridge.WaitForConnection(cancelled, viewID); err == nil {
		t.Fatal("cancelled connection wait succeeded")
	}
}

// IDE-T-025/026 verifies connection waits return for admitted streams, cancellation, and bridge closure.
func TestWaitForConnectionBoundaries(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	const viewID = "11111111-1111-4111-8111-111111111111"
	bridge.Assign(viewID, "/managed/view.code-workspace", 1, "/managed")
	stream := &bridgeStream{messages: make(chan []byte, 1), retired: make(chan struct{})}
	bridge.mu.Lock()
	bridge.views[viewID].stream = stream
	bridge.mu.Unlock()
	if err := bridge.WaitForConnection(context.Background(), viewID); err != nil {
		t.Fatalf("connected wait=%v", err)
	}
	bridge.mu.Lock()
	bridge.views[viewID].stream = nil
	bridge.mu.Unlock()
	waitingCtx, cancel := context.WithCancel(context.Background())
	waiting := make(chan error, 1)
	go func() { waiting <- bridge.WaitForConnection(waitingCtx, viewID) }()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		bridge.mu.Lock()
		count := len(bridge.waiters[viewID])
		bridge.mu.Unlock()
		if count != 0 {
			break
		}
		time.Sleep(time.Millisecond)
	}
	cancel()
	select {
	case err := <-waiting:
		if FailureCode(err) != "BUSY" {
			t.Fatalf("cancelled waiting result=%v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("cancelled waiter remained blocked")
	}
	if err := bridge.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := bridge.WaitForConnection(context.Background(), viewID); FailureCode(err) != "UNAVAILABLE" {
		t.Fatalf("closed bridge wait=%v", err)
	}
}

// IDE-T-014 verifies assigning a replacement workspace retires old streams, waiters, pending commands, and indexes.
func TestAssignRebindFencesOldBinding(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	const viewID = "11111111-1111-4111-8111-111111111111"
	oldWorkspace := "/managed/old.code-workspace"
	newWorkspace := "/managed/new.code-workspace"
	bridge.Assign(viewID, oldWorkspace, 1, "/managed")
	stream := &bridgeStream{hostID: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", messages: make(chan []byte), retired: make(chan struct{})}
	pending := make(chan BridgeResult, 1)
	waiter := make(chan error, 1)
	bridge.mu.Lock()
	bridge.views[viewID].stream = stream
	bridge.views[viewID].hostID = stream.hostID
	bridge.pending["old"] = &bridgePending{viewID: viewID, epoch: 1, result: pending}
	bridge.waiters[viewID] = map[chan error]struct{}{waiter: {}}
	bridge.mu.Unlock()
	bridge.Assign(viewID, newWorkspace, 2, "/managed")
	if result := <-pending; result.Code != "VIEW_GONE" {
		t.Fatalf("old command result=%+v", result)
	}
	if err := <-waiter; FailureCode(err) != "VIEW_GONE" {
		t.Fatalf("old waiter=%v", err)
	}
	select {
	case <-stream.retired:
	default:
		t.Fatal("old stream was not retired")
	}
	bridge.mu.Lock()
	oldID, newID := bridge.byKey[bridge.pathKey(oldWorkspace)], bridge.byKey[bridge.pathKey(newWorkspace)]
	view := bridge.views[viewID]
	bridge.mu.Unlock()
	if oldID != "" || newID != viewID || view.epoch != 2 {
		t.Fatalf("old route=%q new route=%q view=%+v", oldID, newID, view)
	}
	bridge.Assign(viewID, newWorkspace, 3, "/managed")
	bridge.mu.Lock()
	view = bridge.views[viewID]
	bridge.mu.Unlock()
	if view.epoch != 3 || bridge.pathKey(newWorkspace) != view.key {
		t.Fatalf("same-path rebind=%+v", view)
	}
}

// IDE-T-017 verifies a queued command resolves as VIEW_GONE when its stream is retired.
func TestRequestStreamRetirement(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	const viewID = "11111111-1111-4111-8111-111111111111"
	bridge.Assign(viewID, "/managed/view.code-workspace", 1, "/managed")
	stream := &bridgeStream{messages: make(chan []byte), retired: make(chan struct{})}
	bridge.mu.Lock()
	bridge.views[viewID].stream = stream
	bridge.views[viewID].hostID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	bridge.mu.Unlock()
	requestResult := make(chan BridgeResult, 1)
	requestError := make(chan error, 1)
	go func() {
		result, err := bridge.Request(context.Background(), viewID, BridgeCommand{Op: "inspect"}, BridgeRequestOptions{ExpectedEpoch: 1})
		requestResult <- result
		requestError <- err
	}()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		bridge.mu.Lock()
		pending := len(bridge.pending)
		bridge.mu.Unlock()
		if pending > 0 {
			break
		}
		time.Sleep(time.Millisecond)
	}
	stream.close(true)
	select {
	case result := <-requestResult:
		err := <-requestError
		if err != nil || result.Code != "VIEW_GONE" || result.Message != "ide window was closed" {
			t.Fatalf("retired request result=%+v err=%v", result, err)
		}
	case <-time.After(time.Second):
		t.Fatal("retired request remained blocked")
	}
}

// IDE-T-021 verifies pending bridge commands are fenced by epoch and directory-transition outcomes.
func TestBridgePendingLifecycleFences(t *testing.T) {
	makeBridge := func(epoch int64) (*Bridge, *bridgeView, *bridgeStream) {
		stream := &bridgeStream{hostID: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", retired: make(chan struct{})}
		view := &bridgeView{epoch: epoch, stream: stream, hostID: stream.hostID, directory: "/workspace/old"}
		bridge := &Bridge{views: map[string]*bridgeView{"11111111-1111-4111-8111-111111111111": view}, pending: make(map[string]*bridgePending), waiters: make(map[string]map[chan error]struct{})}
		return bridge, view, stream
	}
	const viewID = "11111111-1111-4111-8111-111111111111"

	t.Run("missing pending and stale stream are ignored", func(t *testing.T) {
		bridge, view, stream := makeBridge(3)
		bridge.resolvePending("missing", BridgeResult{OK: true})
		other := &bridgeStream{hostID: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", retired: make(chan struct{})}
		view.stream = other
		bridge.streamDisconnected(viewID, stream)
		if view.stream != other {
			t.Fatal("stale stream disconnected the current host")
		}
	})

	t.Run("directory transition accepted", func(t *testing.T) {
		bridge, view, stream := makeBridge(3)
		result := make(chan BridgeResult, 1)
		bridge.pending["change"] = &bridgePending{viewID: viewID, epoch: 3, op: "changeDirectory", expectedDirectory: "/workspace/new", stream: stream, result: result}
		bridge.resolvePending("change", BridgeResult{OK: true})
		if got := <-result; !got.OK || view.directory != "/workspace/new" {
			t.Fatalf("result=%+v directory=%q", got, view.directory)
		}
	})

	t.Run("stale epoch rejected", func(t *testing.T) {
		bridge, _, stream := makeBridge(4)
		result := make(chan BridgeResult, 1)
		bridge.pending["stale"] = &bridgePending{viewID: viewID, epoch: 3, op: "inspect", stream: stream, result: result}
		bridge.resolvePending("stale", BridgeResult{OK: true})
		if got := <-result; got.Code != "VIEW_GONE" {
			t.Fatalf("stale result=%+v", got)
		}
	})

	t.Run("rejected transition retires untrusted host", func(t *testing.T) {
		bridge, view, stream := makeBridge(3)
		result := make(chan BridgeResult, 1)
		bridge.pending["change"] = &bridgePending{viewID: viewID, epoch: 3, op: "changeDirectory", expectedDirectory: "/workspace/new", stream: stream, result: result}
		bridge.resolvePending("change", BridgeResult{Code: "UNAVAILABLE"})
		if got := <-result; got.Code != "UNAVAILABLE" {
			t.Fatalf("result=%+v", got)
		}
		if view.stream != nil || view.hostID != "" {
			t.Fatalf("untrusted host retained: %+v", view)
		}
		select {
		case <-stream.retired:
		default:
			t.Fatal("retired host was not closed")
		}
	})

	t.Run("dirty transition remains bound", func(t *testing.T) {
		bridge, view, stream := makeBridge(3)
		result := make(chan BridgeResult, 1)
		bridge.pending["change"] = &bridgePending{viewID: viewID, epoch: 3, op: "changeDirectory", stream: stream, result: result}
		bridge.resolvePending("change", BridgeResult{Code: "DIRTY"})
		<-result
		if view.stream != stream || view.hostID == "" {
			t.Fatal("dirty transition disconnected its active host")
		}
	})

	t.Run("disconnect settles ordinary commands and waits for transition confirmation", func(t *testing.T) {
		bridge, view, stream := makeBridge(3)
		ordinary := make(chan BridgeResult, 1)
		transition := make(chan BridgeResult, 1)
		bridge.pending["inspect"] = &bridgePending{viewID: viewID, epoch: 3, op: "inspect", stream: stream, result: ordinary}
		bridge.pending["change"] = &bridgePending{viewID: viewID, epoch: 3, op: "changeDirectory", stream: stream, result: transition}
		bridge.streamDisconnected(viewID, stream)
		if got := <-ordinary; got.Message != "ide window closed" {
			t.Fatalf("ordinary result=%+v", got)
		}
		if view.stream != nil || view.hostID != "" {
			t.Fatalf("transition disconnect state=%+v", view)
		}
		if _, ok := bridge.pending["change"]; !ok {
			t.Fatal("directory transition was settled before confirmation")
		}
		bridge.mu.Lock()
		bridge.settleViewLocked(viewID, BridgeResult{Code: "VIEW_GONE"})
		bridge.mu.Unlock()
		if got := <-transition; got.Code != "VIEW_GONE" {
			t.Fatalf("transition result=%+v", got)
		}
	})
}
