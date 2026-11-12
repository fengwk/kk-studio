package ide

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"github.com/fengwk/kk-studio/daemon/internal/transport"
)

// IDE-T-087 verifies malformed open commands are rejected before process or view registration work.
func TestValidateBeforeReserve(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{
		DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	result := service.HandleCommand(context.Background(), &protocol.IdeCommand{
		Action: protocol.IdeAction_OPEN_VIEW, BrowserSessionId: stringPointer("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
		EventNodeId: stringPointer("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"), ConnectionId: stringPointer("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
		ClaimId: stringPointer("dddddddd-dddd-4ddd-8ddd-dddddddddddd"), Mode: protocol.IdeOpenMode_CREATE,
		ExpectedBindingEpoch: int64Pointer(0), ExpectedDaemonInstanceId: stringPointer("99999999-9999-4999-8999-999999999999"),
	})
	if result.GetOk() || result.GetMessage() != "viewId is required" || manager.startCalls != 0 || manager.registerCalls != 0 {
		t.Fatalf("result = %v, starts = %d, registrations = %d", result, manager.startCalls, manager.registerCalls)
	}
}

// IDE-T-013 verifies service construction validates daemon identity and installs the PTY tracker boundary.
func TestServiceConstructionAndPTYTracker(t *testing.T) {
	if _, err := NewService(config.DaemonConfig{}, nil, nil, ServiceOptions{DaemonInstanceID: "BAD"}); err == nil {
		t.Fatal("invalid daemon identity accepted")
	}
	defaultService, err := NewService(fakeIDEConfig(t.TempDir()), &fakeSupervisor{}, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999"})
	if err != nil {
		t.Fatal(err)
	}
	if defaultService.maxViews != defaultMaxViews || defaultService.recoveryQuota != defaultRecoveryQuota || defaultService.scheduler == nil {
		t.Fatalf("default service configuration: %#v", defaultService)
	}
	if err := defaultService.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	tracker := &fakeDescendantTracker{}
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, tracker, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	body, err := json.Marshal(map[string]any{"workspacePath": opened.GetWorkspacePath(), "pid": 123})
	if err != nil {
		t.Fatal(err)
	}
	if status := postBridge(t, bridge, "/bridge/pty", body); status != http.StatusNoContent || tracker.calls != 1 || tracker.pid != 123 || tracker.rootPID != manager.Session().PID {
		t.Fatalf("PTY status=%d tracker=%+v session=%+v", status, tracker, manager.Session())
	}
	if err := manager.Stop(context.Background()); err != nil {
		t.Fatal(err)
	}
	if status := postBridge(t, bridge, "/bridge/pty", body); status != http.StatusInternalServerError {
		t.Fatalf("PTY was accepted without a live process supervisor, status=%d", status)
	}
}

// IDE-T-118 verifies concurrent manager stops share one operation and cancelled waiters do not cancel it.
func TestConcurrentManagerStopWaiterCancellation(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	manager := &fakeCodeServerManager{stopGate: make(chan struct{})}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	firstResult := make(chan error, 1)
	go func() { firstResult <- service.stopManager(context.Background()) }()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		manager.mu.Lock()
		started := manager.stopCalls == 1
		manager.mu.Unlock()
		if started {
			break
		}
		time.Sleep(time.Millisecond)
	}
	manager.mu.Lock()
	started := manager.stopCalls == 1
	manager.mu.Unlock()
	if !started {
		t.Fatal("manager stop did not start")
	}
	waitCtx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := service.stopManager(waitCtx); !errors.Is(err, ErrCancelled) {
		t.Fatalf("cancelled waiter error=%v", err)
	}
	close(manager.stopGate)
	if err := <-firstResult; err != nil {
		t.Fatalf("shared manager stop error=%v", err)
	}
	manager.mu.Lock()
	stopCalls := manager.stopCalls
	manager.mu.Unlock()
	if stopCalls != 1 {
		t.Fatalf("manager stops=%d", stopCalls)
	}
}

// IDE-T-123 verifies service shutdown aborts active streams and repeated close is convergent.
func TestServiceCloseCancelsStreams(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager})
	if err != nil {
		t.Fatal(err)
	}
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	upstream, err := service.ResolveView(context.Background(), transport.ViewRequest{ViewID: opened.GetViewId(), BindingEpoch: opened.GetBindingEpoch(), Mode: "HTTP", Method: "GET", Path: "/"})
	if err != nil {
		t.Fatal(err)
	}
	if err := service.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	select {
	case <-upstream.Context.Done():
	case <-time.After(time.Second):
		upstream.Release()
		t.Fatal("service shutdown did not cancel stream")
	}
	upstream.Release()
	if err := service.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	if service.ActiveStreamCount() != 0 || manager.closeCalls != 0 {
		t.Fatalf("streams=%d managed view closes=%d", service.ActiveStreamCount(), manager.closeCalls)
	}
	if result := service.HandleCommand(context.Background(), &protocol.IdeCommand{Action: protocol.IdeAction_LIST_VIEWS}); result.GetCode() != "UNAVAILABLE" {
		t.Fatalf("command after close=%v", result)
	}
	if _, _, err := service.RestoreBackup(context.Background(), opened.GetViewId()); FailureCode(err) != "UNAVAILABLE" {
		t.Fatalf("restore after close=%v", err)
	}
	if err := service.StopForUpdate(context.Background()); FailureCode(err) != "UNAVAILABLE" {
		t.Fatalf("update after close=%v", err)
	}
}

// IDE-T-135 verifies stream route validation and restore absence do not allocate work or leak service details.
func TestResolveRouteAndRestoreMissing(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: &fakeCodeServerManager{}})
	if err != nil {
		t.Fatal(err)
	}
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	request := transport.ViewRequest{ViewID: opened.GetViewId(), BindingEpoch: opened.GetBindingEpoch(), Mode: "HTTP", Method: "GET", Path: "//outside"}
	if _, err := service.ResolveView(context.Background(), request); err == nil || service.ActiveStreamCount() != 0 {
		t.Fatalf("unsafe route err=%v streams=%d", err, service.ActiveStreamCount())
	}
	if _, err := service.ResolveView(context.Background(), transport.ViewRequest{ViewID: opened.GetViewId(), BindingEpoch: 2, Mode: "HTTP", Method: "GET", Path: "/"}); err == nil {
		t.Fatal("stale binding resolved")
	}
	transportRequest := transport.ViewRequest{ViewID: opened.GetViewId(), BindingEpoch: 0, Mode: "HTTP", Method: "GET", Path: "/"}
	if _, err := service.ResolveView(context.Background(), transportRequest); FailureCode(err) != "UNAVAILABLE" {
		t.Fatalf("invalid epoch error=%v", err)
	}
	transportRequest.BindingEpoch = opened.GetBindingEpoch()
	resolved, err := service.ResolveView(context.Background(), transportRequest)
	if err != nil || resolved.Host != "127.0.0.1" || resolved.Port == 0 {
		t.Fatalf("transport resolve=%+v err=%v", resolved, err)
	}
	resolved.Release()
	if restored, skipped, err := service.RestoreBackup(context.Background(), "22222222-2222-4222-8222-222222222222"); err != nil || restored != 0 || skipped != 0 {
		t.Fatalf("missing restore=(%d,%d) err=%v", restored, skipped, err)
	}
	if _, _, err := service.RestoreBackup(context.Background(), "invalid"); err == nil {
		t.Fatal("invalid recovery view accepted")
	}
	if err := service.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	if _, err := service.ResolveView(context.Background(), request); err == nil || err.Error() != "IDE service is closed" {
		t.Fatalf("closed resolver error=%v", err)
	}
}

// IDE-T-087 verifies every binding identity and daemon fence is enforced before an open-view command.
func TestRequiredViewCommandValidation(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, &fakeDescendantTracker{}, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: &fakeCodeServerManager{}})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	cases := []struct {
		name   string
		mutate func(*protocol.IdeCommand)
		want   string
	}{
		{name: "daemon omitted", mutate: func(c *protocol.IdeCommand) { c.ExpectedDaemonInstanceId = nil }, want: "VIEW_GONE"},
		{name: "view omitted", mutate: func(c *protocol.IdeCommand) { c.ViewId = nil }, want: "UNAVAILABLE"},
		{name: "invalid view", mutate: func(c *protocol.IdeCommand) { c.ViewId = stringPointer("bad") }, want: "UNAVAILABLE"},
		{name: "browser omitted", mutate: func(c *protocol.IdeCommand) { c.BrowserSessionId = nil }, want: "UNAVAILABLE"},
		{name: "invalid browser", mutate: func(c *protocol.IdeCommand) { c.BrowserSessionId = stringPointer("BAD") }, want: "UNAVAILABLE"},
		{name: "epoch omitted", mutate: func(c *protocol.IdeCommand) { c.BindingEpoch = nil }, want: "UNAVAILABLE"},
		{name: "epoch nonpositive", mutate: func(c *protocol.IdeCommand) { c.BindingEpoch = int64Pointer(0) }, want: "UNAVAILABLE"},
		{name: "view missing", mutate: func(c *protocol.IdeCommand) { c.ViewId = stringPointer("22222222-2222-4222-8222-222222222222") }, want: "VIEW_GONE"},
		{name: "foreign owner", mutate: func(c *protocol.IdeCommand) {
			c.BrowserSessionId = stringPointer("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
		}, want: "VIEW_OWNED_BY_OTHER_SESSION"},
		{name: "stale epoch", mutate: func(c *protocol.IdeCommand) { c.BindingEpoch = int64Pointer(opened.GetBindingEpoch() + 1) }, want: "STALE_BINDING"},
		{name: "wrong daemon", mutate: func(c *protocol.IdeCommand) {
			c.ExpectedDaemonInstanceId = stringPointer("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
		}, want: "VIEW_GONE"},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			command := viewCommandForTest(protocol.IdeAction_COLLAPSE_MOBILE, opened, false)
			test.mutate(command)
			result := service.HandleCommand(context.Background(), command)
			if result.GetCode() != test.want {
				t.Fatalf("result=%v want %q", result, test.want)
			}
		})
	}
	service.mu.Lock()
	record := service.views[opened.GetViewId()]
	record.state = viewClosing
	service.mu.Unlock()
	closing := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_COLLAPSE_MOBILE, opened, false))
	if closing.GetCode() != "BUSY" {
		t.Fatalf("closing view result=%v", closing)
	}
}

