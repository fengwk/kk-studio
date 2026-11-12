package transport

import (
	"bytes"
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"google.golang.org/protobuf/proto"
)

// TRN-N-002
func TestDownload_TicketValidationErrors(t *testing.T) {
	const id = "123e4567-e89b-12d3-a456-426614174000"
	ready := testDownloadTicket(id, "http://storage.example/file", []byte("x"))
	cases := []struct {
		name      string
		requester TicketRequester
		transfer  string
	}{
		{"missing requester", nil, id},
		{"invalid transfer", ticketRequesterFunc(func(context.Context, string) (*protocol.ResourceDownloadTicket, error) {
			t.Fatal("invalid ID sent to requester")
			return nil, nil
		}), "not-uuid"},
		{"request failed", ticketRequesterFunc(func(context.Context, string) (*protocol.ResourceDownloadTicket, error) {
			return nil, errors.New("private")
		}), id},
		{"nil ticket", ticketRequesterFunc(func(context.Context, string) (*protocol.ResourceDownloadTicket, error) { return nil, nil }), id},
		{"mismatched transfer", ticketRequesterFunc(func(context.Context, string) (*protocol.ResourceDownloadTicket, error) {
			v := proto.Clone(ready).(*protocol.ResourceDownloadTicket)
			v.TransferId = proto.String("123e4567-e89b-12d3-a456-426614174099")
			return v, nil
		}), id},
		{"pending ticket", ticketRequesterFunc(func(context.Context, string) (*protocol.ResourceDownloadTicket, error) {
			v := proto.Clone(ready).(*protocol.ResourceDownloadTicket)
			v.State = protocol.TicketState_TICKET_PENDING
			return v, nil
		}), id},
		{"missing GET", ticketRequesterFunc(func(context.Context, string) (*protocol.ResourceDownloadTicket, error) {
			v := proto.Clone(ready).(*protocol.ResourceDownloadTicket)
			v.PresignedGet = nil
			return v, nil
		}), id},
		{"wrong method", ticketRequesterFunc(func(context.Context, string) (*protocol.ResourceDownloadTicket, error) {
			v := proto.Clone(ready).(*protocol.ResourceDownloadTicket)
			v.PresignedGet.Method = proto.String(http.MethodPost)
			return v, nil
		}), id},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			d := &ResourceDownloader{Requester: tc.requester}
			if _, err := d.RequestTicket(context.Background(), tc.transfer); err == nil {
				t.Fatal("invalid ticket accepted")
			}
		})
	}
}

type transportRoundTripper func(*http.Request) (*http.Response, error)

func (f transportRoundTripper) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

type failingBody struct{}

func (failingBody) Read([]byte) (int, error) { return 0, errors.New("private body error") }
func (failingBody) Close() error             { return nil }

type countedDownloadBody struct {
	reader *bytes.Reader
	read   int
	closed bool
}

func (b *countedDownloadBody) Read(p []byte) (int, error) {
	n, err := b.reader.Read(p)
	b.read += n
	return n, err
}

func (b *countedDownloadBody) Close() error {
	b.closed = true
	return nil
}

// TRN-T-089 verifies a resource download can use the applied network runtime lifecycle.
func TestDownload_UsesNetworkRuntime(t *testing.T) {
	body := []byte("download")
	digest := fmt.Sprintf("%x", sha256.Sum256(body))
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write(body) }))
	defer server.Close()
	downloader := &ResourceDownloader{Network: NewNetworkRuntime(nil)}
	got, err := downloader.DownloadPresigned(context.Background(), server.URL, int64(len(body)), digest)
	if err != nil || !bytes.Equal(got, body) {
		t.Fatalf("download=%q err=%v", got, err)
	}
	if err := downloader.Network.RetireWhenIdle(context.Background()); err != nil {
		t.Fatal(err)
	}
}

// TRN-N-002
func TestDownload_PresignedRequestFailuresAreRedacted(t *testing.T) {
	const digest = "2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881"
	body := []byte("x")
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusBadGateway) }))
	defer server.Close()
	bodyErrorClient := &http.Client{Transport: transportRoundTripper(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: http.StatusOK, Body: failingBody{}, Header: make(http.Header)}, nil
	})}
	requestErrorClient := &http.Client{Transport: transportRoundTripper(func(*http.Request) (*http.Response, error) { return nil, errors.New("private request detail") })}
	for _, tc := range []struct {
		name, target string
		size         int64
		hash         string
		client       *http.Client
		policy       *ProxyPolicy
	}{
		{"invalid target", "not-url", 1, digest, nil, nil},
		{"unsupported scheme", "ftp://store.example/file", 1, digest, nil, nil},
		{"credential URL", "http://user:secret@store.example/file", 1, digest, nil, nil},
		{"negative size", server.URL, -1, digest, nil, nil},
		{"bad digest length", server.URL, 1, "abc", nil, nil},
		{"uppercase digest", server.URL, 1, strings.ToUpper(digest), nil, nil},
		{"non-hex digest", server.URL, 1, strings.Repeat("z", 64), nil, nil},
		{"missing network policy", server.URL, 1, digest, nil, nil},
		{"request failed", server.URL, 1, digest, requestErrorClient, nil},
		{"response read failed", server.URL, 1, digest, bodyErrorClient, nil},
		{"storage status", server.URL, 1, digest, nil, mustProxyPolicy(t)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			d := &ResourceDownloader{Client: tc.client, Policy: tc.policy}
			_, err := d.DownloadPresigned(context.Background(), tc.target, tc.size, tc.hash)
			if err == nil {
				t.Fatal("invalid download succeeded")
			}
			if strings.Contains(err.Error(), "secret") || strings.Contains(err.Error(), "private") {
				t.Fatalf("error leaked storage details: %v", err)
			}
		})
	}
	ok := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Ticket") != "" {
			t.Errorf("unexpected ticket header: %v", r.Header)
		}
		_, _ = w.Write(body)
	}))
	defer ok.Close()
	client := &http.Client{Transport: &http.Transport{Proxy: nil}}
	got, err := (&ResourceDownloader{Client: client}).DownloadPresigned(context.Background(), ok.URL, 1, digest)
	if err != nil || string(got) != "x" {
		t.Fatalf("valid ticketed download=%q err=%v", got, err)
	}
}

