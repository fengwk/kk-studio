package transport

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// TRN-T-095, TRN-T-117, TRN-T-119 verify redirects are not followed and requests honor cancellation.
func TestProxy_HTTPClientAndDoDoNotFollowRedirects(t *testing.T) {
	var targetHits int
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		targetHits++
		w.WriteHeader(http.StatusNoContent)
	}))
	defer target.Close()
	redirect := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, target.URL, http.StatusFound)
	}))
	defer redirect.Close()
	policy, err := NewProxyPolicy("")
	if err != nil {
		t.Fatal(err)
	}
	request, err := http.NewRequest(http.MethodGet, redirect.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	response, err := policy.Do(context.Background(), request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusFound || targetHits != 0 {
		t.Fatalf("redirect status=%d target hits=%d", response.StatusCode, targetHits)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	request, _ = http.NewRequest(http.MethodGet, target.URL, nil)
	if _, err := policy.Do(ctx, request); err == nil {
		t.Fatal("canceled request succeeded")
	}
}

// TRN-T-092, TRN-T-112, TRN-T-119 verify an explicit proxy receives absolute-form HTTP requests.
func TestProxy_ExplicitHTTPProxySelection(t *testing.T) {
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.RequestURI, "http://origin.invalid/") {
			t.Errorf("proxy received URI %q", r.RequestURI)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer proxy.Close()
	policy, err := NewProxyPolicy(proxy.URL)
	if err != nil {
		t.Fatal(err)
	}
	request, _ := http.NewRequest(http.MethodGet, "http://origin.invalid/path", nil)
	response, err := policy.HTTPClient().Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
}