// IDE-T-087 verifies command validation and safe failure shapes precede any managed side effect.
func TestCommandValidationAndDispatch(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	invalid := []func(*protocol.IdeCommand){
		func(c *protocol.IdeCommand) { c.ExpectedBindingEpoch = nil },
		func(c *protocol.IdeCommand) { c.ExpectedBindingEpoch = int64Pointer(-1) },
		func(c *protocol.IdeCommand) { c.ExpectedBindingEpoch = int64Pointer(1) },
		func(c *protocol.IdeCommand) { c.Mode = protocol.IdeOpenMode(99) },
		func(c *protocol.IdeCommand) { c.ViewId = stringPointer("bad") },
		func(c *protocol.IdeCommand) { c.BrowserSessionId = stringPointer("BAD") },
		func(c *protocol.IdeCommand) { c.EventNodeId = stringPointer("BAD") },
		func(c *protocol.IdeCommand) { c.ConnectionId = stringPointer("BAD") },
		func(c *protocol.IdeCommand) { c.ClaimId = stringPointer("BAD") },
		func(c *protocol.IdeCommand) { c.Directory = stringPointer(" ") },
		func(c *protocol.IdeCommand) {
			c.Directory = stringPointer(strings.Repeat("x", maxCommandTextCharacters+1))
		},
		func(c *protocol.IdeCommand) { c.Directory = stringPointer("/bad\npath") },
	}
	for i, mutate := range invalid {
		command := openCommandForTest("/tmp", "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd")
		mutate(command)
		result := service.HandleCommand(context.Background(), command)
		if result.GetOk() || result.GetRevision() != 0 || len(result.GetViews()) != 0 {
			t.Errorf("invalid case %d result=%v", i, result)
		}
	}
	wrongDaemon := openCommandForTest("/tmp", "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd")
	wrongDaemon.ExpectedDaemonInstanceId = stringPointer("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
	if result := service.HandleCommand(context.Background(), wrongDaemon); result.GetOk() || result.GetCode() != "VIEW_GONE" {
		t.Fatalf("wrong daemon result=%v", result)
	}
	if manager.startCalls != 0 || manager.registerCalls != 0 {
		t.Fatalf("validation side effects starts=%d register=%d", manager.startCalls, manager.registerCalls)
	}
	if views := service.Handle(context.Background(), &protocol.IdeCommand{Action: protocol.IdeAction_LIST_VIEWS}); !views.GetOk() || views.GetRevision() != 0 {
		t.Fatalf("list views=%v", views)
	}
	if unknown := service.HandleCommand(context.Background(), &protocol.IdeCommand{Action: protocol.IdeAction(99)}); unknown.GetOk() || unknown.GetMessage() != "unknown ide action" {
		t.Fatalf("unknown action=%v", unknown)
	}
	if unknown := service.HandleCommand(context.Background(), nil); unknown.GetOk() || unknown.GetMessage() != "unknown ide action" {
		t.Fatalf("nil action=%v", unknown)
	}
	badListing := service.HandleCommand(context.Background(), &protocol.IdeCommand{Action: protocol.IdeAction_LIST_DIRECTORIES, Directory: stringPointer(" ")})
	if badListing.GetOk() || badListing.Revision != nil || badListing.Views != nil {
		t.Fatalf("list-directory error included snapshot: %v", badListing)
	}
	if service.HasActiveViews() || service.GetViewState("bad") != nil {
		t.Fatal("empty service reports a view")
	}
}

// IDE-T-105 verifies an identical claim is replayed once and request drift is rejected.
func TestClaimReplayAndDrift(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	command := openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd")
	first := service.HandleCommand(context.Background(), command)
	second := service.HandleCommand(context.Background(), command)
	if !first.GetOk() || !second.GetOk() || first.GetViewId() != second.GetViewId() || manager.startCalls != 1 || manager.registerCalls != 1 {
		t.Fatalf("first=%v second=%v starts=%d registers=%d", first, second, manager.startCalls, manager.registerCalls)
	}
	drift := openCommandForTest(filepath.Join(root, "different"), command.GetViewId(), command.GetClaimId())
	conflict := service.HandleCommand(context.Background(), drift)
	if conflict.GetOk() || conflict.GetCode() != "CLAIM_CONFLICT" || manager.registerCalls != 1 {
		t.Fatalf("claim drift result=%v registers=%d", conflict, manager.registerCalls)
	}
}

// IDE-T-107 verifies resume advances the epoch and an old close cannot touch the new binding.
func TestEpochCAS(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	open := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !open.GetOk() {
		t.Fatalf("open reply=%v", open)
	}
	resume := openCommandForTest(root, open.GetViewId(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
	resume.Mode = protocol.IdeOpenMode_RESUME
	resume.ExpectedBindingEpoch = int64Pointer(open.GetBindingEpoch())
	resumed := service.HandleCommand(context.Background(), resume)
	if !resumed.GetOk() || resumed.GetBindingEpoch() <= open.GetBindingEpoch() {
		t.Fatalf("resume reply=%v", resumed)
	}
	oldClose := &protocol.IdeCommand{Action: protocol.IdeAction_CLOSE_VIEW, ViewId: stringPointer(open.GetViewId()), BrowserSessionId: stringPointer("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), BindingEpoch: int64Pointer(open.GetBindingEpoch()), Discard: boolPointer(false), ExpectedDaemonInstanceId: stringPointer("99999999-9999-4999-8999-999999999999")}
	stale := service.HandleCommand(context.Background(), oldClose)
	if stale.GetOk() || stale.GetCode() != "STALE_BINDING" || manager.closeCalls != 0 {
		t.Fatalf("old close=%v closeCalls=%d", stale, manager.closeCalls)
	}
}

func viewCommandForTest(action protocol.IdeAction, opened *protocol.IdeResult, discard bool) *protocol.IdeCommand {
	return &protocol.IdeCommand{Action: action, ViewId: stringPointer(opened.GetViewId()), BrowserSessionId: stringPointer(opened.GetBrowserSessionId()), BindingEpoch: int64Pointer(opened.GetBindingEpoch()), Discard: boolPointer(discard), ExpectedDaemonInstanceId: stringPointer("99999999-9999-4999-8999-999999999999")}
}

func openCommandForTest(directory, viewID, claimID string) *protocol.IdeCommand {
	return &protocol.IdeCommand{Action: protocol.IdeAction_OPEN_VIEW, ViewId: stringPointer(viewID), BrowserSessionId: stringPointer("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), EventNodeId: stringPointer("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"), ConnectionId: stringPointer("cccccccc-cccc-4ccc-8ccc-cccccccccccc"), ClaimId: stringPointer(claimID), Mode: protocol.IdeOpenMode_CREATE, Directory: stringPointer(directory), ExpectedBindingEpoch: int64Pointer(0), ExpectedDaemonInstanceId: stringPointer("99999999-9999-4999-8999-999999999999")}
}

// IDE-T-104 verifies expiry defers when a connected view is dirty instead of discarding its state.
func TestLeaseExpiryDefersDirtyView(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	scheduler := newFakeIdeScheduler(time.Date(2025, 1, 1, 0, 0, 0, 0, time.UTC))
	events := make(chan IdeServiceEvent, 2)
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager, Scheduler: scheduler, OnEvent: func(event IdeServiceEvent) { events <- event }})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	_, cancelHost := startBridgeReplyHostWith(t, bridge, opened.GetWorkspacePath(), opened.GetViewId(), root, map[string]map[string]any{"inspect": {"clean": false}})
	defer cancelHost()
	scheduler.Advance(viewLeaseDuration)
	if state := service.GetViewState(opened.GetViewId()); state == nil || state.GetState() != protocol.IdeViewState_OPEN {
		t.Fatalf("dirty expiry state=%v", state)
	}
	if manager.closeCalls != 0 {
		t.Fatalf("dirty view was closed: %d", manager.closeCalls)
	}
	select {
	case event := <-events:
		if event.Kind != "expiry-deferred" {
			t.Fatalf("expiry event=%+v", event)
		}
	default:
		t.Fatal("dirty expiry deferral was not reported")
	}
}

// IDE-T-104 verifies expiry defers while disconnected and a later connected lease expiry closes cleanly.
func TestLeaseExpiryDefersUntilReachable(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	scheduler := newFakeIdeScheduler(time.Date(2025, 1, 1, 0, 0, 0, 0, time.UTC))
	events := make(chan IdeServiceEvent, 2)
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager, Scheduler: scheduler, OnEvent: func(event IdeServiceEvent) { events <- event }})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	scheduler.Advance(viewLeaseDuration)
	if state := service.GetViewState(opened.GetViewId()); state == nil || state.GetState() != protocol.IdeViewState_OPEN {
		t.Fatalf("disconnected expiry state=%v", state)
	}
	select {
	case event := <-events:
		if event.Kind != "expiry-deferred" {
			t.Fatalf("expiry event=%+v", event)
		}
	default:
		t.Fatal("expiry deferral was not reported")
	}
	_, cancelHost := startBridgeReplyHost(t, bridge, opened.GetWorkspacePath(), opened.GetViewId(), root)
	defer cancelHost()
	scheduler.Advance(viewLeaseDuration)
	if service.GetViewState(opened.GetViewId()) != nil || manager.closeCalls != 1 {
		t.Fatalf("reachable expiry state=%v closes=%d", service.GetViewState(opened.GetViewId()), manager.closeCalls)
	}
}

// IDE-T-104 verifies only newer identity-matching daemon lease proofs renew the view lease.
func TestLeaseRenewalFreshProof(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	scheduler := newFakeIdeScheduler(time.Date(2025, 1, 1, 0, 0, 0, 0, time.UTC))
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager, Scheduler: scheduler})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	_, cancelHost := startBridgeReplyHost(t, bridge, opened.GetWorkspacePath(), opened.GetViewId(), root)
	defer cancelHost()
	binding := &protocol.IdeLeaseBinding{ViewId: stringPointer(opened.GetViewId()), BrowserSessionId: stringPointer(opened.GetBrowserSessionId()), BindingEpoch: int64Pointer(opened.GetBindingEpoch())}
	scheduler.Advance(300 * time.Second)
	proofTime := scheduler.Now()
	service.ApplyLease(&protocol.IdeLease{Bindings: []*protocol.IdeLeaseBinding{binding}}, proofTime)
	service.ApplyLease(&protocol.IdeLease{Bindings: []*protocol.IdeLeaseBinding{binding}}, proofTime)
	service.ApplyLease(&protocol.IdeLease{Bindings: []*protocol.IdeLeaseBinding{nil, {}, {ViewId: stringPointer(opened.GetViewId()), BrowserSessionId: stringPointer("ffffffff-ffff-4fff-8fff-ffffffffffff"), BindingEpoch: int64Pointer(opened.GetBindingEpoch())}}}, proofTime.Add(time.Second))
	service.ApplyLease(&protocol.IdeLease{Bindings: []*protocol.IdeLeaseBinding{binding}}, proofTime.Add(-time.Second))
	service.ApplyLease(nil, proofTime.Add(2*time.Second))
	service.ApplyLease(&protocol.IdeLease{}, time.Time{})
	scheduler.Advance(viewLeaseDuration - time.Second)
	if service.GetViewState(opened.GetViewId()) == nil {
		t.Fatal("fresh proof did not renew lease")
	}
	scheduler.Advance(time.Second)
	if service.GetViewState(opened.GetViewId()) != nil {
		t.Fatal("expired view survived beyond its last fresh proof")
	}
}

// IDE-T-104 verifies fresh lease proof preserves a view through 599 seconds and closes at 600.
func TestLeaseExpiryBoundary(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	scheduler := newFakeIdeScheduler(time.Date(2025, 1, 1, 0, 0, 0, 0, time.UTC))
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager, Scheduler: scheduler})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	_, cancelHost := startBridgeReplyHost(t, bridge, opened.GetWorkspacePath(), opened.GetViewId(), root)
	defer cancelHost()
	binding := &protocol.IdeLeaseBinding{ViewId: stringPointer(opened.GetViewId()), BrowserSessionId: stringPointer("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), BindingEpoch: int64Pointer(opened.GetBindingEpoch())}
	lease := &protocol.IdeLease{Bindings: []*protocol.IdeLeaseBinding{binding}}
	roundStart := scheduler.Now()
	service.ApplyLease(lease, roundStart)
	scheduler.Advance(599 * time.Second)
	if view := service.GetViewState(opened.GetViewId()); view == nil || view.GetState() != protocol.IdeViewState_OPEN {
		t.Fatalf("view at 599s = %v", view)
	}
	scheduler.Advance(time.Second)
	if view := service.GetViewState(opened.GetViewId()); view != nil {
		t.Fatalf("view remains after confirmed clean close: %v", view)
	}
	if manager.closeCalls != 1 {
		t.Fatalf("managed close calls = %d", manager.closeCalls)
	}
}

// IDE-T-108/110/113/120 verify owner fencing, capacity accounting, revision publication, and resume reuse.
func TestOwnerCapacityAndRevision(t *testing.T) {
	root := t.TempDir()
	otherDirectory := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	scheduler := newFakeIdeScheduler(time.Date(2025, 1, 1, 0, 0, 0, 0, time.UTC))
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager, Scheduler: scheduler, MaxViews: 1})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() || opened.GetRevision() != 2 {
		t.Fatalf("open=%v", opened)
	}
	duplicate := openCommandForTest(root, opened.GetViewId(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
	duplicate.BrowserSessionId = stringPointer("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
	if result := service.HandleCommand(context.Background(), duplicate); result.GetCode() != "VIEW_OWNED_BY_OTHER_SESSION" {
		t.Fatalf("foreign create=%v", result)
	}
	duplicate.BrowserSessionId = stringPointer(opened.GetBrowserSessionId())
	if result := service.HandleCommand(context.Background(), duplicate); result.GetCode() != "STALE_BINDING" {
		t.Fatalf("same-owner duplicate create=%v", result)
	}
	capacity := openCommandForTest(root, "22222222-2222-4222-8222-222222222222", "ffffffff-ffff-4fff-8fff-ffffffffffff")
	if result := service.HandleCommand(context.Background(), capacity); result.GetCode() != "IDE_CAPACITY_EXCEEDED" {
		t.Fatalf("capacity result=%v", result)
	}
	foreignResume := openCommandForTest(root, opened.GetViewId(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
	foreignResume.Mode = protocol.IdeOpenMode_RESUME
	foreignResume.ExpectedBindingEpoch = int64Pointer(opened.GetBindingEpoch())
	foreignResume.BrowserSessionId = stringPointer("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
	if result := service.HandleCommand(context.Background(), foreignResume); result.GetCode() != "VIEW_OWNED_BY_OTHER_SESSION" {
		t.Fatalf("foreign resume=%v", result)
	}
	changedResume := openCommandForTest(otherDirectory, opened.GetViewId(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
	changedResume.Mode = protocol.IdeOpenMode_RESUME
	changedResume.ExpectedBindingEpoch = int64Pointer(opened.GetBindingEpoch())
	if result := service.HandleCommand(context.Background(), changedResume); result.GetCode() != "VIEW_IN_USE" {
		t.Fatalf("directory-changing resume=%v", result)
	}
	resume := openCommandForTest(root, opened.GetViewId(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
	resume.Mode = protocol.IdeOpenMode_RESUME
	resume.ExpectedBindingEpoch = int64Pointer(opened.GetBindingEpoch())
	resumed := service.HandleCommand(context.Background(), resume)
	if !resumed.GetOk() || resumed.GetBindingEpoch() <= opened.GetBindingEpoch() || resumed.GetWorkspacePath() != opened.GetWorkspacePath() || resumed.GetRevision() <= opened.GetRevision() {
		t.Fatalf("resume=%v", resumed)
	}
	staleClaim := service.HandleCommand(context.Background(), openCommandForTest(root, opened.GetViewId(), "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if staleClaim.GetCode() != "STALE_BINDING" {
		t.Fatalf("superseded claim=%v", staleClaim)
	}
	staleEpoch := openCommandForTest(root, opened.GetViewId(), "99999999-9999-4999-8999-999999999998")
	staleEpoch.Mode = protocol.IdeOpenMode_RESUME
	staleEpoch.ExpectedBindingEpoch = int64Pointer(opened.GetBindingEpoch())
	if result := service.HandleCommand(context.Background(), staleEpoch); result.GetCode() != "STALE_BINDING" {
		t.Fatalf("stale resume=%v", result)
	}
	beforeProof := service.snapshot().Revision
	proof := &protocol.IdeLease{Bindings: []*protocol.IdeLeaseBinding{{ViewId: stringPointer(resumed.GetViewId()), BrowserSessionId: stringPointer(resumed.GetBrowserSessionId()), BindingEpoch: int64Pointer(resumed.GetBindingEpoch())}}}
	service.ApplyLease(proof, scheduler.Now())
	if service.snapshot().Revision != beforeProof {
		t.Fatal("lease proof changed visible revision")
	}
}

// IDE-T-108 verifies revision exhaustion rolls back the workspace before registration side effects.
func TestRevisionExhaustionRollsBackOpen(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	cfg := config.DaemonConfig{DataDir: t.TempDir()}
	manager := &fakeCodeServerManager{}
	service, err := NewService(cfg, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	service.mu.Lock()
	service.revision = math.MaxInt64
	service.mu.Unlock()
	command := openCommandForTest(t.TempDir(), "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd")
	result := service.HandleCommand(context.Background(), command)
	workspace := filepath.Join(IDEPathsFor(cfg).ViewsDir, command.GetViewId()+".code-workspace")
	if result.GetOk() || result.GetCode() != "BUSY" || manager.startCalls != 0 || manager.registerCalls != 0 {
		t.Fatalf("revision failure=%v starts=%d registers=%d", result, manager.startCalls, manager.registerCalls)
	}
	if _, err := os.Stat(workspace); !os.IsNotExist(err) {
		t.Fatalf("workspace survived revision failure: %v", err)
	}
	if service.HasActiveViews() {
		t.Fatal("failed open retained a view reservation")
	}
}

// IDE-T-121 verifies one claim ID cannot concurrently bind different view IDs.
func TestClaimIDSerializesAcrossViewIDs(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{
		DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: &fakeCodeServerManager{},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	root := t.TempDir()
	claimID := "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
	commands := []*protocol.IdeCommand{
		openCommandForTest(root, "11111111-1111-4111-8111-111111111111", claimID),
		openCommandForTest(root, "22222222-2222-4222-8222-222222222222", claimID),
	}
	start := make(chan struct{})
	results := make(chan *protocol.IdeResult, len(commands))
	for _, command := range commands {
		go func(command *protocol.IdeCommand) {
			<-start
			results <- service.HandleCommand(context.Background(), command)
		}(command)
	}
	close(start)
	var succeeded, conflicted int
	for range commands {
		result := <-results
		switch {
		case result.GetOk():
			succeeded++
		case result.GetCode() == "CLAIM_CONFLICT":
			conflicted++
		default:
			t.Fatalf("claim result=%v", result)
		}
	}
	if succeeded != 1 || conflicted != 1 || len(service.snapshot().Views) != 1 {
		t.Fatalf("success=%d conflicts=%d snapshot=%+v", succeeded, conflicted, service.snapshot())
	}
}

// IDE-T-105 verifies bounded claim replay cache refresh and oldest-claim eviction.
func TestClaimCacheRefreshAndEviction(t *testing.T) {
	service := &Service{claims: make(map[string]claimRecord)}
	service.rememberClaimLocked("first", claimRecord{viewID: "first"})
	service.rememberClaimLocked("second", claimRecord{viewID: "second"})
	service.rememberClaimLocked("first", claimRecord{viewID: "refreshed"})
	if len(service.claimOrder) != 2 || service.claimOrder[0] != "second" || service.claimOrder[1] != "first" || service.claims["first"].viewID != "refreshed" {
		t.Fatalf("claim refresh order=%v claims=%v", service.claimOrder, service.claims)
	}
	for index := 0; index < maxCachedClaims; index++ {
		service.rememberClaimLocked(strconv.Itoa(index), claimRecord{viewID: strconv.Itoa(index)})
	}
	if len(service.claimOrder) != maxCachedClaims || len(service.claims) != maxCachedClaims {
		t.Fatalf("claim cache lengths order=%d map=%d", len(service.claimOrder), len(service.claims))
	}
	if _, ok := service.claims["second"]; ok {
		t.Fatal("oldest claim was retained beyond the configured bound")
	}
}

// IDE-T-080 verifies rejected or drifted registration is removed only after native cleanup succeeds.
func TestFailedRegistrationConvergence(t *testing.T) {
	for _, test := range []struct {
		name, registerMode string
		closeFailures      int
		wantRetained       bool
	}{
		{name: "epoch drift cleanup succeeds", registerMode: "drift"},
		{name: "closed state cleanup succeeds", registerMode: "closed"},
		{name: "registration and cleanup fail", registerMode: "before-error", closeFailures: 1, wantRetained: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			root := t.TempDir()
			bridge, err := NewBridge()
			if err != nil {
				t.Fatal(err)
			}
			cfg := config.DaemonConfig{DataDir: t.TempDir()}
			manager := &fakeCodeServerManager{registerMode: test.registerMode, closeFailures: test.closeFailures}
			service, err := NewService(cfg, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager})
			if err != nil {
				t.Fatal(err)
			}
			defer service.Close(context.Background())
			command := openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd")
			result := service.HandleCommand(context.Background(), command)
			path := filepath.Join(IDEPathsFor(cfg).ViewsDir, command.GetViewId()+".code-workspace")
			state := service.GetViewState(command.GetViewId())
			if result.GetOk() || result.GetCode() != "CLEANUP_FAILED" {
				t.Fatalf("registration result=%v", result)
			}
			if test.wantRetained {
				if state == nil || state.GetState() != protocol.IdeViewState_CLOSING {
					t.Fatalf("retained state=%v", state)
				}
				if _, err := os.Stat(path); err != nil {
					t.Fatalf("retained workspace missing: %v", err)
				}
			} else {
				if state != nil {
					t.Fatalf("view retained after convergent cleanup: %v", state)
				}
				if _, err := os.Stat(path); !os.IsNotExist(err) {
					t.Fatalf("workspace remains after cleanup: %v", err)
				}
			}
		})
	}
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	cfg := config.DaemonConfig{DataDir: t.TempDir()}
	manager := &fakeCodeServerManager{startFailure: true}
	service, err := NewService(cfg, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	command := openCommandForTest(root, "22222222-2222-4222-8222-222222222222", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
	result := service.HandleCommand(context.Background(), command)
	path := filepath.Join(IDEPathsFor(cfg).ViewsDir, command.GetViewId()+".code-workspace")
	if result.GetOk() || result.GetCode() != "UNAVAILABLE" || service.GetViewState(command.GetViewId()) != nil {
		t.Fatalf("start failure=%v", result)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("workspace remains after startup failure: %v", err)
	}
}

// IDE-T-072 verifies a manager generation change during registration retains a CLOSING reservation and reports BUSY.
func TestCreateGenerationChangeDuringRegistration(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	stopGate := make(chan struct{})
	var releaseStop sync.Once
	unlockStop := func() { releaseStop.Do(func() { close(stopGate) }) }
	manager := &fakeCodeServerManager{stopGate: stopGate}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{
		DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager, MaxViews: 1,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		unlockStop()
		_ = service.Close(context.Background())
	}()
	manager.registerHook = manager.Crash
	result := service.HandleCommand(context.Background(), openCommandForTest(t.TempDir(), "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if result.GetOk() || result.GetCode() != "BUSY" {
		t.Fatalf("generation drift result=%v", result)
	}
	view := service.GetViewState("11111111-1111-4111-8111-111111111111")
	if view == nil || view.GetState() != protocol.IdeViewState_CLOSING || !service.HasActiveViews() {
		t.Fatalf("generation drift view=%v active=%v", view, service.HasActiveViews())
	}
	service.mu.Lock()
	recovery := service.crashRecovery
	service.mu.Unlock()
	unlockStop()
	if recovery == nil {
		t.Fatal("process exit did not start recovery")
	}
	select {
	case <-recovery:
	case <-time.After(time.Second):
		t.Fatal("process recovery did not finish")
	}
}

// IDE-T-073 verifies a generation change during resume keeps the prior binding CLOSING until recovery converges.
func TestResumeGenerationChangeDuringRegistration(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	stopGate := make(chan struct{})
	var releaseStop sync.Once
	unlockStop := func() { releaseStop.Do(func() { close(stopGate) }) }
	manager := &fakeCodeServerManager{stopGate: stopGate}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{
		DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		unlockStop()
		_ = service.Close(context.Background())
	}()
	root := t.TempDir()
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	manager.registerHook = manager.Crash
	resume := openCommandForTest(root, opened.GetViewId(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
	resume.Mode = protocol.IdeOpenMode_RESUME
	resume.ExpectedBindingEpoch = int64Pointer(opened.GetBindingEpoch())
	result := service.HandleCommand(context.Background(), resume)
	if result.GetOk() || result.GetCode() != "CLEANUP_FAILED" {
		t.Fatalf("generation drift resume=%v", result)
	}
	view := service.GetViewState(opened.GetViewId())
	if view == nil || view.GetState() != protocol.IdeViewState_CLOSING || view.GetBindingEpoch() != opened.GetBindingEpoch() {
		t.Fatalf("generation drift published a new binding: %v", view)
	}
	service.mu.Lock()
	recovery := service.crashRecovery
	service.mu.Unlock()
	unlockStop()
	if recovery == nil {
		t.Fatal("process exit did not start recovery")
	}
	select {
	case <-recovery:
	case <-time.After(time.Second):
		t.Fatal("process recovery did not finish")
	}
}

// IDE-T-075/076/077 verifies resume reconciles lost management results from the authoritative registry.
func TestResumeRegistrationReconciliation(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	manager.mu.Lock()
	manager.registerMode = "after-error"
	manager.mu.Unlock()
	newBinding := openCommandForTest(root, opened.GetViewId(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
	newBinding.Mode = protocol.IdeOpenMode_RESUME
	newBinding.ExpectedBindingEpoch = int64Pointer(opened.GetBindingEpoch())
	adopted := service.HandleCommand(context.Background(), newBinding)
	if !adopted.GetOk() || adopted.GetBindingEpoch() <= opened.GetBindingEpoch() {
		t.Fatalf("new registry epoch not adopted: %v", adopted)
	}
	manager.mu.Lock()
	manager.registerMode = "before-error"
	manager.listError = false
	manager.mu.Unlock()
	oldStillBound := openCommandForTest(root, opened.GetViewId(), "ffffffff-ffff-4fff-8fff-ffffffffffff")
	oldStillBound.Mode = protocol.IdeOpenMode_RESUME
	oldStillBound.ExpectedBindingEpoch = int64Pointer(adopted.GetBindingEpoch())
	if result := service.HandleCommand(context.Background(), oldStillBound); result.GetOk() || result.GetCode() != "CLEANUP_FAILED" || service.GetViewState(opened.GetViewId()).GetState() != protocol.IdeViewState_OPEN {
		t.Fatalf("old registry epoch was not retained: result=%v state=%v", result, service.GetViewState(opened.GetViewId()))
	}
	manager.mu.Lock()
	manager.listError = true
	manager.mu.Unlock()
	unproven := openCommandForTest(root, opened.GetViewId(), "99999999-9999-4999-8999-999999999998")
	unproven.Mode = protocol.IdeOpenMode_RESUME
	unproven.ExpectedBindingEpoch = int64Pointer(adopted.GetBindingEpoch())
	if result := service.HandleCommand(context.Background(), unproven); result.GetOk() || result.GetCode() != "CLEANUP_FAILED" || service.GetViewState(opened.GetViewId()).GetState() != protocol.IdeViewState_CLOSING {
		t.Fatalf("unverified resume result=%v state=%v", result, service.GetViewState(opened.GetViewId()))
	}
}

// IDE-T-079 verifies a blocked resume does not block another command's canonical view snapshot.
func TestSnapshotDuringBlockedResume(t *testing.T) {
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
	root := t.TempDir()
	first := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	second := service.HandleCommand(context.Background(), openCommandForTest(root, "22222222-2222-4222-8222-222222222222", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"))
	if !first.GetOk() || !second.GetOk() {
		t.Fatalf("opens first=%v second=%v", first, second)
	}
	before := service.snapshot().Revision
	registerEntered := make(chan struct{})
	registerRelease := make(chan struct{})
	var releaseRegistration sync.Once
	unlockRegistration := func() { releaseRegistration.Do(func() { close(registerRelease) }) }
	defer unlockRegistration()
	manager.registerHook = func() {
		close(registerEntered)
		<-registerRelease
	}
	resume := openCommandForTest(root, first.GetViewId(), "ffffffff-ffff-4fff-8fff-ffffffffffff")
	resume.Mode = protocol.IdeOpenMode_RESUME
	resume.ExpectedBindingEpoch = int64Pointer(first.GetBindingEpoch())
	resumeResult := make(chan *protocol.IdeResult, 1)
	go func() { resumeResult <- service.HandleCommand(context.Background(), resume) }()
	select {
	case <-registerEntered:
	case <-time.After(time.Second):
		t.Fatal("resume registration did not block")
	}
	snapshot := service.HandleCommand(context.Background(), &protocol.IdeCommand{
		Action: protocol.IdeAction_LIST_VIEWS, ExpectedDaemonInstanceId: stringPointer("99999999-9999-4999-8999-999999999999"),
	})
	if !snapshot.GetOk() || snapshot.GetRevision() != before+1 || len(snapshot.GetViews()) != 2 || snapshot.GetViews()[0].GetBindingEpoch() != first.GetBindingEpoch() || snapshot.GetViews()[0].GetState() != protocol.IdeViewState_OPEN {
		t.Fatalf("snapshot during resume=%v, revision before=%d", snapshot, before)
	}
	unlockRegistration()
	select {
	case result := <-resumeResult:
		if !result.GetOk() || result.GetRevision() <= before {
			t.Fatalf("resume result=%v revision before=%d", result, before)
		}
	case <-time.After(time.Second):
		t.Fatal("resume did not finish after registration release")
	}
}

// IDE-T-114 verifies failed native cleanup retains the CLOSING view and explicit retry removes it.
func TestRetryCloseAfterManagerCleanupFailure(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	manager := &fakeCodeServerManager{closeFailures: 1}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	_, cancelHost := startBridgeReplyHost(t, bridge, opened.GetWorkspacePath(), opened.GetViewId(), root)
	defer cancelHost()
	closeCommand := viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, opened, false)
	failed := service.HandleCommand(context.Background(), closeCommand)
	if failed.GetOk() || failed.GetCode() != "CLEANUP_FAILED" {
		t.Fatalf("close failure=%v", failed)
	}
	if state := service.GetViewState(opened.GetViewId()); state == nil || state.GetState() != protocol.IdeViewState_CLOSING {
		t.Fatalf("failed cleanup state=%v", state)
	}
	closeCommand.Discard = boolPointer(true)
	retried := service.HandleCommand(context.Background(), closeCommand)
	if !retried.GetOk() || service.GetViewState(opened.GetViewId()) != nil || manager.closeCalls != 2 {
		t.Fatalf("retry=%v state=%v closeCalls=%d", retried, service.GetViewState(opened.GetViewId()), manager.closeCalls)
	}
}

// IDE-T-116 verifies idle code-server stop is armed only after a complete last-view close.
func TestIdleStopBoundary(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	scheduler := newFakeIdeScheduler(time.Date(2025, 1, 1, 0, 0, 0, 0, time.UTC))
	events := make(chan IdeServiceEvent, 2)
	manager := &fakeCodeServerManager{stopFailures: 1}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager, Scheduler: scheduler, OnEvent: func(event IdeServiceEvent) { events <- event }})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	service.idleTimerFired()
	if manager.stopCalls != 0 || service.idleTimer != nil {
		t.Fatalf("idle stop was armed with an open view: stops=%d timer=%v", manager.stopCalls, service.idleTimer)
	}
	_, cancelHost := startBridgeReplyHost(t, bridge, opened.GetWorkspacePath(), opened.GetViewId(), root)
	defer cancelHost()
	closed := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, opened, false))
	if !closed.GetOk() {
		t.Fatalf("close reply=%v", closed)
	}
	scheduler.Advance(59 * time.Second)
	if manager.stopCalls != 0 {
		t.Fatalf("manager stopped at 59s: %d", manager.stopCalls)
	}
	scheduler.Advance(time.Second)
	if manager.stopCalls != 1 {
		t.Fatalf("manager stops at 60s = %d", manager.stopCalls)
	}
	select {
	case event := <-events:
		if event.Kind != "idle-stop-failed" {
			t.Fatalf("idle stop event=%+v", event)
		}
	default:
		t.Fatal("idle stop failure was not reported")
	}
	scheduler.Advance(idleStopDuration)
	if manager.stopCalls != 2 {
		t.Fatalf("idle stop did not retry: %d", manager.stopCalls)
	}
}

// IDE-T-134 verifies an active stream prevents idle stop until release and the idle timer is armed once.
func TestIdleStopWaitsForFinalStreamRelease(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	scheduler := newFakeIdeScheduler(time.Date(2025, 1, 1, 0, 0, 0, 0, time.UTC))
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{
		DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager, Scheduler: scheduler,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	_, cancelHost := startBridgeReplyHost(t, bridge, opened.GetWorkspacePath(), opened.GetViewId(), root)
	defer cancelHost()
	upstream, err := service.ResolveView(context.Background(), transport.ViewRequest{
		ViewID: opened.GetViewId(), BindingEpoch: opened.GetBindingEpoch(), Mode: "HTTP", Method: "GET", Path: "/",
	})
	if err != nil {
		t.Fatal(err)
	}
	closeResult := make(chan *protocol.IdeResult, 1)
	go func() {
		closeResult <- service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, opened, false))
	}()
	select {
	case <-upstream.Context.Done():
	case <-time.After(time.Second):
		upstream.Release()
		t.Fatal("view close did not invalidate its stream")
	}
	scheduler.Advance(2 * idleStopDuration)
	if manager.stopCalls != 0 || service.ActiveStreamCount() != 1 {
		upstream.Release()
		t.Fatalf("idle stopped before stream release: stops=%d streams=%d", manager.stopCalls, service.ActiveStreamCount())
	}
	upstream.Release()
	select {
	case result := <-closeResult:
		if !result.GetOk() {
			t.Fatalf("close result=%v", result)
		}
	case <-time.After(time.Second):
		t.Fatal("close did not finish after stream release")
	}
	service.mu.Lock()
	timerArmed := service.idleTimer != nil
	service.mu.Unlock()
	if !timerArmed {
		t.Fatal("idle timer was not armed after final stream release and close")
	}
	scheduler.Advance(idleStopDuration)
	if manager.stopCalls != 1 {
		t.Fatalf("idle stop calls=%d want=1", manager.stopCalls)
	}
}

// IDE-T-118 verifies update admission is blocked by views and streams, then stops the empty manager.
func TestUpdateAdmission(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() || service.CanStartUpdate() {
		t.Fatalf("open=%v update admitted=%v", opened, service.CanStartUpdate())
	}
	upstream, err := service.ResolveView(context.Background(), transport.ViewRequest{ViewID: opened.GetViewId(), BindingEpoch: opened.GetBindingEpoch(), Mode: "HTTP", Method: "GET", Path: "/"})
	if err != nil {
		t.Fatal(err)
	}
	upstream.Release()
	_, cancelHost := startBridgeReplyHost(t, bridge, opened.GetWorkspacePath(), opened.GetViewId(), root)
	defer cancelHost()
	closed := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, opened, false))
	if !closed.GetOk() || !service.CanStartUpdate() {
		t.Fatalf("close=%v update admitted=%v", closed, service.CanStartUpdate())
	}
	if err := service.StopForUpdate(context.Background()); err != nil || manager.stopCalls != 1 {
		t.Fatalf("stop for update err=%v stopCalls=%d", err, manager.stopCalls)
	}
}

// IDE-T-115 verifies process exit cancels old data streams and converges managed view state.
func TestProcessExitConvergence(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	upstream, err := service.ResolveView(context.Background(), transport.ViewRequest{ViewID: opened.GetViewId(), BindingEpoch: opened.GetBindingEpoch(), Mode: "HTTP", Method: "GET", Path: "/"})
	if err != nil {
		t.Fatal(err)
	}
	manager.Crash()
	select {
	case <-upstream.Context.Done():
	case <-time.After(time.Second):
		t.Fatal("process exit did not cancel the old view stream")
	}
	upstream.Release()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := service.awaitReady(ctx); err != nil {
		t.Fatalf("crash cleanup did not converge: %v", err)
	}
	if service.GetViewState(opened.GetViewId()) != nil || service.ActiveStreamCount() != 0 {
		t.Fatalf("view=%v streams=%d", service.GetViewState(opened.GetViewId()), service.ActiveStreamCount())
	}
}

// IDE-T-133 verifies an unproven crash retains capacity until a later convergent stop succeeds.
func TestUnprovenCrashCleanup(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	manager := &fakeCodeServerManager{stopFailures: 2}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager, MaxViews: 1})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	manager.Crash()
	service.mu.Lock()
	recovery := service.crashRecovery
	service.mu.Unlock()
	select {
	case <-recovery:
	case <-time.After(5 * time.Second):
		t.Fatal("crash convergence did not settle")
	}
	if state := service.GetViewState(opened.GetViewId()); state == nil || state.GetState() != protocol.IdeViewState_CLOSING {
		t.Fatalf("unproven crash state=%v", state)
	}
	replacement := openCommandForTest(root, "22222222-2222-4222-8222-222222222222", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
	blocked := service.HandleCommand(context.Background(), replacement)
	if blocked.GetOk() || blocked.GetCode() != "CLEANUP_FAILED" || service.GetViewState(opened.GetViewId()) == nil || service.GetViewState(replacement.GetViewId()) != nil {
		t.Fatalf("replacement during unproven cleanup=%v old=%v new=%v", blocked, service.GetViewState(opened.GetViewId()), service.GetViewState(replacement.GetViewId()))
	}
	result := service.HandleCommand(context.Background(), replacement)
	if !result.GetOk() || service.GetViewState(opened.GetViewId()) != nil || service.GetViewState(replacement.GetViewId()) == nil {
		t.Fatalf("replacement after convergence=%v old=%v", result, service.GetViewState(opened.GetViewId()))
	}
}

// IDE-T-135 verifies a view rebind cancels the transport-facing stream context and release stays idempotent.
func TestViewRebindCancelsStreamContext(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	upstream, err := service.ResolveView(context.Background(), transport.ViewRequest{ViewID: opened.GetViewId(), BindingEpoch: opened.GetBindingEpoch(), Mode: "HTTP", Method: "GET", Path: "/"})
	if err != nil {
		t.Fatal(err)
	}
	resume := openCommandForTest(root, opened.GetViewId(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
	resume.Mode = protocol.IdeOpenMode_RESUME
	resume.ExpectedBindingEpoch = int64Pointer(opened.GetBindingEpoch())
	resumed := service.HandleCommand(context.Background(), resume)
	if !resumed.GetOk() {
		t.Fatalf("resume reply=%v", resumed)
	}
	select {
	case <-upstream.Context.Done():
	case <-time.After(time.Second):
		t.Fatal("stream context was not cancelled by resume")
	}
	upstream.Release()
	if count := service.ActiveStreamCount(); count != 0 {
		t.Fatalf("active streams after release=%d", count)
	}
	plainUpstream, err := service.ResolveView(context.Background(), transport.ViewRequest{ViewID: resumed.GetViewId(), BindingEpoch: resumed.GetBindingEpoch(), Mode: "HTTP", Method: "GET", Path: "/"})
	if err != nil {
		t.Fatal(err)
	}
	plainUpstream.Release()
}

func startBridgeReplyHost(t *testing.T, bridge *Bridge, workspace, viewID, directory string) (*http.Response, context.CancelFunc) {
	return startBridgeReplyHostWith(t, bridge, workspace, viewID, directory, nil)
}

func startBridgeReplyHostWith(t *testing.T, bridge *Bridge, workspace, viewID, directory string, overrides map[string]map[string]any) (*http.Response, context.CancelFunc) {
	t.Helper()
	canonicalDirectory, err := CanonicalDirectory(directory)
	if err != nil {
		t.Fatal(err)
	}
	directory = canonicalDirectory
	ctx, cancel := context.WithCancel(context.Background())
	response, reader := openManualBridgeStream(t, ctx, bridge, workspace, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", directory)
	if _, err := reader.ReadString('\n'); err != nil {
		cancel()
		response.Body.Close()
		t.Fatal(err)
	}
	go func() {
		decoder := json.NewDecoder(reader)
		for {
			var command struct {
				ID string `json:"id"`
				Op string `json:"op"`
			}
			if err := decoder.Decode(&command); err != nil {
				return
			}
			result := map[string]any{"id": command.ID, "ok": true}
			if command.Op == "inspect" {
				result["clean"] = true
			}
			for key, value := range overrides[command.Op] {
				result[key] = value
			}
			body, err := json.Marshal(result)
			if err != nil {
				return
			}
			request, err := http.NewRequest(http.MethodPost, bridge.URL+"/bridge/result", strings.NewReader(string(body)))
			if err != nil {
				return
			}
			request.Header.Set("Authorization", "Bearer "+bridge.Secret)
			request.Header.Set("Content-Type", "application/json")
			posted, err := http.DefaultClient.Do(request)
			if err != nil {
				return
			}
			_ = posted.Body.Close()
		}
	}()
	return response, cancel
}

// IDE-T-098 verifies create and resume assign the confirmed directory and binding to the bridge.
func TestBridgeDirectoryBindingOnCreateAndResume(t *testing.T) {
	root, err := CanonicalDirectory(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{
		DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: &fakeCodeServerManager{},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("create reply=%v", opened)
	}
	assertBinding := func(result *protocol.IdeResult) {
		t.Helper()
		bridge.mu.Lock()
		binding := bridge.views[result.GetViewId()]
		valid := binding != nil && binding.workspacePath == result.GetWorkspacePath() && binding.epoch == result.GetBindingEpoch() && binding.directory == root
		bridge.mu.Unlock()
		if !valid {
			t.Fatalf("bridge binding for %s does not match create/resume result %v", result.GetViewId(), result)
		}
	}
	assertBinding(opened)
	resume := openCommandForTest(root, opened.GetViewId(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
	resume.Mode = protocol.IdeOpenMode_RESUME
	resume.ExpectedBindingEpoch = int64Pointer(opened.GetBindingEpoch())
	resumed := service.HandleCommand(context.Background(), resume)
	if !resumed.GetOk() || resumed.GetBindingEpoch() <= opened.GetBindingEpoch() {
		t.Fatalf("resume reply=%v", resumed)
	}
	assertBinding(resumed)
}

// IDE-T-101 verifies canonical directory change is acknowledged only by a replacement host.
func TestChangeDirectoryReply(t *testing.T) {
	root, err := CanonicalDirectory(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	next := filepath.Join(root, "next")
	if err := os.Mkdir(next, 0o700); err != nil {
		t.Fatal(err)
	}
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: &fakeCodeServerManager{}})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	oldStream, oldReader := openManualBridgeStream(t, ctx, bridge, opened.GetWorkspacePath(), "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", root)
	defer oldStream.Body.Close()
	if _, err := oldReader.ReadString('\n'); err != nil {
		t.Fatal(err)
	}
	command := &protocol.IdeCommand{Action: protocol.IdeAction_CHANGE_DIRECTORY, ViewId: stringPointer(opened.GetViewId()), BrowserSessionId: stringPointer(opened.GetBrowserSessionId()), BindingEpoch: int64Pointer(opened.GetBindingEpoch()), Directory: stringPointer(next), ExpectedDaemonInstanceId: stringPointer("99999999-9999-4999-8999-999999999999")}
	resultChannel := make(chan *protocol.IdeResult, 1)
	go func() { resultChannel <- service.HandleCommand(context.Background(), command) }()
	var nativeCommand struct {
		ID        string `json:"id"`
		Op        string `json:"op"`
		Directory string `json:"directory"`
	}
	if err := json.NewDecoder(oldReader).Decode(&nativeCommand); err != nil {
		t.Fatal(err)
	}
	if nativeCommand.Op != "changeDirectory" || nativeCommand.Directory != next {
		t.Fatalf("native directory command=%+v", nativeCommand)
	}
	newStream, newReader := openManualBridgeStream(t, ctx, bridge, opened.GetWorkspacePath(), "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", next)
	defer newStream.Body.Close()
	if _, err := newReader.ReadString('\n'); err != nil {
		t.Fatal(err)
	}
	select {
	case result := <-resultChannel:
		if !result.GetOk() || result.GetDirectory() != next {
			t.Fatalf("directory reply=%v", result)
		}
	case <-time.After(time.Second):
		t.Fatal("replacement host did not confirm directory")
	}
	bridge.mu.Lock()
	binding := bridge.views[opened.GetViewId()]
	confirmedByTarget := binding != nil && binding.directory == next && binding.hostID == "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" && binding.stream != nil
	bridge.mu.Unlock()
	if !confirmedByTarget {
		t.Fatal("directory change reassigned the bridge instead of retaining the confirming replacement host")
	}
	workspaceBytes, err := os.ReadFile(opened.GetWorkspacePath())
	var workspace struct {
		Folders []struct {
			Path string `json:"path"`
		} `json:"folders"`
	}
	if err == nil {
		err = json.Unmarshal(workspaceBytes, &workspace)
	}
	if err != nil || len(workspace.Folders) != 1 || workspace.Folders[0].Path != next {
		t.Fatalf("workspace=%q err=%v", workspaceBytes, err)
	}
}

// IDE-T-102 verifies missing tracker and cancelled connection waits fail without creating bridge waiters.
func TestUnconnectedCommandsReturnBusyAndUnavailable(t *testing.T) {
	for _, test := range []struct {
		name         string
		tracker      native.DescendantTracker
		wantFocus    string
		wantCollapse string
	}{{name: "missing tracker", wantFocus: "UNAVAILABLE", wantCollapse: "BUSY"}, {name: "cancelled wait", tracker: &fakeDescendantTracker{}, wantFocus: "BUSY", wantCollapse: "BUSY"}} {
		t.Run(test.name, func(t *testing.T) {
			root := t.TempDir()
			bridge, err := NewBridge()
			if err != nil {
				t.Fatal(err)
			}
			defer bridge.Close(context.Background())
			service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, test.tracker, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: &fakeCodeServerManager{}})
			if err != nil {
				t.Fatal(err)
			}
			defer service.Close(context.Background())
			opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
			if !opened.GetOk() {
				t.Fatalf("open reply=%v", opened)
			}
			ctx, cancel := context.WithCancel(context.Background())
			cancel()
			for _, command := range []struct {
				action protocol.IdeAction
				want   string
			}{{protocol.IdeAction_FOCUS_TERMINAL, test.wantFocus}, {protocol.IdeAction_COLLAPSE_MOBILE, test.wantCollapse}} {
				result := service.HandleCommand(ctx, viewCommandForTest(command.action, opened, false))
				if result.GetCode() != command.want {
					t.Fatalf("action %s error=%v want %q", command.action, result, command.want)
				}
			}
			bridge.mu.Lock()
			waiters := len(bridge.waiters)
			bridge.mu.Unlock()
			if waiters != 0 {
				t.Fatalf("abandoned bridge waiters=%d", waiters)
			}
		})
	}
}

// IDE-T-101/102 verifies bridge rejections and invalid directories never publish successful state.
func TestBoundCommandRejections(t *testing.T) {
	root := t.TempDir()
	next := filepath.Join(root, "next")
	if err := os.Mkdir(next, 0o700); err != nil {
		t.Fatal(err)
	}
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, &fakeDescendantTracker{}, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: &fakeCodeServerManager{}})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	_, cancelHost := startBridgeReplyHostWith(t, bridge, opened.GetWorkspacePath(), opened.GetViewId(), root, map[string]map[string]any{
		"focusTerminal":   {"ok": false, "code": "FOCUS_FAILED", "message": "terminal rejected"},
		"collapseMobile":  {"ok": false, "code": "LAYOUT_FAILED"},
		"changeDirectory": {"ok": false, "code": "DIRTY", "message": "directory rejected"},
	})
	defer cancelHost()
	if result := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_FOCUS_TERMINAL, opened, false)); result.GetCode() != "FOCUS_FAILED" {
		t.Fatalf("focus rejection=%v", result)
	}
	if result := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_COLLAPSE_MOBILE, opened, false)); result.GetCode() != "LAYOUT_FAILED" {
		t.Fatalf("collapse rejection=%v", result)
	}
	command := viewCommandForTest(protocol.IdeAction_CHANGE_DIRECTORY, opened, false)
	command.Directory = stringPointer(next)
	if result := service.HandleCommand(context.Background(), command); result.GetCode() != "DIRTY" {
		t.Fatalf("directory rejection=%v", result)
	}
	command.Directory = stringPointer(filepath.Join(root, "missing"))
	if result := service.HandleCommand(context.Background(), command); result.GetCode() != "UNAVAILABLE" {
		t.Fatalf("invalid directory=%v", result)
	}
	if state := service.GetViewState(opened.GetViewId()); state.GetDirectory() != opened.GetDirectory() {
		t.Fatalf("rejected directory changed state: %v", state)
	}
}

// IDE-T-102 verifies focus and mobile-collapse acknowledgements are driven by bridge results.
func TestBoundViewCommands(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, &fakeDescendantTracker{}, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	_, cancelHost := startBridgeReplyHost(t, bridge, opened.GetWorkspacePath(), opened.GetViewId(), root)
	defer cancelHost()
	focused := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_FOCUS_TERMINAL, opened, false))
	collapsed := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_COLLAPSE_MOBILE, opened, false))
	if !focused.GetOk() || focused.GetViewId() != opened.GetViewId() || !collapsed.GetOk() || collapsed.GetViewId() != opened.GetViewId() {
		t.Fatalf("focus=%v collapse=%v", focused, collapsed)
	}
	upstream, err := service.ResolveView(context.Background(), transport.ViewRequest{ViewID: opened.GetViewId(), BindingEpoch: opened.GetBindingEpoch(), Mode: "HTTP", Method: "GET", Path: "/"})
	if err != nil {
		t.Fatal(err)
	}
	closeResult := make(chan *protocol.IdeResult, 1)
	go func() {
		closeResult <- service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, opened, false))
	}()
	select {
	case <-upstream.Context.Done():
	case <-time.After(time.Second):
		upstream.Release()
		t.Fatal("close did not cancel the active stream context")
	}
	upstream.Release()
	select {
	case closed := <-closeResult:
		if !closed.GetOk() {
			t.Fatalf("close reply=%v", closed)
		}
	case <-time.After(time.Second):
		t.Fatal("close did not finish after stream release")
	}
	if service.ActiveStreamCount() != 0 {
		t.Fatalf("streams after close=%d", service.ActiveStreamCount())
	}
}

// IDE-T-125 verifies an unknown native close outcome preserves the OPEN binding and active stream.
func TestUnknownNativeClosePreservesStream(t *testing.T) {
	root := t.TempDir()
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
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	_, cancelHost := startBridgeReplyHostWith(t, bridge, opened.GetWorkspacePath(), opened.GetViewId(), root, map[string]map[string]any{
		"closeView": {"ok": false, "message": "close outcome unknown"},
	})
	defer cancelHost()
	upstream, err := service.ResolveView(context.Background(), transport.ViewRequest{
		ViewID: opened.GetViewId(), BindingEpoch: opened.GetBindingEpoch(), Mode: "HTTP", Method: "GET", Path: "/",
	})
	if err != nil {
		t.Fatal(err)
	}
	defer upstream.Release()
	result := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, opened, false))
	if result.GetOk() || result.GetCode() != "UNAVAILABLE" || upstream.Context.Err() != nil || service.GetViewState(opened.GetViewId()).GetState() != protocol.IdeViewState_OPEN {
		t.Fatalf("unknown close=%v stream=%v state=%v", result, upstream.Context.Err(), service.GetViewState(opened.GetViewId()))
	}
}

