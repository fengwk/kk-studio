package runtime

import (
	"container/list"
	"context"
	"errors"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

type resourceExchangeKey struct {
	messageType  protocol.MessageType
	invocationID string
	transferID   string
}

const maxRecentResourceExchanges = 256

// resourceExchanges belongs to one connection generation and is guarded by Runtime.mu.
type resourceExchanges struct {
	pending map[resourceExchangeKey]*pendingResourceExchange
	recent  map[resourceExchangeKey]*list.Element
	order   list.List
}

type pendingResourceExchange struct {
	response  chan *protocol.Envelope
	delivered bool
}

func (s *resourceExchanges) remember(key resourceExchangeKey) {
	if entry := s.recent[key]; entry != nil {
		s.order.MoveToBack(entry)
		return
	}
	if s.recent == nil {
		s.recent = make(map[resourceExchangeKey]*list.Element)
	}
	if len(s.recent) == maxRecentResourceExchanges {
		oldest := s.order.Front()
		delete(s.recent, s.order.Remove(oldest).(resourceExchangeKey))
	}
	s.recent[key] = s.order.PushBack(key)
}

type resourceUploadFailure struct {
	cleanupConfirmed bool
}

func (e *resourceUploadFailure) Error() string { return "resource upload failed" }

// RequestTicket requests one binary upload ticket and waits for its correlated control response.
func (r *Runtime) RequestTicket(ctx context.Context, invocationID string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
	if request == nil {
		return nil, errors.New("resource upload request is required")
	}
	envelope := &protocol.Envelope{MessageType: protocol.MessageType_RESOURCE_UPLOAD_REQUEST, InvocationId: stringPtr(invocationID), Payload: &protocol.Envelope_ResourceUploadRequest{ResourceUploadRequest: request}}
	response, err := r.resourceExchange(ctx, protocol.MessageType_RESOURCE_UPLOAD_TICKET, invocationID, request.GetTransferId(), envelope)
	if err != nil {
		return nil, err
	}
	return response.GetResourceUploadTicket(), nil
}

// Commit requests publication of one uploaded resource and waits for its correlated ticket.
func (r *Runtime) Commit(ctx context.Context, invocationID, transferID, uploadID string) (*protocol.ResourceUploadTicket, error) {
	envelope := &protocol.Envelope{MessageType: protocol.MessageType_RESOURCE_UPLOAD_COMMIT, InvocationId: stringPtr(invocationID), Payload: &protocol.Envelope_ResourceUploadCommit{ResourceUploadCommit: &protocol.ResourceUploadCommit{TransferId: stringPtr(transferID), UploadId: stringPtr(uploadID)}}}
	response, err := r.resourceExchange(ctx, protocol.MessageType_RESOURCE_UPLOAD_TICKET, invocationID, transferID, envelope)
	if err != nil {
		return nil, err
	}
	return response.GetResourceUploadTicket(), nil
}

// RequestResourceDownloadTicket requests a skill or other invocation-bound resource ticket.
func (r *Runtime) RequestResourceDownloadTicket(ctx context.Context, invocationID, transferID string) (*protocol.ResourceDownloadTicket, error) {
	envelope := &protocol.Envelope{MessageType: protocol.MessageType_RESOURCE_DOWNLOAD_REQUEST, InvocationId: stringPtr(invocationID), Payload: &protocol.Envelope_ResourceDownloadRequest{ResourceDownloadRequest: &protocol.ResourceDownloadRequest{TransferId: stringPtr(transferID)}}}
	response, err := r.resourceExchange(ctx, protocol.MessageType_RESOURCE_DOWNLOAD_TICKET, invocationID, transferID, envelope)
	if err != nil {
		return nil, err
	}
	return response.GetResourceDownloadTicket(), nil
}

func (r *Runtime) resourceExchange(ctx context.Context, responseType protocol.MessageType, invocationID, transferID string, request *protocol.Envelope) (*protocol.Envelope, error) {
	key := resourceExchangeKey{messageType: responseType, invocationID: invocationID, transferID: transferID}
	r.mu.RLock()
	active := r.connection
	r.mu.RUnlock()
	pending := &pendingResourceExchange{response: make(chan *protocol.Envelope, 1)}
	if err := r.sendResourceRequest(ctx, active, key, pending, request); err != nil {
		return nil, err
	}
	// Only successful sends can leave history. Replace pending with history atomically so the
	// receiver never sees a gap, even when cancellation and a reply arrive together.
	defer func() {
		r.mu.Lock()
		defer r.mu.Unlock()
		if r.connection == active {
			delete(active.resources.pending, key)
			active.resources.remember(key)
		}
	}()
	select {
	case envelope := <-pending.response:
		return envelope, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-active.done:
		return nil, errControlDisconnected
	}
}

func (r *Runtime) sendResourceRequest(ctx context.Context, active *activeConnection, key resourceExchangeKey, pending *pendingResourceExchange, request *protocol.Envelope) error {
	r.sendMu.Lock()
	defer r.sendMu.Unlock()
	sendCtx, err := r.registerResourceRequest(ctx, active, key, pending)
	if err != nil {
		return err
	}
	request.EnvironmentId = stringPtr(r.options.EnvironmentID)
	// Use the runtime's send context, not the ticket wait context: canceling a waiter must not
	// interrupt a control frame write. A transport write error retires this generation.
	return r.writeControlFrame(sendCtx, active, request)
}

func (r *Runtime) registerResourceRequest(ctx context.Context, active *activeConnection, key resourceExchangeKey, pending *pendingResourceExchange) (context.Context, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if active == nil || r.connection != active {
		return nil, errControlDisconnected
	}
	if err := r.gate.RequireReady(); err != nil {
		return nil, err
	}
	sendCtx := r.sendCtx
	if sendCtx == nil {
		sendCtx = context.Background()
	}
	if err := sendCtx.Err(); err != nil {
		return nil, err
	}
	if active.resources.pending[key] != nil {
		return nil, errors.New("duplicate resource transfer request")
	}
	if active.resources.pending == nil {
		active.resources.pending = make(map[resourceExchangeKey]*pendingResourceExchange)
	}
	// Register only at the send boundary, never while queued behind another control write.
	active.resources.pending[key] = pending
	return sendCtx, nil
}

func (r *Runtime) routeResourceTicket(active *activeConnection, envelope *protocol.Envelope) error {
	var transferID string
	switch envelope.MessageType {
	case protocol.MessageType_RESOURCE_UPLOAD_TICKET:
		transferID = envelope.GetResourceUploadTicket().GetTransferId()
	case protocol.MessageType_RESOURCE_DOWNLOAD_TICKET:
		transferID = envelope.GetResourceDownloadTicket().GetTransferId()
	}
	if transferID == "" || envelope.GetInvocationId() == "" {
		return errors.New("resource ticket payload is invalid")
	}
	key := resourceExchangeKey{messageType: envelope.MessageType, invocationID: envelope.GetInvocationId(), transferID: transferID}
	r.mu.Lock()
	defer r.mu.Unlock()
	if active == nil || r.connection != active {
		return errControlDisconnected
	}
	// Once a same-key retry starts writing, the wire format cannot distinguish its response
	// from an earlier attempt's. Give the current waiter the first matching reply only.
	if pending := active.resources.pending[key]; pending != nil {
		if !pending.delivered {
			pending.delivered = true
			pending.response <- envelope
		}
		return nil
	}
	if active.resources.recent[key] != nil {
		return nil
	}
	return errors.New("unsolicited resource ticket")
}
