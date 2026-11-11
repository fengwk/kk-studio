package lsp

import (
	"context"
	"encoding/json"
	"errors"
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
	lastUsed time.Time
	timer    *time.Timer
}
type boot struct {
	done    chan struct{}
	cancel  context.CancelFunc
	client  *Client
	err     error
	waiters int
}

// Pool reuses one LSP client per structured workspace/server configuration key.
type Pool struct {
	mu        sync.Mutex
	discovery *Discovery
	processes native.Supervisor
	options   PoolOptions
	entries   map[string]*poolEntry
	boots     map[string]*boot
	retiring  map[string]error
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
	return &Pool{discovery: discovery, processes: processes, options: options, entries: map[string]*poolEntry{}, boots: map[string]*boot{}, retiring: map[string]error{}, closeDone: make(chan struct{})}
}
func poolKey(root string, server ServerConfig) string {
	b, _ := json.Marshal([]any{root, server.ID, server.Command, server.Extensions, server.RootMarkers, server.FirstMatchMarkers})
	return string(b)
}
func (p *Pool) Lease(ctx context.Context, path string) (*Lease, error) {
	server, err := p.discovery.RequireServer(path)
	if err != nil {
		return nil, err
	}
	executable, err := p.discovery.RequireExecutable(server)
	if err != nil {
		return nil, err
	}
	root, err := p.discovery.WorkspaceRoot(server, path)
	if err != nil {
		return nil, err
	}
	key := poolKey(root, server)
	for {
		p.mu.Lock()
		if p.closing {
			p.mu.Unlock()
			return nil, &LspError{Message: "LSP service is closed"}
		}
		if failure, ok := p.retiring[key]; ok && failure != nil {
			p.mu.Unlock()
			return nil, failure
		}
		if entry := p.entries[key]; entry != nil {
			if entry.client.IsAlive() {
				if entry.timer != nil {
					entry.timer.Stop()
					entry.timer = nil
				}
				entry.inFlight++
				p.mu.Unlock()
				return &Lease{Client: entry.client, pool: p, entry: entry}, nil
			}
			delete(p.entries, key)
		}
		b := p.boots[key]
		if b == nil {
			bootCtx, cancel := context.WithCancel(context.Background())
			b = &boot{done: make(chan struct{}), cancel: cancel}
			p.boots[key] = b
			go p.start(bootCtx, key, server, root, executable, b)
		}
		b.waiters++
		p.mu.Unlock()
		select {
		case <-ctx.Done():
			p.mu.Lock()
			b.waiters--
			if b.waiters == 0 && p.boots[key] == b {
				b.cancel()
			}
			p.mu.Unlock()
			if errors.Is(ctx.Err(), context.DeadlineExceeded) {
				return nil, &LspTimeoutError{Message: "Operation timed out", Cause: ctx.Err()}
			}
			return nil, &LspError{Message: "Operation cancelled", Cause: ctx.Err()}
		case <-b.done:
			p.mu.Lock()
			b.waiters--
			if b.err != nil {
				p.mu.Unlock()
				return nil, b.err
			}
			if p.closing {
				p.mu.Unlock()
				return nil, &LspError{Message: "LSP service is closed"}
			}
			entry := p.entries[key]
			if entry == nil || !entry.client.IsAlive() {
				p.mu.Unlock()
				continue
			}
			entry.inFlight++
			if entry.timer != nil {
				entry.timer.Stop()
				entry.timer = nil
			}
			p.mu.Unlock()
			return &Lease{Client: entry.client, pool: p, entry: entry}, nil
		}
	}
}
func (p *Pool) start(ctx context.Context, key string, server ServerConfig, root, executable string, b *boot) {
	client, err := Launch(ctx, server, root, executable, p.processes)
	if err == nil {
		b.client = client
		err = client.Initialize(ctx, p.options.InitializeTimeout)
	}
	if err != nil && client != nil {
		if stopErr := client.Stop(p.options.ShutdownGrace); stopErr != nil {
			var be *LspBootstrapError
			if errors.As(err, &be) {
				err = &LspBootstrapError{Message: be.Message, Converged: false, Cause: errors.Join(be.Cause, stopErr)}
			} else {
				err = &LspBootstrapError{Message: err.Error(), Converged: false, Cause: errors.Join(err, stopErr)}
			}
		} else {
			var be *LspBootstrapError
			if errors.As(err, &be) {
				be.Converged = true
			}
		}
	}
	p.mu.Lock()
	b.err = err
	if err == nil && !p.closing {
		entry := &poolEntry{key: key, client: client, lastUsed: time.Now()}
		p.entries[key] = entry
		b.client = client
	} else if err != nil {
		var be *LspBootstrapError
		if errors.As(err, &be) && be.Converged {
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
		e.lastUsed = time.Now()
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
	p.retiring[e.key] = nil
	p.mu.Unlock()
	err := e.client.Stop(p.options.ShutdownGrace)
	p.mu.Lock()
	if err != nil {
		p.retiring[e.key] = err
	} else {
		delete(p.retiring, e.key)
	}
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
func (p *Pool) Close() error {
	p.mu.Lock()
	if p.closing {
		done := p.closeDone
		p.mu.Unlock()
		<-done
		return p.closeErr
	}
	p.closing = true
	boots := make([]*boot, 0, len(p.boots))
	for _, b := range p.boots {
		b.cancel()
		boots = append(boots, b)
	}
	entries := make([]*poolEntry, 0, len(p.entries))
	for _, e := range p.entries {
		if e.timer != nil {
			e.timer.Stop()
		}
		entries = append(entries, e)
	}
	p.entries = map[string]*poolEntry{}
	p.mu.Unlock()
	var failures []error
	for _, b := range boots {
		<-b.done
		if b.err != nil {
			var be *LspBootstrapError
			if errors.As(b.err, &be) && !be.Converged {
				failures = append(failures, b.err)
			}
			continue
		}
		if b.client != nil {
			if e := b.client.Stop(p.options.ShutdownGrace); e != nil {
				failures = append(failures, e)
			}
		}
	}
	for _, e := range entries {
		if err := e.client.Stop(p.options.ShutdownGrace); err != nil {
			failures = append(failures, err)
		}
	}
	p.mu.Lock()
	for _, err := range p.retiring {
		if err != nil {
			failures = append(failures, err)
		}
	}
	if len(failures) > 0 {
		p.closeErr = &LspCleanupError{Failures: failures}
	}
	close(p.closeDone)
	p.mu.Unlock()
	return p.closeErr
}
