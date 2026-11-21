package ide

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"github.com/fengwk/kk-studio/daemon/internal/transport"
)

// Automatic expiry is the only close path that runs without a user decision, so every test here
// checks that the view is either reclaimed with explicit proof or left exactly as it was.

const (
	expiryTestViewID    = "11111111-1111-4111-8111-111111111111"
	expiryTestSiblingID = "22222222-2222-4222-8222-222222222222"
	expiryTestClaimID   = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
	expiryTestClaim2ID  = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
)

// expiryFixture owns one service whose clock, host and recovery store are all synthetic.
type expiryFixture struct {
	t       *testing.T
	cfg     config.DaemonConfig
	bridge  *Bridge
	manager *fakeCodeServerManager
	clock   *fakeIdeScheduler
	service *Service
	events  chan IdeServiceEvent
}

func newExpiryFixture(t *testing.T) *expiryFixture {
	t.Helper()
	return newExpiryFixtureWithQuota(t, 0)
}

// newExpiryFixtureWithQuota bounds the recovery store so a draft can outgrow it.
func newExpiryFixtureWithQuota(t *testing.T, recoveryQuota int64) *expiryFixture {
	t.Helper()
	bridge, err := NewBridge()
	if err != nil {
		t.Fatal(err)
	}
	f := &expiryFixture{
		t: t, cfg: config.DaemonConfig{DataDir: t.TempDir()}, bridge: bridge,
		manager: &fakeCodeServerManager{}, clock: newFakeIdeScheduler(time.Date(2025, 1, 1, 0, 0, 0, 0, time.UTC)),
		events: make(chan IdeServiceEvent, 8),
	}
	f.service, err = NewService(f.cfg, nil, nil, ServiceOptions{
		DaemonInstanceID: "99999999-9999-4999-8999-999999999999", Bridge: bridge, Manager: f.manager,
		Scheduler: f.clock, RecoveryQuotaBytes: recoveryQuota, OnEvent: func(event IdeServiceEvent) {
			select {
			case f.events <- event:
			default:
			}
		},
	})
	if err != nil {
		_ = bridge.Close(context.Background())
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = f.service.Close(context.Background())
		_ = bridge.Close(context.Background())
	})
	return f
}

// openRoot creates the view under a fresh temp directory that the host also binds to.
func (f *expiryFixture) openRoot() (*protocol.IdeResult, string) {
	f.t.Helper()
	root := f.t.TempDir()
	opened := f.service.HandleCommand(context.Background(), openCommandForTest(root, expiryTestViewID, expiryTestClaimID))
	if !opened.GetOk() {
		f.t.Fatalf("open reply=%v", opened)
	}
	return opened, root
}

// openSibling creates a second view with its own identity so isolation is observable.
func (f *expiryFixture) openSibling(root string) *protocol.IdeResult {
	f.t.Helper()
	sibling := f.service.HandleCommand(context.Background(), openCommandForTest(root, expiryTestSiblingID, expiryTestClaim2ID))
	if !sibling.GetOk() {
		f.t.Fatalf("sibling open reply=%v", sibling)
	}
	return sibling
}

// host attaches the window that answers this view's bridge commands. A nil snapshot makes the
// host answer with the plain success reply the extension sends for non-snapshot operations.
func (f *expiryFixture) host(opened *protocol.IdeResult, root string, snapshot map[string]any) {
	f.t.Helper()
	_, cancel := startBridgeReplyHostWith(f.t, f.bridge, opened.GetWorkspacePath(), opened.GetViewId(), root,
		map[string]map[string]any{"snapshot": snapshot})
	f.t.Cleanup(cancel)
}

