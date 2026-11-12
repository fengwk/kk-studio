package transport

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
)

const invalidProxyConfig = "Invalid HTTP proxy configuration"

// ProxyPolicy applies only the explicitly configured proxy URL; it never consults environment proxies.
type ProxyPolicy struct {
	proxy *url.URL
}

// NewProxyPolicy validates the optional explicit HTTP proxy URL.
func NewProxyPolicy(proxyURL string) (*ProxyPolicy, error) {
	p := &ProxyPolicy{}
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
	p.proxy = u
	return p, nil
}

// HTTPClient creates an isolated client whose transport uses the configured proxy without bypass rules.
func (p *ProxyPolicy) HTTPClient() *http.Client {
	tr := &http.Transport{Proxy: nil}
	if p != nil && p.proxy != nil {
		tr.Proxy = http.ProxyURL(p.proxy)
	}
	return &http.Client{Transport: tr, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
}

// Do executes an outbound request with request cancellation and explicit proxy semantics.
func (p *ProxyPolicy) Do(ctx context.Context, req *http.Request) (*http.Response, error) {
	return p.HTTPClient().Do(req.WithContext(ctx))
}
