package ide

import (
	"context"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/fengwk/kk-studio/daemon/internal/transport"
)

const maxStreamPathBytes = 8192
const maxStreamHeaders = 100
const maxStreamHeaderBytes = 16 * 1024

var methodPattern = regexp.MustCompile(`^[!#$%&'*+\-.^_` + "`" + `|~0-9A-Za-z]+$`)
var headerNamePattern = regexp.MustCompile(`^[!#$%&'*+\-.^_` + "`" + `|~0-9A-Za-z]+$`)
var protocolTokenPattern = regexp.MustCompile(`^[!#$%&'*+\-.^_` + "`" + `|~0-9A-Za-z]+$`)

var filteredHeaders = map[string]struct{}{
	"host": {}, "connection": {}, "keep-alive": {}, "proxy-authenticate": {},
	"proxy-authorization": {}, "te": {}, "trailer": {}, "transfer-encoding": {},
	"upgrade": {}, "cookie": {}, "authorization": {}, "origin": {}, "referer": {},
	"sec-websocket-key": {}, "sec-websocket-version": {}, "sec-websocket-extensions": {},
}

type resolverBinding struct {
	epoch   int64
	port    int
	cookie  string
	streams map[uint64]context.CancelFunc
}

// Resolver is the only boundary that maps externally supplied view identity to code-server.
type Resolver struct {
	mu       sync.Mutex
	bindings map[string]*resolverBinding
	nextID   atomic.Uint64
}

// NewResolver creates an empty view resolver.
func NewResolver() *Resolver { return &Resolver{bindings: make(map[string]*resolverBinding)} }

// BindView publishes a new current binding and immediately cancels streams from its predecessor.
func (r *Resolver) BindView(viewID string, epoch int64, port int, cookie string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if old := r.bindings[viewID]; old != nil {
		for _, cancel := range old.streams {
			cancel()
		}
	}
	r.bindings[viewID] = &resolverBinding{epoch: epoch, port: port, cookie: cookie, streams: make(map[uint64]context.CancelFunc)}
}

// UnbindView removes only the matching binding; stale close operations cannot revoke a newer epoch.
func (r *Resolver) UnbindView(viewID string, epoch int64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	binding := r.bindings[viewID]
	if binding == nil || binding.epoch != epoch {
		return
	}
	for _, cancel := range binding.streams {
		cancel()
	}
	delete(r.bindings, viewID)
}

// ResolveView validates a data-channel request and returns its fixed loopback destination.
func (r *Resolver) ResolveView(ctx context.Context, request transport.ViewRequest) (transport.ViewUpstream, error) {
	if err := ctx.Err(); err != nil {
		return transport.ViewUpstream{}, err
	}
	viewID, err := ValidateViewID(request.ViewID)
	if err != nil {
		return transport.ViewUpstream{}, err
	}
	if request.BindingEpoch <= 0 {
		return transport.ViewUpstream{}, NewError("bindingEpoch is invalid", "UNAVAILABLE")
	}
	epoch := strconv.FormatInt(request.BindingEpoch, 10)
	path, err := upstreamPath(request, viewID, epoch)
	if err != nil {
		return transport.ViewUpstream{}, err
	}
	headers, protocols, err := upstreamHeaders(request.Headers)
	if err != nil {
		return transport.ViewUpstream{}, err
	}
	r.mu.Lock()
	binding := r.bindings[viewID]
	if binding == nil || binding.epoch != request.BindingEpoch {
		r.mu.Unlock()
		return transport.ViewUpstream{}, NewError("view is not open for this binding epoch", "VIEW_GONE")
	}
	if binding.port < 1 || binding.port > 65535 {
		r.mu.Unlock()
		return transport.ViewUpstream{}, NewError("IDE service is unavailable", "UNAVAILABLE")
	}
	streamID := r.nextID.Add(1)
	streamCtx, cancel := context.WithCancel(ctx)
	binding.streams[streamID] = cancel
	port, cookie := binding.port, binding.cookie
	r.mu.Unlock()
	var once sync.Once
	release := func() {
		once.Do(func() {
			cancel()
			r.mu.Lock()
			if current := r.bindings[viewID]; current != nil {
				delete(current.streams, streamID)
			}
			r.mu.Unlock()
		})
	}
	// Retain the stream context through the cancel closure registered above until release.
	_ = streamCtx
	return transport.ViewUpstream{
		Host: "127.0.0.1", Port: port, Path: path, Headers: headers, Cookie: cookie,
		Protocols: protocols, Release: release,
	}, nil
}

func upstreamPath(request transport.ViewRequest, viewID, epoch string) (string, error) {
	if request.Mode != "http" && request.Mode != "websocket" {
		return "", NewError("stream mode is invalid", "UNAVAILABLE")
	}
	if !methodPattern.MatchString(request.Method) {
		return "", NewError("stream method is invalid", "UNAVAILABLE")
	}
	path := request.Path
	if !strings.HasPrefix(path, "/") || strings.HasPrefix(path, "//") || strings.Contains(path, "\\") || len([]byte(path)) > maxStreamPathBytes || hasControl(path) {
		return "", NewError("stream path is invalid", "UNAVAILABLE")
	}
	pathname := path
	if i := strings.IndexByte(path, '?'); i >= 0 {
		pathname = path[:i]
	}
	for _, segment := range strings.Split(pathname, "/") {
		normalized := strings.ToLower(segment)
		if segment == "." || segment == ".." || normalized == "%2e" || normalized == "%2e%2e" || normalized == ".%2e" || normalized == "%2e." {
			return "", NewError("stream path is invalid", "UNAVAILABLE")
		}
	}
	base := "/__kk/ide/view/" + viewID + "/" + epoch
	if pathname == "/" {
		return base + path, nil
	}
	return base + path, nil
}

func upstreamHeaders(input http.Header) (http.Header, []string, error) {
	count, bytesUsed := 0, 0
	filtered := make(http.Header)
	var protocols []string
	for name, values := range input {
		for _, value := range values {
			count++
			if count > maxStreamHeaders || len(name) > 256 || !headerNamePattern.MatchString(name) || invalidHeaderValue(value) {
				return nil, nil, NewError("stream headers are invalid", "UNAVAILABLE")
			}
			bytesUsed += len(name) + len(value)
			if bytesUsed > maxStreamHeaderBytes {
				return nil, nil, NewError("stream headers are invalid", "UNAVAILABLE")
			}
			key := strings.ToLower(name)
			if key == "sec-websocket-protocol" {
				for _, part := range strings.Split(value, ",") {
					token := strings.TrimSpace(part)
					if token != "" && protocolTokenPattern.MatchString(token) {
						protocols = append(protocols, token)
					}
				}
				continue
			}
			if _, drop := filteredHeaders[key]; drop {
				continue
			}
			filtered.Add(name, value)
		}
	}
	return filtered, protocols, nil
}

func invalidHeaderValue(value string) bool {
	for _, r := range value {
		if r < 0x09 || r > 0x09 && r < 0x20 || r == 0x7f {
			return true
		}
	}
	return false
}
