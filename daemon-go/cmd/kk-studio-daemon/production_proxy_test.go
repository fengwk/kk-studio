package main

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	runtimeapi "github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// TRN-T-091 verifies production uploads use the currently applied proxy, including removal.
func TestDynamicResourceUploaderUsesCurrentProxy(t *testing.T) {
	var firstRequests, secondRequests, directRequests int
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		directRequests++
		w.WriteHeader(http.StatusNoContent)
	}))
	defer target.Close()
	firstProxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		firstRequests++
		if request.Method != http.MethodPut || request.URL.Host == "" {
			t.Errorf("first proxy request method=%q URL=%q", request.Method, request.URL)
		}
		_, _ = io.Copy(io.Discard, request.Body)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer firstProxy.Close()
	secondProxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		secondRequests++
		if request.Method != http.MethodPut || request.URL.Host == "" {
			t.Errorf("second proxy request method=%q URL=%q", request.Method, request.URL)
		}
		_, _ = io.Copy(io.Discard, request.Body)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer secondProxy.Close()

	applied := config.DaemonConfig{}
	uploader := dynamicResourceUploader{current: func() config.DaemonConfig { return applied }, control: uploadProxyTestControl{url: target.URL}}
	for _, proxyURL := range []*string{stringPointer(firstProxy.URL), stringPointer(secondProxy.URL), nil} {
		applied.ProxyURL = proxyURL
		if _, err := uploader.Upload(context.Background(), "123e4567-e89b-42d3-a456-426614174000", runtimeapi.ResultContent{Type: "binary", Bytes: []byte("payload"), MediaType: "application/octet-stream"}); err != nil {
			t.Fatalf("upload with proxy %v: %v", proxyURL, err)
		}
	}
	if firstRequests != 1 || secondRequests != 1 || directRequests != 1 {
		t.Fatalf("requests: first proxy=%d second proxy=%d direct=%d, want 1 each", firstRequests, secondRequests, directRequests)
	}
}

type uploadProxyTestControl struct {
	url string
}

func (c uploadProxyTestControl) RequestTicket(_ context.Context, _ string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
	transferID := request.GetTransferId()
	method := http.MethodPut
	return &protocol.ResourceUploadTicket{
		TransferId: &transferID,
		State:      protocol.TicketState_TICKET_PENDING,
		UploadId:   stringPointer("123e4567-e89b-42d3-a456-426614174001"),
		PresignedPut: &protocol.PresignedPut{
			Method: &method,
			Url:    &c.url,
		},
	}, nil
}

func (c uploadProxyTestControl) Commit(_ context.Context, _ string, transferID, uploadID string) (*protocol.ResourceUploadTicket, error) {
	return &protocol.ResourceUploadTicket{TransferId: &transferID, State: protocol.TicketState_TICKET_READY, UploadId: &uploadID}, nil
}

func stringPointer(value string) *string { return &value }