// IDE-T-127 verifies late native success cannot close a binding after caller abort or bridge disconnect.
func TestLateNativeCloseSuccessIsIgnored(t *testing.T) {
	for _, scenario := range []string{"caller abort", "bridge disconnect"} {
		t.Run(scenario, func(t *testing.T) {
			root, err := CanonicalDirectory(t.TempDir())
			if err != nil {
				t.Fatal(err)
			}
			bridge, err := NewBridge()
			if err != nil {
				t.Fatal(err)
			}
			defer bridge.Close(context.Background())
			service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{
				DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: &fakeCodeServerManager{},
			})
			if err != nil {
				t.Fatal(err)
			}
			defer service.Close(context.Background())
			opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
			if !opened.GetOk() {
				t.Fatalf("open reply=%v", opened)
			}
			streamCtx, stopHost := context.WithCancel(context.Background())
			defer stopHost()
			const hostID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
			response, reader := openManualBridgeStream(t, streamCtx, bridge, opened.GetWorkspacePath(), hostID, root)
			defer response.Body.Close()
			if _, err := reader.ReadString('\n'); err != nil {
				t.Fatal(err)
			}
			upstream, err := service.ResolveView(context.Background(), transport.ViewRequest{
				ViewID: opened.GetViewId(), BindingEpoch: opened.GetBindingEpoch(), Mode: "HTTP", Method: "GET", Path: "/",
			})
			if err != nil {
				t.Fatal(err)
			}
			defer upstream.Release()
			closeCtx, cancelClose := context.WithCancel(context.Background())
			defer cancelClose()
			closeResult := make(chan *protocol.IdeResult, 1)
			go func() {
				closeResult <- service.HandleCommand(closeCtx, viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, opened, false))
			}()
			line, err := reader.ReadString('\n')
			if err != nil {
				cancelClose()
				t.Fatal(err)
			}
			var command struct {
				ID string `json:"id"`
			}
			if err := json.Unmarshal([]byte(line), &command); err != nil || command.ID == "" {
				cancelClose()
				t.Fatalf("close command=%q err=%v", line, err)
			}
			if scenario == "caller abort" {
				cancelClose()
			} else {
				stopHost()
			}
			select {
			case result := <-closeResult:
				if result.GetOk() || result.GetCode() != "UNAVAILABLE" {
					t.Fatalf("interrupted close=%v", result)
				}
			case <-time.After(time.Second):
				t.Fatal("interrupted close remained pending")
			}
			lateSuccess, err := json.Marshal(map[string]any{"id": command.ID, "ok": true, "clean": true})
			if err != nil {
				t.Fatal(err)
			}
			if status := postBridge(t, bridge, "/bridge/result", lateSuccess); status != http.StatusNoContent {
				t.Fatalf("late result status=%d", status)
			}
			if state := service.GetViewState(opened.GetViewId()); state == nil || state.GetState() != protocol.IdeViewState_OPEN || upstream.Context.Err() != nil {
				t.Fatalf("late success mutated view=%v stream=%v", state, upstream.Context.Err())
			}
		})
	}
}