// seedPreviousBackup stores a usable backup so a failed attempt can be shown to keep it.
func (f *expiryFixture) seedPreviousBackup(opened *protocol.IdeResult, root string) {
	f.t.Helper()
	backup := RecoveryBackup{
		ViewID: opened.GetViewId(), BindingEpoch: strconv.FormatInt(opened.GetBindingEpoch(), 10), Directory: root,
		CreatedAt: "2025-01-01T00:00:00Z", Complete: true,
		Documents: []RecoveryDocument{{Path: filepath.Join(root, "saved.txt"), Text: "previous"}},
	}
	if _, err := WriteRecoveryBackup(f.cfg, backup, defaultRecoveryQuota); err != nil {
		f.t.Fatal(err)
	}
}

// requirePreviousBackup asserts the previously persisted draft text is still readable.
func (f *expiryFixture) requirePreviousBackup(viewID string) {
	f.t.Helper()
	backup, err := ReadRecoveryBackup(f.cfg, viewID, defaultRecoveryQuota)
	if err != nil || backup == nil || len(backup.Documents) != 1 || backup.Documents[0].Text != "previous" {
		f.t.Fatalf("previous backup was lost: backup=%#v err=%v", backup, err)
	}
}

// requireDeferred asserts the attempt kept the view OPEN and retryable, closed nothing and reported
// exactly one expiry-deferred event.
func (f *expiryFixture) requireDeferred(viewID string) {
	f.t.Helper()
	if state := f.service.GetViewState(viewID); state == nil || state.GetState() != protocol.IdeViewState_OPEN {
		f.t.Fatalf("expiry was not deferred: state=%v", state)
	}
	f.service.mu.Lock()
	view := f.service.views[viewID]
	retryArmed := view != nil && view.expiryTimer != nil && view.expiresAt.After(f.clock.Now())
	f.service.mu.Unlock()
	if !retryArmed {
		f.t.Fatal("deferred expiry armed no next-lease retry")
	}
	if calls, modes := f.closeCalls(); calls != 0 {
		f.t.Fatalf("deferred expiry still reclaimed the view: calls=%d modes=%v", calls, modes)
	}
	var deferred []IdeServiceEvent
	for {
		select {
		case event := <-f.events:
			if event.Kind == "expiry-deferred" {
				deferred = append(deferred, event)
			}
		default:
			if len(deferred) != 1 || deferred[0].ViewID != viewID {
				f.t.Fatalf("deferred expiry events=%+v want exactly one for %s", deferred, viewID)
			}
			return
		}
	}
}

// requireReclaimed asserts the view is gone after exactly one expired-mode managed close.
func (f *expiryFixture) requireReclaimed(viewID string) {
	f.t.Helper()
	if state := f.service.GetViewState(viewID); state != nil {
		f.t.Fatalf("view was not reclaimed: state=%v", state)
	}
	if calls, modes := f.closeCalls(); calls != 1 || modes[0] != ManagedViewCloseExpired {
		f.t.Fatalf("managed close calls=%d modes=%v want one expired-mode close", calls, modes)
	}
}

// closeCalls reports the managed close invocations the fake manager recorded.
func (f *expiryFixture) closeCalls() (int, []ManagedViewCloseMode) {
	f.manager.mu.Lock()
	defer f.manager.mu.Unlock()
	return f.manager.closeCalls, append([]ManagedViewCloseMode(nil), f.manager.closeModes...)
}

// dirtySnapshot reports one complete ordinary dirty file buffer.
func dirtySnapshot(root string) map[string]any {
	return map[string]any{
		"documents": []map[string]string{{"path": filepath.Join(root, "draft.txt"), "text": "unsaved"}},
		"complete":  true,
	}
}

// cleanSnapshot is the explicit complete, empty snapshot of a window holding no drafts.
func cleanSnapshot() map[string]any {
	return map[string]any{"documents": []map[string]string{}, "omitted": 0, "complete": true}
}