// TRN-N-002 verifies download reads at most one byte beyond the declared size and rejects expired tickets without retrying.
func TestDownload_EnforcesTicketSizeAndExpiry(t *testing.T) {
	const transferID = "123e4567-e89b-12d3-a456-426614174000"
	body := &countedDownloadBody{reader: bytes.NewReader([]byte("oversized"))}
	storageRequests := 0
	client := &http.Client{Transport: transportRoundTripper(func(*http.Request) (*http.Response, error) {
		storageRequests++
		return &http.Response{StatusCode: http.StatusOK, Body: body, Header: make(http.Header)}, nil
	})}
	ticketRequests := 0
	d := &ResourceDownloader{Client: client, Requester: ticketRequesterFunc(func(context.Context, string) (*protocol.ResourceDownloadTicket, error) {
		ticketRequests++
		return testDownloadTicket(transferID, "http://storage.example/archive", []byte("x")), nil
	})}
	download := func() ([]byte, error) {
		ticket, err := d.RequestTicket(context.Background(), transferID)
		if err != nil {
			return nil, err
		}
		return d.Download(context.Background(), ticket)
	}
	if got, err := download(); err == nil || err.Error() != "resource download size failed" || got != nil {
		t.Fatalf("oversized download=%q err=%v", got, err)
	}
	if body.read != 2 || !body.closed || ticketRequests != 1 || storageRequests != 1 {
		t.Fatalf("reads=%d closed=%t tickets=%d requests=%d", body.read, body.closed, ticketRequests, storageRequests)
	}

	expired := testDownloadTicket(transferID, "http://storage.example/archive", []byte("x"))
	expired.PresignedGet.ExpiresAtEpochMs = proto.Int64(time.Now().Add(-time.Hour).UnixMilli())
	storageRequests = 0
	ticketRequests = 0
	d.Requester = ticketRequesterFunc(func(context.Context, string) (*protocol.ResourceDownloadTicket, error) {
		ticketRequests++
		return expired, nil
	})
	if got, err := download(); err == nil || err.Error() != "resource download ticket expired" || got != nil {
		t.Fatalf("expired download=%q err=%v", got, err)
	}
	if ticketRequests != 1 || storageRequests != 0 {
		t.Fatalf("expired ticket retries=%d storage requests=%d", ticketRequests, storageRequests)
	}

	missingExpiry := testDownloadTicket(transferID, "http://storage.example/archive", []byte("x"))
	missingExpiry.PresignedGet.ExpiresAtEpochMs = nil
	ticketRequests = 0
	d.Requester = ticketRequesterFunc(func(context.Context, string) (*protocol.ResourceDownloadTicket, error) {
		ticketRequests++
		return missingExpiry, nil
	})
	if got, err := download(); err == nil || err.Error() != "resource download ticket is invalid" || got != nil {
		t.Fatalf("ticket without expiry download=%q err=%v", got, err)
	}
	if ticketRequests != 1 || storageRequests != 0 {
		t.Fatalf("ticket without expiry retries=%d storage requests=%d", ticketRequests, storageRequests)
	}
}

// TRN-N-002 verifies no independent download-size ceiling rejects a ticket before the fetch.
func TestDownload_DoesNotApplyAdditionalSizeLimit(t *testing.T) {
	requests := 0
	client := &http.Client{Transport: transportRoundTripper(func(*http.Request) (*http.Response, error) {
		requests++
		return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader("")), Header: make(http.Header)}, nil
	})}
	_, err := (&ResourceDownloader{Client: client}).DownloadPresigned(context.Background(), "http://storage.example/file", math.MaxInt64, strings.Repeat("0", 64))
	if err == nil || err.Error() != "resource download size failed" || requests != 1 {
		t.Fatalf("download error=%v requests=%d", err, requests)
	}
}

func mustProxyPolicy(t *testing.T) *ProxyPolicy {
	t.Helper()
	policy, err := NewProxyPolicy("")
	if err != nil {
		t.Fatal(err)
	}
	return policy
}
