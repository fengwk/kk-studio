package transport

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

type runtimeRoundTripper func(*http.Request) (*http.Response, error)

func (f runtimeRoundTripper) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

type runtimeCloseErrorBody struct {
	io.Reader
	err error
}

func (b runtimeCloseErrorBody) Close() error { return b.err }

// TRN-T-108 verifies response-handler and body-release failures are both retained.
func TestNetworkRuntime_WithResponseReportsScopedFailures(t *testing.T) {
	useErr := errors.New("callback failed")
	closeErr := errors.New("private close failure")
	network := NewNetworkRuntime(nil)
	network.client = &http.Client{Transport: runtimeRoundTripper(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: http.StatusOK, Body: runtimeCloseErrorBody{Reader: strings.NewReader("body"), err: closeErr}}, nil
	})}
	request, err := http.NewRequest(http.MethodGet, "http://example.test", nil)
	if err != nil {
		t.Fatal(err)
	}
	got := network.WithResponse(context.Background(), request, func(*http.Response) error { return useErr })
	if !errors.Is(got, useErr) || !errors.Is(got, closeErr) {
		t.Fatalf("scoped error=%v", got)
	}
	if err := network.WithResponse(context.Background(), nil, func(*http.Response) error { return nil }); err == nil {
		t.Fatal("nil request accepted")
	}
	if err := network.WithResponse(context.Background(), request, nil); err == nil {
		t.Fatal("nil response handler accepted")
	}
}

// TRN-N-004, TRN-T-109, TRN-T-110 verify Go's explicit callback and cleanup error outcomes.
func TestNetworkRuntime_WithResponsePropagatesSingleFailures(t *testing.T) {
	closeErr := errors.New("private close failure")
	network := NewNetworkRuntime(nil)
	network.client = &http.Client{Transport: runtimeRoundTripper(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: http.StatusOK, Body: runtimeCloseErrorBody{Reader: strings.NewReader("body"), err: closeErr}}, nil
	})}
	request, err := http.NewRequest(http.MethodGet, "http://example.test", nil)
	if err != nil {
		t.Fatal(err)
	}
	got := network.WithResponse(context.Background(), request, func(*http.Response) error { return nil })
	if !errors.Is(got, closeErr) {
		t.Fatalf("cleanup-only error=%v", got)
	}

	callbackErr := errors.New("callback failed")
	network.client = &http.Client{Transport: runtimeRoundTripper(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader("body"))}, nil
	})}
	got = network.WithResponse(context.Background(), request, func(*http.Response) error { return callbackErr })
	if got != callbackErr {
		t.Fatalf("callback-only error=%v, want original error", got)
	}
}

// TRN-N-004, TRN-T-103 verify each Go retirement waiter receives its own context deadline.
func TestNetworkRuntime_RetireWaitersHaveIndependentDeadlines(t *testing.T) {
	type result struct {
		id  int
		err error
	}
	network := NewNetworkRuntime(nil)
	network.mu.Lock()
	network.active = 1
	network.idle = make(chan struct{})
	idle := network.idle
	network.mu.Unlock()
	results := make(chan result, 2)
	started := make(chan int, 2)
	startTime := time.Now()
	ctx1, cancel1 := context.WithDeadline(context.Background(), startTime.Add(250*time.Millisecond))
	defer cancel1()
	ctx2, cancel2 := context.WithDeadline(context.Background(), startTime.Add(750*time.Millisecond))
	defer cancel2()
	startWaiter := func(id int, ctx context.Context) {
		go func() {
			started <- id
			results <- result{id: id, err: network.RetireWhenIdle(ctx)}
		}()
	}
	startWaiter(1, ctx1)
	startWaiter(2, ctx2)
	<-started
	<-started
	first := <-results
	if first.id != 1 || !errors.Is(first.err, context.DeadlineExceeded) {
		t.Fatalf("first retirement result=%+v, want caller 1 deadline", first)
	}
	select {
	case second := <-results:
		t.Fatalf("second retirement waiter returned before its deadline: %+v", second)
	default:
	}
	second := <-results
	if second.id != 2 || !errors.Is(second.err, context.DeadlineExceeded) {
		t.Fatalf("second retirement result=%+v, want caller 2 deadline", second)
	}
	network.mu.Lock()
	network.active = 0
	close(idle)
	network.mu.Unlock()
	if err := network.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
}

// TRN-T-101, TRN-T-102 verify Close returns its deadline when an admitted callback does not finish.
func TestNetworkRuntime_CloseDeadlineAndAdmission(t *testing.T) {
	network := NewNetworkRuntime(nil)
	network.client = &http.Client{Transport: runtimeRoundTripper(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader("ok"))}, nil
	})}
	request, err := http.NewRequest(http.MethodGet, "http://example.test", nil)
	if err != nil {
		t.Fatal(err)
	}
	entered := make(chan struct{})
	release := make(chan struct{})
	requestDone := make(chan error, 1)
	go func() {
		requestDone <- network.WithResponse(context.Background(), request, func(*http.Response) error {
			close(entered)
			<-release
			return nil
		})
	}()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("response handler did not start")
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Millisecond)
	defer cancel()
	if err := network.Close(ctx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("close error=%v", err)
	}
	if err := network.WithResponse(context.Background(), request, func(*http.Response) error { return nil }); err == nil {
		t.Fatal("closed runtime admitted a request")
	}
	close(release)
	if err := <-requestDone; err != nil {
		t.Fatal(err)
	}
	if err := network.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
}

// TRN-T-098 verifies a timed-out retirement still rejects new work while admitted work drains.
func TestNetworkRuntime_RetireDeadline(t *testing.T) {
	network := NewNetworkRuntime(nil)
	network.active = 1
	network.idle = make(chan struct{})
	ctx, cancel := context.WithTimeout(context.Background(), time.Millisecond)
	defer cancel()
	if err := network.RetireWhenIdle(ctx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("retirement error=%v", err)
	}
	if err := network.WithResponse(context.Background(), nil, func(*http.Response) error { return nil }); err == nil {
		t.Fatal("retiring runtime accepted a new request")
	}
	network.mu.Lock()
	network.active = 0
	close(network.idle)
	network.mu.Unlock()
	if err := network.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
}