// IDE-T-136 verifies shutdown reports child close failures without losing their error identity.
func TestShutdownAggregatesComponentCloseFailure(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	cause := errors.New("manager close failed")
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{
		DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: &fakeCodeServerManager{closeErr: cause},
	})
	if err != nil {
		t.Fatal(err)
	}
	closeErr := service.Close(context.Background())
	if closeErr == nil || !errors.Is(closeErr, cause) || !strings.Contains(closeErr.Error(), "ide service shutdown failed") {
		t.Fatalf("shutdown error=%v", closeErr)
	}
}

// IDE-T-123 verifies native cancellation preserves the binding and disallows force close.
func TestCancelledNativeClosePreservesView(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	_, cancelHost := startBridgeReplyHostWith(t, bridge, opened.GetWorkspacePath(), opened.GetViewId(), root, map[string]map[string]any{"closeView": {"ok": false, "code": "CANCELLED", "message": "save cancelled"}})
	defer cancelHost()
	upstream, err := service.ResolveView(context.Background(), transport.ViewRequest{ViewID: opened.GetViewId(), BindingEpoch: opened.GetBindingEpoch(), Mode: "HTTP", Method: "GET", Path: "/"})
	if err != nil {
		t.Fatal(err)
	}
	closed := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, opened, false))
	if closed.GetOk() || closed.GetCode() != "CANCELLED" || !bridge.IsConnected(opened.GetViewId(), opened.GetBindingEpoch()) || service.GetViewState(opened.GetViewId()).GetState() != protocol.IdeViewState_OPEN || manager.closeCalls != 0 || upstream.Context.Err() != nil {
		upstream.Release()
		t.Fatalf("cancel result=%v connected=%v state=%v native closes=%d stream=%v", closed, bridge.IsConnected(opened.GetViewId(), opened.GetBindingEpoch()), service.GetViewState(opened.GetViewId()), manager.closeCalls, upstream.Context.Err())
	}
	upstream.Release()
	force := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, opened, true))
	if force.GetOk() || force.GetCode() != "BUSY" {
		t.Fatalf("unauthorized force close=%v", force)
	}
}

