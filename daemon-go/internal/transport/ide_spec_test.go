package transport

import (
	"bufio"
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// TRN-T-026, TRN-T-027, TRN-T-028, TRN-T-029, TRN-T-030, TRN-T-031 verify upstream validation boundaries.
func TestTunnel_UpstreamValidationSpecMatrix(t *testing.T) {
	base := ViewUpstream{Host: "127.0.0.1", Port: 8080, Path: "/base", Headers: make(http.Header), Cookie: "session=ok", Protocols: []string{"chat"}}
	for _, host := range []string{"127.0.0.1", "127.1.2.3", "::1", "[::1]"} {
		upstream := base
		upstream.Host = host
		if err := validateViewUpstream(upstream); err != nil {
			t.Errorf("loopback host %q rejected: %v", host, err)
		}
	}
	for _, host := range []string{"localhost", "192.168.1.1", "10.0.0.1", "::ffff:127.0.0.1"} {
		upstream := base
		upstream.Host = host
		if err := validateViewUpstream(upstream); err == nil {
			t.Errorf("non-numeric-loopback host %q accepted", host)
		}
	}
	for _, port := range []int{-1, 0, 65536} {
		upstream := base
		upstream.Port = port
		if err := validateViewUpstream(upstream); err == nil {
			t.Errorf("invalid port %d accepted", port)
		}
	}
	for _, path := range []string{"", "no-leading-slash", "//host/path", "/bad\x00path"} {
		upstream := base
		upstream.Path = path
		if err := validateViewUpstream(upstream); err == nil {
			t.Errorf("invalid path %q accepted", path)
		}
	}
	for name, values := range map[string][]string{"bad name": {"value"}, "X-Test": {"bad\x00value"}} {
		upstream := base
		upstream.Headers = http.Header{name: values}
		if err := validateViewUpstream(upstream); err == nil {
			t.Errorf("invalid header %q=%q accepted", name, values)
		}
	}
	for _, tc := range []struct {
		name  string
		value ViewUpstream
	}{
		{"valid port and path", base},
		{"valid multi-value headers", func() ViewUpstream { u := base; u.Headers = http.Header{"X-Multi": {"one", "two"}}; return u }()},
		{"valid cookie", func() ViewUpstream { u := base; u.Cookie = "session=ok"; return u }()},
		{"valid protocol", func() ViewUpstream { u := base; u.Protocols = []string{"valid-protocol"}; return u }()},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if err := validateViewUpstream(tc.value); err != nil {
				t.Fatalf("valid upstream rejected: %v", err)
			}
		})
	}
	for _, tc := range []struct {
		name   string
		change func(*ViewUpstream)
	}{
		{"cookie control", func(u *ViewUpstream) { u.Cookie = "bad\x00cookie" }},
		{"invalid protocol", func(u *ViewUpstream) { u.Protocols = []string{"invalid protocol!"} }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			upstream := base
			tc.change(&upstream)
			if err := validateViewUpstream(upstream); err == nil {
				t.Fatal("invalid upstream accepted")
			}
		})
	}
}

