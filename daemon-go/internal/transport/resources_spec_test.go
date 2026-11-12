package transport

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	runtimeapi "github.com/fengwk/kk-studio/daemon/internal/runtime"
	"google.golang.org/protobuf/proto"
)

const specInvocationID = "123e4567-e89b-12d3-a456-426614174000"

// TRN-T-003 verifies a FAILED commit ends the transfer without another request or commit.
func TestUpload_FailedCommitIsTerminal(t *testing.T) {
	requestCount, commitCount := 0, 0
	control := uploadExchangeStub{
		request: func(_ context.Context, _ string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
			requestCount++
			ticket := uploadTicket(request.GetTransferId(), protocol.TicketState_TICKET_PENDING, testUploadID)
			ticket.PresignedPut = &protocol.PresignedPut{Method: proto.String(http.MethodPut), Url: proto.String("http://storage.example/upload")}
			return ticket, nil
		},
		commit: func(_ context.Context, _, transferID, uploadID string) (*protocol.ResourceUploadTicket, error) {
			commitCount++
			return uploadTicket(transferID, protocol.TicketState_TICKET_FAILED, uploadID), nil
		},
	}
	putCount := 0
	uploader := &ResourceUploader{
		Control: control,
		Client: &http.Client{Transport: uploadRoundTripper(func(*http.Request) (*http.Response, error) {
			putCount++
			return &http.Response{StatusCode: http.StatusNoContent, Body: io.NopCloser(strings.NewReader("")), Header: make(http.Header)}, nil
		})},
		RetryBackoff: time.Millisecond,
	}
	_, err := uploader.Upload(context.Background(), specInvocationID, uploadContent())
	var uploadErr *runtimeapi.ResourceUploadError
	if !errors.As(err, &uploadErr) || uploadErr.Stage != "commit" || uploadErr.Category != "ticket-failed" {
		t.Fatalf("commit error = %#v", err)
	}
	if putCount != 1 || requestCount != 1 || commitCount != 1 {
		t.Fatalf("request=%d commit=%d PUT=%d; want 1, 1, 1", requestCount, commitCount, putCount)
	}
}

// TRN-T-005 verifies a failed ticket does not expose its message or presigned data.
func TestUpload_FailedTicketRedactsPresignedData(t *testing.T) {
	control := uploadExchangeStub{
		request: func(_ context.Context, _ string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
			return &protocol.ResourceUploadTicket{
				TransferId: proto.String(request.GetTransferId()),
				State:      protocol.TicketState_TICKET_FAILED,
				Message:    proto.String("https://storage.example/object?signature=secret"),
				PresignedPut: &protocol.PresignedPut{
					Method:  proto.String(http.MethodPut),
					Url:     proto.String("https://storage.example/object?signature=secret"),
					Headers: map[string]string{"Authorization": "private-header"},
				},
			}, nil
		},
		commit: func(context.Context, string, string, string) (*protocol.ResourceUploadTicket, error) {
			t.Fatal("failed request ticket was committed")
			return nil, nil
		},
	}
	_, err := (&ResourceUploader{Control: control}).Upload(context.Background(), specInvocationID, uploadContent())
	if err == nil || strings.Contains(err.Error(), "storage.example") || strings.Contains(err.Error(), "secret") || strings.Contains(err.Error(), "private-header") {
		t.Fatalf("failed ticket error leaked presigned data: %v", err)
	}
}

