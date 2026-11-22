package transport

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

const ideTestID = "123e4567-e89b-12d3-a456-426614174000"

type viewResolverFunc func(context.Context, ViewRequest) (ViewUpstream, error)

func (f viewResolverFunc) ResolveView(ctx context.Context, r ViewRequest) (ViewUpstream, error) {
	return f(ctx, r)
}

type codedViewResolutionError struct{ code string }

func (e codedViewResolutionError) Error() string     { return "view is not open for this binding epoch" }
func (e codedViewResolutionError) ErrorCode() string { return e.code }

func newTestIDEClient(t *testing.T, proxy string) *http.Client {
	t.Helper()
	policy, err := NewProxyPolicy(proxy)
	if err != nil {
		t.Fatal(err)
	}
	client := policy.HTTPClient()
	t.Cleanup(client.CloseIdleConnections)
	return client
}

// Sequential IDE requests reuse a local socket even with an explicit external proxy.
func TestHandleIDE_HTTPReusesLoopbackConnection(t *testing.T) {
	var accepted atomic.Int32
	closed := make(chan struct{}, 8)
	backend := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("backend"))
	}))
	backend.Config.ConnState = func(_ net.Conn, state http.ConnState) {
		switch state {
		case http.StateNew:
			accepted.Add(1)
		case http.StateClosed:
			closed <- struct{}{}
		}
	}
	backend.Start()
	defer backend.Close()
	host, portText, err := net.SplitHostPort(backend.Listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	port := mustPort(t, portText)
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		t.Error("IDE request reached explicit proxy")
		w.WriteHeader(http.StatusBadGateway)
	}))
	defer proxy.Close()
	local, peer := net.Pipe()
	defer peer.Close()
	connection := &ControlConnection{
		httpClient:  newTestIDEClient(t, proxy.URL),
		conn:        local,
		server:      &http.Server{},
		closed:      make(chan struct{}),
		stopControl: make(chan struct{}),
		resolver: viewResolverFunc(func(context.Context, ViewRequest) (ViewUpstream, error) {
			return ViewUpstream{Host: host, Port: port, Path: "/"}, nil
		}),
	}
	defer connection.Close()
	for range 8 {
		response := httptest.NewRecorder()
		connection.handleIDE(response, httptest.NewRequest(http.MethodGet, "http://studio/ide/"+ideTestID+"/1/", nil))
		if response.Code != http.StatusOK || response.Body.String() != "backend" {
			t.Fatalf("response=%d body=%q", response.Code, response.Body.String())
		}
	}
	if got := accepted.Load(); got != 1 {
		t.Fatalf("upstream accepted %d connections, want 1", got)
	}
	if err := connection.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-closed:
	case <-time.After(5 * time.Second):
		t.Fatal("closing the control connection did not close the idle upstream socket")
	}
}