// A delivered native close has no reply while the workbench waits for the user.
// Renewing the lease preserves the wait; losing the lease ends it and requires fresh recovery proof.
func TestLeaseExpiryEndsPendingNativeClose(t *testing.T) {
	for _, complete := range []bool{true, false} {
		t.Run(strconv.FormatBool(complete), func(t *testing.T) {
			if complete && !recoveryDirectorySyncSupported {
				t.Skip("durable recovery publication is unavailable on this platform")
			}
			f := newExpiryFixture(t)
			opened, root := f.openRoot()
			viewID, epoch := opened.GetViewId(), opened.GetBindingEpoch()
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			const hostID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
			response, reader := openManualBridgeStream(t, ctx, f.bridge, opened.GetWorkspacePath(), hostID, root)
			defer response.Body.Close()
			if _, err := reader.ReadString('\n'); err != nil {
				t.Fatal(err)
			}
			readCommand := func(want string) string {
				t.Helper()
				line, err := reader.ReadBytes('\n')
				var command struct{ ID, Op string }
				if err != nil || json.Unmarshal(line, &command) != nil || command.Op != want || command.ID == "" {
					t.Fatalf("command=%q err=%v; want %s", line, err, want)
				}
				return command.ID
			}
			closed := make(chan *protocol.IdeResult, 1)
			go func() {
				closed <- f.service.HandleCommand(ctx, viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, opened, false))
			}()
			closeID := readCommand("closeView")
			f.clock.Advance(viewLeaseDuration / 2)
			f.service.ApplyLease(&protocol.IdeLease{Bindings: []*protocol.IdeLeaseBinding{{
				ViewId: &viewID, BrowserSessionId: stringPointer(opened.GetBrowserSessionId()), BindingEpoch: &epoch,
			}}}, f.clock.Now())
			f.clock.Advance(viewLeaseDuration / 2)
			f.bridge.mu.Lock()
			stillPending := f.bridge.pending[closeID] != nil
			f.bridge.mu.Unlock()
			if !stillPending || f.service.GetViewState(viewID).GetState() != protocol.IdeViewState_OPEN {
				t.Fatal("renewed lease did not preserve the native close dialog")
			}

			expired := make(chan struct{})
			go func() {
				f.clock.Advance(viewLeaseDuration / 2)
				close(expired)
			}()
			select {
			case result := <-closed:
				if result.GetOk() || result.GetCode() != "OUTCOME_UNKNOWN" {
					t.Fatalf("expired native close=%v; want OUTCOME_UNKNOWN", result)
				}
			case <-time.After(time.Second):
				f.bridge.mu.Lock()
				stillPending := f.bridge.pending[closeID] != nil
				f.bridge.mu.Unlock()
				t.Fatalf("lease expired but CLOSE_VIEW did not return (bridge command pending=%t, view=%s)", stillPending, f.service.GetViewState(viewID).GetState())
			}
			snapshotID := readCommand("snapshot")
			snapshot := dirtySnapshot(root)
			snapshot["ok"] = true
			snapshot["complete"] = complete
			if status := postBridge(t, f.bridge, "/bridge/result", bridgeTestResultBody(snapshotID, viewID, epoch, hostID, snapshot)); status != http.StatusNoContent {
				t.Fatalf("snapshot reply status=%d", status)
			}
			select {
			case <-expired:
			case <-ctx.Done():
				t.Fatal("expiry did not finish")
			}
			if !complete {
				f.requireDeferred(viewID)
				return
			}
			f.requireReclaimed(viewID)
			backup, err := ReadRecoveryBackup(f.cfg, viewID, defaultRecoveryQuota)
			if err != nil || backup == nil || !backup.Complete || len(backup.Documents) != 1 || backup.Documents[0].Text != "unsaved" {
				t.Fatalf("expiry backup=%#v err=%v", backup, err)
			}
		})
	}
}

