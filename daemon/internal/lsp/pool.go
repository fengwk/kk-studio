package lsp

import (
	"context"
	"encoding/json"
	"errors"
	"reflect"
	"sync"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/native"
)

// PoolOptions controls process reuse and graceful shutdown.
type PoolOptions struct{ IdleTimeout, ShutdownGrace, InitializeTimeout time.Duration }
type poolEntry struct {
	key      string
	client   *Client
	inFlight int
	timer    *time.Timer
}
type boot struct {
	done      chan struct{}
	cancel    context.CancelFunc
	client    *Client
	err       error
	waiters   int
	retryable bool
}
type retirement struct {
	done chan struct{}
	err  error
}

// Pool reuses one LSP client per structured workspace/server configuration key.
type Pool struct {
	mu        sync.Mutex
	discovery *Discovery
	processes native.Supervisor
	options   PoolOptions
	entries   map[string]*poolEntry
	boots     map[string]*boot
	retiring  map[string]*retirement
	closing   bool
	closeDone chan struct{}
	closeErr  error
}
type Lease struct {
	Client *Client
	pool   *Pool
	entry  *poolEntry
	once   sync.Once
}

func NewPool(discovery *Discovery, processes native.Supervisor, options PoolOptions) *Pool {
	if options.IdleTimeout == 0 {
		options.IdleTimeout = 5 * time.Minute
	}
	if options.ShutdownGrace == 0 {
		options.ShutdownGrace = time.Second
	}
	if options.InitializeTimeout == 0 {
		options.InitializeTimeout = 180 * time.Second
	}
	return &Pool{discovery: discovery, processes: processes, options: options, entries: map[string]*poolEntry{}, boots: map[string]*boot{}, retiring: map[string]*retirement{}, closeDone: make(chan struct{})}
}
func poolKey(root string, server ServerConfig) string {
	b, _ := json.Marshal([]any{root, server.ID, server.Command, server.Extensions, server.RootMarkers, server.FirstMatchMarkers})
	return string(b)
}

func leaseContextError(ctx context.Context) error {
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return newLspTimeoutError("Operation timed out", ctx.Err())
	}
	return &LspError{Message: "Operation cancelled", Cause: ctx.Err()}
}

// leaseTarget resolves the server, executable and workspace root, confirming the exit on failure.
func (p *Pool) leaseTarget(path string, onExit func()) (ServerConfig, string, string, string, error) {
	fail := func(err error) (ServerConfig, string, string, string, error) {
		if onExit != nil {
			onExit()
		}
		return ServerConfig{}, "", "", "", err
	}
	server, err := p.discovery.RequireServer(path)
	if err != nil {
		return fail(err)
	}
	executable, err := p.discovery.RequireExecutable(server)
	if err != nil {
		return fail(err)
	}
	root, err := p.discovery.WorkspaceRoot(server, path)
	if err != nil {
		return fail(err)
	}
	return server, root, executable, poolKey(root, server), nil
}

func (p *Pool) LeaseWithExit(ctx context.Context, path string, onExit func()) (*Lease, error) {
	if ctx.Err() != nil {
		if onExit != nil {
			onExit()
		}
		return nil, leaseContextError(ctx)
	}
	server, root, executable, key, err := p.leaseTarget(path, onExit)
	if err != nil {
		return nil, err
	}
	for {
		lease, retry, err := p.awaitLease(ctx, key, server, root, executable, onExit)
		if retry {
			continue
		}
		return lease, err
	}
}

// awaitLease runs one lease attempt; retry asks the caller to re-enter the loop.
func (p *Pool) awaitLease(ctx context.Context, key string, server ServerConfig, root, executable string, onExit func()) (*Lease, bool, error) {
	rejectedBySettledBoot := false
	p.mu.Lock()
	if p.closing {
		done := p.closeDone
		p.mu.Unlock()
		<-done
		if onExit != nil && p.closeErr == nil {
			onExit()
		}
		return nil, false, &LspError{Message: "LSP service is closed"}
	}
	if old, ok := p.retiring[key]; ok {
		p.mu.Unlock()
		select {
		case <-ctx.Done():
			if onExit != nil {
				onExit()
			}
			return nil, false, leaseContextError(ctx)
		case <-old.done:
			if old.err != nil {
				if onExit != nil {
					onExit()
				}
				return nil, false, old.err
			}
			return nil, true, nil
		}
	}
	if entry := p.entries[key]; entry != nil {
		if entry.client.IsAlive() {
			if entry.timer != nil {
				entry.timer.Stop()
				entry.timer = nil
			}
			entry.inFlight++
			p.mu.Unlock()
			return &Lease{Client: entry.client, pool: p, entry: entry}, false, nil
		}
		delete(p.entries, key)
	}
	b := p.boots[key]
	if b == nil {
		bootCtx, cancel := context.WithCancel(context.Background())
		b = &boot{done: make(chan struct{}), cancel: cancel}
		p.boots[key] = b
		go p.start(bootCtx, key, server, root, executable, b)
	} else {
		select {
		case <-b.done:
			rejectedBySettledBoot = true
		default:
		}
	}
	b.waiters++
	p.mu.Unlock()
	return p.awaitBoot(ctx, key, b, rejectedBySettledBoot, onExit)
}

