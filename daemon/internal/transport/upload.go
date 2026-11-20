package transport

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	runtimeapi "github.com/fengwk/kk-studio/daemon/internal/runtime"
	"github.com/fengwk/kk-studio/daemon/internal/uuid"
)

// UploadControl performs correlated upload-ticket and commit exchanges on the control stream.
type UploadControl interface {
	RequestTicket(context.Context, string, *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error)
	Commit(context.Context, string, string, string) (*protocol.ResourceUploadTicket, error)
}

const maxUploadAttempts = 3

// ResourceUploader uploads binary invocation results through the active outbound policy.
type ResourceUploader struct {
	Control          UploadControl
	Policy           *ProxyPolicy
	Client           *http.Client
	Network          *NetworkRuntime
	MaxResourceBytes int64
	TicketWait       time.Duration
	RetryBackoff     time.Duration
}

var _ runtimeapi.ResourceUploader = (*ResourceUploader)(nil)

// Upload uploads one binary result and replaces its bytes with a resource reference.
func (u *ResourceUploader) Upload(ctx context.Context, invocationID string, content runtimeapi.ResultContent) (result runtimeapi.ResultContent, err error) {
	defer func() {
		err = normalizeUploadError(err)
	}()
	if err := u.validate(ctx, invocationID, content); err != nil {
		return runtimeapi.ResultContent{}, err
	}
	ticketWait, retryBackoff, err := u.retryOptions()
	if err != nil {
		return runtimeapi.ResultContent{}, uploadFailure("request", "invalid-input", true, err)
	}
	transferID := uuid.New()
	request := uploadRequest(transferID, content)
	var uploadID string
	for attempt := 0; attempt < maxUploadAttempts; attempt++ {
		ticket, err := u.acquireTicket(ctx, ticketWait, retryBackoff, invocationID, transferID, request)
		if err != nil {
			return runtimeapi.ResultContent{}, err
		}
		switch ticket.GetState() {
		case protocol.TicketState_TICKET_FAILED:
			return runtimeapi.ResultContent{}, &runtimeapi.ResourceUploadError{Stage: "request", Category: "ticket-failed", CleanupConfirmed: true}
		case protocol.TicketState_TICKET_READY:
			if !transferIDPattern.MatchString(ticket.GetUploadId()) || uploadID != "" && uploadID != ticket.GetUploadId() {
				return runtimeapi.ResultContent{}, fmt.Errorf("resource upload ticket is invalid")
			}
			return uploadedContent(content, ticket.GetUploadId()), nil
		}
		if ticket.GetState() != protocol.TicketState_TICKET_PENDING || ticket.GetPresignedPut() == nil || !transferIDPattern.MatchString(ticket.GetUploadId()) || ticket.GetPresignedPut().GetMethod() != http.MethodPut || ticket.GetPresignedPut().GetExpiresAtEpochMs() <= 0 {
			return runtimeapi.ResultContent{}, fmt.Errorf("resource upload ticket is invalid")
		}
		if uploadID != "" && uploadID != ticket.GetUploadId() {
			return runtimeapi.ResultContent{}, fmt.Errorf("resource upload ticket is invalid")
		}
		uploadID = ticket.GetUploadId()
		requestAgain, err := u.putWithRetry(ctx, ticket, content.Bytes, retryBackoff)
		if err != nil {
			return runtimeapi.ResultContent{}, err
		}
		if requestAgain {
			continue
		}
		uploadID, err = u.commitUntilReady(ctx, ticketWait, retryBackoff, invocationID, transferID, ticket.GetUploadId())
		if err != nil {
			return runtimeapi.ResultContent{}, err
		}
		return uploadedContent(content, uploadID), nil
	}
	return runtimeapi.ResultContent{}, uploadFailure("put", "retry-exhausted", false, nil)
}

