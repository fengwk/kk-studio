package runtime

import (
	"context"
	"errors"
	"fmt"
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
	if err := r.routeResourceTicket(r.connection, wrongCorrelation); err == nil {
		t.Fatal("ticket for another invocation was accepted")
	}
	if err := r.routeResourceTicket(r.connection, response); err != nil {
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

// A timed-out request can still get responses, including while a same-key retry is sending.
func TestRuntimeResourceTicketLateAndRepeated(t *testing.T) {
	connection := &resourceTestConnection{testConnection: newTestConnection(), sends: make(chan struct{}), results: make(chan error)}
	r := newResourceTestRuntime(t, connection)
	const invocation, transfer = "invocation", "123e4567-e89b-42d3-a456-426614174001"
	response := testDownloadReply(invocation, transfer)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	first := requestTestDownload(r, ctx, invocation, transfer)
	awaitResourceTest(t, connection.sends)
	cancel()
	connection.results <- nil
	if err := awaitResourceTest(t, first); !errors.Is(err, context.Canceled) {
		t.Fatalf("first request = %v, want canceled", err)
	}
	if err := r.routeResourceTicket(r.connection, response); err != nil {
		t.Fatalf("late reply: %v", err)
	}

	retry := requestTestDownload(r, context.Background(), invocation, transfer)
	awaitResourceTest(t, connection.sends)
	// The peer may respond before Send returns, and there is no attempt ID on the wire.
	for range 2 {
		if err := r.routeResourceTicket(r.connection, response); err != nil {
			t.Fatalf("retry reply: %v", err)
		}
	}
	connection.results <- nil
	if err := awaitResourceTest(t, retry); err != nil {
		t.Fatal(err)
	}
	// Exercise the receive loop: if duplicates still ended the session, the next transfer's
	// reply would never reach its waiter.
	serveCtx, stop := context.WithCancel(context.Background())
	defer stop()
	active := r.connection
	served := make(chan error, 1)
	go func() { served <- r.serveConnection(serveCtx, active) }()
	connection.in <- response
	const nextTransfer = "123e4567-e89b-42d3-a456-426614174002"
	followUp := requestTestDownload(r, context.Background(), invocation, nextTransfer)
	awaitResourceTest(t, connection.sends)
	connection.in <- testDownloadReply(invocation, nextTransfer)
	connection.results <- nil
	if err := awaitResourceTest(t, followUp); err != nil {
		t.Fatal(err)
	}
	stop()
	if err := awaitResourceTest(t, served); !errors.Is(err, context.Canceled) {
		t.Fatalf("receive loop ended before cancellation: %v", err)
	}
}

// Only sent exchanges leave history, and neither pending nor finished exchanges cross generations.
func TestRuntimeResourceTicketGenerationAndSendErrors(t *testing.T) {
	connection := newTestConnection()
	r := newResourceTestRuntime(t, connection)
	old := r.connection
	const invocation, transfer = "invocation", "123e4567-e89b-42d3-a456-426614174001"
	response := testDownloadReply(invocation, transfer)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := r.RequestResourceDownloadTicket(ctx, invocation, transfer); !errors.Is(err, context.Canceled) {
		t.Fatalf("pre-canceled request: %v", err)
	}
	if connection.sent(protocol.MessageType_RESOURCE_DOWNLOAD_REQUEST) || r.routeResourceTicket(old, response) == nil {
		t.Fatal("pre-canceled request was sent or left reply history")
	}

	next := &resourceTestConnection{testConnection: newTestConnection(), sends: make(chan struct{}), results: make(chan error)}
	if err := r.dropConnection(old); err != nil {
		t.Fatal(err)
	}
	active := attachReadyResourceTestConnection(t, r, next)
	failed := requestTestDownload(r, context.Background(), invocation, transfer)
	awaitResourceTest(t, next.sends)
	writeErr := errors.New("fake control write failed")
	next.results <- writeErr
	if err := awaitResourceTest(t, failed); !errors.Is(err, writeErr) || r.isActiveConnection(active) {
		t.Fatalf("control write failure did not retire generation: %v", err)
	}

	next = &resourceTestConnection{testConnection: newTestConnection(), sends: make(chan struct{}), results: make(chan error)}
	active = attachReadyResourceTestConnection(t, r, next)
	finished := requestTestDownload(r, context.Background(), invocation, transfer)
	awaitResourceTest(t, next.sends)
	if err := r.routeResourceTicket(active, response); err != nil {
		t.Fatal(err)
	}
	next.results <- nil
	if err := awaitResourceTest(t, finished); err != nil {
		t.Fatal(err)
	}
	pending := requestTestDownload(r, context.Background(), invocation, transfer)
	awaitResourceTest(t, next.sends)
	next.results <- nil
	if err := r.dropConnection(active); err != nil {
		t.Fatal(err)
	}
	if err := awaitResourceTest(t, pending); !errors.Is(err, errControlDisconnected) {
		t.Fatalf("retired generation waiter: %v", err)
	}
	current := attachReadyResourceTestConnection(t, r, newTestConnection())
	if r.routeResourceTicket(active, response) == nil || r.routeResourceTicket(current, response) == nil {
		t.Fatal("retired generation's exchange was accepted")
	}
}

// The history is bounded by distinct keys and never matches a different response type or payload.
func TestRuntimeResourceTicketHistoryBound(t *testing.T) {
	r := newResourceTestRuntime(t, newTestConnection())
	active := r.connection
	const invocation = "invocation"
	transfer := func(i int) string { return fmt.Sprintf("123e4567-e89b-42d3-a456-%012d", i) }
	for i := 0; i <= maxRecentResourceExchanges; i++ {
		active.resources.remember(resourceExchangeKey{protocol.MessageType_RESOURCE_DOWNLOAD_TICKET, invocation, transfer(i)})
	}
	if len(active.resources.recent) != maxRecentResourceExchanges || r.routeResourceTicket(active, testDownloadReply(invocation, transfer(0))) == nil {
		t.Fatal("oldest key was not evicted")
	}
	latest := testDownloadReply(invocation, transfer(maxRecentResourceExchanges))
	if err := r.routeResourceTicket(active, latest); err != nil {
		t.Fatal(err)
	}
	latest.MessageType = protocol.MessageType_RESOURCE_UPLOAD_TICKET
	if r.routeResourceTicket(active, latest) == nil {
		t.Fatal("mismatched payload accepted")
	}
	latest.Payload = &protocol.Envelope_ResourceUploadTicket{ResourceUploadTicket: &protocol.ResourceUploadTicket{TransferId: stringPtr(transfer(maxRecentResourceExchanges))}}
	if r.routeResourceTicket(active, latest) == nil {
		t.Fatal("unrelated response type accepted")
	}
}

func testDownloadReply(invocation, transfer string) *protocol.Envelope {
	return &protocol.Envelope{
		MessageType: protocol.MessageType_RESOURCE_DOWNLOAD_TICKET, InvocationId: stringPtr(invocation),
		Payload: &protocol.Envelope_ResourceDownloadTicket{ResourceDownloadTicket: &protocol.ResourceDownloadTicket{
			TransferId: stringPtr(transfer), State: protocol.TicketState_TICKET_FAILED, Message: stringPtr("test ticket failure"),
		}},
	}
}

func newResourceTestRuntime(t *testing.T, connection Connection) *Runtime {
	t.Helper()
	r, err := NewRuntime(RuntimeOptions{
		EnvironmentID: "env", Connections: fixedConnectionFactory{connection: connection},
		ApplyConfig: func(context.Context, int64, *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	attachReadyResourceTestConnection(t, r, connection)
	t.Cleanup(func() { _ = r.closeActiveConnection() })
	return r
}

func attachReadyResourceTestConnection(t *testing.T, r *Runtime, connection Connection) *activeConnection {
	t.Helper()
	active := r.attachConnection(connection)
	if err := r.gate.Bind("env"); err != nil {
		t.Fatal(err)
	}
	r.gate.MarkReady()
	r.mu.Lock()
	r.bound, r.ready = true, true
	r.mu.Unlock()
	return active
}

type resourceTestConnection struct {
	*testConnection
	sends   chan struct{}
	results chan error
}

func (c *resourceTestConnection) Send(ctx context.Context, _ *protocol.Envelope) error {
	select {
	case c.sends <- struct{}{}:
	case <-c.closed:
		return errors.New("connection closed")
	case <-ctx.Done():
		return ctx.Err()
	}
	select {
	case err := <-c.results:
		return err
	case <-c.closed:
		return errors.New("connection closed")
	case <-ctx.Done():
		return ctx.Err()
	}
}

func requestTestDownload(r *Runtime, ctx context.Context, invocation, transfer string) <-chan error {
	done := make(chan error, 1)
	go func() {
		_, err := r.RequestResourceDownloadTicket(ctx, invocation, transfer)
		done <- err
	}()
	return done
}

func awaitResourceTest[T any](t *testing.T, result <-chan T) T {
	t.Helper()
	select {
	case value := <-result:
		return value
	case <-time.After(time.Second):
		t.Fatal("resource exchange did not advance")
		var zero T
		return zero
	}
}