// awaitBoot waits for the shared launch; retry asks the caller to re-enter the lease loop.
func (p *Pool) awaitBoot(ctx context.Context, key string, b *boot, rejectedBySettledBoot bool, onExit func()) (*Lease, bool, error) {
	select {
	case <-ctx.Done():
		p.mu.Lock()
		b.waiters--
		if b.waiters == 0 && p.boots[key] == b {
			b.cancel()
		}
		p.mu.Unlock()
		p.confirmAfterAbandonedBoot(b, onExit)
		return nil, false, leaseContextError(ctx)
	case <-b.done:
		p.mu.Lock()
		b.waiters--
		if b.err != nil {
			var bootstrap *LspBootstrapError
			converged := (errors.As(b.err, &bootstrap) && bootstrap.Converged) || b.retryable
			p.mu.Unlock()
			if onExit != nil && (converged || rejectedBySettledBoot) {
				onExit()
			}
			return nil, false, b.err
		}
		if p.closing {
			done := p.closeDone
			p.mu.Unlock()
			<-done
			if onExit != nil && p.closeErr == nil {
				onExit()
			}
			return nil, false, &LspError{Message: "LSP service is closed"}
		}
		entry := p.entries[key]
		if entry == nil || !entry.client.IsAlive() {
			p.mu.Unlock()
			return nil, true, nil
		}
		entry.inFlight++
		if entry.timer != nil {
			entry.timer.Stop()
			entry.timer = nil
		}
		p.mu.Unlock()
		return &Lease{Client: entry.client, pool: p, entry: entry}, false, nil
	}
}

// confirmAfterAbandonedBoot verifies the shared launch without blocking the cancelled caller.
func (p *Pool) confirmAfterAbandonedBoot(b *boot, onExit func()) {
	if onExit == nil {
		return
	}
	go func() {
		<-b.done
		p.mu.Lock()
		converged := b.err == nil && !p.closing || b.retryable
		var bootstrap *LspBootstrapError
		if errors.As(b.err, &bootstrap) && bootstrap.Converged {
			converged = true
		}
		p.mu.Unlock()
		if converged {
			onExit()
		}
	}()
}

