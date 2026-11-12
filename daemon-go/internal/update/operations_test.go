package update

import (
	"testing"
)

// Unit coverage verifies that an eligible command is admitted.
func TestOperationsAdmitUpdate(t *testing.T) {
	operations := NewOperations()
	decision, err := operations.Decide(Command{OperationID: "op-1", TargetVersion: "2.0.0"}, "1.0.0", true, "")
	if err != nil || decision.Action != ActionAdmit || decision.Receipt == nil || decision.Receipt.Phase != PhaseAccepted {
		t.Fatalf("decision = %+v, %v", decision, err)
	}
}

// Unit coverage verifies that a protected execution blocks update admission.
func TestOperationsRejectBusyUpdate(t *testing.T) {
	operations := NewOperations()
	decision, err := operations.Decide(Command{OperationID: "op-1", TargetVersion: "2.0.0"}, "1.0.0", true, "protected execution is still running")
	if err != nil || decision.Action != ActionReject || decision.Receipt == nil || decision.Receipt.Message != "update is busy: protected execution is still running" {
		t.Fatalf("busy decision = %+v, %v", decision, err)
	}
}

// RUN-T-128 replays an active command and holds admission until a terminal failure.
func TestOperationsReplayActiveAndPreparedReceipts(t *testing.T) {
	operations := NewOperations()
	command := Command{OperationID: "op-1", TargetVersion: "2.0.0"}
	if decision, err := operations.Decide(command, "1.0.0", true, ""); err != nil || decision.Action != ActionAdmit {
		t.Fatalf("first decision = %+v, %v", decision, err)
	}
	if decision, err := operations.Decide(command, "1.0.0", true, ""); err != nil || decision.Action != ActionStatus {
		t.Fatalf("unprepared duplicate = %+v, %v", decision, err)
	}
	if _, ok := operations.Complete("op-1", PhasePrepared, "ready"); !ok {
		t.Fatal("prepared runner result was not accepted")
	}
	if decision, err := operations.Decide(command, "1.0.0", true, ""); err != nil || decision.Action != ActionReplayActive || decision.Receipt.Phase != PhasePrepared {
		t.Fatalf("prepared replay = %+v, %v", decision, err)
	}
	if _, ok := operations.Active(); !ok {
		t.Fatal("prepared result released the active admission hold")
	}
}

// Unit coverage verifies that a post-PREPARED failure releases the active operation.
func TestOperationsFailPreparedActiveUpdate(t *testing.T) {
	operations := NewOperations()
	_, _ = operations.Decide(Command{OperationID: "op-1", TargetVersion: "2.0.0"}, "1.0.0", true, "")
	_, _ = operations.Complete("op-1", PhasePrepared, "")
	receipt, ok := operations.FailActive("official installer failed")
	if !ok || receipt.Phase != PhaseFailed || receipt.Message != "official installer failed" {
		t.Fatalf("failure receipt = %+v, present=%t", receipt, ok)
	}
	if _, ok := operations.Active(); ok {
		t.Fatal("failed active operation retained its admission hold")
	}
}

// RUN-T-128 protects operation IDs from argument drift and rejects a second target while active.
func TestOperationsRejectDriftAndConcurrentTarget(t *testing.T) {
	operations := NewOperations()
	first := Command{OperationID: "op-1", TargetVersion: "2.0.0"}
	_, _ = operations.Decide(first, "1.0.0", true, "")
	if _, err := operations.Decide(Command{OperationID: "op-1", TargetVersion: "3.0.0"}, "1.0.0", true, ""); err == nil || err.Error() != "the same update operationId must not change its arguments" {
		t.Fatalf("operation drift error = %v", err)
	}
	decision, err := operations.Decide(Command{OperationID: "op-2", TargetVersion: "3.0.0"}, "1.0.0", true, "")
	if err != nil || decision.Action != ActionReject || decision.Receipt.Message != "update is busy for target 2.0.0" {
		t.Fatalf("concurrent target decision = %+v, %v", decision, err)
	}
}

