package transport

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

// TRN-T-111 verifies only an explicitly configured proxy is used and failures do not fall back.
func TestProxy_ExplicitNoFallback(t *testing.T) {
	var targetHits, proxyHits atomic.Int32
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		targetHits.Add(1)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer target.Close()
	proxyServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		proxyHits.Add(1)
		conn, _, err := w.(http.Hijacker).Hijack()
		if err != nil {
			t.Errorf("hijack proxy connection: %v", err)
			return
		}
		_ = conn.Close()
	}))
	defer proxyServer.Close()
	policy, err := NewProxyPolicy(proxyServer.URL)
	if err != nil {
		t.Fatal(err)
	}
	resp, err := policy.HTTPClient().Get(target.URL)
	if err == nil {
		if resp != nil {
			_ = resp.Body.Close()
		}
		t.Fatal("request unexpectedly succeeded through a failed proxy")
	}
	if proxyHits.Load() != 1 || targetHits.Load() != 0 {
		t.Fatalf("failed proxy requests=%d direct target requests=%d", proxyHits.Load(), targetHits.Load())
	}
}

// TRN-T-112 verifies configured proxies apply to public and loopback destinations without bypass rules.
func TestProxy_ExplicitProxyAppliesToDestinations(t *testing.T) {
	p, err := NewProxyPolicy("http://proxy.example:8080")
	if err != nil {
		t.Fatal(err)
	}
	proxy := p.HTTPClient().Transport.(*http.Transport).Proxy
	for _, target := range []string{"http://public.example", "http://127.0.0.1"} {
		request, err := http.NewRequest(http.MethodGet, target, nil)
		if err != nil {
			t.Fatal(err)
		}
		got, err := proxy(request)
		if err != nil || got == nil || got.String() != "http://proxy.example:8080" {
			t.Errorf("proxy(%q)=%v, %v", target, got, err)
		}
	}
	direct, err := NewProxyPolicy("")
	if err != nil {
		t.Fatal(err)
	}
	if direct.HTTPClient().Transport.(*http.Transport).Proxy != nil {
		t.Fatal("direct policy configured an HTTP proxy")
	}
}

// TRN-T-118 verifies malformed or credential-bearing proxy settings are rejected without leaking them.
func TestProxy_InvalidRedacted(t *testing.T) {
	for _, target := range []string{
		"https://proxy.example:443",
		"http://proxy.example",
		"http://proxy.example:0",
		"http://proxy.example:65536",
		"http://proxy.example:8080/path",
		"http://user:secret@proxy.example:8080",
		"http://proxy.example:8080?query=secret",
	} {
		_, err := NewProxyPolicy(target)
		if err == nil || err.Error() != "Invalid HTTP proxy configuration" {
			t.Errorf("proxy %q error=%v", target, err)
		}
		if err != nil && (strings.Contains(err.Error(), "secret") || strings.Contains(err.Error(), "proxy.example")) {
			t.Errorf("error leaked proxy configuration: %v", err)
		}
	}
}
