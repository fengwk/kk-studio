package transport

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	runtimeapi "github.com/fengwk/kk-studio/daemon/internal/runtime"
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
	MaxResourceBytes int64
	TicketWait       time.Duration
	RetryBackoff     time.Duration
}

var _ runtimeapi.ResourceUploader = (*ResourceUploader)(nil)

// Upload uploads one binary result and replaces its bytes with a resource reference.
func (u *ResourceUploader) Upload(ctx context.Context, invocationID string, content runtimeapi.ResultContent) (runtimeapi.ResultContent, error) {
	if u.Control == nil {
		return runtimeapi.ResultContent{}, fmt.Errorf("resource upload control is unavailable")
	}
	if !transferIDPattern.MatchString(invocationID) {
		return runtimeapi.ResultContent{}, fmt.Errorf("resource upload invocation id is invalid")
	}
	if len(content.Bytes) == 0 || u.MaxResourceBytes > 0 && int64(len(content.Bytes)) > u.MaxResourceBytes {
		return runtimeapi.ResultContent{}, fmt.Errorf("resource upload size is invalid")
	}
	mediaParts := strings.Split(content.MediaType, "/")
	if len(mediaParts) != 2 || strings.ToLower(content.MediaType) != content.MediaType || !validHeaderName(mediaParts[0]) || !validHeaderName(mediaParts[1]) {
		return runtimeapi.ResultContent{}, fmt.Errorf("resource upload media type is invalid")
	}
	if content.Name != nil && hasUnsafeHeaderCharacter(*content.Name) {
		return runtimeapi.ResultContent{}, fmt.Errorf("resource upload name is invalid")
	}
	transferID, err := newTransferID()
	if err != nil {
		return runtimeapi.ResultContent{}, fmt.Errorf("resource upload transfer id failed")
	}
	digest := sha256.Sum256(content.Bytes)
	request := &protocol.ResourceUploadRequest{TransferId: &transferID, MediaType: protoString(content.MediaType), Size: protoInt64(int64(len(content.Bytes))), Sha256: protoString(hex.EncodeToString(digest[:]))}
	if content.Name != nil {
		request.Name = content.Name
	}
	ticketWait, retryBackoff, err := u.retryOptions()
	if err != nil {
		return runtimeapi.ResultContent{}, err
	}
	for {
		ticket, requestErr := u.requestTicket(ctx, ticketWait, invocationID, request)
		if requestErr != nil {
			if err = retryAfter(ctx, retryBackoff); err != nil {
				return runtimeapi.ResultContent{}, fmt.Errorf("resource upload ticket request failed")
			}
			continue
		}
		if ticket == nil || ticket.GetTransferId() != transferID {
			return runtimeapi.ResultContent{}, fmt.Errorf("resource upload ticket is invalid")
		}
		if ticket.GetState() == protocol.TicketState_TICKET_FAILED {
			return runtimeapi.ResultContent{}, fmt.Errorf("resource upload ticket failed")
		}
		if ticket.GetState() == protocol.TicketState_TICKET_READY {
			if !transferIDPattern.MatchString(ticket.GetUploadId()) {
				return runtimeapi.ResultContent{}, fmt.Errorf("resource upload ticket is invalid")
			}
			return uploadedContent(content, ticket.GetUploadId()), nil
		}
		if ticket.GetState() != protocol.TicketState_TICKET_PENDING || ticket.GetPresignedPut() == nil || !transferIDPattern.MatchString(ticket.GetUploadId()) || ticket.GetPresignedPut().GetMethod() != http.MethodPut {
			return runtimeapi.ResultContent{}, fmt.Errorf("resource upload ticket is invalid")
		}
		if err = u.put(ctx, ticket.GetPresignedPut().GetUrl(), ticket.GetPresignedPut().Headers, content.Bytes); err != nil {
			if ctx.Err() != nil {
				return runtimeapi.ResultContent{}, fmt.Errorf("resource upload put failed")
			}
			if err = retryAfter(ctx, retryBackoff); err != nil {
				return runtimeapi.ResultContent{}, fmt.Errorf("resource upload put failed")
			}
			continue
		}
		for {
			committed, commitErr := u.commit(ctx, ticketWait, invocationID, transferID, ticket.GetUploadId())
			if commitErr != nil {
				if err = retryAfter(ctx, retryBackoff); err != nil {
					return runtimeapi.ResultContent{}, fmt.Errorf("resource upload commit failed")
				}
				continue
			}
			if committed == nil || committed.GetTransferId() != transferID || committed.GetUploadId() != ticket.GetUploadId() {
				return runtimeapi.ResultContent{}, fmt.Errorf("resource upload commit ticket is invalid")
			}
			if committed.GetState() == protocol.TicketState_TICKET_FAILED {
				return runtimeapi.ResultContent{}, fmt.Errorf("resource upload ticket failed")
			}
			if committed.GetState() == protocol.TicketState_TICKET_READY {
				return uploadedContent(content, committed.GetUploadId()), nil
			}
			if committed.GetState() != protocol.TicketState_TICKET_PENDING {
				return runtimeapi.ResultContent{}, fmt.Errorf("resource upload commit ticket is invalid")
			}
			if err = retryAfter(ctx, retryBackoff); err != nil {
				return runtimeapi.ResultContent{}, fmt.Errorf("resource upload commit failed")
			}
		}
	}
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
	fail := func(stage string) error { return fmt.Errorf("resource upload %s failed", stage) }
	parsed, err := url.Parse(targetURL)
	if err != nil || parsed.Host == "" || parsed.User != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		return fail("request")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, targetURL, bytes.NewReader(body))
	if err != nil {
		return fail("request")
	}
	for key, value := range headers {
		if !validHeaderName(key) || hasUnsafeHeaderCharacter(value) {
			return fail("request")
		}
		req.Header.Set(key, value)
	}
	client := u.Client
	owned := false
	if client == nil {
		if u.Policy == nil {
			return fail("network")
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
	client = &copy
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
	_, err = io.Copy(io.Discard, resp.Body)
	if err != nil {
		return fail("response")
	}
	return nil
}

func newTransferID() (string, error) {
	var id [16]byte
	if _, err := rand.Read(id[:]); err != nil {
		return "", err
	}
	id[6] = (id[6] & 0x0f) | 0x40
	id[8] = (id[8] & 0x3f) | 0x80
	encoded := hex.EncodeToString(id[:])
	return encoded[:8] + "-" + encoded[8:12] + "-" + encoded[12:16] + "-" + encoded[16:20] + "-" + encoded[20:], nil
}

func protoString(s string) *string { return &s }
func protoInt64(n int64) *int64    { return &n }
