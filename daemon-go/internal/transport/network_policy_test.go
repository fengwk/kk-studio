package transport

import (
	"net/http"
	"testing"
)

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
}

// TRN-T-118 verifies invalid proxy configuration is rejected without exposing credentials.
func TestProxy_InvalidRedacted(t *testing.T) {
	_, err := NewProxyPolicy("http://user:secret@proxy:8080/path")
	if err == nil || err.Error() != "Invalid HTTP proxy configuration" {
		t.Fatalf("error=%v", err)
	}
}
