package transport

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"sync"
)

// NetworkRuntime owns the outbound HTTP client for one applied network-policy lifetime.
// Each exchange is scoped by WithResponse; Close cancels active exchanges and waits for
// their response bodies to be closed, while RetireWhenIdle stops admission and drains them.
type NetworkRuntime struct {
	client *http.Client

	mu       sync.Mutex
	closed   bool
	retiring bool
	active   int
	idle     chan struct{}
	cancels  map[uint64]context.CancelFunc
	nextID   uint64
}

// NewNetworkRuntime creates an outbound runtime bound to one immutable proxy policy.
func NewNetworkRuntime(policy *ProxyPolicy) *NetworkRuntime {
	idle := make(chan struct{})
	close(idle)
	return &NetworkRuntime{client: policy.HTTPClient(), idle: idle, cancels: make(map[uint64]context.CancelFunc)}
}

// WithResponse performs one request and scopes its response body to use. The body is always
// closed before the exchange leaves the runtime; callback and cleanup errors are both retained.
func (n *NetworkRuntime) WithResponse(ctx context.Context, req *http.Request, use func(*http.Response) error) error {
	if req == nil || use == nil {
		return fmt.Errorf("outbound request and response handler are required")
	}
	requestCtx, cancel := context.WithCancel(ctx)
	n.mu.Lock()
	if n.closed || n.retiring {
		n.mu.Unlock()
		cancel()
		return fmt.Errorf("outbound network is closed")
	}
	if n.active == 0 {
		n.idle = make(chan struct{})
	}
	n.active++
	n.nextID++
	id := n.nextID
	n.cancels[id] = cancel
	n.mu.Unlock()
	defer func() {
		cancel()
		n.mu.Lock()
		delete(n.cancels, id)
		n.active--
		if n.active == 0 {
			close(n.idle)
		}
		n.mu.Unlock()
	}()

	resp, err := n.client.Do(req.WithContext(requestCtx))
	if err != nil {
		return fmt.Errorf("outbound request failed: %w", err)
	}
	useErr := use(resp)
	closeErr := resp.Body.Close()
	if useErr != nil && closeErr != nil {
		return errors.Join(useErr, closeErr)
	}
	if useErr != nil {
		return useErr
	}
	if closeErr != nil {
		return closeErr
	}
	return nil
}

// RetireWhenIdle rejects new exchanges, waits for active exchanges to finish, then closes idle sockets.
func (n *NetworkRuntime) RetireWhenIdle(ctx context.Context) error {
	n.mu.Lock()
	n.retiring = true
	idle := n.idle
	n.mu.Unlock()
	select {
	case <-idle:
		return n.Close(ctx)
	case <-ctx.Done():
		return ctx.Err()
	}
}

// Close rejects new exchanges, cancels active ones, and waits for their scopes to release.
func (n *NetworkRuntime) Close(ctx context.Context) error {
	n.mu.Lock()
	n.closed = true
	idle := n.idle
	for _, cancel := range n.cancels {
		cancel()
	}
	n.mu.Unlock()
	select {
	case <-idle:
		n.client.CloseIdleConnections()
		return nil
	case <-ctx.Done():
		n.client.CloseIdleConnections()
		return ctx.Err()
	}
}
