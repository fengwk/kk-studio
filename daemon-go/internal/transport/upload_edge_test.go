package transport

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	runtimeapi "github.com/fengwk/kk-studio/daemon/internal/runtime"
	"google.golang.org/protobuf/proto"
)

type uploadExchangeStub struct {
	request func(context.Context, string, *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error)
	commit  func(context.Context, string, string, string) (*protocol.ResourceUploadTicket, error)
}

func (s uploadExchangeStub) RequestTicket(ctx context.Context, invocation string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
	return s.request(ctx, invocation, request)
}
func (s uploadExchangeStub) Commit(ctx context.Context, invocation, transfer, upload string) (*protocol.ResourceUploadTicket, error) {
	return s.commit(ctx, invocation, transfer, upload)
}
func uploadContent() runtimeapi.ResultContent {
	return runtimeapi.ResultContent{Bytes: []byte("bytes"), MediaType: "application/octet-stream"}
}
func uploadTicket(transfer string, state protocol.TicketState, upload string) *protocol.ResourceUploadTicket {
	return &protocol.ResourceUploadTicket{TransferId: proto.String(transfer), State: state, UploadId: proto.String(upload)}
}

type uploadRoundTripper func(*http.Request) (*http.Response, error)

func (f uploadRoundTripper) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

type uploadCloseErrorBody struct{ io.Reader }

func (uploadCloseErrorBody) Close() error { return errors.New("private cleanup detail") }

// TRN-T-010 verifies invalid ticket and retry timings fail before sending upload control messages.
func TestUpload_RejectsNegativeTimingOptions(t *testing.T) {
	control := uploadExchangeStub{
		request: func(context.Context, string, *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
			t.Fatal("request with invalid timing")
			return nil, nil
		},
		commit: func(context.Context, string, string, string) (*protocol.ResourceUploadTicket, error) {
			t.Fatal("commit with invalid timing")
			return nil, nil
		},
	}
	for _, uploader := range []*ResourceUploader{
		{Control: control, TicketWait: -time.Second},
		{Control: control, RetryBackoff: -time.Second},
	} {
		_, err := uploader.Upload(context.Background(), "123e4567-e89b-12d3-a456-426614174000", uploadContent())
		var uploadErr *runtimeapi.ResourceUploadError
		if !errors.As(err, &uploadErr) || !uploadErr.CleanupConfirmed || uploadErr.Category != "invalid-input" {
			t.Fatalf("timing error=%v", err)
		}
	}
}

// TRN-T-019 verifies a failed network release remains an explicit unknown cleanup fact.
func TestUpload_NetworkCleanupFailureIsUnconfirmed(t *testing.T) {
	control := uploadExchangeStub{
		request: func(_ context.Context, _ string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
			ticket := uploadTicket(request.GetTransferId(), protocol.TicketState_TICKET_PENDING, testUploadID)
			ticket.PresignedPut = &protocol.PresignedPut{Method: proto.String(http.MethodPut), Url: proto.String("http://storage.example/upload")}
			return ticket, nil
		},
		commit: func(context.Context, string, string, string) (*protocol.ResourceUploadTicket, error) {
			t.Fatal("commit after cleanup failure")
			return nil, nil
		},
	}
	network := NewNetworkRuntime(nil)
	network.client = &http.Client{Transport: uploadRoundTripper(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: http.StatusOK, Body: uploadCloseErrorBody{Reader: strings.NewReader("")}}, nil
	})}
	uploader := &ResourceUploader{Control: control, Network: network, RetryBackoff: time.Millisecond}
	_, err := uploader.Upload(context.Background(), "123e4567-e89b-12d3-a456-426614174000", uploadContent())
	var uploadErr *runtimeapi.ResourceUploadError
	if !errors.As(err, &uploadErr) || uploadErr.CleanupConfirmed || uploadErr.Category != "cleanup-failed" {
		t.Fatalf("upload error=%#v", err)
	}
	if strings.Contains(err.Error(), "private cleanup detail") {
		t.Fatal("cleanup error leaked into user-facing text")
	}
}

