package transport

import "testing"

// TRN-T-112
func TestProxy_BypassKinds(t *testing.T) {
	p, err := NewProxyPolicy("http://proxy.example:8080", "exact.example,*.example.org,.internal,127.*")
	if err != nil {
		t.Fatal(err)
	}
	for _, target := range []string{"http://exact.example/a", "https://x.example.org", "http://a.internal", "http://127.0.0.4"} {
		if p.UseProxy(target) {
			t.Errorf("proxy selected for %s", target)
		}
	}
	if !p.UseProxy("https://public.example") {
		t.Fatal("proxy not selected")
	}
}

// TRN-T-113
func TestProxy_BypassPort(t *testing.T) {
	p, err := NewProxyPolicy("http://proxy.example:8080", "example.test:443")
	if err != nil {
		t.Fatal(err)
	}
	if p.UseProxy("https://example.test") {
		t.Fatal("expected bypass")
	}
	if !p.UseProxy("http://example.test") {
		t.Fatal("bypass incorrectly matched other port")
	}
}

// TRN-T-118
func TestProxy_InvalidRedacted(t *testing.T) {
	_, err := NewProxyPolicy("http://user:secret@proxy:8080/path", "")
	if err == nil || err.Error() != "Invalid HTTP proxy or bypass configuration" {
		t.Fatalf("error=%v", err)
	}
}
