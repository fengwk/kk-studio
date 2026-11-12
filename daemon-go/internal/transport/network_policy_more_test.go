package transport

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// TRN-T-118 verifies the proxy policy rejects malformed host labels before dialing.
func TestProxyPolicy_ValidBypassHost(t *testing.T) {
	for _, tc := range []struct {
		host string
		want bool
	}{
		{"", false},
		{"localhost", true},
		{"127.0.0.1", true},
		{"bad/name", false},
		{"bad\\\\host", false},
		{"a..example", false},
		{"-bad.example", false},
		{"bad-.example", false},
		{strings.Repeat("a", 64) + ".example", false},
		{"bad_host.example", false},
	} {
		if got := validBypassHost(tc.host); got != tc.want {
			t.Errorf("validBypassHost(%q)=%t want %t", tc.host, got, tc.want)
		}
	}
}

// TRN-T-111, TRN-T-117
func TestProxy_InvalidConfigurationTable(t *testing.T) {
	for _, tc := range []struct{ proxy, bypass string }{
		{"https://proxy.example:443", ""}, {"http://proxy.example", ""}, {"http://proxy.example:0", ""}, {"http://proxy.example:65536", ""}, {"http://proxy.example:8080/path", ""}, {"http://proxy.example:8080", "bad..example"}, {"http://proxy.example:8080", "*.bad*domain"}, {"http://proxy.example:8080", "[127.0.0.1]"}, {"http://proxy.example:8080", "host:0"}, {"http://proxy.example:8080", "192.168.1.0/99"}, {"http://proxy.example:8080", strings.Repeat("a", 4097)},
	} {
		if _, err := NewProxyPolicy(tc.proxy, tc.bypass); err == nil {
			t.Errorf("accepted proxy=%q bypass=%q", tc.proxy, tc.bypass)
		}
	}
	if p, err := NewProxyPolicy("", ""); err != nil || p == nil || p.UseProxy("http://public.example") {
		t.Fatalf("empty policy: policy=%v err=%v", p, err)
	}
}

// TRN-T-117, TRN-T-119
func TestProxy_HTTPClientAndDoDoNotFollowRedirects(t *testing.T) {
	var targetHits int
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { targetHits++; w.WriteHeader(http.StatusNoContent) }))
	defer target.Close()
	redirect := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, target.URL, http.StatusFound) }))
	defer redirect.Close()
	policy, err := NewProxyPolicy("", "")
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

// TRN-T-112, TRN-T-119
func TestProxy_ExplicitHTTPProxySelection(t *testing.T) {
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.RequestURI, "http://origin.invalid/") {
			t.Errorf("proxy received URI %q", r.RequestURI)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer proxy.Close()
	policy, err := NewProxyPolicy(proxy.URL, "")
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
