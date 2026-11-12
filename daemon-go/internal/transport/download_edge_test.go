package transport

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

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
			if _, err := d.Download(context.Background(), tc.transfer); err == nil {
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
		headers      map[string]string
		size         int64
		hash         string
		client       *http.Client
		policy       *ProxyPolicy
	}{
		{"invalid target", "not-url", nil, 1, digest, nil, nil},
		{"unsupported scheme", "ftp://store.example/file", nil, 1, digest, nil, nil},
		{"credential URL", "http://user:secret@store.example/file", nil, 1, digest, nil, nil},
		{"negative size", server.URL, nil, -1, digest, nil, nil},
		{"overflow size", server.URL, nil, int64(^uint64(0) >> 1), digest, nil, nil},
		{"bad digest length", server.URL, nil, 1, "abc", nil, nil},
		{"uppercase digest", server.URL, nil, 1, strings.ToUpper(digest), nil, nil},
		{"non-hex digest", server.URL, nil, 1, strings.Repeat("z", 64), nil, nil},
		{"bad header name", server.URL, map[string]string{"bad name": "x"}, 1, digest, nil, nil},
		{"unsafe header value", server.URL, map[string]string{"X-Test": "bad\nvalue"}, 1, digest, nil, nil},
		{"missing network policy", server.URL, nil, 1, digest, nil, nil},
		{"request failed", server.URL, nil, 1, digest, requestErrorClient, nil},
		{"response read failed", server.URL, nil, 1, digest, bodyErrorClient, nil},
		{"storage status", server.URL, nil, 1, digest, nil, mustProxyPolicy(t)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			d := &ResourceDownloader{Client: tc.client, Policy: tc.policy}
			_, err := d.DownloadPresigned(context.Background(), tc.target, tc.headers, tc.size, tc.hash)
			if err == nil {
				t.Fatal("invalid download succeeded")
			}
			if strings.Contains(err.Error(), "secret") || strings.Contains(err.Error(), "private") {
				t.Fatalf("error leaked storage details: %v", err)
			}
		})
	}
	ok := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Ticket") != "required" {
			t.Errorf("ticket header missing: %v", r.Header)
		}
		_, _ = w.Write(body)
	}))
	defer ok.Close()
	client := &http.Client{Transport: &http.Transport{Proxy: nil}}
	got, err := (&ResourceDownloader{Client: client}).DownloadPresigned(context.Background(), ok.URL, map[string]string{"X-Ticket": "required"}, 1, digest)
	if err != nil || string(got) != "x" {
		t.Fatalf("valid ticketed download=%q err=%v", got, err)
	}
}

func mustProxyPolicy(t *testing.T) *ProxyPolicy {
	t.Helper()
	policy, err := NewProxyPolicy("", "")
	if err != nil {
		t.Fatal(err)
	}
	return policy
}