// IDE-T-089 verifies snapshot rejection, incomplete data, and quota exhaustion warn without blocking force-close.
func TestForcedCloseSnapshotOutcomes(t *testing.T) {
	cases := []struct {
		name     string
		quota    int64
		snapshot map[string]any
		event    string
	}{
		{name: "rejected", quota: 4096, snapshot: map[string]any{"ok": false, "code": "DIRTY", "message": "snapshot rejected"}, event: "backup-failed"},
		{name: "incomplete", quota: 4096, snapshot: map[string]any{"complete": false}, event: "backup-incomplete"},
		{name: "quota exhausted", quota: 1, snapshot: map[string]any{"documents": []map[string]string{{"path": "/workspace/file.txt", "text": "unsaved"}}}, event: "backup-failed"},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			root := t.TempDir()
			bridge, err := NewBridge()
			if err != nil {
				t.Fatal(err)
			}
			defer bridge.Close(context.Background())
			events := make(chan IdeServiceEvent, 2)
			manager := &fakeCodeServerManager{}
			service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager, RecoveryQuotaBytes: test.quota, OnEvent: func(event IdeServiceEvent) { events <- event }})
			if err != nil {
				t.Fatal(err)
			}
			defer service.Close(context.Background())
			opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
			if !opened.GetOk() {
				t.Fatalf("open reply=%v", opened)
			}
			_, cancelHost := startBridgeReplyHostWith(t, bridge, opened.GetWorkspacePath(), opened.GetViewId(), root, map[string]map[string]any{"snapshot": test.snapshot})
			defer cancelHost()
			service.mu.Lock()
			service.views[opened.GetViewId()].forceAllowed = true
			service.mu.Unlock()
			closed := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, opened, true))
			if !closed.GetOk() || service.GetViewState(opened.GetViewId()) != nil {
				t.Fatalf("snapshot outcome blocked close: %v", closed)
			}
			select {
			case event := <-events:
				if event.Kind != test.event {
					t.Fatalf("event=%+v want %q", event, test.event)
				}
			default:
				t.Fatal("snapshot warning event missing")
			}
		})
	}
}

