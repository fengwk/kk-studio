package transport

import (
	"context"
	"crypto/sha256"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"google.golang.org/protobuf/proto"
)

type ticketRequesterFunc func(context.Context, string) (*protocol.ResourceDownloadTicket, error)

func (f ticketRequesterFunc) RequestTicket(ctx context.Context, id string) (*protocol.ResourceDownloadTicket, error) {
	return f(ctx, id)
}

func testDownloadTicket(id, url string, body []byte) *protocol.ResourceDownloadTicket {
	sum := sha256.Sum256(body)
	return &protocol.ResourceDownloadTicket{TransferId: proto.String(id), State: protocol.TicketState_TICKET_READY, PresignedGet: &protocol.PresignedGet{Method: proto.String(http.MethodGet), Url: proto.String(url), ExpiresAtEpochMs: proto.Int64(time.Now().Add(time.Hour).UnixMilli())}, Size: proto.Int64(int64(len(body))), Sha256: proto.String(fmt.Sprintf("%x", sum))}
}

// TRN-F-005
func TestDownload_RequestTicketThenFetchAndVerify(t *testing.T) {
	const transfer = "123e4567-e89b-12d3-a456-426614174000"
	body := []byte("archive")
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			t.Errorf("request method = %s", r.Method)
		}
		_, _ = w.Write(body)
	}))
	defer server.Close()
	var calls atomic.Int32
	d := &ResourceDownloader{Client: &http.Client{Transport: &http.Transport{Proxy: nil}}, Requester: ticketRequesterFunc(func(_ context.Context, id string) (*protocol.ResourceDownloadTicket, error) {
		calls.Add(1)
		if id != transfer {
			t.Fatalf("transfer id=%s", id)
		}
		return testDownloadTicket(id, server.URL, body), nil
	})}
	got, err := d.Download(context.Background(), transfer)
	if err != nil || string(got) != string(body) {
		t.Fatalf("Download=%q err=%v", got, err)
	}
	if calls.Load() != 1 {
		t.Fatalf("ticket requests=%d", calls.Load())
	}
}

// TRN-F-005
func TestDownload_RejectsFailedTicketAndDoesNotFollowRedirect(t *testing.T) {
	const transfer = "123e4567-e89b-12d3-a456-426614174000"
	var targetHits atomic.Int32
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { targetHits.Add(1) }))
	defer target.Close()
	redirect := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Location", target.URL)
		w.WriteHeader(http.StatusFound)
	}))
	defer redirect.Close()
	failed := &ResourceDownloader{Requester: ticketRequesterFunc(func(context.Context, string) (*protocol.ResourceDownloadTicket, error) {
		return &protocol.ResourceDownloadTicket{TransferId: proto.String(transfer), State: protocol.TicketState_TICKET_FAILED, Message: proto.String("secret detail")}, nil
	})}
	if _, err := failed.Download(context.Background(), transfer); err == nil || err.Error() != "resource download ticket failed" {
		t.Fatalf("failed ticket error=%v", err)
	}
	d := &ResourceDownloader{Client: &http.Client{Transport: &http.Transport{Proxy: nil}}, Requester: ticketRequesterFunc(func(context.Context, string) (*protocol.ResourceDownloadTicket, error) {
		return testDownloadTicket(transfer, redirect.URL, []byte("x")), nil
	})}
	if _, err := d.Download(context.Background(), transfer); err == nil || err.Error() != "resource download storage status 302 failed" {
		t.Fatalf("redirect error=%v", err)
	}
	if targetHits.Load() != 0 {
		t.Fatalf("redirect target hit %d times", targetHits.Load())
	}
}

// TRN-F-005
func TestDownload_RejectsInvalidSizeAndDigest(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte("x")) }))
	defer server.Close()
	for _, tc := range []struct {
		name   string
		size   int64
		digest string
		want   string
	}{{"size", 2, fmt.Sprintf("%064x", 0), "resource download size failed"}, {"digest", 1, fmt.Sprintf("%064x", 0), "resource download digest failed"}} {
		d := &ResourceDownloader{Client: &http.Client{Transport: &http.Transport{Proxy: nil}}}
		_, err := d.DownloadPresigned(context.Background(), server.URL, tc.size, tc.digest)
		if err == nil || err.Error() != tc.want {
			t.Errorf("%s error=%v want %q", tc.name, err, tc.want)
		}
	}
}
