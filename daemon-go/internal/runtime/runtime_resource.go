package runtime

import (
	"context"
	"errors"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

type resourceExchangeKey struct {
	messageType  protocol.MessageType
	invocationID string
	transferID   string
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
	response := make(chan *protocol.Envelope, 1)
	r.resourceMu.Lock()
	if _, exists := r.resourceRequests[key]; exists {
		r.resourceMu.Unlock()
		return nil, errors.New("duplicate resource transfer request")
	}
	r.resourceRequests[key] = response
	r.resourceMu.Unlock()
	defer func() {
		r.resourceMu.Lock()
		if r.resourceRequests[key] == response {
			delete(r.resourceRequests, key)
		}
		r.resourceMu.Unlock()
	}()
	if err := r.sendBound(request, true); err != nil {
		return nil, err
	}
	select {
	case envelope := <-response:
		return envelope, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func (r *Runtime) routeResourceTicket(envelope *protocol.Envelope) error {
	var transferID string
	switch ticket := envelope.Payload.(type) {
	case *protocol.Envelope_ResourceUploadTicket:
		transferID = ticket.ResourceUploadTicket.GetTransferId()
	case *protocol.Envelope_ResourceDownloadTicket:
		transferID = ticket.ResourceDownloadTicket.GetTransferId()
	default:
		return errors.New("resource ticket payload is invalid")
	}
	key := resourceExchangeKey{messageType: envelope.MessageType, invocationID: envelope.GetInvocationId(), transferID: transferID}
	r.resourceMu.Lock()
	response := r.resourceRequests[key]
	if response != nil {
		delete(r.resourceRequests, key)
	}
	r.resourceMu.Unlock()
	if response == nil {
		return errors.New("unsolicited resource ticket")
	}
	response <- envelope
	return nil
}
