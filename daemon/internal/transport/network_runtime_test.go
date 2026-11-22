package transport

import (
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// Verify shutdown cancels active exchanges and waits for their scope to leave.
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

// Policy replacement retires the entire old pool, including sockets opened by other clients of that policy.
func TestNetworkRuntime_RetirementClosesSharedPolicyIdleConnections(t *testing.T) {
	closed := make(chan struct{}, 1)
	proxy := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, "ok")
	}))
	proxy.Config.ConnState = func(_ net.Conn, state http.ConnState) {
		if state == http.StateClosed {
			close(closed)
		}
	}
	proxy.Start()
	defer proxy.Close()
	policy, err := NewProxyPolicy(proxy.URL)
	if err != nil {
		t.Fatal(err)
	}
	old := NewNetworkRuntime(policy)
	client := policy.HTTPClient()
	defer client.CloseIdleConnections()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "http://studio.invalid/", nil)
	if err != nil {
		t.Fatal(err)
	}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_, readErr := io.Copy(io.Discard, resp.Body)
	closeErr := resp.Body.Close()
	if readErr != nil || closeErr != nil {
		t.Fatalf("read=%v, close=%v", readErr, closeErr)
	}
	select {
	case <-closed:
		t.Fatal("connection closed before policy retirement")
	default:
	}
	if err := old.RetireWhenIdle(ctx); err != nil {
		t.Fatal(err)
	}
	select {
	case <-closed:
	case <-ctx.Done():
		t.Fatal("retiring the old policy did not close its idle socket")
	}
}

// Verify retirement drains admitted work and rejects work after the boundary.
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