// validate rejects an upload request the storage service can never accept.
func (u *ResourceUploader) validate(ctx context.Context, invocationID string, content runtimeapi.ResultContent) error {
	if u.Control == nil {
		return uploadFailure("request", "control-unavailable", true, nil)
	}
	if err := ctx.Err(); err != nil {
		return uploadFailure("request", "cancelled", true, err)
	}
	if !transferIDPattern.MatchString(invocationID) {
		return uploadFailure("request", "invalid-input", true, nil)
	}
	if len(content.Bytes) == 0 || u.MaxResourceBytes > 0 && int64(len(content.Bytes)) > u.MaxResourceBytes {
		return uploadFailure("request", "invalid-input", true, nil)
	}
	mediaParts := strings.Split(content.MediaType, "/")
	if len(mediaParts) != 2 || strings.ToLower(content.MediaType) != content.MediaType || !validHeaderName(mediaParts[0]) || !validHeaderName(mediaParts[1]) {
		return uploadFailure("request", "invalid-input", true, nil)
	}
	if content.Name != nil && hasUnsafeHeaderCharacter(*content.Name) {
		return uploadFailure("request", "invalid-input", true, nil)
	}
	return nil
}

// acquireTicket retries an unanswered control exchange within the attempt and call budgets.
func (u *ResourceUploader) acquireTicket(ctx context.Context, wait, backoff time.Duration, invocationID, transferID string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
	for attempt := 0; attempt < maxUploadAttempts; attempt++ {
		ticket, err := u.requestTicket(ctx, wait, invocationID, request)
		if err != nil {
			if err = retryAfter(ctx, backoff); err != nil {
				return nil, uploadFailure("request", "cancelled", true, err)
			}
			continue
		}
		if ticket == nil || ticket.GetTransferId() != transferID {
			return nil, fmt.Errorf("resource upload ticket is invalid")
		}
		return ticket, nil
	}
	return nil, uploadFailure("request", "retry-exhausted", true, nil)
}

// putWithRetry reports whether the ticket must be refreshed before commit.
func (u *ResourceUploader) putWithRetry(ctx context.Context, ticket *protocol.ResourceUploadTicket, body []byte, backoff time.Duration) (bool, error) {
	if time.Now().UnixMilli() >= ticket.GetPresignedPut().GetExpiresAtEpochMs() {
		if err := retryAfter(ctx, backoff); err != nil {
			return false, uploadFailure("put", "cancelled", true, err)
		}
		return true, nil
	}
	err := u.put(ctx, ticket.GetPresignedPut().GetUrl(), ticket.GetPresignedPut().Headers, body)
	if err == nil {
		return false, nil
	}
	var statusErr *storageStatusError
	if errors.As(err, &statusErr) {
		switch {
		case statusErr.status == http.StatusPreconditionFailed:
			return false, nil
		case (statusErr.status == http.StatusUnauthorized || statusErr.status == http.StatusForbidden) && time.Now().UnixMilli() >= ticket.GetPresignedPut().GetExpiresAtEpochMs():
			if retryErr := retryAfter(ctx, backoff); retryErr != nil {
				return false, uploadFailure("put", "cancelled", true, retryErr)
			}
			return true, nil
		case retryableStorageStatus(statusErr.status):
			if retryErr := retryAfter(ctx, backoff); retryErr != nil {
				return false, uploadFailure("put", "cancelled", true, retryErr)
			}
			return true, nil
		default:
			return false, uploadFailure("put", "storage-rejected", true, err)
		}
	}
	var uploadErr *runtimeapi.ResourceUploadError
	if errors.As(err, &uploadErr) && !uploadErr.CleanupConfirmed {
		return false, uploadErr
	}
	if ctx.Err() != nil || errors.Is(err, context.Canceled) {
		return false, uploadFailure("put", "cancelled", true, err)
	}
	return false, uploadFailure("put", "storage-rejected", true, err)
}