// IDE-T-085 verifies an unreachable view remains open until explicit force-close is authorized.
func TestForceCloseUnreachableView(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	events := make(chan IdeServiceEvent, 2)
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager, OnEvent: func(event IdeServiceEvent) { events <- event }})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	normal := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, opened, false))
	if normal.GetOk() || service.GetViewState(opened.GetViewId()).GetState() != protocol.IdeViewState_OPEN {
		t.Fatalf("unreachable normal close=%v state=%v", normal, service.GetViewState(opened.GetViewId()))
	}
	force := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, opened, true))
	if !force.GetOk() || service.GetViewState(opened.GetViewId()) != nil || manager.closeCalls != 1 {
		t.Fatalf("force close=%v view=%v closes=%d", force, service.GetViewState(opened.GetViewId()), manager.closeCalls)
	}
	select {
	case event := <-events:
		if event.Kind != "backup-failed" {
			t.Fatalf("event=%+v", event)
		}
	default:
		t.Fatal("unreachable force-close did not report backup-failed")
	}
}

// IDE-T-132 verifies force-close authorization does not survive a resumed binding epoch.
func TestForceAuthorizationDoesNotCrossEpoch(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{
		DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: &fakeCodeServerManager{},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	unknown := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, opened, false))
	if unknown.GetOk() || service.GetViewState(opened.GetViewId()).GetState() != protocol.IdeViewState_OPEN {
		t.Fatalf("disconnected close=%v state=%v", unknown, service.GetViewState(opened.GetViewId()))
	}
	resume := openCommandForTest(root, opened.GetViewId(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
	resume.Mode = protocol.IdeOpenMode_RESUME
	resume.ExpectedBindingEpoch = int64Pointer(opened.GetBindingEpoch())
	resumed := service.HandleCommand(context.Background(), resume)
	if !resumed.GetOk() || resumed.GetBindingEpoch() <= opened.GetBindingEpoch() {
		t.Fatalf("resume=%v", resumed)
	}
	_, cancelHost := startBridgeReplyHost(t, bridge, resumed.GetWorkspacePath(), resumed.GetViewId(), root)
	defer cancelHost()
	force := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, resumed, true))
	if force.GetOk() || force.GetCode() != "BUSY" || service.GetViewState(resumed.GetViewId()).GetState() != protocol.IdeViewState_OPEN {
		t.Fatalf("new epoch inherited force authorization: result=%v state=%v", force, service.GetViewState(resumed.GetViewId()))
	}
}

