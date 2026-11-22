package transport

import (
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"time"
)

const invalidProxyConfig = "Invalid HTTP proxy configuration"

// ProxyPolicy owns the HTTP connection pool for an explicit proxy or direct policy.
// It never consults environment proxies.
type ProxyPolicy struct {
	transport *http.Transport
}

// NewProxyPolicy validates the optional explicit HTTP proxy URL.
func NewProxyPolicy(proxyURL string) (*ProxyPolicy, error) {
	p := &ProxyPolicy{transport: &http.Transport{
		IdleConnTimeout:     90 * time.Second,
		MaxIdleConns:        100,
		MaxIdleConnsPerHost: 4,
	}}
	if proxyURL == "" {
		return p, nil
	}
	u, err := url.Parse(proxyURL)
	if err != nil || u.Scheme != "http" || u.Hostname() == "" || u.Port() == "" || u.User != nil || u.Opaque != "" || u.Path != "" || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || u.RawFragment != "" {
		return nil, fmt.Errorf("%s", invalidProxyConfig)
	}
	port, err := strconv.Atoi(u.Port())
	if err != nil || port < 1 || port > 65535 {
		return nil, fmt.Errorf("%s", invalidProxyConfig)
	}
	p.transport.Proxy = func(r *http.Request) (*url.URL, error) {
		// IDE requests are pinned to the resolver's validated local address. Unlike
		// ordinary requests to a loopback URL, they must never reach an external proxy.
		if addr, ok := r.Context().Value(ideUpstreamAddressKey{}).(string); ok && r.URL.Host == addr {
			return nil, nil
		}
		return u, nil
	}
	return p, nil
}

// HTTPClient creates a client using the policy's shared transport. Redirects are not followed.
func (p *ProxyPolicy) HTTPClient() *http.Client {
	if p == nil {
		p, _ = NewProxyPolicy("")
	}
	return &http.Client{Transport: p.transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
}