// TRN-T-012 verifies cancellation interrupts a ticket wait.
func TestUpload_AbortedTicketWait(t *testing.T) {
	started := make(chan struct{})
	control := uploadExchangeStub{
		request: func(ctx context.Context, _ string, _ *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
			close(started)
			<-ctx.Done()
			return nil, ctx.Err()
		},
		commit: func(context.Context, string, string, string) (*protocol.ResourceUploadTicket, error) {
			t.Fatal("cancelled upload was committed")
			return nil, nil
		},
	}
	uploader := &ResourceUploader{Control: control, TicketWait: time.Second, RetryBackoff: time.Millisecond}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		_, err := uploader.Upload(ctx, specInvocationID, uploadContent())
		done <- err
	}()
	<-started
	cancel()
	select {
	case err := <-done:
		var uploadErr *runtimeapi.ResourceUploadError
		if !errors.As(err, &uploadErr) || uploadErr.Category != "cancelled" || !uploadErr.CleanupConfirmed {
			t.Fatalf("cancelled upload error = %#v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("cancelled upload did not return")
	}
}

// TRN-T-006 verifies an already-cancelled invocation fails without sending a request.
func TestUpload_AlreadyCancelledDoesNotRequestTicket(t *testing.T) {
	called := false
	control := uploadExchangeStub{
		request: func(context.Context, string, *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
			called = true
			return nil, errors.New("unexpected request")
		},
		commit: func(context.Context, string, string, string) (*protocol.ResourceUploadTicket, error) {
			t.Fatal("cancelled upload was committed")
			return nil, nil
		},
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := (&ResourceUploader{Control: control}).Upload(ctx, specInvocationID, uploadContent())
	var uploadErr *runtimeapi.ResourceUploadError
	if !errors.As(err, &uploadErr) || uploadErr.Category != "cancelled" || !uploadErr.CleanupConfirmed {
		t.Fatalf("cancelled upload error = %#v", err)
	}
	if called {
		t.Fatal("cancelled invocation sent a control request")
	}
}

// TRN-T-010 verifies zero-valued timing options select the Go defaults.
func TestUpload_ZeroTimingOptionsUseDefaults(t *testing.T) {
	ticketWait, retryBackoff, err := (&ResourceUploader{}).retryOptions()
	if err != nil {
		t.Fatal(err)
	}
	if ticketWait != 30*time.Second || retryBackoff != 200*time.Millisecond {
		t.Fatalf("default timings = %s / %s", ticketWait, retryBackoff)
	}
}

// TRN-T-013 verifies a timed-out request retries with the original transfer ID.
func TestUpload_TicketTimeoutRetriesTransfer(t *testing.T) {
	var transferIDs []string
	requestCount := 0
	control := uploadExchangeStub{
		request: func(ctx context.Context, _ string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
			requestCount++
			transferIDs = append(transferIDs, request.GetTransferId())
			if requestCount == 1 {
				<-ctx.Done()
				return nil, ctx.Err()
			}
			return uploadTicket(request.GetTransferId(), protocol.TicketState_TICKET_READY, testUploadID), nil
		},
		commit: func(context.Context, string, string, string) (*protocol.ResourceUploadTicket, error) {
			t.Fatal("READY request ticket needed no commit")
			return nil, nil
		},
	}
	uploader := &ResourceUploader{Control: control, TicketWait: 10 * time.Millisecond, RetryBackoff: time.Millisecond}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := uploader.Upload(ctx, specInvocationID, uploadContent()); err != nil {
		t.Fatal(err)
	}
	if requestCount != 2 || len(transferIDs) != 2 || transferIDs[0] == "" || transferIDs[0] != transferIDs[1] {
		t.Fatalf("request count=%d transfer IDs=%v", requestCount, transferIDs)
	}
}

// TRN-T-015 verifies a pending commit is retried without repeating the PUT.
func TestUpload_PendingCommitRetriesWithoutPut(t *testing.T) {
	requestCount, commitCount, putCount := 0, 0, 0
	control := uploadExchangeStub{
		request: func(_ context.Context, _ string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
			requestCount++
			ticket := uploadTicket(request.GetTransferId(), protocol.TicketState_TICKET_PENDING, testUploadID)
			ticket.PresignedPut = &protocol.PresignedPut{Method: proto.String(http.MethodPut), Url: proto.String("http://storage.example/upload")}
			return ticket, nil
		},
		commit: func(_ context.Context, _, transferID, uploadID string) (*protocol.ResourceUploadTicket, error) {
			commitCount++
			state := protocol.TicketState_TICKET_PENDING
			if commitCount == 2 {
				state = protocol.TicketState_TICKET_READY
			}
			return uploadTicket(transferID, state, uploadID), nil
		},
	}
	uploader := &ResourceUploader{
		Control: control,
		Client: &http.Client{Transport: uploadRoundTripper(func(*http.Request) (*http.Response, error) {
			putCount++
			return &http.Response{StatusCode: http.StatusNoContent, Body: io.NopCloser(strings.NewReader("")), Header: make(http.Header)}, nil
		})},
		RetryBackoff: time.Millisecond,
	}
	if _, err := uploader.Upload(context.Background(), specInvocationID, uploadContent()); err != nil {
		t.Fatal(err)
	}
	if requestCount != 1 || commitCount != 2 || putCount != 1 {
		t.Fatalf("request=%d commit=%d PUT=%d; want 1, 2, 1", requestCount, commitCount, putCount)
	}
}
