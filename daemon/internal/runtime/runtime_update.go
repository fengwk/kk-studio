package runtime

import (
	"context"
	"errors"
	"strings"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"github.com/fengwk/kk-studio/daemon/internal/update"
	"github.com/fengwk/kk-studio/daemon/internal/uuid"
)

func (r *Runtime) handleUpdate(ctx context.Context, envelope *protocol.Envelope) error {
	payload, ok := envelope.Payload.(*protocol.Envelope_Update)
	if !ok || payload.Update == nil || payload.Update.OperationId == nil || payload.Update.TargetVersion == nil || len(payload.Update.ProtoReflect().GetUnknown()) != 0 {
		return r.sendError(nil, "invalid update command")
	}
	command := update.Command{OperationID: payload.Update.GetOperationId(), TargetVersion: payload.Update.GetTargetVersion()}
	if err := update.ValidateCommand(command); err != nil {
		return r.sendError(nil, err.Error())
	}
	r.mu.RLock()
	configuration := cloneDaemonConfig(r.config)
	r.mu.RUnlock()
	runner := r.options.Update
	configured := runner != nil && runner.Configured()
	r.admissionMu.Lock()
	decision, err := r.updateOps.Decide(command, r.options.DaemonVersion, configured, r.updateBusyReason())
	if err != nil {
		r.admissionMu.Unlock()
		return err
	}
	switch decision.Action {
	case update.ActionStatus:
		r.admissionMu.Unlock()
		return r.sendStatus()
	case update.ActionReplayActive, update.ActionReplayCache:
		r.admissionMu.Unlock()
		resultErr := r.sendUpdateReceipt(*decision.Receipt)
		statusErr := r.sendStatus()
		releaseErr := r.releaseUpdatePermit()
		return firstError(resultErr, statusErr, releaseErr)
	case update.ActionReject:
		r.admissionMu.Unlock()
		return r.sendUpdateReceipt(*decision.Receipt)
	case update.ActionAdmit:
		updateCtx, cancel := context.WithCancel(ctx)
		r.updateMu.Lock()
		r.updateCancel = cancel
		r.updateMu.Unlock()
		if err := r.status.BeginUpdate(command.TargetVersion); err != nil {
			cancel()
			failed, _ := r.updateOps.Complete(command.OperationID, update.PhaseFailed, "managed update failed")
			r.admissionMu.Unlock()
			return firstError(err, r.sendUpdateReceipt(failed))
		}
		r.admissionMu.Unlock()
		statusErr := r.sendStatus()
		acceptedErr := r.sendUpdateReceipt(*decision.Receipt)
		r.wg.Add(1)
		go r.executeUpdate(updateCtx, cancel, command, configuration)
		return firstError(statusErr, acceptedErr)
	default:
		r.admissionMu.Unlock()
		return errors.New("invalid update admission decision")
	}
}

// beginManagedUpdate starts the managed update a 426 handshake rejection demands and reports whether
// the daemon may keep running. It reuses the admission bookkeeping, runner, receipts and shutdown
// cancellation of a server-sent UPDATE, but the operation id is generated locally because no UPDATE
// frame exists. An update that is already active or already prepared keeps the daemon alive; only a
// rejection the daemon cannot act on, or an update that never prepared, stops it.
func (r *Runtime) beginManagedUpdate(ctx context.Context, targetVersion string) bool {
	runner := r.options.Update
	if targetVersion == "" || runner == nil || !runner.Configured() {
		if r.options.ReportError != nil {
			r.options.ReportError(errProtocolUpgradeRequired)
		}
		return false
	}
	// A 426 rejection carries no UPDATE envelope, so the operation id is minted locally.
	command := update.Command{OperationID: uuid.New(), TargetVersion: targetVersion}
	r.mu.RLock()
	configuration := cloneDaemonConfig(r.config)
	r.mu.RUnlock()
	// A rejection-driven upgrade is not turned down by in-flight work: like the TS beginUpgrade it
	// hands the runner the stop signal instead of waiting for admission to free up.
	r.admissionMu.Lock()
	decision, err := r.updateOps.Decide(command, r.options.DaemonVersion, true, "")
	if err != nil {
		r.admissionMu.Unlock()
		if r.options.ReportError != nil {
			r.options.ReportError(err)
		}
		return false
	}
	if decision.Action != update.ActionAdmit {
		// The target is already installed, the same target is already being handled, or the daemon is
		// busy. None of those is a reason to stop: no update is started twice.
		r.admissionMu.Unlock()
		return true
	}
	updateCtx, cancel := context.WithCancel(ctx)
	r.updateMu.Lock()
	r.updateCancel = cancel
	r.updateMu.Unlock()
	if err := r.status.BeginUpdate(command.TargetVersion); err != nil {
		cancel()
		_, _ = r.updateOps.Complete(command.OperationID, update.PhaseFailed, "managed update failed")
		r.admissionMu.Unlock()
		if r.options.ReportError != nil {
			r.options.ReportError(err)
		}
		return false
	}
	r.admissionMu.Unlock()
	_ = r.sendStatus()
	_ = r.sendUpdateReceipt(*decision.Receipt)
	r.wg.Add(1)
	go r.executeUpgradeUpdate(updateCtx, cancel, command, configuration)
	return true
}

