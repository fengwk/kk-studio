package runtime

import (
	"context"
	"encoding/json"
	"errors"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

type testUploader struct{ calls int }

func (u *testUploader) Upload(_ context.Context, _ string, content ResultContent) (ResultContent, error) {
	u.calls++
	return ResultContent{Type: "resource", UploadID: "upload-1", MediaType: content.MediaType, Name: content.Name, Size: int64(len(content.Bytes)), SHA256: "abc"}, nil
}

func newEncodingRuntime(t *testing.T, uploader ResourceUploader) *Runtime {
	t.Helper()
	r, err := NewRuntime(RuntimeOptions{EnvironmentID: "env", Connection: newTestConnection(), Resources: uploader, ApplyConfig: func(_ context.Context, v int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
		return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: v}, nil
	}})
	if err != nil {
		t.Fatal(err)
	}
	return r
}

// RUN-T-021 externalizes binary output and emits the returned resource reference.
func TestRuntimeEncodesUploadedBinaryResult(t *testing.T) {
	uploader := &testUploader{}
	r := newEncodingRuntime(t, uploader)
	got, err := r.encodeResult(context.Background(), testInvocationID, CapabilityResult{CallID: testInvocationID, Contents: []ResultContent{{Type: "binary", Bytes: []byte("abc"), MediaType: "text/plain"}}}, 8, false)
	if err != nil {
		t.Fatal(err)
	}
	if uploader.calls != 1 || len(got.Contents) != 1 || got.Contents[0].GetResource().GetUploadId() != "upload-1" {
		t.Fatalf("unexpected upload result: calls=%d result=%+v", uploader.calls, got)
	}
}

// RUN-T-022 checks the full aggregate before performing any binary upload.
func TestRuntimeRejectsResultOverResourceBudgetBeforeUpload(t *testing.T) {
	uploader := &testUploader{}
	r := newEncodingRuntime(t, uploader)
	_, err := r.encodeResult(context.Background(), testInvocationID, CapabilityResult{CallID: testInvocationID, Contents: []ResultContent{{Type: "resource", Size: 2}, {Type: "binary", Bytes: []byte("ab")}}}, 3, false)
	if err == nil || err.Error() != "aggregate resource bytes exceed maximumResourceBytes: maximum=3" {
		t.Fatalf("unexpected budget error: %v", err)
	}
	if uploader.calls != 0 {
		t.Fatalf("uploaded before aggregate validation: calls=%d", uploader.calls)
	}
}

// RUN-T-091 replaces blank capability errors with the fixed safe fallback.
func TestSafeCapabilityErrorUsesFallbackForBlankMessages(t *testing.T) {
	if got := safeCapabilityError(errors.New("  \t")); got != "capability execution failed" {
		t.Fatalf("blank error fallback = %q", got)
	}
	if got := safeCapabilityError(nil); got != "capability execution failed" {
		t.Fatalf("nil error fallback = %q", got)
	}
}

// RUN-T-023 permits text and JSON progress but rejects binary content without uploading it.
func TestRuntimeEncodesProgressAndRejectsBinaryProgress(t *testing.T) {
	uploader := &testUploader{}
	r := newEncodingRuntime(t, uploader)
	got, err := r.encodeResult(context.Background(), testInvocationID, CapabilityResult{CallID: testInvocationID, Details: json.RawMessage(`{}`), Contents: []ResultContent{{Type: "text", Text: "partial"}, {Type: "json", JSON: json.RawMessage(`{"ok":true}`)}}}, 0, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Contents) != 2 || got.Contents[0].GetText().GetText() != "partial" || got.Contents[1].GetJson().GetJson() != `{"ok":true}` {
		t.Fatalf("unexpected progress encoding: %+v", got)
	}
	_, err = r.encodeResult(context.Background(), testInvocationID, CapabilityResult{CallID: testInvocationID, Contents: []ResultContent{{Type: "binary", Bytes: []byte("x")}}}, 0, true)
	if err == nil || err.Error() != "progress does not allow binary content" {
		t.Fatalf("unexpected binary progress error: %v", err)
	}
	if uploader.calls != 0 {
		t.Fatalf("progress unexpectedly uploaded binary: calls=%d", uploader.calls)
	}
}
