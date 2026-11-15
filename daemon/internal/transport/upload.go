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
	for {
		ticket, err := u.acquireTicket(ctx, ticketWait, retryBackoff, invocationID, transferID, request)
		if err != nil {
			return runtimeapi.ResultContent{}, err
		}
		switch ticket.GetState() {
		case protocol.TicketState_TICKET_FAILED:
			return runtimeapi.ResultContent{}, &runtimeapi.ResourceUploadError{Stage: "request", Category: "ticket-failed", CleanupConfirmed: true}
		case protocol.TicketState_TICKET_READY:
			if !transferIDPattern.MatchString(ticket.GetUploadId()) {
				return runtimeapi.ResultContent{}, fmt.Errorf("resource upload ticket is invalid")
			}
			return uploadedContent(content, ticket.GetUploadId()), nil
		}
		if ticket.GetState() != protocol.TicketState_TICKET_PENDING || ticket.GetPresignedPut() == nil || !transferIDPattern.MatchString(ticket.GetUploadId()) || ticket.GetPresignedPut().GetMethod() != http.MethodPut {
			return runtimeapi.ResultContent{}, fmt.Errorf("resource upload ticket is invalid")
		}
		requestAgain, err := u.putWithRetry(ctx, ticket, content.Bytes, retryBackoff)
		if err != nil {
			return runtimeapi.ResultContent{}, err
		}
		if requestAgain {
			continue
		}
		uploadID, err := u.commitUntilReady(ctx, ticketWait, retryBackoff, invocationID, transferID, ticket.GetUploadId())
		if err != nil {
			return runtimeapi.ResultContent{}, err
		}
		return uploadedContent(content, uploadID), nil
	}
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

// acquireTicket requests an upload ticket and retries the control exchange until the storage
// service answers for this transfer.
func (u *ResourceUploader) acquireTicket(ctx context.Context, wait, backoff time.Duration, invocationID, transferID string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
	for {
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
}

// putWithRetry uploads the payload for a pending ticket. It reports whether the ticket has to be
// requested again, which is the retry path for a storage failure that confirmed its own cleanup.
func (u *ResourceUploader) putWithRetry(ctx context.Context, ticket *protocol.ResourceUploadTicket, body []byte, backoff time.Duration) (bool, error) {
	err := u.put(ctx, ticket.GetPresignedPut().GetUrl(), ticket.GetPresignedPut().Headers, body)
	if err == nil {
		return false, nil
	}
	var uploadErr *runtimeapi.ResourceUploadError
	if errors.As(err, &uploadErr) && !uploadErr.CleanupConfirmed {
		return false, uploadErr
	}
	if ctx.Err() != nil || errors.Is(err, context.Canceled) {
		return false, uploadFailure("put", "cancelled", true, err)
	}
	if err = retryAfter(ctx, backoff); err != nil {
		return false, uploadFailure("put", "cancelled", true, err)
	}
	return true, nil
}

// commitUntilReady publishes the uploaded object and retries the commit exchange until the
// storage service reports it ready.
func (u *ResourceUploader) commitUntilReady(ctx context.Context, wait, backoff time.Duration, invocationID, transferID, uploadID string) (string, error) {
	for {
		committed, err := u.commit(ctx, wait, invocationID, transferID, uploadID)
		if err != nil {
			if err = retryAfter(ctx, backoff); err != nil {
				return "", uploadFailure("commit", "cancelled", true, err)
			}
			continue
		}
		if committed == nil || committed.GetTransferId() != transferID || committed.GetUploadId() != uploadID {
			return "", fmt.Errorf("resource upload commit ticket is invalid")
		}
		switch committed.GetState() {
		case protocol.TicketState_TICKET_FAILED:
			return "", &runtimeapi.ResourceUploadError{Stage: "commit", Category: "ticket-failed", CleanupConfirmed: true}
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
	fail := func(stage string) error { return fmt.Errorf("resource upload %s failed", stage) }
	var responseErr error
	err := u.Network.WithResponse(ctx, req, func(resp *http.Response) error {
		if resp.StatusCode < 200 || resp.StatusCode >= 300 {
			responseErr = fail(fmt.Sprintf("storage status %d", resp.StatusCode))
			return responseErr
		}
		if _, err := io.Copy(io.Discard, resp.Body); err != nil {
			responseErr = fail("response")
			return responseErr
		}
		return nil
	})
	if err != nil {
		if responseErr != nil {
			return uploadFailure("put", "storage-rejected", true, responseErr)
		}
		if errors.Is(err, context.Canceled) {
			return err
		}
		return uploadFailure("put", "cleanup-failed", false, err)
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
		return fail("request")
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("resource upload storage status %d failed", resp.StatusCode)
	}
	if _, err = io.Copy(io.Discard, resp.Body); err != nil {
		return fail("response")
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

func protoString(s string) *string { return &s }
func protoInt64(n int64) *int64    { return &n }
