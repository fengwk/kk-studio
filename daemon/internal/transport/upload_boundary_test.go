package transport

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	runtimeapi "github.com/fengwk/kk-studio/daemon/internal/runtime"
	"google.golang.org/protobuf/proto"
)

const uploadBoundaryInvocationID = "123e4567-e89b-12d3-a456-426614174000"

// Preserves a contract error that confirms cleanup.
func TestUploadBoundary_Confirmed(t *testing.T) {
	want := &runtimeapi.ResourceUploadError{Stage: "put", Category: "storage-rejected", CleanupConfirmed: true}
	if got := normalizeUploadError(want); got != want {
		t.Fatalf("normalized error = %#v, want original contract error", got)
	}
}

// Preserves a contract error when cleanup is unknown.
func TestUploadBoundary_Unknown(t *testing.T) {
	want := &runtimeapi.ResourceUploadError{Stage: "put", Category: "cleanup-failed", CleanupConfirmed: false}
	if got := normalizeUploadError(want); got != want {
		t.Fatalf("normalized error = %#v, want original contract error", got)
	}
}

// Converts an off-contract error into a conservative boundary violation.
func TestUploadBoundary_OffContract(t *testing.T) {
	cause := errors.New("private uploader detail")
	got := normalizeUploadError(cause)
	var uploadErr *runtimeapi.ResourceUploadError
	if !errors.As(got, &uploadErr) || uploadErr.Stage != "internal" || uploadErr.Category != "boundary-violation" || uploadErr.CleanupConfirmed || !errors.Is(got, cause) {
		t.Fatalf("normalized error = %#v, want unconfirmed internal boundary violation", got)
	}
}

func TestUploadBoundary_ExpiredTicketRefreshesSameBinding(t *testing.T) {
	requestCount, putCount, commitCount := 0, 0, 0
	var transferIDs []string
	control := uploadExchangeStub{
		request: func(_ context.Context, _ string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
			requestCount++
			transferIDs = append(transferIDs, request.GetTransferId())
			ticket := uploadTicket(request.GetTransferId(), protocol.TicketState_TICKET_PENDING, testUploadID)
			expires := time.Now().Add(time.Hour).UnixMilli()
			url := "http://storage.example/renewed"
			if requestCount == 1 {
				expires = time.Now().Add(-time.Second).UnixMilli()
				url = "http://storage.example/expired"
			}
			ticket.PresignedPut = &protocol.PresignedPut{Method: proto.String(http.MethodPut), Url: proto.String(url), ExpiresAtEpochMs: &expires}
			return ticket, nil
		},
		commit: func(_ context.Context, _, transferID, uploadID string) (*protocol.ResourceUploadTicket, error) {
			commitCount++
			return uploadTicket(transferID, protocol.TicketState_TICKET_READY, uploadID), nil
		},
	}
	client := &http.Client{Transport: uploadRoundTripper(func(request *http.Request) (*http.Response, error) {
		putCount++
		if request.URL.Path != "/renewed" {
			t.Fatalf("PUT used stale ticket %q", request.URL.Path)
		}
		return &http.Response{StatusCode: http.StatusNoContent, Body: io.NopCloser(strings.NewReader("")), Header: make(http.Header)}, nil
	})}
	uploader := &ResourceUploader{Control: control, Client: client, RetryBackoff: time.Millisecond}
	if _, err := uploader.Upload(context.Background(), uploadBoundaryInvocationID, uploadContent()); err != nil {
		t.Fatal(err)
	}
	if requestCount != 2 || putCount != 1 || commitCount != 1 || len(transferIDs) != 2 || transferIDs[0] != transferIDs[1] {
		t.Fatalf("request=%d PUT=%d commit=%d transfers=%v", requestCount, putCount, commitCount, transferIDs)
	}
}

// A real HTTP 412 through the production network runtime must never publish bytes without a READY commit.
func TestUploadBoundary_PreconditionFailureRequiresCommit(t *testing.T) {
	for _, tc := range []struct {
		state    protocol.TicketState
		category string
		attempts int
	}{
		{protocol.TicketState_TICKET_READY, "", 1},
		{protocol.TicketState_TICKET_FAILED, "ticket-failed", 1},
		{protocol.TicketState_TICKET_PENDING, "retry-exhausted", maxUploadAttempts},
	} {
		t.Run(tc.state.String(), func(t *testing.T) {
			var putCount atomic.Int32
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method == http.MethodPut {
					putCount.Add(1)
				}
				w.WriteHeader(http.StatusPreconditionFailed)
			}))
			defer server.Close()
			commitCount := 0
			control := uploadExchangeStub{
				request: func(_ context.Context, _ string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
					ticket := uploadTicket(request.GetTransferId(), protocol.TicketState_TICKET_PENDING, testUploadID)
					ticket.PresignedPut = &protocol.PresignedPut{Method: proto.String(http.MethodPut), Url: proto.String(server.URL), ExpiresAtEpochMs: futureUploadExpiry()}
					return ticket, nil
				},
				commit: func(_ context.Context, _, transferID, uploadID string) (*protocol.ResourceUploadTicket, error) {
					commitCount++
					if tc.state == protocol.TicketState_TICKET_FAILED {
						uploadID = ""
					}
					return uploadTicket(transferID, tc.state, uploadID), nil
				},
			}
			uploader := &ResourceUploader{Control: control, Network: NewNetworkRuntime(nil), RetryBackoff: time.Millisecond}
			got, err := uploader.Upload(context.Background(), uploadBoundaryInvocationID, uploadContent())
			if tc.category == "" {
				if err != nil || got.UploadID != testUploadID {
					t.Fatalf("upload=%+v error=%v", got, err)
				}
			} else {
				var uploadErr *runtimeapi.ResourceUploadError
				if !errors.As(err, &uploadErr) || uploadErr.Stage != "commit" || uploadErr.Category != tc.category || got.UploadID != "" {
					t.Fatalf("upload=%+v error=%v", got, err)
				}
			}
			if putCount.Load() != 1 || commitCount != tc.attempts {
				t.Fatalf("PUT=%d commit=%d; expected one PUT and %d commits", putCount.Load(), commitCount, tc.attempts)
			}
		})
	}
}