// RUN-T-131 caches at most eight receipts, evicting the oldest insertion.
func TestOperationsBoundReceiptCache(t *testing.T) {
	operations := NewOperations()
	for index := 0; index < 9; index++ {
		command := Command{OperationID: string(rune('a' + index)), TargetVersion: "2.0.0"}
		decision, err := operations.Decide(command, "1.0.0", false, "")
		if err != nil || decision.Action != ActionReject {
			t.Fatalf("reject %d = %+v, %v", index, decision, err)
		}
	}
	decision, err := operations.Decide(Command{OperationID: "a", TargetVersion: "2.0.0"}, "1.0.0", false, "")
	if err != nil || decision.Action != ActionReject || decision.Receipt.Message != "managed update is not configured" {
		t.Fatalf("evicted operation was not reconsidered: %+v, %v", decision, err)
	}
	decision, err = operations.Decide(Command{OperationID: "i", TargetVersion: "2.0.0"}, "1.0.0", true, "")
	if err != nil || decision.Action != ActionReplayCache || decision.Receipt.Phase != PhaseFailed {
		t.Fatalf("newest receipt was not replayed: %+v, %v", decision, err)
	}
}

// RUN-T-132 redelivers the active operation before completed cached receipts.
func TestOperationsResendActiveBeforeCached(t *testing.T) {
	operations := NewOperations()
	_, _ = operations.Decide(Command{OperationID: "op-1", TargetVersion: "2.0.0"}, "1.0.0", true, "")
	_, _ = operations.Decide(Command{OperationID: "op-2", TargetVersion: "3.0.0"}, "1.0.0", true, "")
	receipt, ok := operations.ReceiptToResend()
	if !ok || receipt.Command.OperationID != "op-1" || receipt.Phase != PhaseAccepted {
		t.Fatalf("resend receipt = %+v, present=%t", receipt, ok)
	}
	_, _ = operations.Complete("op-1", PhasePrepared, "ready")
	receipt, ok = operations.ReceiptToResend()
	if !ok || receipt.Command.OperationID != "op-1" || receipt.Phase != PhasePrepared {
		t.Fatalf("prepared resend receipt = %+v, present=%t", receipt, ok)
	}
	_, _ = operations.FailActive("failed")
	receipt, ok = operations.ReceiptToResend()
	if !ok || receipt.Command.OperationID != "op-1" || receipt.Phase != PhaseFailed {
		t.Fatalf("cached resend receipt = %+v, present=%t", receipt, ok)
	}
}

// ValidateCommand enforces the operation ID and release version formats at the wire boundary.
func TestValidateCommandMatchesWireContract(t *testing.T) {
	valid := Command{OperationID: "123e4567-e89b-42d3-a456-426614174000", TargetVersion: "1.0.0-rc.1+build"}
	if err := ValidateCommand(valid); err != nil {
		t.Fatal(err)
	}
	for _, command := range []Command{
		{OperationID: "123E4567-e89b-42d3-a456-426614174000", TargetVersion: "1.0.0"},
		{OperationID: "123e4567-e89b-42d3-a456-426614174000", TargetVersion: "../1"},
		{OperationID: "123e4567-e89b-42d3-a456-426614174000", TargetVersion: ""},
	} {
		if err := ValidateCommand(command); err == nil {
			t.Fatalf("accepted invalid update command: %+v", command)
		}
	}
}

func TestOperationsCompleteRejectsUnknownPhaseAndAbsentOperation(t *testing.T) {
	operations := NewOperations()
	if _, ok := operations.Complete("missing", PhasePrepared, ""); ok {
		t.Fatal("completed an operation that was never admitted")
	}
	_, _ = operations.Decide(Command{OperationID: "op-1", TargetVersion: "2.0.0"}, "1.0.0", true, "")
	if _, ok := operations.Complete("op-1", PhaseAccepted, ""); ok {
		t.Fatal("accepted a non-terminal completion phase")
	}
	if _, ok := operations.Active(); !ok {
		t.Fatal("invalid completion phase released the operation")
	}
}

// RUN-T-129 returns command drift errors for cached results as well as active work.
func TestOperationsRejectCachedArgumentDrift(t *testing.T) {
	operations := NewOperations()
	command := Command{OperationID: "op-1", TargetVersion: "2.0.0"}
	_, _ = operations.Decide(command, "1.0.0", false, "")
	_, err := operations.Decide(Command{OperationID: "op-1", TargetVersion: "3.0.0"}, "1.0.0", false, "")
	if err == nil || err.Error() != "the same update operationId must not change its arguments" {
		t.Fatalf("cached operation drift error = %v", err)
	}
}
