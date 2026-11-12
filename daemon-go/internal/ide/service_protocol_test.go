package ide

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"google.golang.org/protobuf/proto"
)

// IDE-T-100 validates live IDE replies against the generated protobuf wire contract.
func TestLiveRepliesProtocol(t *testing.T) {
	root, err := CanonicalDirectory(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{
		DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())

	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	validateLiveIDEReply(t, opened, true)
	if !opened.GetOk() || opened.GetViewId() == "" || opened.GetBindingEpoch() <= 0 {
		t.Fatalf("invalid OPEN reply: %v", opened)
	}
	for _, view := range opened.GetViews() {
		if view.GetState() != protocol.IdeViewState_OPEN && view.GetState() != protocol.IdeViewState_CLOSING {
			t.Fatalf("invalid view state: %v", view)
		}
	}

	listed := service.HandleCommand(context.Background(), &protocol.IdeCommand{Action: protocol.IdeAction_LIST_VIEWS})
	validateLiveIDEReply(t, listed, true)
	if !listed.GetOk() || len(listed.GetViews()) != 1 || listed.GetViews()[0].GetViewId() != opened.GetViewId() {
		t.Fatalf("invalid LIST_VIEWS reply: %v", listed)
	}

	next := filepath.Join(root, "next")
	if err := os.Mkdir(next, 0o700); err != nil {
		t.Fatal(err)
	}
	oldCtx, cancelOld := context.WithCancel(context.Background())
	oldStream, oldReader := openManualBridgeStream(t, oldCtx, bridge, opened.GetWorkspacePath(), "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", root)
	if _, err := oldReader.ReadString('\n'); err != nil {
		cancelOld()
		t.Fatal(err)
	}
	changedResult := make(chan *protocol.IdeResult, 1)
	go func() {
		changedResult <- service.HandleCommand(context.Background(), &protocol.IdeCommand{
			Action: protocol.IdeAction_CHANGE_DIRECTORY, ViewId: stringPointer(opened.GetViewId()),
			BrowserSessionId: stringPointer(opened.GetBrowserSessionId()), BindingEpoch: int64Pointer(opened.GetBindingEpoch()),
			Directory: stringPointer(next), ExpectedDaemonInstanceId: stringPointer("99999999-9999-4999-8999-999999999999"),
		})
	}()
	var nativeCommand struct {
		ID        string `json:"id"`
		Op        string `json:"op"`
		Directory string `json:"directory"`
	}
	if err := json.NewDecoder(oldReader).Decode(&nativeCommand); err != nil {
		cancelOld()
		t.Fatal(err)
	}
	if nativeCommand.Op != "changeDirectory" || nativeCommand.Directory != next {
		cancelOld()
		t.Fatalf("native command=%+v", nativeCommand)
	}
	nextCtx, cancelNext := context.WithCancel(context.Background())
	nextStream, nextReader := openManualBridgeStream(t, nextCtx, bridge, opened.GetWorkspacePath(), "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", next)
	if _, err := nextReader.ReadString('\n'); err != nil {
		cancelOld()
		cancelNext()
		t.Fatal(err)
	}
	var changed *protocol.IdeResult
	select {
	case changed = <-changedResult:
	case <-time.After(time.Second):
		cancelOld()
		cancelNext()
		t.Fatal("CHANGE_DIRECTORY did not receive replacement-host confirmation")
	}
	cancelOld()
	cancelNext()
	_ = oldStream.Body.Close()
	_ = nextStream.Body.Close()
	deadline := time.Now().Add(time.Second)
	for service.ActiveStreamCount() != 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if service.ActiveStreamCount() != 0 {
		t.Fatal("bridge streams remained active after host cancellation")
	}
	validateLiveIDEReply(t, changed, true)
	if !changed.GetOk() || changed.GetDirectory() != next {
		t.Fatalf("invalid CHANGE_DIRECTORY reply: %v", changed)
	}

	normalCloseUnknown := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, opened, false))
	validateLiveIDEReply(t, normalCloseUnknown, true)
	if normalCloseUnknown.GetOk() || normalCloseUnknown.GetCode() != "UNAVAILABLE" {
		t.Fatalf("unexpected normal close reply: %v", normalCloseUnknown)
	}
	manager.closeFailures = 1
	failedClose := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, opened, true))
	validateLiveIDEReply(t, failedClose, true)
	if failedClose.GetOk() || failedClose.GetCode() != "CLEANUP_FAILED" || failedClose.Revision == nil || len(failedClose.GetViews()) != 1 || failedClose.GetViews()[0].GetState() != protocol.IdeViewState_CLOSING {
		t.Fatalf("invalid failed CLOSE_VIEW reply: %v", failedClose)
	}

	closed := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, opened, true))
	validateLiveIDEReply(t, closed, true)
	if !closed.GetOk() || closed.GetRevision() <= failedClose.GetRevision() || len(closed.GetViews()) != 0 {
		t.Fatalf("invalid successful CLOSE_VIEW reply: %v", closed)
	}
}

func validateLiveIDEReply(t *testing.T, reply *protocol.IdeResult, snapshotRequired bool) {
	t.Helper()
	if reply == nil || reply.Ok == nil {
		t.Fatalf("IDE reply omits required ok field: %v", reply)
	}
	if !reply.GetOk() && (reply.Code == nil || reply.Message == nil) {
		t.Fatalf("failed IDE reply omits code/message: %v", reply)
	}
	if snapshotRequired && (reply.Revision == nil || reply.GetRevision() < 0 || reply.Views == nil) {
		t.Fatalf("IDE reply omits its complete snapshot: %v", reply)
	}
	previousID := ""
	for _, view := range reply.GetViews() {
		if view == nil || view.ViewId == nil || view.BrowserSessionId == nil || view.EventNodeId == nil || view.ConnectionId == nil || view.ClaimId == nil || view.BindingEpoch == nil || view.Directory == nil || view.WorkspacePath == nil {
			t.Fatalf("view summary omits frozen identity fields: %v", view)
		}
		if view.GetBindingEpoch() <= 0 || view.GetViewId() <= previousID || (view.GetState() != protocol.IdeViewState_OPEN && view.GetState() != protocol.IdeViewState_CLOSING) {
			t.Fatalf("invalid or non-canonical view summary: %v", view)
		}
		previousID = view.GetViewId()
	}
	envelope := &protocol.Envelope{
		MessageType:   protocol.MessageType_IDE_RESULT,
		EnvironmentId: stringPointer("99999999-9999-4999-8999-999999999999"),
		InvocationId:  stringPointer("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
		Payload:       &protocol.Envelope_IdeResult{IdeResult: reply},
	}
	encoded, err := proto.Marshal(envelope)
	if err != nil {
		t.Fatal(err)
	}
	var decoded protocol.Envelope
	if err := proto.Unmarshal(encoded, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.GetMessageType() != protocol.MessageType_IDE_RESULT || decoded.GetEnvironmentId() == "" || decoded.GetInvocationId() == "" || decoded.GetIdeResult() == nil {
		t.Fatalf("invalid IDE_RESULT envelope after protobuf round trip: %v", &decoded)
	}
}
