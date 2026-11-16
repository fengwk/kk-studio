package runtime

import (
	"context"
	"errors"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

func (r *Runtime) runtimeSnapshot() *protocol.RuntimeSnapshot {
	body := &protocol.RuntimeBody{DaemonVersion: stringPtr(r.options.DaemonVersion)}
	r.mu.RLock()
	ready, snapshot := r.ready, r.config
	r.mu.RUnlock()
	configurationStatus := &protocol.ConfigurationStatus{}
	if ready {
		configurationStatus.AppliedVersion = int64Ptr(snapshot.ConfigVersion)
		configurationStatus.ProxyUrl = snapshot.ProxyURL
	}
	if failure, ok := r.status.ConfigurationError(); ok {
		configurationStatus.Error = &protocol.ConfigurationError{Version: int64Ptr(failure.Version), Code: failure.Code}
		if failure.Field != "" {
			configurationStatus.Error.Field = stringPtr(failure.Field)
		}
	}
	if ready || configurationStatus.Error != nil {
		body.Configuration = configurationStatus
	}
	if progress, ok := r.status.Progress(); ok {
		body.Update = &protocol.UpdateProgress{TargetVersion: stringPtr(progress.TargetVersion), DownloadedBytes: int64Ptr(progress.DownloadedBytes), TotalBytes: progress.TotalBytes}
	}
	return &protocol.RuntimeSnapshot{Revision: int64Ptr(r.status.Revision()), Status: body}
}

func (r *Runtime) sendReady() error {
	if err := r.sendFrame(&protocol.Envelope{MessageType: protocol.MessageType_READY, EnvironmentId: stringPtr(r.options.EnvironmentID), Payload: &protocol.Envelope_Ready{Ready: &protocol.Ready{DaemonVersion: stringPtr(r.options.DaemonVersion), HostInfo: r.hostInfo, RuntimeStatus: r.runtimeSnapshot()}}}); err != nil {
		return err
	}
	r.admissionMu.Lock()
	r.admissionPaused = false
	r.configReconnectPending = false
	r.candidateSuspension = nil
	r.admissionMu.Unlock()
	return r.resendUpdateReceipt()
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
		return errors.New("control connection is disconnected")
	}
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := active.connection.Send(ctx, e); err != nil {
		r.dropConnection(active)
		return err
	}
	return nil
}