func TestHandleIDE_HTTPForwardsAndFiltersHopHeaders(t *testing.T) {
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.RequestURI() != "/base/api?q=one" {
			t.Errorf("request URI=%q", r.URL.RequestURI())
		}
		if r.Header.Get("X-Forward") != "client" || r.Header.Get("X-Hop") != "" || r.Header.Get("X-Upstream") != "private" || r.Header.Get("Cookie") != "session=private" {
			t.Errorf("unexpected forwarded headers: %v", r.Header)
		}
		w.Header().Set("Connection", "X-Response-Hop")
		w.Header().Set("X-Response-Hop", "filtered")
		w.Header().Set("X-Reply", "ok")
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte("backend"))
	}))
	defer backend.Close()
	_, portText, err := net.SplitHostPort(strings.TrimPrefix(backend.URL, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	var released atomic.Int32
	resolver := viewResolverFunc(func(_ context.Context, view ViewRequest) (ViewUpstream, error) {
		if view.ViewID != ideTestID || view.BindingEpoch != 8 || view.Mode != "http" || view.Path != "/api?q=one" {
			t.Errorf("view request=%+v", view)
		}
		return ViewUpstream{Host: "127.0.0.1", Port: mustPort(t, portText), Path: "/base/api?q=one", Headers: http.Header{"X-Upstream": []string{"private"}}, Cookie: "session=private", Release: func() { released.Add(1) }}, nil
	})
	connection := &ControlConnection{httpClient: newTestIDEClient(t, ""), resolver: resolver}
	req := httptest.NewRequest(http.MethodGet, "http://studio/ide/"+ideTestID+"/8/api?q=one", nil)
	req.Header.Set("Connection", "keep-alive, X-Hop")
	req.Header.Set("X-Hop", "filtered")
	req.Header.Set("X-Forward", "client")
	response := httptest.NewRecorder()
	connection.handleIDE(response, req)
	if response.Code != http.StatusCreated || response.Body.String() != "backend" || response.Header().Get("X-Reply") != "ok" || response.Header().Get("X-Response-Hop") != "" {
		t.Fatalf("response=%d headers=%v body=%q", response.Code, response.Header(), response.Body.String())
	}
	if released.Load() != 1 {
		t.Fatalf("release count=%d", released.Load())
	}
}

func TestHandleIDE_ResolutionAndRouteErrors(t *testing.T) {
	for _, tc := range []struct {
		name, path string
		resolver   ViewResolver
		want       int
	}{
		{"bad route", "/ide/not-a-uuid/1", nil, http.StatusBadRequest},
		{"missing resolver", "/ide/" + ideTestID + "/1/", nil, http.StatusServiceUnavailable},
		{"gone", "/ide/" + ideTestID + "/1/", viewResolverFunc(func(context.Context, ViewRequest) (ViewUpstream, error) {
			return ViewUpstream{}, fmt.Errorf("resolve view: %w", codedViewResolutionError{code: "VIEW_GONE"})
		}), http.StatusNotFound},
		{"gone with safe message", "/ide/" + ideTestID + "/1/", viewResolverFunc(func(context.Context, ViewRequest) (ViewUpstream, error) {
			return ViewUpstream{}, codedViewResolutionError{code: "VIEW_GONE"}
		}), http.StatusNotFound},
		{"unavailable", "/ide/" + ideTestID + "/1/", viewResolverFunc(func(context.Context, ViewRequest) (ViewUpstream, error) {
			return ViewUpstream{}, fmt.Errorf("resolve view: %w", codedViewResolutionError{code: "UNAVAILABLE"})
		}), http.StatusServiceUnavailable},
		{"resolution failure", "/ide/" + ideTestID + "/1/", viewResolverFunc(func(context.Context, ViewRequest) (ViewUpstream, error) { return ViewUpstream{}, fmt.Errorf("other") }), http.StatusBadGateway},
		{"invalid upstream", "/ide/" + ideTestID + "/1/", viewResolverFunc(func(context.Context, ViewRequest) (ViewUpstream, error) {
			return ViewUpstream{Host: "example.com", Port: 80, Path: "/"}, nil
		}), http.StatusBadGateway},
	} {
		t.Run(tc.name, func(t *testing.T) {
			connection := &ControlConnection{resolver: tc.resolver}
			req := httptest.NewRequest(http.MethodGet, "http://studio"+tc.path, nil)
			response := httptest.NewRecorder()
			connection.handleIDE(response, req)
			if response.Code != tc.want {
				t.Fatalf("status=%d want %d body=%q", response.Code, tc.want, response.Body.String())
			}
		})
	}
}

func TestHandleIDE_RejectsUnsupportedConnectProtocol(t *testing.T) {
	connection := &ControlConnection{resolver: viewResolverFunc(func(context.Context, ViewRequest) (ViewUpstream, error) {
		t.Fatal("resolver must not be called")
		return ViewUpstream{}, nil
	})}
	req := httptest.NewRequest(http.MethodConnect, "http://studio/ide/"+ideTestID+"/1/", nil)
	response := httptest.NewRecorder()
	connection.handleIDE(response, req)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("status=%d", response.Code)
	}
}

func TestHandleIDE_RejectsMissingPathBeforeResolve(t *testing.T) {
	for _, method := range []string{http.MethodGet, http.MethodConnect} {
		t.Run(method, func(t *testing.T) {
			connection := &ControlConnection{resolver: viewResolverFunc(func(context.Context, ViewRequest) (ViewUpstream, error) {
				t.Fatal("resolver called for missing path")
				return ViewUpstream{}, nil
			})}
			req := httptest.NewRequest(method, "http://studio/ide/"+ideTestID+"/1", nil)
			response := httptest.NewRecorder()
			connection.handleIDE(response, req)
			if response.Code != http.StatusBadRequest {
				t.Fatalf("status=%d, want 400", response.Code)
			}
		})
	}
}

func mustPort(t *testing.T, s string) int {
	t.Helper()
	var port int
	if _, err := fmt.Sscanf(s, "%d", &port); err != nil {
		t.Fatal(err)
	}
	return port
}
