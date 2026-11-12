package runtime

import (
	"context"
	"errors"
	"time"

	"google.golang.org/protobuf/proto"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

const (
	maxInFlightIDECommands = 256
	ideLeaseRoundTTL       = 15 * time.Second
)

type ideCommandCall struct {
	connection *activeConnection
	cancel     context.CancelFunc
}

func (r *Runtime) acceptIDECommand(ctx context.Context, invocationID string, command *protocol.IdeCommand) error {
	if invocationID == "" {
		return errors.New("IDE command requires a non-empty invocationId")
	}
	r.mu.RLock()
	active := r.connection
	r.mu.RUnlock()
	if active == nil {
		return errors.New("IDE command requires an active connection")
	}

	r.ideMu.Lock()
	if _, exists := r.ideCommands[invocationID]; exists {
		r.ideMu.Unlock()
		return nil
	}
	if r.updateIsHeld() && command.GetAction() == protocol.IdeAction_OPEN_VIEW {
		r.ideMu.Unlock()
		return r.sendIDEResultFor(active, invocationID, failedIDEResult("BUSY", "environment update is in progress"))
	}
	if r.options.IDE == nil {
		r.ideMu.Unlock()
		return r.sendIDEResultFor(active, invocationID, failedIDEResult("UNAVAILABLE", "IDE service is unavailable"))
	}
	if len(r.ideCommands) >= maxInFlightIDECommands {
		r.ideMu.Unlock()
		return r.sendIDEResultFor(active, invocationID, failedIDEResult("BUSY", "too many in-flight IDE commands"))
	}

	var commandCtx context.Context
	var commandCancel context.CancelFunc
	if isUnboundedIDEClose(command) {
		commandCtx, commandCancel = context.WithCancel(ctx)
	} else {
		commandCtx, commandCancel = context.WithTimeout(ctx, r.options.IDECommandTimeout)
	}
	call := &ideCommandCall{connection: active, cancel: commandCancel}
	r.ideCommands[invocationID] = call
	r.ideCommandCount.Add(1)
	r.ideWG.Add(1)
	r.ideMu.Unlock()
	go r.runIDECommand(commandCtx, invocationID, command, call, commandCancel)
	return nil
}

func isUnboundedIDEClose(command *protocol.IdeCommand) bool {
	return command.GetAction() == protocol.IdeAction_CLOSE_VIEW && command.Discard != nil && !command.GetDiscard()
}

func (r *Runtime) runIDECommand(ctx context.Context, invocationID string, command *protocol.IdeCommand, call *ideCommandCall, cancel context.CancelFunc) {
	defer r.ideWG.Done()
	defer cancel()
	defer r.ideCommandCount.Add(-1)
	result := r.options.IDE.HandleCommand(ctx, command)
	if result == nil {
		result = failedIDEResult("UNAVAILABLE", "IDE command failed")
	}
	r.ideMu.Lock()
	current := r.ideCommands[invocationID] == call
	if current {
		delete(r.ideCommands, invocationID)
	}
	r.ideMu.Unlock()
	if current {
		_ = r.sendIDEResultFor(call.connection, invocationID, result)
	}
}

func (r *Runtime) updateIsHeld() bool {
	_, active := r.updateOps.Active()
	return active
}

func failedIDEResult(code, message string) *protocol.IdeResult {
	return &protocol.IdeResult{Ok: proto.Bool(false), Code: &code, Message: &message}
}

func (r *Runtime) sendIDEResultFor(active *activeConnection, invocationID string, result *protocol.IdeResult) error {
	if err := r.gate.RequireReadyFrame(); err != nil {
		return err
	}
	frame := &protocol.Envelope{
		MessageType:   protocol.MessageType_IDE_RESULT,
		EnvironmentId: stringPtr(r.options.EnvironmentID),
		InvocationId:  stringPtr(invocationID),
		Payload:       &protocol.Envelope_IdeResult{IdeResult: result},
	}
	r.sendMu.Lock()
	defer r.sendMu.Unlock()
	r.mu.RLock()
	ctx, current := r.sendCtx, r.connection
	r.mu.RUnlock()
	if current != active {
		return nil
	}
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := current.connection.Send(ctx, frame); err != nil {
		r.dropConnection(current)
		return err
	}
	return nil
}

func (r *Runtime) cancelIDECommands(connection *activeConnection) {
	r.ideMu.Lock()
	for id, call := range r.ideCommands {
		if connection == nil || call.connection == connection {
			delete(r.ideCommands, id)
			call.cancel()
		}
	}
	r.ideMu.Unlock()
}

func (r *Runtime) newIDELeaseRound() (int64, time.Time) {
	r.ideLeaseMu.Lock()
	defer r.ideLeaseMu.Unlock()
	const maxSafeInteger = int64(1<<53 - 1)
	if r.ideLeaseRound >= maxSafeInteger {
		r.ideLeaseRound = 0
	}
	r.ideLeaseRound++
	r.ideLeaseSentAt = time.Now()
	r.ideLeaseConsumed = false
	return r.ideLeaseRound, r.ideLeaseSentAt
}

func (r *Runtime) consumeIDELease(lease *protocol.IdeLease, now time.Time) (time.Time, error) {
	r.ideLeaseMu.Lock()
	defer r.ideLeaseMu.Unlock()
	age := now.Sub(r.ideLeaseSentAt)
	if lease == nil || lease.Round == nil || r.ideLeaseSentAt.IsZero() || lease.GetRound() != r.ideLeaseRound || r.ideLeaseConsumed || age < 0 || age >= ideLeaseRoundTTL {
		return time.Time{}, errors.New("IDE_LEASE round is not the current unexpired round")
	}
	r.ideLeaseConsumed = true
	return r.ideLeaseSentAt, nil
}

func (r *Runtime) clearIDELeaseRound() {
	r.ideLeaseMu.Lock()
	r.ideLeaseRound = 0
	r.ideLeaseSentAt = time.Time{}
	r.ideLeaseConsumed = false
	r.ideLeaseMu.Unlock()
}
