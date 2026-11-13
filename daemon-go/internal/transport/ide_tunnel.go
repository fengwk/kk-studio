package transport

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"regexp"
	"strconv"
	"strings"
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
		if r.Header.Get(":protocol") != "websocket" {
			http.Error(w, "unsupported CONNECT protocol", http.StatusBadRequest)
			return
		}
		mode = "websocket"
	}
	viewPath := subpath
	if r.URL.RawQuery != "" {
		viewPath += "?" + r.URL.RawQuery
	}
	headers := r.Header.Clone()
	if mode == "websocket" {
		headers.Del(":protocol")
	}
	up, err := c.resolver.ResolveView(r.Context(), ViewRequest{ViewID: id, BindingEpoch: epoch, Mode: mode, Method: r.Method, Path: viewPath, Headers: headers})
	if err != nil {
		http.Error(w, "IDE view resolution failed", resolverStatus(err))
		return
	}
	if up.Release != nil {
		defer up.Release()
	}
	upCtx, cancel := upstreamContext(r.Context(), up.Context)
	defer cancel()
	r = r.WithContext(upCtx)
	if err := validateViewUpstream(up); err != nil {
		http.Error(w, "invalid IDE upstream", http.StatusBadGateway)
		return
	}
	if r.Method == http.MethodConnect {
		c.tunnelIDEWebSocket(w, r, up)
		return
	}
	c.proxyIDEHTTP(w, r, up)
}

var ideUUID = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

