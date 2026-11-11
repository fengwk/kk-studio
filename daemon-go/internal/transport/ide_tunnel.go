package transport

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
)

func (c *ControlConnection) handleIDE(w http.ResponseWriter, r *http.Request) {
	id, epoch, subpath, ok := parseIDERoute(r.URL.EscapedPath())
	if !ok {
		http.Error(w, "invalid IDE path", http.StatusBadRequest)
		return
	}
	if c.resolver == nil {
		http.Error(w, "IDE view unavailable", http.StatusServiceUnavailable)
		return
	}
	mode := "http"
	if r.Method == http.MethodConnect {
		mode = "websocket"
	}
	up, err := c.resolver.ResolveView(r.Context(), ViewRequest{ViewID: id, BindingEpoch: epoch, Mode: mode, Method: r.Method, Path: subpath, Headers: r.Header.Clone()})
	if err != nil {
		http.Error(w, "IDE view resolution failed", resolverStatus(err))
		return
	}
	if up.Release != nil {
		defer up.Release()
	}
	if err := validateViewUpstream(up); err != nil {
		http.Error(w, "invalid IDE upstream", http.StatusBadGateway)
		return
	}
	if r.Method == http.MethodConnect {
		c.tunnelIDEWebSocket(w, r, up, subpath)
		return
	}
	c.proxyIDEHTTP(w, r, up, subpath)
}

var ideUUID = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

func parseIDERoute(path string) (string, int64, string, bool) {
	parts := strings.Split(strings.TrimPrefix(path, "/"), "/")
	if len(parts) < 3 || parts[0] != "ide" || !ideUUID.MatchString(parts[1]) || parts[2] == "" || parts[2] == "0" || (len(parts[2]) > 1 && parts[2][0] == '0') {
		return "", 0, "", false
	}
	epoch, err := strconv.ParseInt(parts[2], 10, 64)
	if err != nil || epoch < 1 {
		return "", 0, "", false
	}
	subpath := "/"
	if len(parts) > 3 {
		subpath = "/" + strings.Join(parts[3:], "/")
	}
	return parts[1], epoch, subpath, true
}

func resolverStatus(err error) int {
	s := strings.ToUpper(err.Error())
	if strings.Contains(s, "VIEW_GONE") {
		return http.StatusNotFound
	}
	if strings.Contains(s, "UNAVAILABLE") {
		return http.StatusServiceUnavailable
	}
	return http.StatusBadGateway
}

func validateViewUpstream(up ViewUpstream) error {
	ip := net.ParseIP(strings.Trim(up.Host, "[]"))
	if ip == nil || !ip.IsLoopback() || (ip.To4() == nil && !ip.Equal(net.ParseIP("::1"))) || up.Port < 1 || up.Port > 65535 {
		return fmt.Errorf("invalid loopback upstream")
	}
	if up.Path == "" || !strings.HasPrefix(up.Path, "/") || strings.HasPrefix(up.Path, "//") {
		return fmt.Errorf("invalid upstream path")
	}
	if strings.ContainsAny(up.Path, "\r\n\\") {
		return fmt.Errorf("invalid upstream path")
	}
	for k, values := range up.Headers {
		if !validHeaderName(k) {
			return fmt.Errorf("invalid upstream header")
		}
		for _, v := range values {
			if strings.ContainsAny(v, "\r\n") {
				return fmt.Errorf("invalid upstream header")
			}
		}
	}
	if strings.ContainsAny(up.Cookie, "\r\n") {
		return fmt.Errorf("invalid upstream cookie")
	}
	for _, p := range up.Protocols {
		if p == "" || strings.ContainsAny(p, " \t\r\n,") {
			return fmt.Errorf("invalid protocol")
		}
	}
	return nil
}

func validHeaderName(s string) bool {
	if s == "" {
		return false
	}
	for _, c := range s {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || strings.ContainsRune("!#$%&'*+-.^_`|~", c)) {
			return false
		}
	}
	return true
}