func TestUploadBoundary_TemporaryHTTPRetryIsBounded(t *testing.T) {
	requestCount, putCount, commitCount := 0, 0, 0
	control := uploadExchangeStub{
		request: func(_ context.Context, _ string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
			requestCount++
			ticket := uploadTicket(request.GetTransferId(), protocol.TicketState_TICKET_PENDING, testUploadID)
			ticket.PresignedPut = &protocol.PresignedPut{Method: proto.String(http.MethodPut), Url: proto.String("http://storage.example/file"), ExpiresAtEpochMs: futureUploadExpiry()}
			return ticket, nil
		},
		commit: func(context.Context, string, string, string) (*protocol.ResourceUploadTicket, error) {
			commitCount++
			return nil, nil
		},
	}
	client := &http.Client{Transport: uploadRoundTripper(func(*http.Request) (*http.Response, error) {
		putCount++
		return &http.Response{StatusCode: http.StatusInternalServerError, Body: io.NopCloser(strings.NewReader("")), Header: make(http.Header)}, nil
	})}
	_, err := (&ResourceUploader{Control: control, Client: client, RetryBackoff: time.Millisecond}).Upload(context.Background(), uploadBoundaryInvocationID, uploadContent())
	var uploadErr *runtimeapi.ResourceUploadError
	if !errors.As(err, &uploadErr) || uploadErr.Category != "retry-exhausted" || uploadErr.CleanupConfirmed {
		t.Fatalf("exhausted transient error=%#v", err)
	}
	if requestCount != maxUploadAttempts || putCount != maxUploadAttempts || commitCount != 0 {
		t.Fatalf("request=%d PUT=%d commit=%d; attempts=%d", requestCount, putCount, commitCount, maxUploadAttempts)
	}
}

func TestUploadBoundary_PermanentHTTPFailuresAreNotReplayed(t *testing.T) {
	for _, status := range []int{http.StatusUnauthorized, http.StatusForbidden} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			requestCount, putCount, commitCount := 0, 0, 0
			control := uploadExchangeStub{
				request: func(_ context.Context, _ string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
					requestCount++
					ticket := uploadTicket(request.GetTransferId(), protocol.TicketState_TICKET_PENDING, testUploadID)
					ticket.PresignedPut = &protocol.PresignedPut{Method: proto.String(http.MethodPut), Url: proto.String("http://storage.example/file"), ExpiresAtEpochMs: futureUploadExpiry()}
					return ticket, nil
				},
				commit: func(context.Context, string, string, string) (*protocol.ResourceUploadTicket, error) {
					commitCount++
					return nil, nil
				},
			}
			client := &http.Client{Transport: uploadRoundTripper(func(*http.Request) (*http.Response, error) {
				putCount++
				return &http.Response{StatusCode: status, Body: io.NopCloser(strings.NewReader("")), Header: make(http.Header)}, nil
			})}
			_, err := (&ResourceUploader{Control: control, Client: client}).Upload(context.Background(), uploadBoundaryInvocationID, uploadContent())
			var uploadErr *runtimeapi.ResourceUploadError
			if !errors.As(err, &uploadErr) || uploadErr.Category != "storage-rejected" || !uploadErr.CleanupConfirmed {
				t.Fatalf("HTTP %d error=%#v", status, err)
			}
			if requestCount != 1 || putCount != 1 || commitCount != 0 {
				t.Fatalf("HTTP %d request=%d PUT=%d commit=%d", status, requestCount, putCount, commitCount)
			}
		})
	}
}

// Returns the resource reference when upload succeeds.
func TestUploadBoundary_Success(t *testing.T) {
	uploader := &ResourceUploader{Control: uploadExchangeStub{
		request: func(_ context.Context, _ string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
			return uploadTicket(request.GetTransferId(), protocol.TicketState_TICKET_READY, testUploadID), nil
		},
		commit: func(context.Context, string, string, string) (*protocol.ResourceUploadTicket, error) {
			t.Fatal("unexpected commit")
			return nil, nil
		},
	}}

	content := uploadContent()
	got, err := uploader.Upload(context.Background(), uploadBoundaryInvocationID, content)
	if err != nil || got.Type != "resource" || got.UploadID != testUploadID || got.Bytes != nil || got.Size != int64(len(content.Bytes)) || got.SHA256 == "" {
		t.Fatalf("uploaded content = %+v, error = %v", got, err)
	}
}

// Describes upload failures without exposing the underlying cause.
func TestUploadBoundary_RedactedDescription(t *testing.T) {
	cause := errors.New("private ticket and cleanup details")
	uploadErr := normalizeUploadError(&runtimeapi.ResourceUploadError{Stage: "put", Category: "cleanup-failed", CleanupConfirmed: false, Cause: cause})
	const want = "resource upload failed during put (cleanup-failed); cleanupConfirmed=false"
	if uploadErr.Error() != want || errors.Is(uploadErr, cause) == false {
		t.Fatalf("upload error = %q, want %q with retained cause", uploadErr, want)
	}
	if got := normalizeUploadError(errors.New("private detail")); got.Error() == "private detail" {
		t.Fatal("off-contract detail was exposed")
	}
}