// IDE-T-130 verifies an authorized force-close snapshot can be restored.
func TestForceCloseRecoveryRoundTrip(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	cfg := config.DaemonConfig{DataDir: t.TempDir()}
	manager := &fakeCodeServerManager{}
	service, err := NewService(cfg, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	file := filepath.Join(root, "draft.txt")
	_, err = WriteRecoveryBackup(cfg, RecoveryBackup{ViewID: opened.GetViewId(), BindingEpoch: strconv.FormatInt(opened.GetBindingEpoch(), 10), Directory: root, CreatedAt: "2025-01-01T00:00:00Z", Complete: true, Documents: []RecoveryDocument{{Path: file, Text: "old"}}}, 4096)
	if err != nil {
		t.Fatal(err)
	}
	resume := openCommandForTest(root, "11111111-1111-4111-8111-111111111111", opened.GetViewId())
	resume.Mode = protocol.IdeOpenMode_RESUME
	resume.ExpectedBindingEpoch = int64Pointer(opened.GetBindingEpoch())
	resumed := service.HandleCommand(context.Background(), resume)
	if !resumed.GetOk() || resumed.GetBindingEpoch() == opened.GetBindingEpoch() {
		t.Fatalf("resume reply=%v", resumed)
	}
	_, cancelHost := startBridgeReplyHostWith(t, bridge, resumed.GetWorkspacePath(), resumed.GetViewId(), root, map[string]map[string]any{
		"restore":  {"restored": float64(1), "skipped": float64(0)},
		"snapshot": {"documents": []map[string]string{{"path": file, "text": "unsaved"}}, "complete": true},
	})
	defer cancelHost()
	restored, skipped, err := service.RestoreBackup(context.Background(), resumed.GetViewId())
	if err != nil || restored != 1 || skipped != 0 {
		t.Fatalf("restore=(%d,%d), err=%v", restored, skipped, err)
	}
	service.mu.Lock()
	service.views[resumed.GetViewId()].forceAllowed = true
	service.mu.Unlock()
	closed := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, resumed, true))
	if !closed.GetOk() {
		t.Fatalf("force close=%v", closed)
	}
	backup, err := ReadRecoveryBackup(cfg, opened.GetViewId(), defaultRecoveryQuota)
	if err != nil || backup == nil || len(backup.Documents) != 1 || backup.Documents[0].Text != "unsaved" {
		t.Fatalf("backup=%#v err=%v", backup, err)
	}
}

type fakeDescendantTracker struct {
	calls   int
	rootPID int
	pid     int
}

func (tracker *fakeDescendantTracker) TrackDescendant(_ context.Context, rootPID int, pid int) error {
	tracker.calls++
	tracker.rootPID = rootPID
	tracker.pid = pid
	return nil
}

// IDE-T-093 verifies restore rejects disconnected, rejected, corrupt, and closed-view cases without losing the backup.
func TestRestoreBackupFailureBoundaries(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	cfg := config.DaemonConfig{DataDir: t.TempDir()}
	service, err := NewService(cfg, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: &fakeCodeServerManager{}})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	backup := RecoveryBackup{ViewID: opened.GetViewId(), BindingEpoch: strconv.FormatInt(opened.GetBindingEpoch(), 10), Directory: root, CreatedAt: "2025-01-01T00:00:00Z", Documents: []RecoveryDocument{{Path: filepath.Join(root, "draft.txt"), Text: "draft"}}}
	if _, err := WriteRecoveryBackup(cfg, backup, 4096); err != nil {
		t.Fatal(err)
	}
	assertBackupPreserved := func() {
		t.Helper()
		stored, err := ReadRecoveryBackup(cfg, opened.GetViewId(), defaultRecoveryQuota)
		if err != nil || stored == nil || len(stored.Documents) != 1 || stored.Documents[0].Text != "draft" {
			t.Fatalf("restore failure lost backup: backup=%#v err=%v", stored, err)
		}
	}
	if _, _, err := service.RestoreBackup(context.Background(), opened.GetViewId()); FailureCode(err) != "UNAVAILABLE" {
		t.Fatalf("disconnected restore=%v", err)
	}
	assertBackupPreserved()
	_, cancelHost := startBridgeReplyHostWith(t, bridge, opened.GetWorkspacePath(), opened.GetViewId(), root, map[string]map[string]any{"restore": {"ok": false, "code": "DIRTY", "message": "restore rejected"}})
	if _, _, err := service.RestoreBackup(context.Background(), opened.GetViewId()); FailureCode(err) != "DIRTY" {
		cancelHost()
		t.Fatalf("rejected restore=%v", err)
	}
	assertBackupPreserved()
	cancelHost()
	path := filepath.Join(recoveryDirectory(cfg), opened.GetViewId()+".json")
	if err := os.WriteFile(path, []byte("{"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, _, err := service.RestoreBackup(context.Background(), opened.GetViewId()); FailureCode(err) != "UNAVAILABLE" || SafeMessage(err) != "ide request failed" {
		t.Fatalf("corrupt restore=%v", err)
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("corrupt backup was removed: %v", err)
	}
	if _, err := WriteRecoveryBackup(cfg, backup, 4096); err != nil {
		t.Fatal(err)
	}
	service.mu.Lock()
	delete(service.views, opened.GetViewId())
	service.mu.Unlock()
	if _, _, err := service.RestoreBackup(context.Background(), opened.GetViewId()); FailureCode(err) != "VIEW_GONE" {
		t.Fatalf("closed-view restore=%v", err)
	}
	assertBackupPreserved()
}

// IDE-T-096 verifies the omitted directory defaults to host HOME and missing HOME is reported safely.
func TestDefaultDirectoryListingUsesHostHome(t *testing.T) {
	root := t.TempDir()
	canonicalRoot, err := CanonicalDirectory(root)
	if err != nil {
		t.Fatal(err)
	}
	child := filepath.Join(canonicalRoot, "projects")
	if err := os.Mkdir(child, 0o700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("HOME", root)
	t.Setenv("USERPROFILE", "")
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: &fakeCodeServerManager{}})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	listed := service.HandleCommand(context.Background(), &protocol.IdeCommand{Action: protocol.IdeAction_LIST_DIRECTORIES})
	if !listed.GetOk() || listed.GetDirectoryListing().GetPath() != canonicalRoot || len(listed.GetDirectoryListing().GetChildren()) != 1 {
		t.Fatalf("home listing=%v", listed)
	}
	t.Setenv("HOME", "")
	missing := service.HandleCommand(context.Background(), &protocol.IdeCommand{Action: protocol.IdeAction_LIST_DIRECTORIES})
	if missing.GetOk() || missing.GetCode() != "UNAVAILABLE" {
		t.Fatalf("missing-home listing=%v", missing)
	}
}

// IDE-T-096 verifies directory listing is routed through the IDE command service.
func TestDirectoryListingCommand(t *testing.T) {
	root := t.TempDir()
	canonicalRoot, err := CanonicalDirectory(root)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(canonicalRoot, "workspace"), 0o700); err != nil {
		t.Fatal(err)
	}
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{
		DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: &fakeCodeServerManager{},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	result := service.HandleCommand(context.Background(), &protocol.IdeCommand{
		Action: protocol.IdeAction_LIST_DIRECTORIES, Directory: stringPointer(canonicalRoot),
	})
	listing := result.GetDirectoryListing()
	if !result.GetOk() || listing == nil || listing.GetPath() != canonicalRoot || len(listing.GetChildren()) != 1 || listing.GetChildren()[0].GetName() != "workspace" {
		t.Fatalf("directory reply = %v", result)
	}
}

