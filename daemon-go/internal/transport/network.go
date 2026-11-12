package transport

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

const invalidProxyConfig = "Invalid HTTP proxy configuration"

// ProxyPolicy uses only the explicitly applied proxy URL; the daemon has no bypass setting.
type ProxyPolicy struct {
	proxy *url.URL
}

// NewProxyPolicy validates the explicitly applied HTTP proxy URL.
func NewProxyPolicy(proxyURL string) (*ProxyPolicy, error) {
	p := &ProxyPolicy{}
	if strings.TrimSpace(proxyURL) == "" {
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

// UseProxy reports whether target uses the explicitly configured proxy.
func (p *ProxyPolicy) UseProxy(target string) bool {
	if p == nil || p.proxy == nil {
		return false
	}
	u, err := url.Parse(target)
	if err != nil || u.Hostname() == "" || u.Opaque != "" {
		return false
	}
	scheme := strings.ToLower(u.Scheme)
	return scheme == "http" || scheme == "https" || scheme == "ws" || scheme == "wss"
}

// HTTPClient creates an isolated client whose transport is bound to this policy.
func (p *ProxyPolicy) HTTPClient() *http.Client {
	tr := &http.Transport{Proxy: nil}
	if p != nil && p.proxy != nil {
		tr.Proxy = func(req *http.Request) (*url.URL, error) {
			if p.UseProxy(req.URL.String()) {
				return p.proxy, nil
			}
			return nil, nil
		}
	}
	return &http.Client{Transport: tr, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
}

// Do executes an outbound request with request cancellation and explicit proxy semantics.
func (p *ProxyPolicy) Do(ctx context.Context, req *http.Request) (*http.Response, error) {
	return p.HTTPClient().Do(req.WithContext(ctx))
}
