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
)

const ideTestID = "123e4567-e89b-12d3-a456-426614174000"

type viewResolverFunc func(context.Context, ViewRequest) (ViewUpstream, error)

func (f viewResolverFunc) ResolveView(ctx context.Context, r ViewRequest) (ViewUpstream, error) {
	return f(ctx, r)
}

type codedViewResolutionError struct{ code string }

func (e codedViewResolutionError) Error() string     { return "view is not open for this binding epoch" }
func (e codedViewResolutionError) ErrorCode() string { return e.code }

// TRN-T-033, TRN-T-034, TRN-T-035
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
		return ViewUpstream{Host: "127.0.0.1", Port: mustPort(t, portText), Path: "/base", Headers: http.Header{"X-Upstream": []string{"private"}}, Cookie: "session=private", Release: func() { released.Add(1) }}, nil
	})
	connection := &ControlConnection{resolver: resolver}
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

// TRN-T-033
func TestHandleIDE_ResolutionAndRouteErrors(t *testing.T) {
	for _, tc := range []struct {
		name, path string
		resolver   ViewResolver
		want       int
	}{
		{"bad route", "/ide/not-a-uuid/1", nil, http.StatusBadRequest},
		{"missing resolver", "/ide/" + ideTestID + "/1/", nil, http.StatusServiceUnavailable},
		{"gone", "/ide/" + ideTestID + "/1/", viewResolverFunc(func(context.Context, ViewRequest) (ViewUpstream, error) {
			return ViewUpstream{}, fmt.Errorf("VIEW_GONE")
		}), http.StatusNotFound},
		{"gone with safe message", "/ide/" + ideTestID + "/1/", viewResolverFunc(func(context.Context, ViewRequest) (ViewUpstream, error) {
			return ViewUpstream{}, codedViewResolutionError{code: "VIEW_GONE"}
		}), http.StatusNotFound},
		{"unavailable", "/ide/" + ideTestID + "/1/", viewResolverFunc(func(context.Context, ViewRequest) (ViewUpstream, error) {
			return ViewUpstream{}, fmt.Errorf("UNAVAILABLE")
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

// TRN-T-033
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

// TRN-T-034
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