// Verifies a complete dirty backup reclaims the view and is stored under its binding epoch.
func TestLeaseExpiryReclaimsAfterCompleteDirtyBackup(t *testing.T) {
	if !recoveryDirectorySyncSupported {
		t.Skip("durable recovery publication is unavailable on this platform")
	}
	for _, test := range []struct {
		name   string
		omit   any
		absent bool
	}{
		{name: "omitted zero", omit: 0},
		{name: "omitted absent", absent: true},
		{name: "omitted null", omit: nil},
	} {
		t.Run(test.name, func(t *testing.T) {
			f := newExpiryFixture(t)
			opened, root := f.openRoot()
			snapshot := dirtySnapshot(root)
			if test.absent {
				delete(snapshot, "omitted")
			} else {
				snapshot["omitted"] = test.omit
			}
			f.host(opened, root, snapshot)
			f.clock.Advance(viewLeaseDuration)
			f.requireReclaimed(opened.GetViewId())
			backup, err := ReadRecoveryBackup(f.cfg, opened.GetViewId(), defaultRecoveryQuota)
			if err != nil || backup == nil || !backup.Complete || len(backup.Documents) != 1 || backup.Documents[0].Text != "unsaved" {
				t.Fatalf("expiry backup=%#v err=%v", backup, err)
			}
			if backup.BindingEpoch != strconv.FormatInt(opened.GetBindingEpoch(), 10) {
				t.Fatalf("backup epoch=%q want %d", backup.BindingEpoch, opened.GetBindingEpoch())
			}
		})
	}
}

// Verifies a clean expiry keeps the previous usable backup and reclaims only the expired view.
func TestLeaseExpiryCleanSnapshotKeepsPreviousBackupAndOnlyItsView(t *testing.T) {
	f := newExpiryFixture(t)
	opened, root := f.openRoot()
	sibling := f.openSibling(root)
	f.seedPreviousBackup(opened, root)
	f.host(opened, root, cleanSnapshot())
	// The sibling carries a later proof, so its own deadline is still in the future.
	f.clock.Advance(viewLeaseDuration / 2)
	f.service.ApplyLease(&protocol.IdeLease{Bindings: []*protocol.IdeLeaseBinding{{
		ViewId: stringPointer(sibling.GetViewId()), BrowserSessionId: stringPointer(sibling.GetBrowserSessionId()),
		BindingEpoch: int64Pointer(sibling.GetBindingEpoch()),
	}}}, f.clock.Now())
	f.clock.Advance(viewLeaseDuration / 2)
	f.requireReclaimed(opened.GetViewId())
	f.requirePreviousBackup(opened.GetViewId())
	if state := f.service.GetViewState(sibling.GetViewId()); state == nil || state.GetState() != protocol.IdeViewState_OPEN {
		t.Fatalf("sibling view was affected: %v", state)
	}
}

// Verifies an unreachable window, a rejected snapshot, an incomplete snapshot and a backup that
// cannot be persisted all defer expiry and keep the previous backup.
func TestLeaseExpiryDefersWithoutExplicitCompleteProof(t *testing.T) {
	cases := []struct {
		name     string
		snapshot map[string]any
		host     bool
	}{
		{name: "bridge unavailable"},
		{name: "snapshot rejected", host: true, snapshot: map[string]any{"ok": false, "code": "DIRTY", "message": "snapshot rejected"}},
		{name: "complete absent", host: true, snapshot: map[string]any{"documents": []map[string]string{}}},
		{name: "documents absent", host: true, snapshot: map[string]any{"complete": true}},
		{name: "complete false", host: true, snapshot: map[string]any{"documents": []map[string]string{}, "complete": false}},
		{name: "omitted entries", host: true, snapshot: map[string]any{"documents": []map[string]string{}, "omitted": 1, "complete": true}},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			f := newExpiryFixture(t)
			opened, root := f.openRoot()
			f.seedPreviousBackup(opened, root)
			if test.host {
				f.host(opened, root, test.snapshot)
			}
			f.clock.Advance(viewLeaseDuration)
			f.requireDeferred(opened.GetViewId())
			f.requirePreviousBackup(opened.GetViewId())
			if _, err := os.Stat(opened.GetWorkspacePath()); err != nil {
				t.Fatalf("deferred expiry removed the managed workspace: %v", err)
			}
		})
	}
}

