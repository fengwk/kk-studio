package runtime

import (
	"context"
	"errors"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

var errControlDisconnected = errors.New("control connection is disconnected")

func (r *Runtime) runtimeSnapshot() *protocol.RuntimeSnapshot {
	body := &protocol.RuntimeBody{DaemonVersion: stringPtr(r.options.DaemonVersion)}
	status := r.status.Snapshot()
	configurationStatus := &protocol.ConfigurationStatus{
		AppliedVersion: status.AppliedVersion,
		ProxyUrl:       status.ProxyURL,
	}
	if failure := status.ConfigurationError; failure != nil {
		configurationStatus.Error = &protocol.ConfigurationError{Version: int64Ptr(failure.Version), Code: failure.Code}
		if failure.Field != "" {
			configurationStatus.Error.Field = stringPtr(failure.Field)
		}
	}
	if configurationStatus.AppliedVersion != nil || configurationStatus.Error != nil {
		body.Configuration = configurationStatus
	}
	if progress := status.Progress; progress != nil {
		body.Update = &protocol.UpdateProgress{TargetVersion: stringPtr(progress.TargetVersion), DownloadedBytes: int64Ptr(progress.DownloadedBytes), TotalBytes: progress.TotalBytes}
	}
	return &protocol.RuntimeSnapshot{Revision: int64Ptr(status.Revision), Status: body}
}

func (r *Runtime) sendReady() error {
	if err := r.sendFrame(&protocol.Envelope{MessageType: protocol.MessageType_READY, EnvironmentId: stringPtr(r.options.EnvironmentID), Payload: &protocol.Envelope_Ready{Ready: &protocol.Ready{DaemonVersion: stringPtr(r.options.DaemonVersion), HostInfo: r.hostInfo, RuntimeStatus: r.runtimeSnapshot()}}}); err != nil {
		return err
	}
	r.admissionMu.Lock()
	r.admissionPaused = false
	r.candidateSuspension = nil
	r.admissionMu.Unlock()
	if err := r.resendUnacknowledgedExits(); err != nil {
		return err
	}
	return r.resendUpdateReceipt()
}

func (r *Runtime) resendUnacknowledgedExits() error {
	for _, entry := range r.journal.UnacknowledgedExits() {
		if err := r.sendExecutionExited(entry.InvocationID); err != nil {
			return err
		}
	}
	return nil
}

func (r *Runtime) sendStatus() error {
	frame := &protocol.Envelope{MessageType: protocol.MessageType_RUNTIME_STATUS, EnvironmentId: stringPtr(r.options.EnvironmentID), Payload: &protocol.Envelope_RuntimeStatus{RuntimeStatus: &protocol.RuntimeStatus{RuntimeStatus: r.runtimeSnapshot()}}}
	return r.sendBound(frame, false)
}

func (r *Runtime) sendBound(e *protocol.Envelope, readyRequired bool) error {
	var err error
	if readyRequired {
		err = r.gate.RequireReady()
	} else {
		err = r.gate.RequireBound()
	}
	if err != nil {
		return err
	}
	e.EnvironmentId = stringPtr(r.options.EnvironmentID)
	return r.sendFrame(e)
}

func (r *Runtime) sendFrame(e *protocol.Envelope) error {
	r.sendMu.Lock()
	defer r.sendMu.Unlock()
	r.mu.RLock()
	ctx, active := r.sendCtx, r.connection
	r.mu.RUnlock()
	if active == nil {
		return errControlDisconnected
	}
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	return r.writeControlFrame(ctx, active, e)
}

// writeControlFrame writes to the selected generation while sendMu is held.
func (r *Runtime) writeControlFrame(ctx context.Context, active *activeConnection, e *protocol.Envelope) error {
	if err := active.connection.Send(ctx, e); err != nil {
		r.dropConnection(active)
		return err
	}
	if e.MessageType == protocol.MessageType_READY {
		active.sentReady = true
	}
	return nil
}
