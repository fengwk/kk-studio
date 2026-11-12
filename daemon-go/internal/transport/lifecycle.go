package transport

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	runtimeapi "github.com/fengwk/kk-studio/daemon/internal/runtime"
)

type RuntimeState string

const (
	StateStopped      RuntimeState = "STOPPED"
	StateDisconnected RuntimeState = "DISCONNECTED"
	StateConnecting   RuntimeState = "CONNECTING"
	StateReady        RuntimeState = "READY"
	StateFailed       RuntimeState = "FAILED"
)

type ConnectionDialer func(context.Context, func(uint32)) (runtimeapi.Connection, error)

type LifecycleCallbacks struct {
	OnEnvelope     func(uint64, *protocol.Envelope)
	OnDisconnected func(uint64, runtimeapi.Connection)
}

type ConnectionLifecycle struct {
	mu                 sync.Mutex
	dial               ConnectionDialer
	initialDelay       time.Duration
	maxDelay           time.Duration
	callbacks          LifecycleCallbacks
	state              RuntimeState
	failureReason      string
	started            bool
	closed             bool
	generation         uint64
	activeGeneration   uint64
	active             runtimeapi.Connection
	dialCancel         context.CancelFunc
	reconnectRequested bool
	ctx                context.Context
	cancel             context.CancelFunc
	done               chan struct{}
}

func NewConnectionLifecycle(dial ConnectionDialer, initialDelay, maxDelay time.Duration, callbacks LifecycleCallbacks) (*ConnectionLifecycle, error) {
	if dial == nil || initialDelay < 0 || maxDelay < initialDelay {
		return nil, errors.New("invalid connection lifecycle options")
	}
	return &ConnectionLifecycle{dial: dial, initialDelay: initialDelay, maxDelay: maxDelay, callbacks: callbacks, state: StateStopped, done: make(chan struct{})}, nil
}

func (l *ConnectionLifecycle) Start(parent context.Context) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.closed || l.started {
		return false
	}
	l.started = true
	l.state = StateDisconnected
	l.ctx, l.cancel = context.WithCancel(parent)
	go l.run()
	return true
}

func (l *ConnectionLifecycle) State() RuntimeState { l.mu.Lock(); defer l.mu.Unlock(); return l.state }
func (l *ConnectionLifecycle) FailureReason() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.failureReason
}
func (l *ConnectionLifecycle) Generation() uint64 {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.generation
}
func (l *ConnectionLifecycle) IsCurrent(generation uint64) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.started && !l.closed && generation == l.generation
}

func (l *ConnectionLifecycle) MarkReady(generation uint64) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.closed || l.active == nil || l.activeGeneration != generation {
		return false
	}
	l.state = StateReady
	return true
}

func (l *ConnectionLifecycle) Send(ctx context.Context, e *protocol.Envelope) bool {
	l.mu.Lock()
	conn, generation := l.active, l.activeGeneration
	l.mu.Unlock()
	if conn == nil {
		return false
	}
	if err := conn.Send(ctx, e); err != nil {
		l.requestReconnect(generation)
		return false
	}
	return true
}

// Reconnect immediately replaces the current generation without resetting backoff.
func (l *ConnectionLifecycle) Reconnect() bool {
	l.mu.Lock()
	generation := l.generation
	valid := l.started && !l.closed && (l.active != nil || l.state == StateConnecting)
	l.mu.Unlock()
	if !valid {
		return false
	}
	l.requestReconnect(generation)
	return true
}

func (l *ConnectionLifecycle) requestReconnect(generation uint64) {
	l.mu.Lock()
	if l.closed || !l.started || l.generation != generation {
		l.mu.Unlock()
		return
	}
	l.reconnectRequested = true
	conn := l.active
	cancel := l.dialCancel
	l.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	if conn != nil {
		_ = conn.Close()
	}
}

func (l *ConnectionLifecycle) Close(state RuntimeState, reason string) {
	l.mu.Lock()
	if l.closed {
		l.mu.Unlock()
		return
	}
	wasStarted := l.started
	l.closed = true
	l.started = false
	l.state = state
	l.failureReason = reason
	if l.cancel != nil {
		l.cancel()
	}
	if l.dialCancel != nil {
		l.dialCancel()
	}
	conn := l.active
	l.active = nil
	l.mu.Unlock()
	if conn != nil {
		_ = conn.Close()
	}
	if !wasStarted {
		close(l.done)
	}
}

func (l *ConnectionLifecycle) stopFromParent() {
	l.mu.Lock()
	if !l.closed {
		l.closed = true
		l.started = false
		l.state = StateStopped
	}
	conn := l.active
	l.active = nil
	l.mu.Unlock()
	if conn != nil {
		_ = conn.Close()
	}
}

func (l *ConnectionLifecycle) Wait(ctx context.Context) error {
	select {
	case <-l.done:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (l *ConnectionLifecycle) run() {
	defer close(l.done)
	delay := l.initialDelay
	for {
		l.mu.Lock()
		if l.closed {
			l.mu.Unlock()
			return
		}
		if l.ctx.Err() != nil {
			l.mu.Unlock()
			l.stopFromParent()
			return
		}
		l.generation++
		generation := l.generation
		l.state = StateConnecting
		ctx, cancel := context.WithCancel(l.ctx)
		l.dialCancel = cancel
		l.mu.Unlock()

		conn, err := l.dial(ctx, func(code uint32) {
			if code == 0 {
				l.requestReconnect(generation)
			}
		})
		cancel()
		l.mu.Lock()
		if l.dialCancel != nil {
			l.dialCancel = nil
		}
		if l.closed || l.ctx.Err() != nil || generation != l.generation {
			closed, canceled := l.closed, l.ctx.Err() != nil
			l.mu.Unlock()
			if conn != nil {
				_ = conn.Close()
			}
			if canceled && !closed {
				l.stopFromParent()
			}
			return
		}
		immediate := l.reconnectRequested
		l.reconnectRequested = false
		if err == nil && conn != nil && !immediate {
			l.active = conn
			l.activeGeneration = generation
			l.state = StateConnecting
			delay = l.initialDelay
			l.mu.Unlock()
			for {
				envelope, receiveErr := conn.Receive(l.ctx)
				if receiveErr != nil {
					break
				}
				if l.callbacks.OnEnvelope != nil {
					l.callbacks.OnEnvelope(generation, envelope)
				}
			}
			if l.ctx.Err() != nil {
				_ = conn.Close()
				l.stopFromParent()
				return
			}
			_ = conn.Close()
			l.mu.Lock()
			if l.active == conn && l.activeGeneration == generation {
				l.active = nil
				l.state = StateDisconnected
			}
			immediate = l.reconnectRequested
			l.reconnectRequested = false
			l.mu.Unlock()
			if l.callbacks.OnDisconnected != nil {
				l.callbacks.OnDisconnected(generation, conn)
			}
		} else {
			if conn != nil {
				_ = conn.Close()
			}
			l.state = StateDisconnected
			l.mu.Unlock()
			if l.callbacks.OnDisconnected != nil {
				l.callbacks.OnDisconnected(generation, nil)
			}
		}
		wait := delay
		if immediate {
			wait = 0
		}
		delay *= 2
		if delay > l.maxDelay {
			delay = l.maxDelay
		}
		if !waitLifecycle(l.ctx, wait) {
			l.stopFromParent()
			return
		}
	}
}

func waitLifecycle(ctx context.Context, delay time.Duration) bool {
	if delay <= 0 {
		select {
		case <-ctx.Done():
			return false
		default:
			return true
		}
	}
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}
