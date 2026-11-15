package runtime

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

func (r *Runtime) handleWithIDEContext(ctx, ideCtx context.Context, e *protocol.Envelope) error {
	if e.MessageType == protocol.MessageType_SETTINGS {
		return r.handleSettings(e)
	}
	ready, err := r.admitEnvelope(e)
	if err != nil {
		return err
	}
	switch e.MessageType {
	case protocol.MessageType_ERROR:
		return serverRejection(e)
	case protocol.MessageType_ENVIRONMENT_CONFIG:
		return r.handleEnvironmentConfig(ctx, e)
	case protocol.MessageType_INVOKE:
		if !ready {
			return r.sendBoundError(e.InvocationId, "INVOKE requires a READY bound connection")
		}
		return r.handleInvoke(ctx, e)
	case protocol.MessageType_CANCEL:
		return r.handleCancel(e)
	case protocol.MessageType_RECOVER:
		return r.handleRecover(e)
	case protocol.MessageType_UPDATE:
		if !ready || e.EnvironmentId == nil || *e.EnvironmentId == "" {
			return r.sendBoundError(e.InvocationId, "UPDATE requires a READY bound connection")
		}
		return r.handleUpdate(ctx, e)
	case protocol.MessageType_RESOURCE_UPLOAD_TICKET, protocol.MessageType_RESOURCE_DOWNLOAD_TICKET:
		if !ready {
			return errors.New("resource ticket requires a READY bound connection")
		}
		return r.routeResourceTicket(e)
	case protocol.MessageType_IDE_COMMAND:
		if !ready {
			return r.sendBoundError(e.InvocationId, "IDE command requires a READY bound connection")
		}
		return r.handleIDECommand(ideCtx, e)
	case protocol.MessageType_IDE_LEASE:
		if !ready || r.options.IDE == nil {
			return errors.New("IDE lease requires a READY bound connection")
		}
		return r.handleIDELease(e)
	case protocol.MessageType_TEMPORARY_RESOURCE_POLICY:
		if !ready {
			return r.sendBoundError(e.InvocationId, "TEMPORARY_RESOURCE_POLICY requires a READY bound connection")
		}
		return r.handleTemporaryResourcePolicy(e)
	case protocol.MessageType_READY:
		if err := r.sendBoundError(nil, "daemon must not receive READY from server"); err != nil {
			return err
		}
		return fmt.Errorf("daemon must not receive READY from server: %w", ErrForbiddenInboundFrame)
	default:
		return r.sendBoundError(e.InvocationId, "unsupported inbound message type")
	}
}

func (r *Runtime) handleIDECommand(ctx context.Context, e *protocol.Envelope) error {
	request, ok := e.Payload.(*protocol.Envelope_IdeCommand)
	if !ok || request.IdeCommand == nil {
		return errors.New("IDE command payload is invalid")
	}
	r.admissionMu.Lock()
	if r.admissionPaused {
		r.admissionMu.Unlock()
		return r.sendBoundError(e.InvocationId, "environment configuration is switching; new work is not admitted")
	}
	err := r.acceptIDECommand(ctx, e.GetInvocationId(), request.IdeCommand)
	r.admissionMu.Unlock()
	return err
}

func (r *Runtime) handleIDELease(e *protocol.Envelope) error {
	r.admissionMu.Lock()
	if r.admissionPaused {
		r.admissionMu.Unlock()
		return r.sendBoundError(nil, "environment configuration is switching; new work is not admitted")
	}
	if r.updateIsHeld() {
		r.admissionMu.Unlock()
		return errors.New("IDE_LEASE is not allowed while environment update admission is held")
	}
	request, ok := e.Payload.(*protocol.Envelope_IdeLease)
	if !ok || request.IdeLease == nil {
		r.admissionMu.Unlock()
		return errors.New("IDE lease payload is invalid")
	}
	roundStartedAt, err := r.consumeIDELease(request.IdeLease, time.Now())
	if err == nil {
		r.options.IDE.ApplyLease(request.IdeLease, roundStartedAt)
	}
	r.admissionMu.Unlock()
	return err
}

// serverRejection reports the reason the control server closed the connection, so the daemon log
// names the rejected field instead of hiding it.
func serverRejection(e *protocol.Envelope) error {
	message := e.GetError().GetMessage()
	if message == "" {
		return errors.New("control server rejected the connection")
	}
	return fmt.Errorf("control server rejected the connection: %s", message)
}

// admitEnvelope applies the scope gates every control message after SETTINGS must pass, and reports
// whether the environment scope has already published a READY configuration.
func (r *Runtime) admitEnvelope(e *protocol.Envelope) (bool, error) {
	r.mu.RLock()
	bound, ready := r.bound, r.ready
	r.mu.RUnlock()
	if !bound {
		return false, errors.New("environment scope is not bound")
	}
	if e.EnvironmentId != nil && *e.EnvironmentId != r.options.EnvironmentID {
		return false, errors.New("envelope is bound to a different environment")
	}
	return ready, nil
}