func (p *Pool) start(ctx context.Context, key string, server ServerConfig, root, executable string, b *boot) {
	client, err := Launch(ctx, server, root, executable, p.processes, p.discovery.environment)
	if err == nil {
		client.shutdownGrace = p.options.ShutdownGrace
		b.client = client
		err = client.Initialize(ctx, p.options.InitializeTimeout)
	}
	if err != nil && client != nil {
		if stopErr := client.Stop(p.options.ShutdownGrace); stopErr != nil {
			const message = "LSP client startup failed and cleanup failed"
			err = &LspBootstrapError{
				Message:   message,
				Converged: false,
				Cause:     &LspAggregateError{Message: message, Errors: []error{err, stopErr}},
			}
		} else {
			b.retryable = true
			var bootstrap *LspBootstrapError
			if errors.As(err, &bootstrap) {
				bootstrap.Converged = true
			} else {
				err = &LspBootstrapError{Message: err.Error(), Converged: true, Cause: err}
			}
		}
	}
	p.mu.Lock()
	b.err = err
	if err == nil && !p.closing {
		entry := &poolEntry{key: key, client: client}
		p.entries[key] = entry
		b.client = client
		if p.boots[key] == b {
			delete(p.boots, key)
		}
	} else if err != nil {
		var be *LspBootstrapError
		if b.retryable || (errors.As(err, &be) && be.Converged) {
			delete(p.boots, key)
		}
	}
	close(b.done)
	p.mu.Unlock()
}
func (l *Lease) Release() {
	l.once.Do(func() {
		p := l.pool
		p.mu.Lock()
		e := l.entry
		if e.inFlight > 0 {
			e.inFlight--
		}
		if e.inFlight == 0 && !p.closing {
			if e.timer != nil {
				e.timer.Stop()
			}
			e.timer = time.AfterFunc(p.options.IdleTimeout, func() { p.retire(e) })
		}
		p.mu.Unlock()
	})
}
func (p *Pool) retire(e *poolEntry) {
	p.mu.Lock()
	if p.entries[e.key] != e || e.inFlight != 0 || p.closing {
		p.mu.Unlock()
		return
	}
	delete(p.entries, e.key)
	r := &retirement{done: make(chan struct{})}
	p.retiring[e.key] = r
	p.mu.Unlock()
	r.err = e.client.Stop(p.options.ShutdownGrace)
	p.mu.Lock()
	if r.err == nil {
		delete(p.retiring, e.key)
	}
	close(r.done)
	p.mu.Unlock()
}
func (p *Pool) Client(path string) *Client {
	server, ok := p.discovery.Server(path)
	if !ok {
		return nil
	}
	root, err := p.discovery.WorkspaceRoot(server, path)
	if err != nil {
		return nil
	}
	key := poolKey(root, server)
	p.mu.Lock()
	defer p.mu.Unlock()
	e := p.entries[key]
	if e == nil || !e.client.IsAlive() {
		return nil
	}
	return e.client
}
func (p *Pool) ActiveClientCount() int { p.mu.Lock(); defer p.mu.Unlock(); return len(p.entries) }

// drainSnapshot captures the pool state that Close finishes outside the lock.
type drainSnapshot struct {
	boots       []*boot
	entries     []*poolEntry
	retirements []*retirement
}

// beginClose marks the pool closed and captures what must be drained; closed reports a repeat call.
func (p *Pool) beginClose() (drainSnapshot, bool, error) {
	p.mu.Lock()
	if p.closing {
		done := p.closeDone
		p.mu.Unlock()
		<-done
		return drainSnapshot{}, true, p.closeErr
	}
	p.closing = true
	snapshot := drainSnapshot{
		boots:       make([]*boot, 0, len(p.boots)),
		entries:     make([]*poolEntry, 0, len(p.entries)),
		retirements: make([]*retirement, 0, len(p.retiring)),
	}
	for _, b := range p.boots {
		b.cancel()
		snapshot.boots = append(snapshot.boots, b)
	}
	for _, e := range p.entries {
		if e.timer != nil {
			e.timer.Stop()
		}
		snapshot.entries = append(snapshot.entries, e)
	}
	for _, r := range p.retiring {
		snapshot.retirements = append(snapshot.retirements, r)
	}
	p.entries = map[string]*poolEntry{}
	p.mu.Unlock()
	return snapshot, false, nil
}

// drain stops every boot, entry and retirement once and reports distinct failures.
func (p *Pool) drain(snapshot drainSnapshot) []error {
	var failures []error
	seenFailures := map[error]struct{}{}
	addFailure := func(err error) {
		typeOf := reflect.TypeOf(err)
		if typeOf != nil && typeOf.Comparable() {
			if _, exists := seenFailures[err]; exists {
				return
			}
			seenFailures[err] = struct{}{}
		}
		failures = append(failures, err)
	}
	stopped := map[*Client]bool{}
	stopOnce := func(client *Client) {
		if client == nil || stopped[client] {
			return
		}
		stopped[client] = true
		if err := client.Stop(p.options.ShutdownGrace); err != nil {
			addFailure(err)
		}
	}
	for _, b := range snapshot.boots {
		<-b.done
		if b.err != nil {
			var be *LspBootstrapError
			if errors.As(b.err, &be) && !be.Converged {
				addFailure(b.err)
			}
			continue
		}
		stopOnce(b.client)
	}
	for _, e := range snapshot.entries {
		stopOnce(e.client)
	}
	for _, r := range snapshot.retirements {
		<-r.done
		if r.err != nil {
			addFailure(r.err)
		}
	}
	return failures
}

func (p *Pool) Close() error {
	snapshot, closed, err := p.beginClose()
	if closed {
		return err
	}
	if failures := p.drain(snapshot); len(failures) > 0 {
		p.closeErr = &LspCleanupError{Failures: failures}
	}
	p.mu.Lock()
	close(p.closeDone)
	p.mu.Unlock()
	return p.closeErr
}