func parseIDERoute(path string) (string, int64, string, bool) {
	parts := strings.Split(strings.TrimPrefix(path, "/"), "/")
	if len(parts) < 4 || parts[0] != "ide" || !ideUUID.MatchString(parts[1]) || parts[2] == "" || parts[2] == "0" || (len(parts[2]) > 1 && parts[2][0] == '0') {
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
	var coded interface{ ErrorCode() string }
	if errors.As(err, &coded) {
		switch coded.ErrorCode() {
		case "VIEW_GONE":
			return http.StatusNotFound
		case "UNAVAILABLE":
			return http.StatusServiceUnavailable
		}
	}
	s := strings.ToUpper(err.Error())
	if strings.Contains(s, "VIEW_GONE") {
		return http.StatusNotFound
	}
	if strings.Contains(s, "UNAVAILABLE") {
		return http.StatusServiceUnavailable
	}
	return http.StatusBadGateway
}

func upstreamContext(parent, upstream context.Context) (context.Context, context.CancelFunc) {
	ctx, cancel := context.WithCancel(parent)
	if upstream == nil {
		return ctx, cancel
	}
	stop := context.AfterFunc(upstream, cancel)
	return ctx, func() { stop(); cancel() }
}

func validateViewUpstream(up ViewUpstream) error {
	host := up.Host
	if strings.HasPrefix(host, "[") && strings.HasSuffix(host, "]") {
		host = host[1 : len(host)-1]
	}
	ip := net.ParseIP(host)
	loopback := host == "::1" || ip != nil && !strings.Contains(host, ":") && ip.To4() != nil && ip.IsLoopback()
	if !loopback || up.Port < 1 || up.Port > 65535 {
		return fmt.Errorf("invalid loopback upstream")
	}
	if up.Path == "" || !strings.HasPrefix(up.Path, "/") || strings.HasPrefix(up.Path, "//") {
		return fmt.Errorf("invalid upstream path")
	}
	if hasUnsafeHeaderCharacter(up.Path) {
		return fmt.Errorf("invalid upstream path")
	}
	for k, values := range up.Headers {
		if !validHeaderName(k) {
			return fmt.Errorf("invalid upstream header")
		}
		for _, v := range values {
			if hasUnsafeHeaderCharacter(v) {
				return fmt.Errorf("invalid upstream header")
			}
		}
	}
	if hasUnsafeHeaderCharacter(up.Cookie) {
		return fmt.Errorf("invalid upstream cookie")
	}
	for _, p := range up.Protocols {
		if !validHeaderName(p) {
			return fmt.Errorf("invalid protocol")
		}
	}
	return nil
}

func hasUnsafeHeaderCharacter(s string) bool {
	for _, r := range s {
		if r < 0x20 || r == 0x7f {
			return true
		}
	}
	return false
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

func (c *ControlConnection) proxyIDEHTTP(w http.ResponseWriter, r *http.Request, up ViewUpstream) {
	path := up.Path
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
	if _, err = io.Copy(w, resp.Body); err != nil {
		panic(http.ErrAbortHandler)
	}
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

func (c *ControlConnection) tunnelIDEWebSocket(w http.ResponseWriter, r *http.Request, up ViewUpstream) {
	addr := net.JoinHostPort(strings.Trim(up.Host, "[]"), strconv.Itoa(up.Port))
	upstream, err := (&net.Dialer{}).DialContext(r.Context(), "tcp", addr)
	if err != nil {
		http.Error(w, "IDE upstream unavailable", http.StatusBadGateway)
		return
	}
	defer upstream.Close()
	path := up.Path
	targetURL := "http://" + addr + path
	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, targetURL, nil)
	if err != nil {
		http.Error(w, "IDE upstream unavailable", http.StatusBadGateway)
		return
	}
	req.Host = addr
	req.Header = cloneEndToEndHeaders(up.Headers)
	req.Header.Del("Host")
	req.Header.Set("Connection", "Upgrade")
	req.Header.Set("Upgrade", "websocket")
	if up.Cookie != "" {
		req.Header.Set("Cookie", up.Cookie)
	}
	for _, name := range []string{"Sec-WebSocket-Key", "Sec-WebSocket-Version", "Sec-WebSocket-Protocol", "Sec-WebSocket-Extensions"} {
		if values := r.Header.Values(name); len(values) > 0 {
			req.Header[name] = append([]string(nil), values...)
		}
	}
	if len(up.Protocols) > 0 && req.Header.Get("Sec-WebSocket-Protocol") == "" {
		req.Header.Set("Sec-WebSocket-Protocol", strings.Join(up.Protocols, ", "))
	}
	if err = req.Write(upstream); err != nil {
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
		if _, err = io.Copy(w, response.Body); err != nil {
			panic(http.ErrAbortHandler)
		}
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
	if err := bridgeDuplex(r.Context(), client, &bufferedConn{Conn: upstream, reader: br}); err != nil {
		panic(http.ErrAbortHandler)
	}
}

type responseBodyWriter struct {
	reader io.Reader
	writer io.Writer
}

func (p *responseBodyWriter) Read(b []byte) (int, error) { return p.reader.Read(b) }
func (p *responseBodyWriter) Write(b []byte) (int, error) {
	n, err := p.writer.Write(b)
	if flusher, ok := p.writer.(http.Flusher); ok && n > 0 {
		flusher.Flush()
	}
	return n, err
}
func (p *responseBodyWriter) Close() error {
	if closer, ok := p.reader.(io.Closer); ok {
		return closer.Close()
	}
	return nil
}

type bufferedConn struct {
	net.Conn
	reader *bufio.Reader
}

func (c *bufferedConn) Read(b []byte) (int, error) { return c.reader.Read(b) }
func bridgeDuplex(ctx context.Context, a, b io.ReadWriteCloser) error {
	stopped := context.AfterFunc(ctx, func() { _ = a.Close(); _ = b.Close() })
	defer stopped()
	errs := make(chan error, 2)
	go func() { _, err := io.Copy(a, b); errs <- err; _ = a.Close() }()
	go func() { _, err := io.Copy(b, a); errs <- err; _ = b.Close() }()
	first := <-errs
	_ = a.Close()
	_ = b.Close()
	second := <-errs
	if ctx.Err() != nil {
		return nil
	}
	if first != nil {
		return first
	}
	return second
}