// TRN-N-004, TRN-T-107
func TestUpload_ShutdownAbortIsNotCleanupFailure(t *testing.T) {
	control := uploadExchangeStub{
		request: func(_ context.Context, _ string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
			ticket := uploadTicket(request.GetTransferId(), protocol.TicketState_TICKET_PENDING, testUploadID)
			ticket.PresignedPut = &protocol.PresignedPut{Method: proto.String(http.MethodPut), Url: proto.String("http://storage.example/upload")}
			return ticket, nil
		},
		commit: func(context.Context, string, string, string) (*protocol.ResourceUploadTicket, error) {
			t.Fatal("commit after cancelled PUT")
			return nil, nil
		},
	}
	network := NewNetworkRuntime(nil)
	network.client = &http.Client{Transport: uploadRoundTripper(func(request *http.Request) (*http.Response, error) {
		<-request.Context().Done()
		return nil, request.Context().Err()
	})}
	uploader := &ResourceUploader{Control: control, Network: network}
	uploadDone := make(chan error, 1)
	go func() {
		_, err := uploader.Upload(context.Background(), "123e4567-e89b-12d3-a456-426614174000", uploadContent())
		uploadDone <- err
	}()
	waitUntil(t, func() bool {
		network.mu.Lock()
		defer network.mu.Unlock()
		return network.active == 1
	})
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := network.Close(ctx); err != nil {
		t.Fatal(err)
	}
	err := <-uploadDone
	var uploadErr *runtimeapi.ResourceUploadError
	if !errors.As(err, &uploadErr) || uploadErr.Category != "cancelled" || !uploadErr.CleanupConfirmed {
		t.Fatalf("shutdown abort classified as cleanup failure: %v (isCanceled=%t)", err, errors.Is(err, context.Canceled))
	}
}

// TRN-F-005
func TestUpload_ValidationAndTicketFailures(t *testing.T) {
	const invocation = "123e4567-e89b-12d3-a456-426614174000"
	valid := uploadExchangeStub{request: func(context.Context, string, *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
		return nil, errors.New("unexpected request")
	}, commit: func(context.Context, string, string, string) (*protocol.ResourceUploadTicket, error) {
		return nil, errors.New("unexpected commit")
	}}
	for _, tc := range []struct {
		name       string
		uploader   *ResourceUploader
		invocation string
		content    runtimeapi.ResultContent
	}{
		{"missing control", &ResourceUploader{}, invocation, uploadContent()},
		{"invalid invocation", &ResourceUploader{Control: valid}, "not-uuid", uploadContent()},
		{"empty bytes", &ResourceUploader{Control: valid}, invocation, runtimeapi.ResultContent{MediaType: "text/plain"}},
		{"too large", &ResourceUploader{Control: valid, MaxResourceBytes: 1}, invocation, uploadContent()},
		{"invalid media type", &ResourceUploader{Control: valid}, invocation, runtimeapi.ResultContent{Bytes: []byte("x"), MediaType: "application/json; charset=utf-8"}},
		{"invalid media type token", &ResourceUploader{Control: valid}, invocation, runtimeapi.ResultContent{Bytes: []byte("x"), MediaType: "bad token/value"}},
		{"unsafe name", &ResourceUploader{Control: valid}, invocation, runtimeapi.ResultContent{Bytes: []byte("x"), MediaType: "text/plain", Name: proto.String("bad\nname")}},
	} {
		if _, err := tc.uploader.Upload(context.Background(), tc.invocation, tc.content); err == nil {
			t.Errorf("%s accepted", tc.name)
		}
	}

	for _, tc := range []struct {
		name      string
		request   func(string) (*protocol.ResourceUploadTicket, error)
		commit    func(string, string) (*protocol.ResourceUploadTicket, error)
		wantReady bool
	}{
		{"request error", func(string) (*protocol.ResourceUploadTicket, error) { return nil, errors.New("private") }, nil, false},
		{"nil ticket", func(string) (*protocol.ResourceUploadTicket, error) { return nil, nil }, nil, false},
		{"mismatched transfer", func(id string) (*protocol.ResourceUploadTicket, error) {
			return uploadTicket("123e4567-e89b-12d3-a456-426614174099", protocol.TicketState_TICKET_READY, testUploadID), nil
		}, nil, false},
		{"invalid ready upload id", func(id string) (*protocol.ResourceUploadTicket, error) {
			return uploadTicket(id, protocol.TicketState_TICKET_READY, "bad"), nil
		}, nil, false},
		{"invalid pending method", func(id string) (*protocol.ResourceUploadTicket, error) {
			v := uploadTicket(id, protocol.TicketState_TICKET_PENDING, testUploadID)
			v.PresignedPut = &protocol.PresignedPut{Method: proto.String(http.MethodPost), Url: proto.String("http://storage.example")}
			return v, nil
		}, nil, false},
		{"pending missing presign", func(id string) (*protocol.ResourceUploadTicket, error) {
			return uploadTicket(id, protocol.TicketState_TICKET_PENDING, testUploadID), nil
		}, nil, false},
		{"ready immediately", func(id string) (*protocol.ResourceUploadTicket, error) {
			return uploadTicket(id, protocol.TicketState_TICKET_READY, testUploadID), nil
		}, nil, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			control := uploadExchangeStub{request: func(_ context.Context, _ string, r *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
				return tc.request(r.GetTransferId())
			}, commit: func(context.Context, string, string, string) (*protocol.ResourceUploadTicket, error) {
				if tc.commit == nil {
					return nil, errors.New("unexpected commit")
				}
				return tc.commit("", "")
			}}
			uploader := &ResourceUploader{Control: control, RetryBackoff: time.Millisecond}
			ctx := context.Background()
			if tc.name == "request error" {
				var cancel context.CancelFunc
				ctx, cancel = context.WithTimeout(ctx, 10*time.Millisecond)
				defer cancel()
			}
			got, err := uploader.Upload(ctx, invocation, uploadContent())
			if tc.wantReady {
				if err != nil || got.Type != "resource" || got.Bytes != nil {
					t.Fatalf("ready upload=%+v err=%v", got, err)
				}
			} else if err == nil {
				t.Fatal("invalid upload ticket accepted")
			}
		})
	}
}

