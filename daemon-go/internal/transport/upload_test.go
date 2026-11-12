package transport

import (
	"context"
	"crypto/sha256"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	runtimeapi "github.com/fengwk/kk-studio/daemon/internal/runtime"
	"google.golang.org/protobuf/proto"
)

const testUploadID = "123e4567-e89b-12d3-a456-426614174001"

type uploadControlStub struct {
	requestState protocol.TicketState
	commitState  protocol.TicketState
	putURL       string
	requestCount int
	commitCount  int
	gotRequest   *protocol.ResourceUploadRequest
}

func (s *uploadControlStub) RequestTicket(_ context.Context, invocation string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
	s.requestCount++
	s.gotRequest = proto.Clone(request).(*protocol.ResourceUploadRequest)
	if invocation != "123e4567-e89b-12d3-a456-426614174000" {
		return nil, fmt.Errorf("wrong invocation")
	}
	ticket := &protocol.ResourceUploadTicket{TransferId: proto.String(request.GetTransferId()), State: s.requestState, UploadId: proto.String(testUploadID)}
	if s.requestState == protocol.TicketState_TICKET_PENDING {
		ticket.PresignedPut = &protocol.PresignedPut{Method: proto.String(http.MethodPut), Url: proto.String(s.putURL), Headers: map[string]string{"X-Upload": "signed"}}
	}
	if s.requestState == protocol.TicketState_TICKET_FAILED {
		ticket.Message = proto.String("private ticket details")
	}
	return ticket, nil
}
func (s *uploadControlStub) Commit(_ context.Context, _ string, transferID, uploadID string) (*protocol.ResourceUploadTicket, error) {
	s.commitCount++
	if uploadID != testUploadID {
		return nil, fmt.Errorf("wrong upload id")
	}
	return &protocol.ResourceUploadTicket{TransferId: proto.String(transferID), State: s.commitState, UploadId: proto.String(uploadID)}, nil
}

// TRN-F-005
func TestUpload_RequestPutCommitAndReplaceBytes(t *testing.T) {
	body := []byte("binary result")
	var gotBody []byte
	storage := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotBody, _ = io.ReadAll(r.Body)
		if r.Method != http.MethodPut || r.Header.Get("X-Upload") != "signed" {
			t.Errorf("PUT request = %s %v", r.Method, r.Header)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer storage.Close()
	control := &uploadControlStub{requestState: protocol.TicketState_TICKET_PENDING, commitState: protocol.TicketState_TICKET_READY, putURL: storage.URL}
	uploader := &ResourceUploader{Control: control, Client: &http.Client{Transport: &http.Transport{Proxy: nil}}, MaxResourceBytes: 100}
	name := "capture.png"
	input := runtimeapi.ResultContent{Type: "image", Bytes: body, MediaType: "image/png", Name: &name, Preview: "preview"}
	got, err := uploader.Upload(context.Background(), "123e4567-e89b-12d3-a456-426614174000", input)
	if err != nil {
		t.Fatal(err)
	}
	if string(gotBody) != string(body) || got.Type != "resource" || got.Bytes != nil || got.UploadID != testUploadID || got.Size != int64(len(body)) || got.SHA256 != fmt.Sprintf("%x", sha256.Sum256(body)) || got.Preview != "preview" {
		t.Fatalf("uploaded content=%+v body=%q", got, string(gotBody))
	}
	if control.requestCount != 1 || control.commitCount != 1 || control.gotRequest.GetTransferId() == "" || control.gotRequest.GetMediaType() != "image/png" || control.gotRequest.GetName() != name || control.gotRequest.GetSize() != int64(len(body)) {
		t.Fatalf("upload exchange request=%+v commits=%d", control.gotRequest, control.commitCount)
	}
}

// TRN-F-005
func TestUpload_FailedTicketDoesNotUpload(t *testing.T) {
	control := &uploadControlStub{requestState: protocol.TicketState_TICKET_FAILED}
	uploader := &ResourceUploader{Control: control}
	_, err := uploader.Upload(context.Background(), "123e4567-e89b-12d3-a456-426614174000", runtimeapi.ResultContent{Bytes: []byte("x"), MediaType: "application/octet-stream"})
	if err == nil || err.Error() != "resource upload ticket failed" || strings.Contains(err.Error(), "private") {
		t.Fatalf("upload failure=%v", err)
	}
	if control.commitCount != 0 {
		t.Fatalf("failed ticket committed %d times", control.commitCount)
	}
}

// TRN-T-018
func TestUpload_RedactsStorageError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusForbidden) }))
	defer server.Close()
	uploader := &ResourceUploader{Client: &http.Client{Transport: &http.Transport{Proxy: nil}}}
	err := uploader.put(context.Background(), server.URL+"?signature=hidden", nil, []byte("secret"))
	if err == nil || err.Error() != "resource upload storage status 403 failed" || strings.Contains(err.Error(), "signature") || strings.Contains(err.Error(), "secret") {
		t.Fatalf("storage error=%v", err)
	}
}