// commitUntilReady waits for a matching READY ticket within the attempt and call budgets.
func (u *ResourceUploader) commitUntilReady(ctx context.Context, wait, backoff time.Duration, invocationID, transferID, uploadID string) (string, error) {
	for attempt := 0; attempt < maxUploadAttempts; attempt++ {
		committed, err := u.commit(ctx, wait, invocationID, transferID, uploadID)
		if err != nil {
			if err = retryAfter(ctx, backoff); err != nil {
				return "", uploadFailure("commit", "cancelled", true, err)
			}
			continue
		}
		if committed == nil || committed.GetTransferId() != transferID {
			return "", fmt.Errorf("resource upload commit ticket is invalid")
		}
		if committed.GetState() == protocol.TicketState_TICKET_FAILED && committed.GetUploadId() == "" {
			return "", uploadFailure("commit", "ticket-failed", true, nil)
		}
		if committed.GetUploadId() != uploadID {
			return "", fmt.Errorf("resource upload commit ticket is invalid")
		}
		switch committed.GetState() {
		case protocol.TicketState_TICKET_READY:
			return committed.GetUploadId(), nil
		case protocol.TicketState_TICKET_PENDING:
		default:
			return "", fmt.Errorf("resource upload commit ticket is invalid")
		}
		if err = retryAfter(ctx, backoff); err != nil {
			return "", uploadFailure("commit", "cancelled", true, err)
		}
	}
	return "", uploadFailure("commit", "retry-exhausted", false, nil)
}

func uploadRequest(transferID string, content runtimeapi.ResultContent) *protocol.ResourceUploadRequest {
	digest := sha256.Sum256(content.Bytes)
	request := &protocol.ResourceUploadRequest{TransferId: &transferID, MediaType: protoString(content.MediaType), Size: protoInt64(int64(len(content.Bytes))), Sha256: protoString(hex.EncodeToString(digest[:]))}
	if content.Name != nil {
		request.Name = content.Name
	}
	return request
}

func normalizeUploadError(err error) error {
	if err == nil {
		return nil
	}
	var uploadErr *runtimeapi.ResourceUploadError
	if errors.As(err, &uploadErr) {
		return err
	}
	return &runtimeapi.ResourceUploadError{Stage: "internal", Category: "boundary-violation", CleanupConfirmed: false, Cause: err}
}

func uploadFailure(stage, category string, cleanupConfirmed bool, cause error) error {
	return &runtimeapi.ResourceUploadError{Stage: stage, Category: category, CleanupConfirmed: cleanupConfirmed, Cause: cause}
}

func (u *ResourceUploader) retryOptions() (time.Duration, time.Duration, error) {
	ticketWait := u.TicketWait
	if ticketWait == 0 {
		ticketWait = 30 * time.Second
	}
	retryBackoff := u.RetryBackoff
	if retryBackoff == 0 {
		retryBackoff = 200 * time.Millisecond
	}
	if ticketWait < 0 || retryBackoff < 0 {
		return 0, 0, fmt.Errorf("resource upload timing options must be positive")
	}
	return ticketWait, retryBackoff, nil
}

func (u *ResourceUploader) requestTicket(ctx context.Context, wait time.Duration, invocationID string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
	ticketCtx, cancel := context.WithTimeout(ctx, wait)
	defer cancel()
	return u.Control.RequestTicket(ticketCtx, invocationID, request)
}

func (u *ResourceUploader) commit(ctx context.Context, wait time.Duration, invocationID, transferID, uploadID string) (*protocol.ResourceUploadTicket, error) {
	commitCtx, cancel := context.WithTimeout(ctx, wait)
	defer cancel()
	return u.Control.Commit(commitCtx, invocationID, transferID, uploadID)
}

