package transport

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

const invalidProxyConfig = "Invalid HTTP proxy or bypass configuration"

// ProxyPolicy contains only explicitly supplied card network policy; it never consults env proxies.
type ProxyPolicy struct {
	proxy  *url.URL
	bypass []bypassRule
}
type bypassRule struct {
	host, port string
	wildcard   bool
	cidr       *net.IPNet
	prefix     bool
}

// NewProxyPolicy validates an explicit proxy URL and comma-separated bypass patterns.
func NewProxyPolicy(proxyURL, noProxyHosts string) (*ProxyPolicy, error) {
	p := &ProxyPolicy{}
	if strings.TrimSpace(proxyURL) != "" {
		u, err := url.Parse(proxyURL)
		if err != nil || u.Scheme != "http" || u.Hostname() == "" || u.Port() == "" || u.User != nil || u.Path != "" || u.RawQuery != "" || u.Fragment != "" {
			return nil, fmt.Errorf("%s", invalidProxyConfig)
		}
		port, err := strconv.Atoi(u.Port())
		if err != nil || port < 1 || port > 65535 {
			return nil, fmt.Errorf("%s", invalidProxyConfig)
		}
		p.proxy = u
	}
	for _, raw := range strings.Split(noProxyHosts, ",") {
		s := strings.TrimSpace(strings.ToLower(raw))
		if s == "" {
			continue
		}
		r := bypassRule{}
		if _, n, err := net.ParseCIDR(s); err == nil {
			r.cidr = n
			p.bypass = append(p.bypass, r)
			continue
		}
		if strings.Contains(s, "/") || strings.ContainsAny(s, " \t/@") {
			return nil, fmt.Errorf("%s", invalidProxyConfig)
		}
		if strings.HasPrefix(s, "*.") {
			r.host, r.wildcard = strings.TrimPrefix(s, "*."), true
		} else if strings.HasPrefix(s, ".") {
			r.host, r.prefix = strings.TrimPrefix(s, "."), true
		} else {
			r.host = s
		}
		if strings.Contains(r.host, ":") { // bracketed IPv6 literals are retained; an optional port follows the bracket.
			if strings.HasPrefix(r.host, "[") {
				end := strings.IndexByte(r.host, ']')
				if end < 0 {
					return nil, fmt.Errorf("%s", invalidProxyConfig)
				}
				if end+1 < len(r.host) {
					if r.host[end+1] != ':' {
						return nil, fmt.Errorf("%s", invalidProxyConfig)
					}
					r.port = r.host[end+2:]
					r.host = r.host[1:end]
				} else {
					r.host = r.host[1:end]
				}
			} else {
				idx := strings.LastIndexByte(r.host, ':')
				if idx < 1 {
					return nil, fmt.Errorf("%s", invalidProxyConfig)
				}
				r.port, r.host = r.host[idx+1:], r.host[:idx]
			}
		}
		if strings.HasSuffix(r.host, ".*") && r.host == "127.*" {
			r.prefix = true
			r.host = "127."
		}
		if r.port != "" {
			n, err := strconv.Atoi(r.port)
			if err != nil || n < 1 || n > 65535 {
				return nil, fmt.Errorf("%s", invalidProxyConfig)
			}
		}
		p.bypass = append(p.bypass, r)
	}
	return p, nil
}

// UseProxy reports whether target should use the explicit proxy.
func (p *ProxyPolicy) UseProxy(target string) bool {
	if p == nil || p.proxy == nil {
		return false
	}
	u, err := url.Parse(target)
	if err != nil {
		return false
	}
	scheme := strings.ToLower(u.Scheme)
	if scheme != "http" && scheme != "https" && scheme != "ws" && scheme != "wss" {
		return false
	}
	host := strings.ToLower(u.Hostname())
	port := u.Port()
	if port == "" {
		if scheme == "https" || scheme == "wss" {
			port = "443"
		} else {
			port = "80"
		}
	}
	for _, r := range p.bypass {
		if r.port != "" && r.port != port {
			continue
		}
		if r.cidr != nil {
			ip := net.ParseIP(host)
			if ip != nil && sameIPFamily(ip, r.cidr.IP) && r.cidr.Contains(ip) {
				return false
			}
			continue
		}
		if r.prefix && r.host == "127." && strings.HasPrefix(host, r.host) {
			return false
		}
		if r.prefix && strings.HasSuffix(host, r.host) {
			return false
		}
		if r.wildcard && host != r.host && strings.HasSuffix(host, "."+r.host) {
			return false
		}
		if !r.prefix && !r.wildcard && host == r.host {
			return false
		}
	}
	return true
}

func sameIPFamily(a, b net.IP) bool { return (a.To4() != nil) == (b.To4() != nil) }

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
