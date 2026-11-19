package ide

import (
	"context"
	"path/filepath"
	"strings"
	"time"
)

const (
	maxQueuedExplorerRefreshes = 256
	explorerRefreshDebounce    = 100 * time.Millisecond
	explorerRefreshTimeout     = 2 * time.Second
)

type explorerRefreshKey struct {
	viewID string
	epoch  int64
}

type explorerRefreshRequest struct {
	key  explorerRefreshKey
	path string
}

// FileChanged queues a bounded, best-effort refresh for managed views containing path.
func (s *Service) FileChanged(path string) {
	if !filepath.IsAbs(path) {
		return
	}
	path = filepath.Clean(path)
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	candidates := make([]explorerRefreshKey, 0, len(s.views))
	for viewID, view := range s.views {
		if view.state == viewOpen && pathWithin(view.directory, path) {
			candidates = append(candidates, explorerRefreshKey{viewID: viewID, epoch: view.bindingEpoch})
		}
	}
	s.mu.Unlock()
	for _, key := range candidates {
		if !s.bridge.IsConnected(key.viewID, key.epoch) {
			continue
		}
		s.mu.Lock()
		view := s.views[key.viewID]
		current := !s.closed && view != nil && view.state == viewOpen && view.bindingEpoch == key.epoch && pathWithin(view.directory, path)
		if !current {
			s.mu.Unlock()
			continue
		}
		if _, exists := s.refreshPending[key]; exists || len(s.refreshPending) >= maxQueuedExplorerRefreshes {
			s.mu.Unlock()
			continue
		}
		s.refreshPending[key] = struct{}{}
		request := explorerRefreshRequest{key: key, path: path}
		select {
		case s.refreshQueue <- request:
		default:
			delete(s.refreshPending, key)
		}
		s.mu.Unlock()
	}
}

func pathWithin(directory, path string) bool {
	relative, err := filepath.Rel(directory, path)
	if err != nil || filepath.IsAbs(relative) || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return false
	}
	return true
}

func (s *Service) runExplorerRefreshes() {
	defer s.background.Done()
	for {
		var first explorerRefreshRequest
		select {
		case <-s.lifetime.Done():
			return
		case first = <-s.refreshQueue:
		}
		batch := map[explorerRefreshKey]explorerRefreshRequest{first.key: first}
		timer := time.NewTimer(explorerRefreshDebounce)
	collect:
		for {
			select {
			case <-s.lifetime.Done():
				timer.Stop()
				return
			case request := <-s.refreshQueue:
				batch[request.key] = request
			case <-timer.C:
				break collect
			}
		}
		for _, request := range batch {
			s.mu.Lock()
			delete(s.refreshPending, request.key)
			view := s.views[request.key.viewID]
			current := !s.closed && view != nil && view.state == viewOpen && view.bindingEpoch == request.key.epoch && pathWithin(view.directory, request.path)
			s.mu.Unlock()
			if !current || !s.bridge.IsConnected(request.key.viewID, request.key.epoch) {
				continue
			}
			ctx, cancel := context.WithTimeout(s.lifetime, explorerRefreshTimeout)
			_, _ = s.bridge.Request(ctx, request.key.viewID, BridgeCommand{Op: "refreshExplorer"}, BridgeRequestOptions{ExpectedEpoch: request.key.epoch})
			cancel()
		}
	}
}