func retryAfter(ctx context.Context, backoff time.Duration) error {
	timer := time.NewTimer(backoff)
	defer timer.Stop()
	select {
	case <-timer.C:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func uploadedContent(content runtimeapi.ResultContent, uploadID string) runtimeapi.ResultContent {
	digest := sha256.Sum256(content.Bytes)
	content.Type = "resource"
	content.UploadID = uploadID
	content.Size = int64(len(content.Bytes))
	content.SHA256 = hex.EncodeToString(digest[:])
	content.Bytes = nil
	content.JSON = nil
	return content
}

func (u *ResourceUploader) put(ctx context.Context, targetURL string, headers map[string]string, body []byte) error {
	req, err := newPutRequest(ctx, targetURL, headers, body)
	if err != nil {
		return err
	}
	if u.Network != nil {
		return u.putThroughNetwork(ctx, req)
	}
	return u.putThroughClient(req)
}

// newPutRequest builds the presigned PUT, rejecting destinations or headers the storage service
// must never receive.
func newPutRequest(ctx context.Context, targetURL string, headers map[string]string, body []byte) (*http.Request, error) {
	fail := func(stage string) error { return fmt.Errorf("resource upload %s failed", stage) }
	parsed, err := url.Parse(targetURL)
	if err != nil || parsed.Host == "" || parsed.User != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		return nil, fail("request")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, targetURL, bytes.NewReader(body))
	if err != nil {
		return nil, fail("request")
	}
	for key, value := range headers {
		if !validHeaderName(key) || hasUnsafeHeaderCharacter(value) {
			return nil, fail("request")
		}
		req.Header.Set(key, value)
	}
	return req, nil
}

// putThroughNetwork performs the PUT inside the applied network policy so a policy change drains it.
func (u *ResourceUploader) putThroughNetwork(ctx context.Context, req *http.Request) error {
	var status int
	err := u.Network.WithResponse(ctx, req, func(resp *http.Response) error {
		status = resp.StatusCode
		if status < 200 || status >= 300 {
			return nil
		}
		_, err := io.Copy(io.Discard, resp.Body)
		return err
	})
	if err != nil {
		if status == 0 && errors.Is(err, context.Canceled) {
			return err
		}
		return uploadFailure("put", "cleanup-failed", false, err)
	}
	if status < 200 || status >= 300 {
		return &storageStatusError{status: status}
	}
	return nil
}

// putThroughClient performs the PUT with the injected or policy-derived client, isolated from
// caller cookies and redirects.
func (u *ResourceUploader) putThroughClient(req *http.Request) error {
	fail := func(stage string) error { return fmt.Errorf("resource upload %s failed", stage) }
	client, owned, err := u.uploadClient()
	if err != nil {
		return err
	}
	if owned {
		defer client.CloseIdleConnections()
	}
	resp, err := client.Do(req)
	if err != nil {
		return uploadFailure("put", "cleanup-failed", false, fail("request"))
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return &storageStatusError{status: resp.StatusCode}
	}
	if _, err = io.Copy(io.Discard, resp.Body); err != nil {
		return uploadFailure("put", "cleanup-failed", false, fail("response"))
	}
	return nil
}

// uploadClient returns the HTTP client for one PUT and whether its idle connections are owned by
// this call.
func (u *ResourceUploader) uploadClient() (*http.Client, bool, error) {
	fail := func(stage string) error { return fmt.Errorf("resource upload %s failed", stage) }
	client := u.Client
	owned := false
	if client == nil {
		if u.Policy == nil {
			return nil, false, fail("network")
		}
		client = u.Policy.HTTPClient()
		owned = true
	} else {
		copy := *client
		copy.Jar = nil
		if copy.Transport == nil {
			copy.Transport = &http.Transport{Proxy: nil}
			owned = true
		}
		client = &copy
	}
	copy := *client
	copy.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	return &copy, owned, nil
}

func retryableStorageStatus(status int) bool {
	switch status {
	case http.StatusInternalServerError, http.StatusBadGateway, http.StatusServiceUnavailable, http.StatusGatewayTimeout:
		return true
	default:
		return false
	}
}

type storageStatusError struct {
	status int
}

func (e *storageStatusError) Error() string {
	return fmt.Sprintf("resource upload storage status %d failed", e.status)
}

func protoString(s string) *string { return &s }
func protoInt64(n int64) *int64    { return &n }
