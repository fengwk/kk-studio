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

// TRN-T-114
func TestProxy_IPv4CIDR(t *testing.T) {
	p, err := NewProxyPolicy("http://proxy.example:8080", "192.168.1.99/24")
	if err != nil {
		t.Fatal(err)
	}
	for target, want := range map[string]bool{"http://192.168.1.1": false, "http://192.168.2.1": true, "http://private.internal": true} {
		if got := p.UseProxy(target); got != want {
			t.Errorf("UseProxy(%q)=%t want %t", target, got, want)
		}
	}
}

// TRN-T-115, TRN-T-116
func TestProxy_IPv6CIDRFamily(t *testing.T) {
	p, err := NewProxyPolicy("http://proxy.example:8080", "2001:db8::/32")
	if err != nil {
		t.Fatal(err)
	}
	if p.UseProxy("http://[2001:db8::2]") || !p.UseProxy("http://192.0.2.1") {
		t.Fatal("CIDR matched wrong address family")
	}
	mapped, err := NewProxyPolicy("http://proxy.example:8080", "::ffff:192.0.2.0/120")
	if err != nil {
		t.Fatal(err)
	}
	if mapped.UseProxy("http://[::ffff:192.0.2.1]") || !mapped.UseProxy("http://192.0.2.1") {
		t.Fatal("IPv4-mapped IPv6 CIDR lost its address family")
	}
}

// TRN-T-112
func TestProxy_DomainAndLoopbackBypass(t *testing.T) {
	p, err := NewProxyPolicy("http://proxy.example:8080", ".internal,127.*")
	if err != nil {
		t.Fatal(err)
	}
	for _, target := range []string{"http://internal", "http://a.internal", "http://127.1.2.3", "http://[::1]"} {
		if p.UseProxy(target) {
			t.Errorf("proxy selected for bypass target %s", target)
		}
	}
	for _, target := range []string{"http://127.example", "http://xpublic.internal.example"} {
		if !p.UseProxy(target) {
			t.Errorf("bypass incorrectly selected for %s", target)
		}
	}
}

// TRN-T-118
func TestProxy_InvalidRedacted(t *testing.T) {
	_, err := NewProxyPolicy("http://user:secret@proxy:8080/path", "")
	if err == nil || err.Error() != "Invalid HTTP proxy or bypass configuration" {
		t.Fatalf("error=%v", err)
	}
}
