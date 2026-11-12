package transport

import (
	"context"
	"errors"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	runtimeapi "github.com/fengwk/kk-studio/daemon/internal/runtime"
)

const uploadBoundaryInvocationID = "123e4567-e89b-12d3-a456-426614174000"

// TRN-T-163 preserves a contract error that confirms cleanup.
func TestUploadBoundary_Confirmed(t *testing.T) {
	want := &runtimeapi.ResourceUploadError{Stage: "put", Category: "storage-rejected", CleanupConfirmed: true}
	if got := normalizeUploadError(want); got != want {
		t.Fatalf("normalized error = %#v, want original contract error", got)
	}
}

// TRN-T-164 preserves a contract error when cleanup is unknown.
func TestUploadBoundary_Unknown(t *testing.T) {
	want := &runtimeapi.ResourceUploadError{Stage: "put", Category: "cleanup-failed", CleanupConfirmed: false}
	if got := normalizeUploadError(want); got != want {
		t.Fatalf("normalized error = %#v, want original contract error", got)
	}
}

// TRN-T-165 converts an off-contract error into a conservative boundary violation.
func TestUploadBoundary_OffContract(t *testing.T) {
	cause := errors.New("private uploader detail")
	got := normalizeUploadError(cause)
	var uploadErr *runtimeapi.ResourceUploadError
	if !errors.As(got, &uploadErr) || uploadErr.Stage != "internal" || uploadErr.Category != "boundary-violation" || uploadErr.CleanupConfirmed || !errors.Is(got, cause) {
		t.Fatalf("normalized error = %#v, want unconfirmed internal boundary violation", got)
	}
}

// TRN-T-166 returns the resource reference when upload succeeds.
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

// TRN-T-167 describes upload failures without exposing the underlying cause.
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