// TRN-F-005
func TestUpload_CommitAndPutFailureBoundaries(t *testing.T) {
	const invocation = "123e4567-e89b-12d3-a456-426614174000"
	request := func(_ context.Context, _ string, r *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
		ticket := uploadTicket(r.GetTransferId(), protocol.TicketState_TICKET_PENDING, testUploadID)
		ticket.PresignedPut = &protocol.PresignedPut{Method: proto.String(http.MethodPut), Url: proto.String("http://storage.example/file")}
		return ticket, nil
	}
	for _, tc := range []struct {
		name   string
		commit func(string, string) (*protocol.ResourceUploadTicket, error)
	}{
		{"commit error", func(string, string) (*protocol.ResourceUploadTicket, error) { return nil, errors.New("private") }},
		{"nil commit", func(string, string) (*protocol.ResourceUploadTicket, error) { return nil, nil }},
		{"mismatched transfer", func(_ string, upload string) (*protocol.ResourceUploadTicket, error) {
			return uploadTicket("123e4567-e89b-12d3-a456-426614174099", protocol.TicketState_TICKET_READY, upload), nil
		}},
		{"failed commit", func(id, upload string) (*protocol.ResourceUploadTicket, error) {
			return uploadTicket(id, protocol.TicketState_TICKET_FAILED, upload), nil
		}},
		{"mismatched upload", func(id, _ string) (*protocol.ResourceUploadTicket, error) {
			return uploadTicket(id, protocol.TicketState_TICKET_READY, "123e4567-e89b-12d3-a456-426614174099"), nil
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			control := uploadExchangeStub{request: request, commit: func(_ context.Context, _ string, transfer, upload string) (*protocol.ResourceUploadTicket, error) {
				return tc.commit(transfer, upload)
			}}
			uploader := &ResourceUploader{Control: control, Client: &http.Client{Transport: transportRoundTripper(func(*http.Request) (*http.Response, error) {
				return &http.Response{StatusCode: http.StatusNoContent, Body: io.NopCloser(strings.NewReader("")), Header: make(http.Header)}, nil
			})}, RetryBackoff: time.Millisecond}
			ctx := context.Background()
			if tc.name == "commit error" {
				var cancel context.CancelFunc
				ctx, cancel = context.WithTimeout(ctx, 10*time.Millisecond)
				defer cancel()
			}
			if _, err := uploader.Upload(ctx, invocation, uploadContent()); err == nil {
				t.Fatal("invalid commit accepted")
			}
		})
	}

	redirect := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "http://127.0.0.1:1/", http.StatusFound)
	}))
	defer redirect.Close()
	for _, tc := range []struct {
		name    string
		url     string
		headers map[string]string
		client  *http.Client
		policy  *ProxyPolicy
	}{
		{"bad URL", "bad", nil, nil, nil},
		{"unsafe credentials", "http://user:secret@storage.example/file", nil, nil, nil},
		{"bad header", "http://storage.example/file", map[string]string{"bad name": "x"}, nil, nil},
		{"unsafe header value", "http://storage.example/file", map[string]string{"X-Test": "bad\rvalue"}, nil, nil},
		{"no network policy", "http://storage.example/file", nil, nil, nil},
		{"request failure", "http://storage.example/file", nil, &http.Client{Transport: transportRoundTripper(func(*http.Request) (*http.Response, error) { return nil, errors.New("private") })}, nil},
		{"response failure", "http://storage.example/file", nil, &http.Client{Transport: transportRoundTripper(func(*http.Request) (*http.Response, error) {
			return &http.Response{StatusCode: http.StatusOK, Body: failingBody{}, Header: make(http.Header)}, nil
		})}, nil},
		{"redirect rejected", redirect.URL, nil, &http.Client{Transport: &http.Transport{Proxy: nil}}, nil},
	} {
		t.Run(tc.name, func(t *testing.T) {
			uploader := &ResourceUploader{Client: tc.client, Policy: tc.policy}
			err := uploader.put(context.Background(), tc.url, tc.headers, []byte("secret"))
			if err == nil {
				t.Fatal("invalid PUT succeeded")
			}
			if strings.Contains(err.Error(), "secret") || strings.Contains(err.Error(), "private") {
				t.Fatalf("error leaked request data: %v", err)
			}
		})
	}
}