// Verifies a draft that cannot be persisted keeps the view and the previous good backup.
func TestLeaseExpiryDefersOnBackupWriteFailure(t *testing.T) {
	if !recoveryDirectorySyncSupported {
		t.Skip("durable recovery publication is unavailable on this platform")
	}
	// The previous backup fits the quota, the new draft does not, so publication fails while the
	// record already on disk stays readable.
	f := newExpiryFixtureWithQuota(t, 320)
	opened, root := f.openRoot()
	f.seedPreviousBackup(opened, root)
	f.host(opened, root, map[string]any{
		"documents": []map[string]string{{"path": filepath.Join(root, "draft.txt"), "text": strings.Repeat("x", 2048)}},
		"complete":  true,
	})
	f.clock.Advance(viewLeaseDuration)
	f.requireDeferred(opened.GetViewId())
	f.requirePreviousBackup(opened.GetViewId())
}

// Verifies a deferred view stays usable: it renews on a fresh proof, retries only at the next lease
// and can still be closed explicitly.
func TestLeaseExpiryDeferralKeepsViewUsable(t *testing.T) {
	f := newExpiryFixture(t)
	opened, _ := f.openRoot()
	f.clock.Advance(viewLeaseDuration)
	f.requireDeferred(opened.GetViewId())
	f.clock.Advance(viewLeaseDuration - time.Second)
	if state := f.service.GetViewState(opened.GetViewId()); state == nil || state.GetState() != protocol.IdeViewState_OPEN {
		t.Fatalf("deferred view was not retained for the next lease: %v", state)
	}
	select {
	case event := <-f.events:
		t.Fatalf("deferred expiry retried before the next lease: %+v", event)
	default:
	}
	f.service.ApplyLease(&protocol.IdeLease{Bindings: []*protocol.IdeLeaseBinding{{
		ViewId: stringPointer(opened.GetViewId()), BrowserSessionId: stringPointer(opened.GetBrowserSessionId()),
		BindingEpoch: int64Pointer(opened.GetBindingEpoch()),
	}}}, f.clock.Now())
	closed := f.service.HandleCommand(context.Background(), viewCommandForTest(protocol.IdeAction_CLOSE_VIEW, opened, true))
	if !closed.GetOk() || f.service.GetViewState(opened.GetViewId()) != nil {
		t.Fatalf("explicit close after a deferred expiry=%v state=%v", closed, f.service.GetViewState(opened.GetViewId()))
	}
}

// Verifies an already-fired expiry callback cannot close a view that was renewed or reattached
// before that callback ran, while the current lease still reclaims the view later.
func TestLeaseExpiryIgnoresStaleAlreadyFiredCallback(t *testing.T) {
	t.Run("same epoch renewal", func(t *testing.T) {
		f := newExpiryFixture(t)
		opened, root := f.openRoot()
		f.host(opened, root, cleanSnapshot())
		f.clock.advanceTime(viewLeaseDuration)
		stale := f.clock.takeDue()
		if len(stale) != 1 {
			t.Fatalf("due callbacks=%d want 1", len(stale))
		}
		f.service.ApplyLease(&protocol.IdeLease{Bindings: []*protocol.IdeLeaseBinding{{
			ViewId: stringPointer(opened.GetViewId()), BrowserSessionId: stringPointer(opened.GetBrowserSessionId()),
			BindingEpoch: int64Pointer(opened.GetBindingEpoch()),
		}}}, f.clock.Now())
		f.clock.advanceTime(30 * time.Second)
		for _, callback := range stale {
			callback()
		}
		if state := f.service.GetViewState(opened.GetViewId()); state == nil || state.GetState() != protocol.IdeViewState_OPEN {
			t.Fatalf("stale callback closed a renewed view: %v", state)
		}
		if calls, modes := f.closeCalls(); calls != 0 {
			t.Fatalf("stale callback reclaimed the view: modes=%v", modes)
		}
		f.clock.Advance(viewLeaseDuration - 30*time.Second)
		f.requireReclaimed(opened.GetViewId())
	})
	t.Run("new epoch reattach", func(t *testing.T) {
		f := newExpiryFixture(t)
		opened, root := f.openRoot()
		f.clock.advanceTime(viewLeaseDuration)
		stale := f.clock.takeDue()
		if len(stale) != 1 {
			t.Fatalf("due callbacks=%d want 1", len(stale))
		}
		resume := openCommandForTest(root, opened.GetViewId(), expiryTestClaim2ID)
		resume.Mode = protocol.IdeOpenMode_RESUME
		resume.ExpectedBindingEpoch = int64Pointer(opened.GetBindingEpoch())
		resumed := f.service.HandleCommand(context.Background(), resume)
		if !resumed.GetOk() || resumed.GetBindingEpoch() == opened.GetBindingEpoch() {
			t.Fatalf("resume reply=%v want a new binding epoch", resumed)
		}
		for _, callback := range stale {
			callback()
		}
		state := f.service.GetViewState(resumed.GetViewId())
		if state == nil || state.GetState() != protocol.IdeViewState_OPEN || state.GetBindingEpoch() != resumed.GetBindingEpoch() {
			t.Fatalf("stale callback disturbed the reattached view: %v", state)
		}
		if calls, modes := f.closeCalls(); calls != 0 {
			t.Fatalf("stale callback reclaimed the reattached view: modes=%v", modes)
		}
		f.host(resumed, root, cleanSnapshot())
		f.clock.Advance(viewLeaseDuration)
		f.requireReclaimed(resumed.GetViewId())
	})
}