// IDE-T-117 verifies concurrent create reservations enforce the configured 64-view limit before process work.
func TestConcurrentCreateReservationsHonorViewLimit(t *testing.T) {
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	const capacity = 64
	gate := make(chan struct{})
	var gateOnce sync.Once
	releaseGate := func() { gateOnce.Do(func() { close(gate) }) }
	allStarted := make(chan struct{})
	var started atomic.Int32
	manager := &fakeCodeServerManager{ensureHook: func() {
		if started.Add(1) == capacity {
			close(allStarted)
		}
		<-gate
	}}
	dataDir := filepath.Join(t.TempDir(), "daemon")
	if err := native.EnsurePrivateDirectory(dataDir); err != nil {
		t.Fatal(err)
	}
	service, err := NewService(config.DaemonConfig{DataDir: dataDir}, nil, nil, ServiceOptions{
		DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager, MaxViews: capacity,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	defer releaseGate()
	for _, directory := range []string{service.paths.IDEDir, service.paths.ViewsDir} {
		if err := native.EnsurePrivateDirectory(directory); err != nil {
			t.Fatal(err)
		}
	}
	root := t.TempDir()
	results := make(chan *protocol.IdeResult, capacity)
	for index := 0; index < capacity; index++ {
		viewID := fmt.Sprintf("%08x-0000-4000-8000-%012x", index+1, index+1)
		claimID := fmt.Sprintf("%08x-0000-4000-8000-%012x", index+capacity+1, index+capacity+1)
		command := openCommandForTest(root, viewID, claimID)
		go func() { results <- service.HandleCommand(context.Background(), command) }()
	}
	select {
	case <-allStarted:
	case <-time.After(15 * time.Second):
		releaseGate()
		t.Fatal("all view reservations did not reach process startup")
	}
	service.mu.Lock()
	reservations := len(service.reservations)
	service.mu.Unlock()
	if reservations != capacity {
		t.Fatalf("reservations=%d want=%d", reservations, capacity)
	}
	overflow := openCommandForTest(root, "ffffffff-ffff-4fff-8fff-ffffffffffff", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
	if result := service.HandleCommand(context.Background(), overflow); result.GetOk() || result.GetCode() != "IDE_CAPACITY_EXCEEDED" {
		t.Fatalf("capacity overflow result=%v", result)
	}
	releaseGate()
	for index := 0; index < capacity; index++ {
		if result := <-results; !result.GetOk() {
			t.Fatalf("reservation %d failed: %v", index, result)
		}
	}
}

// fakeCodeServerManager supplies a bounded code-server contract without launching a real process.
type fakeCodeServerManager struct {
	mu            sync.Mutex
	startCalls    int
	registerCalls int
	closeCalls    int
	closeFailures int
	stopCalls     int
	stopFailures  int
	stopGate      chan struct{}
	ensureHook    func()
	registerHook  func()
	closeErr      error
	generation    uint64
	registerMode  string
	startFailure  bool
	listError     bool
	views         map[string]ManagedViewInspection
	listeners     []func(uint64)
	session       *CodeServerSession
}

func (m *fakeCodeServerManager) EnsureStarted(context.Context) (*CodeServerSession, error) {
	if m.ensureHook != nil {
		m.ensureHook()
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.startCalls++
	if m.startFailure {
		return nil, NewError("code-server did not become ready", "UNAVAILABLE")
	}
	if m.session == nil {
		m.generation++
		m.session = &CodeServerSession{Host: "127.0.0.1", Port: 12345, Cookie: "code-server-session=fake", PID: 42, Generation: m.generation, ManagementToken: "management"}
	}
	return cloneSession(m.session), nil
}
func (m *fakeCodeServerManager) Session() *CodeServerSession {
	m.mu.Lock()
	defer m.mu.Unlock()
	return cloneSession(m.session)
}
func (m *fakeCodeServerManager) Diagnostics() string { return "" }
func (m *fakeCodeServerManager) Stop(ctx context.Context) error {
	m.mu.Lock()
	m.stopCalls++
	gate := m.stopGate
	m.mu.Unlock()
	if gate != nil {
		select {
		case <-gate:
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.stopFailures > 0 {
		m.stopFailures--
		return NewError("process scope did not converge", "CLEANUP_FAILED")
	}
	m.session = nil
	return nil
}
func (m *fakeCodeServerManager) Close(context.Context) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.closeErr
}
func (m *fakeCodeServerManager) RegisterView(_ context.Context, viewID string, epoch int64, workspace string) (*ManagedViewInspection, error) {
	m.mu.Lock()
	m.registerCalls++
	mode := m.registerMode
	hook := m.registerHook
	if mode == "before-error" {
		m.mu.Unlock()
		return nil, NewError("managed view write failed", "CLEANUP_FAILED")
	}
	if m.views == nil {
		m.views = make(map[string]ManagedViewInspection)
	}
	view := ManagedViewInspection{ViewID: viewID, BindingEpoch: strconv.FormatInt(epoch, 10), WorkspacePath: workspace, State: ManagedViewOpen}
	if mode == "drift" {
		view.BindingEpoch = "999"
	}
	if mode == "closed" {
		view.State = ManagedViewClosing
	}
	m.views[viewID] = view
	m.mu.Unlock()
	if hook != nil {
		hook()
	}
	if mode == "after-error" {
		return nil, NewError("managed view response was lost", "CLEANUP_FAILED")
	}
	return &view, nil
}
func (m *fakeCodeServerManager) InspectView(_ context.Context, viewID string, _ int64) (*ManagedViewInspection, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	view, ok := m.views[viewID]
	if !ok {
		return nil, NewError("managed view rejected (VIEW_NOT_FOUND)", "VIEW_GONE")
	}
	return &view, nil
}
func (m *fakeCodeServerManager) CloseView(_ context.Context, viewID string, epoch int64, _ ManagedViewCloseMode) (*ManagedViewCloseResult, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.closeCalls++
	view, ok := m.views[viewID]
	if !ok {
		return nil, NewError("managed view rejected (VIEW_NOT_FOUND)", "VIEW_GONE")
	}
	if m.closeFailures > 0 {
		m.closeFailures--
		return nil, NewError("managed view resources did not report exit", "CLEANUP_FAILED")
	}
	delete(m.views, viewID)
	result := &ManagedViewCloseResult{ManagedViewInspection: view}
	result.BindingEpoch = strconv.FormatInt(epoch, 10)
	result.State = ManagedViewClosed
	result.Cleanup.Complete = true
	return result, nil
}
func (m *fakeCodeServerManager) ListViews(context.Context) ([]ManagedViewInspection, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.listError {
		return nil, NewError("managed view list failed", "UNAVAILABLE")
	}
	result := make([]ManagedViewInspection, 0, len(m.views))
	for _, view := range m.views {
		result = append(result, view)
	}
	return result, nil
}
func (m *fakeCodeServerManager) OnUnexpectedExit(listener func(uint64)) {
	m.mu.Lock()
	m.listeners = append(m.listeners, listener)
	m.mu.Unlock()
}
func (m *fakeCodeServerManager) Crash() {
	m.mu.Lock()
	generation := m.generation
	m.session = nil
	listeners := append([]func(uint64){}, m.listeners...)
	m.mu.Unlock()
	for _, listener := range listeners {
		listener(generation)
	}
}

type fakeIdeScheduler struct {
	mu     sync.Mutex
	now    time.Time
	timers []*fakeIdeTimer
}

type fakeIdeTimer struct {
	scheduler *fakeIdeScheduler
	deadline  time.Time
	callback  func()
	stopped   bool
}

func newFakeIdeScheduler(now time.Time) *fakeIdeScheduler { return &fakeIdeScheduler{now: now} }
func (s *fakeIdeScheduler) Now() time.Time                { s.mu.Lock(); defer s.mu.Unlock(); return s.now }
func (s *fakeIdeScheduler) AfterFunc(delay time.Duration, callback func()) IdeTimer {
	s.mu.Lock()
	defer s.mu.Unlock()
	timer := &fakeIdeTimer{scheduler: s, deadline: s.now.Add(delay), callback: callback}
	s.timers = append(s.timers, timer)
	return timer
}
func (timer *fakeIdeTimer) Stop() bool {
	timer.scheduler.mu.Lock()
	defer timer.scheduler.mu.Unlock()
	wasActive := !timer.stopped
	timer.stopped = true
	return wasActive
}
func (s *fakeIdeScheduler) Advance(delta time.Duration) {
	s.mu.Lock()
	s.now = s.now.Add(delta)
	s.mu.Unlock()
	for {
		s.mu.Lock()
		var next *fakeIdeTimer
		for _, timer := range s.timers {
			if timer.stopped || timer.deadline.After(s.now) {
				continue
			}
			if next == nil || timer.deadline.Before(next.deadline) {
				next = timer
			}
		}
		if next != nil {
			next.stopped = true
		}
		s.mu.Unlock()
		if next == nil {
			return
		}
		next.callback()
	}
}

func stringPointer(value string) *string { return &value }
func int64Pointer(value int64) *int64    { return &value }
func boolPointer(value bool) *bool       { return &value }

// IDE-T-013 verifies compact helper output omits empty optional values and reads manager generations safely.
func TestOptionalServiceHelpers(t *testing.T) {
	if optionalStringPtr("") != nil {
		t.Fatal("empty optional string was materialized")
	}
	if value := optionalStringPtr("parent"); value == nil || *value != "parent" {
		t.Fatalf("optional string=%v", value)
	}
	if currentGeneration(&fakeCodeServerManager{}) != 0 {
		t.Fatal("generation without session was nonzero")
	}
	manager := &fakeCodeServerManager{session: &CodeServerSession{Generation: 7}}
	if currentGeneration(manager) != 7 {
		t.Fatalf("generation=%d", currentGeneration(manager))
	}
}

// IDE-T-114 verifies workspace removal failure remains explicit and retryable.
func TestWorkspaceRemovalFailureRetainsClosingView(t *testing.T) {
	root := t.TempDir()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close(context.Background())
	manager := &fakeCodeServerManager{}
	service, err := NewService(config.DaemonConfig{DataDir: t.TempDir()}, nil, nil, ServiceOptions{DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: manager})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(context.Background())
	opened := service.HandleCommand(context.Background(), openCommandForTest(root, "11111111-1111-4111-8111-111111111111", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
	if !opened.GetOk() {
		t.Fatalf("open reply=%v", opened)
	}
	workspace := opened.GetWorkspacePath()
	if err := os.Remove(workspace); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(workspace, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(workspace, "keep"), []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	service.mu.Lock()
	service.views[opened.GetViewId()].forceAllowed = true
	service.mu.Unlock()
	result := service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, opened, true))
	state := service.GetViewState(opened.GetViewId())
	service.mu.Lock()
	cleanupFailed := service.views[opened.GetViewId()].cleanupFailed
	service.mu.Unlock()
	if result.GetCode() != "CLEANUP_FAILED" || state == nil || state.GetState() != protocol.IdeViewState_CLOSING || !cleanupFailed {
		t.Fatalf("close=%v state=%v cleanupFailed=%v", result, state, cleanupFailed)
	}
}