// TRN-T-039 verifies a failed HTTP upstream returns 502 and releases its lease.
func TestHandleIDE_HTTPUpstreamFailureReleasesLease(t *testing.T) {
	closed, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	host, portText, err := net.SplitHostPort(closed.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	if err = closed.Close(); err != nil {
		t.Fatal(err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil {
		t.Fatal(err)
	}
	var releases atomic.Int32
	connection := &ControlConnection{resolver: viewResolverFunc(func(context.Context, ViewRequest) (ViewUpstream, error) {
		return ViewUpstream{Host: host, Port: port, Path: "/", Release: func() { releases.Add(1) }}, nil
	})}
	response := httptest.NewRecorder()
	connection.handleIDE(response, httptest.NewRequest(http.MethodGet, "http://studio/ide/"+ideTestID+"/1/", nil))
	if response.Code != http.StatusBadGateway || releases.Load() != 1 {
		t.Fatalf("status=%d release count=%d", response.Code, releases.Load())
	}
}

// TRN-T-045 verifies request cancellation reaches the upstream and releases its lease.
func TestHandleIDE_HTTPRequestCancellationReleasesLease(t *testing.T) {
	started := make(chan struct{})
	backend := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		close(started)
		<-r.Context().Done()
	}))
	defer backend.Close()
	_, portText, err := net.SplitHostPort(strings.TrimPrefix(backend.URL, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil {
		t.Fatal(err)
	}
	var releases atomic.Int32
	connection := &ControlConnection{resolver: viewResolverFunc(func(context.Context, ViewRequest) (ViewUpstream, error) {
		return ViewUpstream{Host: "127.0.0.1", Port: port, Path: "/", Release: func() { releases.Add(1) }}, nil
	})}
	ctx, cancel := context.WithCancel(context.Background())
	response := httptest.NewRecorder()
	done := make(chan struct{})
	go func() {
		defer close(done)
		connection.handleIDE(response, httptest.NewRequest(http.MethodGet, "http://studio/ide/"+ideTestID+"/1/", nil).WithContext(ctx))
	}()
	select {
	case <-started:
		cancel()
	case <-time.After(time.Second):
		cancel()
		t.Fatal("request did not reach upstream")
	}
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("cancelled upstream request did not finish")
	}
	if releases.Load() != 1 {
		t.Fatalf("release count=%d, want 1", releases.Load())
	}
}

// TRN-T-047 verifies repeated upstream header values are forwarded individually.
func TestHandleIDE_ForwardsRepeatedUpstreamHeaders(t *testing.T) {
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Values("X-Multi"); len(got) != 2 || got[0] != "one" || got[1] != "two" {
			t.Errorf("multi-value header = %q", got)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer backend.Close()
	_, portText, err := net.SplitHostPort(strings.TrimPrefix(backend.URL, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil {
		t.Fatal(err)
	}
	connection := &ControlConnection{resolver: viewResolverFunc(func(context.Context, ViewRequest) (ViewUpstream, error) {
		return ViewUpstream{Host: "127.0.0.1", Port: port, Path: "/", Headers: http.Header{"X-Multi": {"one", "two"}}}, nil
	})}
	response := httptest.NewRecorder()
	connection.handleIDE(response, httptest.NewRequest(http.MethodGet, "http://studio/ide/"+ideTestID+"/1/", nil))
	if response.Code != http.StatusNoContent {
		t.Fatalf("status=%d", response.Code)
	}
}

// TRN-T-048 and TRN-T-049 verify CONNECT path validation and resolver error mapping.
func TestHandleIDE_ConnectRouteAndResolutionErrors(t *testing.T) {
	called := false
	connection := &ControlConnection{resolver: viewResolverFunc(func(context.Context, ViewRequest) (ViewUpstream, error) {
		called = true
		return ViewUpstream{}, nil
	})}
	badPath := &http.Request{Method: http.MethodConnect, URL: &url.URL{Path: "/invalid"}, Header: http.Header{":protocol": {"websocket"}}, Body: http.NoBody}
	badResponse := httptest.NewRecorder()
	connection.handleIDE(badResponse, badPath)
	if badResponse.Code != http.StatusBadRequest || called {
		t.Fatalf("invalid CONNECT path status=%d resolver called=%t", badResponse.Code, called)
	}
	for _, tc := range []struct {
		name string
		err  error
		want int
	}{
		{"gone", errors.New("VIEW_GONE"), http.StatusNotFound},
		{"unavailable", errors.New("UNAVAILABLE"), http.StatusServiceUnavailable},
		{"other", errors.New("failure"), http.StatusBadGateway},
	} {
		t.Run(tc.name, func(t *testing.T) {
			connection := &ControlConnection{resolver: viewResolverFunc(func(context.Context, ViewRequest) (ViewUpstream, error) { return ViewUpstream{}, tc.err })}
			request := &http.Request{Method: http.MethodConnect, URL: &url.URL{Path: "/ide/" + ideTestID + "/1/"}, Header: http.Header{":protocol": {"websocket"}}, Body: http.NoBody}
			response := httptest.NewRecorder()
			connection.handleIDE(response, request)
			if response.Code != tc.want {
				t.Fatalf("CONNECT status=%d want %d", response.Code, tc.want)
			}
		})
	}
}

// TRN-T-058 and TRN-T-063 verify upgrade headers and closure after upstream EOF.
func TestTunnel_WebSocketExtensionsAndUpstreamEOF(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	serverDone := make(chan struct{})
	go func() {
		defer close(serverDone)
		conn, acceptErr := listener.Accept()
		if acceptErr != nil {
			return
		}
		defer conn.Close()
		_, _ = http.ReadRequest(bufio.NewReader(conn))
		_, _ = io.WriteString(conn, "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: accept\r\nSec-WebSocket-Protocol: chat\r\nSec-WebSocket-Extensions: permessage-deflate\r\n\r\nhead")
	}()
	host, portText, err := net.SplitHostPort(listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodConnect, "http://daemon/ide/"+ideTestID+"/1", http.NoBody)
	request.Header[":protocol"] = []string{"websocket"}
	writer := &tunnelResponseWriter{header: make(http.Header), writer: io.Discard}
	done := make(chan any, 1)
	go func() {
		defer func() { done <- recover() }()
		(&ControlConnection{}).tunnelIDEWebSocket(writer, request, ViewUpstream{Host: host, Port: port, Path: "/"}, "/")
	}()
	select {
	case outcome := <-done:
		abortErr, ok := outcome.(error)
		if !ok || !errors.Is(abortErr, http.ErrAbortHandler) {
			t.Fatalf("upstream EOF outcome = %v, want stream abort", outcome)
		}
	case <-time.After(time.Second):
		t.Fatal("upstream EOF did not close websocket bridge")
	}
	<-serverDone
	if writer.header.Get("Sec-WebSocket-Extensions") != "permessage-deflate" {
		t.Fatalf("extensions response header=%q", writer.header.Get("Sec-WebSocket-Extensions"))
	}
}