// Verifies expiry freezes and drains the matching view's data plane before it gathers drafts.
func TestLeaseExpiryDrainsDataStreamsBeforeBackup(t *testing.T) {
	if !recoveryDirectorySyncSupported {
		t.Skip("durable recovery publication is unavailable on this platform")
	}
	f := newExpiryFixture(t)
	opened, root := f.openRoot()
	f.host(opened, root, dirtySnapshot(root))
	upstream, err := f.service.ResolveView(context.Background(), transport.ViewRequest{
		ViewID: opened.GetViewId(), BindingEpoch: opened.GetBindingEpoch(), Mode: "http", Method: "GET", Path: "/",
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(upstream.Release)
	f.clock.advanceTime(viewLeaseDuration)
	due := f.clock.takeDue()
	if len(due) != 1 {
		t.Fatalf("due callbacks=%d want 1", len(due))
	}
	expiryDone := make(chan struct{})
	go func() {
		defer close(expiryDone)
		for _, callback := range due {
			callback()
		}
	}()
	select {
	case <-upstream.Context.Done():
	case <-time.After(5 * time.Second):
		t.Fatal("expiry did not freeze the view's data plane")
	}
	if state := f.service.GetViewState(opened.GetViewId()); state == nil || state.GetState() != protocol.IdeViewState_CLOSING {
		t.Fatalf("frozen view state=%v", state)
	}
	// The stream is still draining, so nothing may be gathered or reclaimed yet. A persisted backup
	// is the observable proof that the snapshot was requested.
	if backup, err := ReadRecoveryBackup(f.cfg, opened.GetViewId(), defaultRecoveryQuota); err != nil || backup != nil {
		t.Fatalf("drafts were gathered before the drain: backup=%#v err=%v", backup, err)
	}
	if calls, modes := f.closeCalls(); calls != 0 {
		t.Fatalf("expiry reclaimed a view with live streams: modes=%v", modes)
	}
	upstream.Release()
	select {
	case <-expiryDone:
	case <-time.After(5 * time.Second):
		t.Fatal("expiry did not finish after the data stream drained")
	}
	f.requireReclaimed(opened.GetViewId())
	backup, err := ReadRecoveryBackup(f.cfg, opened.GetViewId(), defaultRecoveryQuota)
	if err != nil || backup == nil || len(backup.Documents) != 1 || backup.Documents[0].Text != "unsaved" {
		t.Fatalf("drained expiry backup=%#v err=%v", backup, err)
	}
}
