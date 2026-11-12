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
	portSet    bool
	wildcard   bool
	cidr       *net.IPNet
	prefix     bool
	suffix     bool
}

// NewProxyPolicy validates an explicit proxy URL and comma-separated bypass patterns.
func NewProxyPolicy(proxyURL, noProxyHosts string) (*ProxyPolicy, error) {
	p := &ProxyPolicy{}
	if strings.TrimSpace(proxyURL) != "" {
		u, err := url.Parse(proxyURL)
		if err != nil || u.Scheme != "http" || u.Hostname() == "" || u.Port() == "" || u.User != nil || u.Opaque != "" || u.Path != "" || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || u.RawFragment != "" {
			return nil, fmt.Errorf("%s", invalidProxyConfig)
		}
		port, err := strconv.Atoi(u.Port())
		if err != nil || port < 1 || port > 65535 {
			return nil, fmt.Errorf("%s", invalidProxyConfig)
		}
		p.proxy = u
	}
	if len(noProxyHosts) > 4096 {
		return nil, fmt.Errorf("%s", invalidProxyConfig)
	}
	if noProxyHosts != "" {
		for _, raw := range strings.Split(noProxyHosts, ",") {
			s := strings.TrimSpace(strings.ToLower(raw))
			if s == "" {
				return nil, fmt.Errorf("%s", invalidProxyConfig)
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
				r.host = strings.TrimPrefix(s, "*.")
				r.suffix = true
			} else if strings.HasPrefix(s, ".") {
				r.host = strings.TrimPrefix(s, ".")
				r.suffix = true
			} else {
				r.host = s
			}
			if strings.HasPrefix(r.host, "[") {
				end := strings.IndexByte(r.host, ']')
				if end < 0 {
					return nil, fmt.Errorf("%s", invalidProxyConfig)
				}
				if end+1 < len(r.host) {
					if r.host[end+1] != ':' {
						return nil, fmt.Errorf("%s", invalidProxyConfig)
					}
					r.portSet = true
					r.port = r.host[end+2:]
				}
				r.host = r.host[1:end]
				ip := net.ParseIP(r.host)
				if ip == nil || ip.To4() != nil {
					return nil, fmt.Errorf("%s", invalidProxyConfig)
				}
			} else if strings.Count(r.host, ":") == 1 {
				idx := strings.LastIndexByte(r.host, ':')
				r.portSet = true
				r.port, r.host = r.host[idx+1:], r.host[:idx]
			} else if strings.Contains(r.host, ":") && net.ParseIP(r.host) == nil {
				return nil, fmt.Errorf("%s", invalidProxyConfig)
			}
			if r.host == "127.*" {
				r.prefix = true
				r.host = "127."
			}
			if !strings.Contains(r.host, ":") {
				r.host = strings.TrimSuffix(r.host, ".")
			}
			if r.host == "*" {
				r.wildcard = true
			} else if r.prefix { // exact IPv4 wildcard syntax
			} else if r.suffix && !validBypassDomain(r.host) {
				return nil, fmt.Errorf("%s", invalidProxyConfig)
			} else if !validBypassHost(r.host) {
				return nil, fmt.Errorf("%s", invalidProxyConfig)
			}
			if r.portSet {
				n, err := strconv.Atoi(r.port)
				if err != nil || n < 1 || n > 65535 {
					return nil, fmt.Errorf("%s", invalidProxyConfig)
				}
			}
			p.bypass = append(p.bypass, r)
		}
	}
	return p, nil
}

// UseProxy reports whether target should use the explicit proxy.
func (p *ProxyPolicy) UseProxy(target string) bool {
	if p == nil || p.proxy == nil {
		return false
	}
	u, err := url.Parse(target)
	if err != nil || u.Hostname() == "" || u.Opaque != "" {
		return false
	}
	scheme := strings.ToLower(u.Scheme)
	if scheme != "http" && scheme != "https" && scheme != "ws" && scheme != "wss" {
		return false
	}
	host := strings.ToLower(u.Hostname())
	if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
		return false
	}
	port := u.Port()
	if port == "" {
		if scheme == "https" || scheme == "wss" {
			port = "443"
		} else {
			port = "80"
		}
	}
	for _, r := range p.bypass {
		if r.portSet && r.port != port {
			continue
		}
		if r.cidr != nil {
			ip := net.ParseIP(host)
			if ip != nil && strings.Contains(host, ":") == (len(r.cidr.IP) == net.IPv6len) && r.cidr.Contains(ip) {
				return false
			}
			continue
		}
		if r.wildcard || r.prefix && strings.HasPrefix(host, "127.") && net.ParseIP(host) != nil {
			return false
		}
		if host == r.host || r.suffix && strings.HasSuffix(host, "."+r.host) {
			return false
		}
	}
	return true
}

func validBypassDomain(host string) bool {
	if strings.Contains(host, ":") || strings.Contains(host, "*") || net.ParseIP(host) != nil {
		return false
	}
	return validBypassHost(host)
}

func validBypassHost(host string) bool {
	if host == "" {
		return false
	}
	if net.ParseIP(host) != nil {
		return true
	}
	if strings.ContainsAny(host, "*:/\\[]@") {
		return false
	}
	for _, label := range strings.Split(host, ".") {
		if label == "" || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, r := range label {
			if !(r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '-') {
				return false
			}
		}
	}
	return true
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