// executeUpgradeUpdate runs one rejection-triggered update and stops the daemon when the installer
// never accepted the handover, mirroring the TS onUnprepared callback.
func (r *Runtime) executeUpgradeUpdate(ctx context.Context, cancel context.CancelFunc, command update.Command, configuration config.DaemonConfig) {
	if r.runUpdate(ctx, cancel, command, configuration) {
		r.stopRun(errProtocolUpgradeRequired)
	}
}

func (r *Runtime) updateBusyReason() string {
	r.callsMu.Lock()
	protected := len(r.calls) > 0
	r.callsMu.Unlock()
	if protected {
		return "protected execution is still running"
	}
	if r.options.IDECommandCount != nil && r.options.IDECommandCount() > 0 {
		return "IDE command is in progress"
	}
	if r.options.IDECanStartUpdate != nil && !r.options.IDECanStartUpdate() {
		return "IDE views, cleanup or data streams are active"
	}
	return ""
}

func (r *Runtime) executeUpdate(ctx context.Context, cancel context.CancelFunc, command update.Command, configuration config.DaemonConfig) {
	r.runUpdate(ctx, cancel, command, configuration)
}

// runUpdate drives one admitted managed update through the runner and publishes its receipts. It
// reports whether the daemon must stop because the installer never accepted the handover; a
// shutdown cancellation is not a failure.
func (r *Runtime) runUpdate(ctx context.Context, cancel context.CancelFunc, command update.Command, configuration config.DaemonConfig) (stopDaemon bool) {
	defer r.wg.Done()
	defer cancel()
	runner := r.options.Update
	if runner == nil {
		r.failUpdate(command.OperationID, "managed update is not configured")
		return true
	}
	if err := runner.StopForUpdate(ctx); err != nil {
		r.reportStopFailure(ctx, command, err)
		return true
	}
	outcome, err := runner.HandleUpdate(ctx, command, configuration, func(progress update.Progress) {
		r.publishUpdateProgress(command, progress)
	})
	if ctx.Err() != nil {
		return false
	}
	if err != nil || outcome.Phase != update.PhasePrepared {
		r.failUpdate(command.OperationID, updateFailureMessage(outcome, err))
		return true
	}
	receipt, ok := r.updateOps.Complete(command.OperationID, update.PhasePrepared, outcome.Message)
	if !ok {
		return true
	}
	var stopWatching func()
	if outcome.WatchFailure != nil {
		preparedDelivered := make(chan struct{})
		defer close(preparedDelivered)
		stopWatching = outcome.WatchFailure(func(message string) {
			// The watcher may report during registration, and stopping it can wait for this callback.
			// Publish the failure after the PREPARED delivery attempt and off the watcher goroutine.
			r.wg.Go(func() {
				<-preparedDelivered
				r.failUpdate(command.OperationID, safeUpdateError(errors.New(message)))
			})
		})
	}
	r.updateMu.Lock()
	if ctx.Err() != nil {
		r.updateMu.Unlock()
		if stopWatching != nil {
			stopWatching()
		}
		return false
	}
	if active, ok := r.updateOps.Active(); !ok || active.Command.OperationID != command.OperationID {
		r.updateMu.Unlock()
		if stopWatching != nil {
			stopWatching()
		}
		return true
	}
	r.updateCancel = nil
	r.updatePermit = outcome.ReleasePermit
	r.updateWatch = stopWatching
	r.updateMu.Unlock()
	_ = r.sendStatus()
	_ = r.deliverUpdateReceipt(receipt)
	return false
}

// reportStopFailure records why the running work could not be stopped, unless the update context
// itself was cancelled.
func (r *Runtime) reportStopFailure(ctx context.Context, command update.Command, err error) {
	if ctx.Err() != nil {
		return
	}
	message := "capability execution failed"
	if !errors.Is(err, context.Canceled) && strings.TrimSpace(err.Error()) != "" {
		message = safeUpdateError(err)
	}
	r.failUpdate(command.OperationID, message)
}

