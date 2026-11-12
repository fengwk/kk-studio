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

// TRN-N-004, TRN-T-096, TRN-T-112 verify explicit proxies also apply to loopback destinations.
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

// TRN-N-004, TRN-T-068 verify a direct policy ignores unusable process proxy settings.
func TestProxy_DirectDialIgnoresEnvironmentProxy(t *testing.T) {
	t.Setenv("HTTP_PROXY", "http://127.0.0.1:1")
	t.Setenv("http_proxy", "http://127.0.0.1:1")
	t.Setenv("HTTPS_PROXY", "http://127.0.0.1:1")
	t.Setenv("https_proxy", "http://127.0.0.1:1")
	var hits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		hits.Add(1)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	policy, err := NewProxyPolicy("")
	if err != nil {
		t.Fatal(err)
	}
	response, err := policy.HTTPClient().Get(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	_ = response.Body.Close()
	if hits.Load() != 1 {
		t.Fatalf("direct target hits=%d, want 1", hits.Load())
	}
}

// TRN-N-004, TRN-T-099 verify an invalid proxy value cannot mutate an already-created policy.
func TestProxy_InvalidConfigurationDoesNotReplaceExistingPolicy(t *testing.T) {
	policy, err := NewProxyPolicy("http://proxy.example:8080")
	if err != nil {
		t.Fatal(err)
	}
	request, err := http.NewRequest(http.MethodGet, "http://target.example", nil)
	if err != nil {
		t.Fatal(err)
	}
	before, err := policy.HTTPClient().Transport.(*http.Transport).Proxy(request)
	if err != nil || before == nil || before.String() != "http://proxy.example:8080" {
		t.Fatalf("initial policy proxy=%v err=%v", before, err)
	}
	if _, err = NewProxyPolicy("not a proxy URL"); err == nil {
		t.Fatal("invalid new proxy accepted")
	}
	after, err := policy.HTTPClient().Transport.(*http.Transport).Proxy(request)
	if err != nil || after == nil || after.String() != before.String() {
		t.Fatalf("existing policy changed after rejected replacement: proxy=%v err=%v", after, err)
	}
}

// TRN-N-004, TRN-T-118 verify invalid proxy errors are redacted and have the Go wording.
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
