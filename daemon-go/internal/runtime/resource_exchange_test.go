package runtime

import (
	"context"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// TestRuntimeCorrelatesResourceTickets verifies upload-ticket responses are matched to both invocation and transfer.
func TestRuntimeCorrelatesResourceTickets(t *testing.T) {
	connection := newTestConnection()
	r, err := NewRuntime(RuntimeOptions{
		EnvironmentID: "env", Connections: fixedConnectionFactory{connection: connection},
		ApplyConfig: func(context.Context, int64, *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	r.attachConnection(connection)
	if err := r.gate.Bind("env"); err != nil {
		t.Fatal(err)
	}
	r.gate.MarkReady()
	invocationID := "123e4567-e89b-42d3-a456-426614174000"
	transferID := "123e4567-e89b-42d3-a456-426614174001"
	requestDone := make(chan error, 1)
	go func() {
		_, err := r.RequestTicket(context.Background(), invocationID, &protocol.ResourceUploadRequest{TransferId: stringPtr(transferID)})
		requestDone <- err
	}()
	deadline := time.After(time.Second)
	for !connection.sent(protocol.MessageType_RESOURCE_UPLOAD_REQUEST) {
		select {
		case <-deadline:
			t.Fatal("upload ticket request was not sent")
		default:
			time.Sleep(time.Millisecond)
		}
	}
	response := &protocol.Envelope{
		MessageType:  protocol.MessageType_RESOURCE_UPLOAD_TICKET,
		InvocationId: stringPtr(invocationID),
		Payload:      &protocol.Envelope_ResourceUploadTicket{ResourceUploadTicket: &protocol.ResourceUploadTicket{TransferId: stringPtr(transferID)}},
	}
	wrongCorrelation := &protocol.Envelope{
		MessageType:  protocol.MessageType_RESOURCE_UPLOAD_TICKET,
		InvocationId: stringPtr("123e4567-e89b-42d3-a456-426614174002"),
		Payload:      &protocol.Envelope_ResourceUploadTicket{ResourceUploadTicket: &protocol.ResourceUploadTicket{TransferId: stringPtr(transferID)}},
	}
	if err := r.routeResourceTicket(wrongCorrelation); err == nil {
		t.Fatal("ticket for another invocation was accepted")
	}
	if err := r.routeResourceTicket(response); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-requestDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("correlated upload ticket did not release the request")
	}
}
