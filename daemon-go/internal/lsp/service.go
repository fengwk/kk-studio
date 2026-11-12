package lsp

import (
	"context"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/native"
)

// Service groups discovery and the reusable process pool for one configuration generation.
type Service struct {
	Discovery *Discovery
	Pool      *Pool
}

func NewService(discovery *Discovery, processes native.Supervisor, options PoolOptions) *Service {
	return &Service{Discovery: discovery, Pool: NewPool(discovery, processes, options)}
}

// Support reports whether the path matches a configured server and whether it is installed.
func (s *Service) Support(path string) Support { return s.Discovery.Support(path) }
func (s *Service) FileChanged(path string) {
	if client := s.Pool.Client(path); client != nil {
		client.Refresh(path)
	}
}
func (s *Service) ActiveClientCount() int { return s.Pool.ActiveClientCount() }
func (s *Service) Close() error           { return s.Pool.Close() }

func (s *Service) GotoDefinition(ctx context.Context, path string, line, character int, onExit ...func()) (string, error) {
	callback := firstCallback(onExit)
	lease, err := s.Pool.LeaseWithExit(ctx, path, callback)
	if err != nil {
		return "", err
	}
	defer lease.Release()
	sent := false
	items, err := lease.Client.Definition(ctx, path, line, character, func() { sent = true }, callback)
	if err != nil && !sent && callback != nil {
		callback()
		return "", err
	}
	return joinItems(items), err
}
func (s *Service) WorkspaceSymbols(ctx context.Context, path, query string, limit int, onExit ...func()) (string, error) {
	callback := firstCallback(onExit)
	lease, err := s.Pool.LeaseWithExit(ctx, path, callback)
	if err != nil {
		return "", err
	}
	defer lease.Release()
	sent := false
	items, err := lease.Client.WorkspaceSymbols(ctx, query, limit, func() { sent = true }, callback)
	if err != nil && !sent && callback != nil {
		callback()
		return "", err
	}
	return joinItems(items), err
}
func (s *Service) JavaDecompile(ctx context.Context, path, target string, onExit ...func()) (string, error) {
	callback := firstCallback(onExit)
	lease, err := s.Pool.LeaseWithExit(ctx, path, callback)
	if err != nil {
		return "", err
	}
	defer lease.Release()
	sent := false
	result, err := lease.Client.JavaDecompile(ctx, target, func() { sent = true }, callback)
	if err != nil && !sent && callback != nil {
		callback()
	}
	return result, err
}
func firstCallback(callbacks []func()) func() {
	if len(callbacks) == 0 {
		return nil
	}
	return callbacks[0]
}
func joinItems(items []string) string {
	result := ""
	for i, item := range items {
		if i > 0 {
			result += "\n"
		}
		result += item
	}
	return result
}
func requestContext(ctx context.Context, timeout int64) (context.Context, context.CancelFunc) {
	if timeout <= 0 {
		return ctx, func() {}
	}
	return context.WithTimeout(ctx, time.Duration(timeout)*time.Millisecond)
}