func (c *ControlConnection) proxyIDEHTTP(w http.ResponseWriter, r *http.Request, up ViewUpstream, subpath string) {
	path := strings.TrimSuffix(up.Path, "/") + subpath
	if r.URL.RawQuery != "" {
		path += "?" + r.URL.RawQuery
	}
	urlText := "http://" + net.JoinHostPort(strings.Trim(up.Host, "[]"), strconv.Itoa(up.Port)) + path
	req, err := http.NewRequestWithContext(r.Context(), r.Method, urlText, r.Body)
	if err != nil {
		http.Error(w, "invalid IDE request", http.StatusBadRequest)
		return
	}
	req.Header = cloneEndToEndHeaders(r.Header)
	for k, vals := range up.Headers {
		for _, v := range vals {
			req.Header.Add(k, v)
		}
	}
	if up.Cookie != "" {
		req.Header.Set("Cookie", up.Cookie)
	}
	resp, err := (&http.Client{Transport: &http.Transport{Proxy: nil, DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "tcp", net.JoinHostPort(strings.Trim(up.Host, "[]"), strconv.Itoa(up.Port)))
	}}}).Do(req)
	if err != nil {
		http.Error(w, "IDE upstream unavailable", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	for k, vals := range resp.Header {
		if !isHopHeader(k, resp.Header) {
			for _, v := range vals {
				w.Header().Add(k, v)
			}
		}
	}
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
}

func cloneEndToEndHeaders(src http.Header) http.Header {
	dst := make(http.Header)
	for k, v := range src {
		if !isHopHeader(k, src) && !strings.EqualFold(k, "Host") {
			dst[k] = append([]string(nil), v...)
		}
	}
	return dst
}
func isHopHeader(name string, h http.Header) bool {
	switch strings.ToLower(name) {
	case "connection", "proxy-connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade":
		return true
	}
	for _, v := range h.Values("Connection") {
		for _, token := range strings.Split(v, ",") {
			if strings.EqualFold(strings.TrimSpace(token), name) {
				return true
			}
		}
	}
	return false
}

func (c *ControlConnection) tunnelIDEWebSocket(w http.ResponseWriter, r *http.Request, up ViewUpstream, subpath string) {
	addr := net.JoinHostPort(strings.Trim(up.Host, "[]"), strconv.Itoa(up.Port))
	upstream, err := (&net.Dialer{}).DialContext(r.Context(), "tcp", addr)
	if err != nil {
		http.Error(w, "IDE upstream unavailable", http.StatusBadGateway)
		return
	}
	defer upstream.Close()
	path := strings.TrimSuffix(up.Path, "/") + subpath
	if r.URL.RawQuery != "" {
		path += "?" + r.URL.RawQuery
	}
	var req strings.Builder
	fmt.Fprintf(&req, "GET %s HTTP/1.1\r\nHost: %s\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n", path, addr)
	for k, vs := range up.Headers {
		for _, v := range vs {
			fmt.Fprintf(&req, "%s: %s\r\n", k, v)
		}
	}
	if up.Cookie != "" {
		fmt.Fprintf(&req, "Cookie: %s\r\n", up.Cookie)
	}
	if len(up.Protocols) > 0 {
		fmt.Fprintf(&req, "Sec-WebSocket-Protocol: %s\r\n", strings.Join(up.Protocols, ", "))
	}
	req.WriteString("\r\n")
	if _, err = io.WriteString(upstream, req.String()); err != nil {
		http.Error(w, "IDE upstream unavailable", http.StatusBadGateway)
		return
	}
	br := bufio.NewReader(upstream)
	response, err := http.ReadResponse(br, &http.Request{Method: http.MethodGet})
	if err != nil {
		http.Error(w, "IDE upstream unavailable", http.StatusBadGateway)
		return
	}
	if response.StatusCode != http.StatusSwitchingProtocols {
		for k, vs := range response.Header {
			if strings.EqualFold(k, "Set-Cookie") || strings.EqualFold(k, "Content-Type") {
				for _, v := range vs {
					w.Header().Add(k, v)
				}
			}
		}
		w.WriteHeader(response.StatusCode)
		_, _ = io.Copy(w, response.Body)
		return
	}
	for _, h := range []string{"Sec-WebSocket-Accept", "Sec-WebSocket-Protocol", "Sec-WebSocket-Extensions"} {
		if v := response.Header.Values(h); len(v) > 0 {
			w.Header()[http.CanonicalHeaderKey(h)] = v
		}
	}
	w.WriteHeader(http.StatusOK)
	if f, ok := w.(http.Flusher); ok {
		f.Flush()
	}
	client := io.ReadWriteCloser(&responseBodyWriter{reader: r.Body, writer: w})
	bridgeDuplex(r.Context(), client, &bufferedConn{Conn: upstream, reader: br})
}

type responseBodyWriter struct {
	reader io.Reader
	writer io.Writer
}

func (p *responseBodyWriter) Read(b []byte) (int, error)  { return p.reader.Read(b) }
func (p *responseBodyWriter) Write(b []byte) (int, error) { return p.writer.Write(b) }
func (*responseBodyWriter) Close() error                  { return nil }

type bufferedConn struct {
	net.Conn
	reader *bufio.Reader
}

func (c *bufferedConn) Read(b []byte) (int, error) { return c.reader.Read(b) }
func bridgeDuplex(ctx context.Context, a, b io.ReadWriteCloser) {
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); _, _ = io.Copy(a, b); _ = a.Close() }()
	go func() { defer wg.Done(); _, _ = io.Copy(b, a); _ = b.Close() }()
	go func() {
		select {
		case <-ctx.Done():
			_ = a.Close()
			_ = b.Close()
		case <-func() chan struct{} { ch := make(chan struct{}); go func() { wg.Wait(); close(ch) }(); return ch }():
		}
	}()
	wg.Wait()
}
