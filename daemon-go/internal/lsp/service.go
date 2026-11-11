package lsp

import (
	"context"
	"fmt"
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

func (s *Service) GotoDefinition(ctx context.Context, path string, line, character int) (string, error) {
	lease, err := s.Pool.Lease(ctx, path)
	if err != nil {
		return "", err
	}
	defer lease.Release()
	items, err := lease.Client.Definition(ctx, path, line, character)
	if err != nil {
		return "", err
	}
	return joinItems(items), nil
}
func (s *Service) WorkspaceSymbols(ctx context.Context, path, query string, limit int) (string, error) {
	lease, err := s.Pool.Lease(ctx, path)
	if err != nil {
		return "", err
	}
	defer lease.Release()
	items, err := lease.Client.WorkspaceSymbols(ctx, query, limit)
	if err != nil {
		return "", err
	}
	return joinItems(items), nil
}
func (s *Service) JavaDecompile(ctx context.Context, path, target string) (string, error) {
	lease, err := s.Pool.Lease(ctx, path)
	if err != nil {
		return "", err
	}
	defer lease.Release()
	return lease.Client.JavaDecompile(ctx, target)
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
func noServerError() error            { return &LspError{Message: "LSP is not configured for this environment"} }
func inputError(message string) error { return fmt.Errorf("%s", message) }
