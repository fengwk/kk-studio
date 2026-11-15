package main

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"sync"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/lsp"
	"github.com/fengwk/kk-studio/daemon/internal/runtime"
	"github.com/fengwk/kk-studio/daemon/internal/transport"
)

type networkRuntimeGeneration struct {
	runtime   *transport.NetworkRuntime
	leases    int
	retired   bool
	drained   chan struct{}
	drainOnce sync.Once
}

func (g *networkRuntimeGeneration) release() {
	g.leases--
	if g.retired && g.leases == 0 {
		g.drainOnce.Do(func() { close(g.drained) })
	}
}

type networkRuntimeStore struct {
	mu      sync.Mutex
	current *networkRuntimeGeneration
	closed  bool
}

func newNetworkRuntimeStore(initial *transport.NetworkRuntime) *networkRuntimeStore {
	return &networkRuntimeStore{current: &networkRuntimeGeneration{runtime: initial, drained: make(chan struct{})}}
}

func (s *networkRuntimeStore) acquire() (*transport.NetworkRuntime, func(), error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || s.current == nil {
		return nil, nil, errors.New("outbound network is closed")
	}
	generation := s.current
	generation.leases++
	var once sync.Once
	return generation.runtime, func() {
		once.Do(func() {
			s.mu.Lock()
			generation.release()
			s.mu.Unlock()
		})
	}, nil
}

func (s *networkRuntimeStore) publish(next *transport.NetworkRuntime) (func() error, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || s.current == nil {
		return nil, errors.New("outbound network is closed")
	}
	previous := s.current
	s.current = &networkRuntimeGeneration{runtime: next, drained: make(chan struct{})}
	previous.retired = true
	if previous.leases == 0 {
		previous.drainOnce.Do(func() { close(previous.drained) })
	}
	return func() error {
		<-previous.drained
		return previous.runtime.RetireWhenIdle(context.Background())
	}, nil
}

func (s *networkRuntimeStore) retireCandidate(candidate *transport.NetworkRuntime) error {
	if candidate == nil {
		return nil
	}
	return candidate.Close(context.Background())
}

func (s *networkRuntimeStore) close() error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil
	}
	s.closed = true
	previous := s.current
	s.current = nil
	if previous == nil {
		s.mu.Unlock()
		return nil
	}
	previous.retired = true
	if previous.leases == 0 {
		previous.drainOnce.Do(func() { close(previous.drained) })
	}
	s.mu.Unlock()
	<-previous.drained
	return previous.runtime.Close(context.Background())
}

func prepareProductionEnvironment(lspFacade *lsp.Facade, networks *networkRuntimeStore) runtime.ConfigurationPreparer {
	return func(_ context.Context, candidate config.DaemonConfig, previous *config.DaemonConfig) (runtime.PreparedConfiguration, error) {
		resolvedBash, err := config.ResolveBash(candidate.BashExecutable)
		if err != nil {
			return nil, fmt.Errorf("configured Bash executable is unavailable: %w", err)
		}
		candidate.BashExecutable = &resolvedBash
		policy, err := proxyPolicy(candidate)
		if err != nil {
			return nil, fmt.Errorf("configured proxy is invalid: %w", err)
		}
		networkChanged := previous == nil || !sameProxy(previous.ProxyURL, candidate.ProxyURL)
		lspChanged := previous == nil || !reflect.DeepEqual(previous.LSP, candidate.LSP)
		prepared := &preparedEnvironment{config: candidate, networks: networks}
		if networkChanged {
			prepared.network = transport.NewNetworkRuntime(policy)
		}
		if lspChanged {
			prepared.lsp, err = lspFacade.Prepare(candidate.LSP)
			if err != nil {
				if cleanupErr := networks.retireCandidate(prepared.network); cleanupErr != nil {
					return nil, errors.Join(err, fmt.Errorf("network candidate cleanup failed: %w", cleanupErr))
				}
				return nil, err
			}
		}
		return prepared, nil
	}
}

func sameProxy(left, right *string) bool {
	if left == nil || right == nil {
		return left == nil && right == nil
	}
	return *left == *right
}
