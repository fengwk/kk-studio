package runtime

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"github.com/fengwk/kk-studio/daemon/internal/update"
)

// RUN-T-031 validates update command framing, version idempotency, and protected-execution rejection.
func TestRuntimeHandleUpdateAdmissionEdges(t *testing.T) {
	r, _ := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	for _, envelope := range []*protocol.Envelope{
		{},
		{Payload: &protocol.Envelope_Update{Update: &protocol.Update{OperationId: stringPtr("bad"), TargetVersion: stringPtr("2.0.0")}}},
	} {
		if err := r.handleUpdate(context.Background(), envelope); err != nil {
			t.Fatalf("invalid update command should be reported on connection: %v", err)
		}
	}
	valid := updateEnvelope(testUpdateOperationID, "1.0.0")
	r.options.DaemonVersion = "1.0.0"
	if err := r.handleUpdate(context.Background(), valid); err != nil {
		t.Fatalf("same-version status request = %v", err)
	}
	r.options.Update = &testUpdateRunner{}
	r.options.DaemonVersion = "0.9.0"
	r.callsMu.Lock()
	r.calls["protected"] = &runningCall{cancel: func() {}}
	r.callsMu.Unlock()
	if err := r.handleUpdate(context.Background(), updateEnvelope(testUpdateOperationID, "2.0.0")); err != nil {
		t.Fatalf("busy update should be rejected with receipt: %v", err)
	}
	r.callsMu.Lock()
	delete(r.calls, "protected")
	r.callsMu.Unlock()

	unknown := updateEnvelope("123e4567-e89b-42d3-a456-426614174001", "2.0.0")
	unknown.GetUpdate().ProtoReflect().SetUnknown([]byte{0x20, 0x01})
	if err := r.handleUpdate(context.Background(), unknown); err != nil {
		t.Fatalf("unknown update fields should be rejected on connection: %v", err)
	}
}

// RUN-T-125/126/127 exercises runner stop, error, and non-PREPARED completion paths.
func TestRuntimeExecuteUpdateFailureBranches(t *testing.T) {
	cases := []struct {
		name    string
		runner  *testUpdateRunner
		wantMsg string
	}{
		{"stop error", &testUpdateRunner{stopError: errors.New("IDE shutdown failed")}, "IDE shutdown failed"},
		{"blank stop error", &testUpdateRunner{stopError: errors.New("  ")}, "capability execution failed"},
		{"runner error", &testUpdateRunner{runError: errors.New("installer failed")}, "installer failed"},
		{"blank failed outcome", &testUpdateRunner{outcome: update.Outcome{Phase: update.PhaseFailed}}, "managed update failed"},
		{"unexpected phase", &testUpdateRunner{outcome: update.Outcome{Phase: update.PhaseAccepted}}, "capability execution failed"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			o := runtimeOptionsForTest()
			o.Update = tc.runner
			r, connection := newReadyRuntimeForTest(t, o)
			command := updateCommandForTest()
			if _, err := r.updateOps.Decide(command, "1.0.0", true, ""); err != nil {
				t.Fatal(err)
			}
			if err := r.status.BeginUpdate(command.TargetVersion); err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithCancel(context.Background())
			r.wg.Add(1)
			go r.executeUpdate(ctx, cancel, command, config.DaemonConfig{EnvironmentID: "env"})
			r.wg.Wait()
			frames := connection.frames()
			var message string
			for _, frame := range frames {
				if frame.MessageType == protocol.MessageType_UPDATE_RESULT && frame.GetUpdateResult().GetPhase() == protocol.UpdatePhase_UPDATE_FAILED {
					message = frame.GetUpdateResult().GetMessage()
				}
			}
			if message != tc.wantMsg {
				t.Fatalf("failed update message=%q, want %q; frames=%+v", message, tc.wantMsg, frames)
			}
			if _, active := r.updateOps.Active(); active {
				t.Fatal("failed update retained admission")
			}
		})
	}
}

// RUN-T-132 resends the latest receipt and releases a prepared permit exactly through the permit boundary.
func TestRuntimeResendsUpdateReceiptsAndReportsPermitErrors(t *testing.T) {
	r, connection := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	if err := r.resendUpdateReceipt(); err != nil {
		t.Fatal(err)
	}
	command := updateCommandForTest()
	if _, err := r.updateOps.Decide(command, "1.0.0", true, ""); err != nil {
		t.Fatal(err)
	}
	if err := r.resendUpdateReceipt(); err != nil {
		t.Fatal(err)
	}
	if _, ok := r.updateOps.Complete(command.OperationID, update.PhasePrepared, "ready"); !ok {
		t.Fatal("could not retain prepared operation")
	}
	permitErr := errors.New("permit write failed")
	var reports atomic.Int32
	r.options.ReportError = func(err error) {
		if errors.Is(err, permitErr) {
			reports.Add(1)
		}
	}
	r.updatePermit = func() error { return permitErr }
	if err := r.resendUpdateReceipt(); err != nil {
		t.Fatal(err)
	}
	if reports.Load() != 1 || countUpdatePhase(connection.frames(), protocol.UpdatePhase_UPDATE_PREPARED) != 1 {
		t.Fatalf("permit reports=%d frames=%+v", reports.Load(), connection.frames())
	}
	if err := r.sendUpdateReceipt(update.Receipt{Phase: update.Phase("invalid")}); err == nil || err.Error() != "invalid update receipt phase" {
		t.Fatalf("invalid receipt phase error = %v", err)
	}
	if firstError(nil, nil) != nil || firstError(nil, permitErr) != permitErr {
		t.Fatal("firstError did not preserve the first failure")
	}
	if got := r.updateBusyReason(&testUpdateRunner{}); got != "" {
		t.Fatalf("empty runner busy reason = %q", got)
	}
}