// publishUpdateProgress forwards runner progress for the operation that still holds admission.
func (r *Runtime) publishUpdateProgress(command update.Command, progress update.Progress) {
	if active, ok := r.updateOps.Active(); !ok || active.Command.OperationID != command.OperationID {
		return
	}
	value := UpdateProgress{TargetVersion: command.TargetVersion, DownloadedBytes: progress.DownloadedBytes, TotalBytes: progress.TotalBytes}
	if err := r.status.UpdateProgress(value); err == nil {
		_ = r.sendStatus()
	}
}

// updateFailureMessage turns a runner error or failed outcome into the receipt message.
func updateFailureMessage(outcome update.Outcome, err error) string {
	if err != nil {
		return safeUpdateError(err)
	}
	if outcome.Phase != update.PhaseFailed {
		return "capability execution failed"
	}
	if outcome.Message == "" {
		return "managed update failed"
	}
	return safeUpdateError(errors.New(outcome.Message))
}

// failUpdate restores process admission before another operation can start or observe FAILED.
func (r *Runtime) failUpdate(operationID, message string) {
	r.admissionMu.Lock()
	receipt, ok := r.updateOps.Complete(operationID, update.PhaseFailed, message)
	if !ok {
		r.admissionMu.Unlock()
		return
	}
	if runner := r.options.Update; runner != nil {
		runner.ResumeAfterFailedUpdate()
	}
	r.status.EndUpdate()
	r.updateMu.Lock()
	stop := r.updateWatch
	r.updateWatch = nil
	r.updateCancel = nil
	r.updatePermit = nil
	r.updateMu.Unlock()
	r.admissionMu.Unlock()
	if stop != nil {
		stop()
	}
	_ = r.sendStatus()
	_ = r.sendUpdateReceipt(receipt)
}

func (r *Runtime) sendUpdateReceipt(receipt update.Receipt) error {
	phase := protocol.UpdatePhase_UPDATE_PHASE_UNSPECIFIED
	switch receipt.Phase {
	case update.PhaseAccepted:
		phase = protocol.UpdatePhase_UPDATE_ACCEPTED
	case update.PhasePrepared:
		phase = protocol.UpdatePhase_UPDATE_PREPARED
	case update.PhaseFailed:
		phase = protocol.UpdatePhase_UPDATE_FAILED
	default:
		return errors.New("invalid update receipt phase")
	}
	result := &protocol.UpdateResult{OperationId: stringPtr(receipt.Command.OperationID), Phase: phase}
	if receipt.Message != "" {
		result.Message = stringPtr(receipt.Message)
	}
	return r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_UPDATE_RESULT, Payload: &protocol.Envelope_UpdateResult{UpdateResult: result}}, true)
}

func (r *Runtime) deliverUpdateReceipt(receipt update.Receipt) error {
	resultErr := r.sendUpdateReceipt(receipt)
	releaseErr := r.releaseUpdatePermit()
	return firstError(resultErr, releaseErr)
}

func (r *Runtime) releaseUpdatePermit() error {
	r.updateMu.Lock()
	permit := r.updatePermit
	if permit == nil {
		r.updateMu.Unlock()
		return nil
	}
	if err := permit(); err != nil {
		r.updateMu.Unlock()
		if r.options.ReportError != nil {
			r.options.ReportError(err)
		}
		return nil
	}
	// A later update command must not observe or re-run the permit this release already consumed; a
	// failed release keeps it so the receipt replay can retry.
	r.updatePermit = nil
	r.updateMu.Unlock()
	return nil
}

func (r *Runtime) resendUpdateReceipt() error {
	receipt, ok := r.updateOps.ReceiptToResend()
	if !ok {
		return nil
	}
	resultErr := r.sendUpdateReceipt(receipt)
	var releaseErr error
	if receipt.Phase == update.PhasePrepared {
		releaseErr = r.releaseUpdatePermit()
	}
	return firstError(resultErr, releaseErr)
}

func firstError(errors ...error) error {
	for _, err := range errors {
		if err != nil {
			return err
		}
	}
	return nil
}

func safeUpdateError(err error) string {
	if err == nil || strings.TrimSpace(err.Error()) == "" {
		return "capability execution failed"
	}
	return err.Error()
}

func (r *Runtime) stopUpdateForShutdown() {
	r.updateMu.Lock()
	cancel := r.updateCancel
	stop := r.updateWatch
	r.updateCancel = nil
	r.updateWatch = nil
	r.updateMu.Unlock()
	if cancel != nil {
		cancel()
	}
	if stop != nil {
		stop()
	}
}
