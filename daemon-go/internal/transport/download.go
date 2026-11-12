package transport

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"github.com/fengwk/kk-studio/daemon/internal/skill"
)

// ResourceDownloader obtains ticketed downloads over the currently applied network policy.
type TicketRequester interface {
	RequestTicket(context.Context, string) (*protocol.ResourceDownloadTicket, error)
}

type ResourceDownloader struct {
	Policy    *ProxyPolicy
	Client    *http.Client
	Requester TicketRequester
}

var _ skill.ResourceDownloader = (*ResourceDownloader)(nil)

var transferIDPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// Download requests a one-time GET ticket, then verifies the downloaded body.
func (d *ResourceDownloader) Download(ctx context.Context, transferID string) ([]byte, error) {
	if d.Requester == nil || !transferIDPattern.MatchString(transferID) {
		return nil, fmt.Errorf("resource download ticket is required")
	}
	ticket, err := d.Requester.RequestTicket(ctx, transferID)
	if err != nil {
		return nil, fmt.Errorf("resource download ticket request failed")
	}
	if ticket == nil || ticket.GetTransferId() != transferID {
		return nil, fmt.Errorf("resource download ticket is invalid")
	}
	if ticket.GetState() == protocol.TicketState_TICKET_FAILED {
		return nil, fmt.Errorf("resource download ticket failed")
	}
	if ticket.GetState() != protocol.TicketState_TICKET_READY || ticket.GetPresignedGet() == nil {
		return nil, fmt.Errorf("resource download ticket is invalid")
	}
	get := ticket.GetPresignedGet()
	if get.GetMethod() != http.MethodGet {
		return nil, fmt.Errorf("resource download ticket is invalid")
	}
	return d.DownloadPresigned(ctx, get.GetUrl(), nil, ticket.GetSize(), ticket.GetSha256())
}

// DownloadPresigned fetches an explicit presigned GET and verifies its declared metadata.
func (d *ResourceDownloader) DownloadPresigned(ctx context.Context, targetURL string, headers map[string]string, size int64, digest string) ([]byte, error) {
	fail := func(stage string) ([]byte, error) { return nil, fmt.Errorf("resource download %s failed", stage) }
	u, err := url.Parse(targetURL)
	if err != nil || u.Host == "" || u.User != nil || (u.Scheme != "http" && u.Scheme != "https") {
		return fail("request")
	}
	if size < 0 || size == int64(^uint64(0)>>1) {
		return fail("size")
	}
	if len(digest) != 64 || digest != strings.ToLower(digest) {
		return fail("digest")
	}
	if _, err = hex.DecodeString(digest); err != nil {
		return fail("digest")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, targetURL, nil)
	if err != nil {
		return fail("request")
	}
	for k, v := range headers {
		if !validHeaderName(k) || hasUnsafeHeaderCharacter(v) {
			return fail("request")
		}
		req.Header.Set(k, v)
	}
	client := d.Client
	ownedClient := false
	if client == nil {
		if d.Policy == nil {
			return fail("network")
		}
		client = d.Policy.HTTPClient()
		ownedClient = true
	} else {
		copy := *client
		if copy.Transport == nil {
			copy.Transport = &http.Transport{Proxy: nil}
			ownedClient = true
		}
		client = &copy
	}
	copy := *client
	copy.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	client = &copy
	if ownedClient {
		defer client.CloseIdleConnections()
	}
	resp, err := client.Do(req)
	if err != nil {
		return fail("request")
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fail(fmt.Sprintf("storage status %d", resp.StatusCode))
	}
	var body []byte
	limit := size + 1
	if size < 0 {
		return fail("size")
	}
	body, err = io.ReadAll(io.LimitReader(resp.Body, limit))
	if err != nil {
		return fail("response")
	}
	if int64(len(body)) != size {
		return fail("size")
	}
	sum := sha256.Sum256(body)
	if fmt.Sprintf("%x", sum) != digest {
		return fail("digest")
	}
	return body, nil
}
