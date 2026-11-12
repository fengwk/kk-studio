package transport

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// TRN-T-101, TRN-T-102 verify shutdown cancels active exchanges and waits for their scope to leave.
func TestNetworkRuntime_CloseCancelsActiveExchange(t *testing.T) {
	started := make(chan struct{})
	cancelled := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(started)
		<-r.Context().Done()
		close(cancelled)
	}))
	defer server.Close()

	network := NewNetworkRuntime(nil)
	request, err := http.NewRequest(http.MethodGet, server.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	requestDone := make(chan error, 1)
	go func() {
		requestDone <- network.WithResponse(context.Background(), request, func(*http.Response) error { return nil })
	}()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("request did not start")
	}
	closeCtx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := network.Close(closeCtx); err != nil {
		t.Fatal(err)
	}
	select {
	case <-cancelled:
	case <-time.After(time.Second):
		t.Fatal("server request was not cancelled")
	}
	if err := <-requestDone; err == nil {
		t.Fatal("cancelled exchange succeeded")
	}
}

// TRN-T-097, TRN-T-098 verify retirement drains admitted work and rejects work after the boundary.
func TestNetworkRuntime_RetireWhenIdle(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, "ok") }))
	defer server.Close()
	network := NewNetworkRuntime(nil)
	request, err := http.NewRequest(http.MethodGet, server.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	entered := make(chan struct{})
	release := make(chan struct{})
	requestDone := make(chan error, 1)
	go func() {
		requestDone <- network.WithResponse(context.Background(), request, func(resp *http.Response) error {
			close(entered)
			<-release
			_, err := io.Copy(io.Discard, resp.Body)
			return err
		})
	}()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("response handler did not start")
	}
	retireDone := make(chan error, 1)
	go func() { retireDone <- network.RetireWhenIdle(context.Background()) }()
	deadline := time.Now().Add(time.Second)
	for {
		network.mu.Lock()
		retiring := network.retiring
		network.mu.Unlock()
		if retiring {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("runtime did not enter retirement")
		}
		time.Sleep(time.Millisecond)
	}
	if err := network.WithResponse(context.Background(), request, func(*http.Response) error { return nil }); err == nil {
		t.Fatal("retired runtime admitted a new request")
	}
	select {
	case err := <-retireDone:
		t.Fatalf("retirement completed before active request left: %v", err)
	default:
	}
	close(release)
	if err := <-requestDone; err != nil {
		t.Fatal(err)
	}
	if err := <-retireDone; err != nil {
		t.Fatal(err)
	}
}
