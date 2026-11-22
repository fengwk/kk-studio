package ide

import (
	"context"
	"errors"
	"fmt"
	"os"
	"sync"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/coding/keylock"
	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"github.com/fengwk/kk-studio/daemon/internal/transport"
)

const (
	defaultMaxViews          = 64
	defaultRecoveryQuota     = int64(16 << 20)
	viewLeaseDuration        = 10 * time.Minute
	idleStopDuration         = time.Minute
	closeOperationTimeout    = 10 * time.Second
	maxCachedClaims          = 256
	maxCommandTextCharacters = 4096
)

// IdeScheduler supplies monotonic time and cancellable timers for lease lifecycle decisions.
type IdeScheduler interface {
	Now() time.Time
	AfterFunc(time.Duration, func()) IdeTimer
}

// IdeTimer is the timer capability owned by one IDE view or idle manager.
type IdeTimer interface{ Stop() bool }

type systemIdeScheduler struct{}

func (systemIdeScheduler) Now() time.Time { return time.Now() }

func (systemIdeScheduler) AfterFunc(delay time.Duration, callback func()) IdeTimer {
	return time.AfterFunc(delay, callback)
}

// IdeServiceEvent is a bounded diagnostic event; messages never contain paths or credentials.
type IdeServiceEvent struct {
	Kind    string
	ViewID  string
	Message string
}

// ServiceOptions injects lifecycle collaborators for deterministic tests and runtime ownership.
type ServiceOptions struct {
	DaemonInstanceID   string
	Bridge             *Bridge
	Manager            CodeServerManager
	Scheduler          IdeScheduler
	OnEvent            func(IdeServiceEvent)
	RecoveryQuotaBytes int64
	MaxViews           int
}

type viewState string

const (
	viewOpen    viewState = "OPEN"
	viewClosing viewState = "CLOSING"
)

// Service owns managed IDE state and implements the runtime-facing command service.
type Service struct {
	cfg            config.DaemonConfig
	paths          IDEPaths
	processes      native.Supervisor
	tracker        native.DescendantTracker
	manager        CodeServerManager
	bridge         *Bridge
	resolver       *Resolver
	scheduler      IdeScheduler
	onEvent        func(IdeServiceEvent)
	recoveryQuota  int64
	maxViews       int
	daemonInstance string

	mu               sync.Mutex
	views            map[string]*viewRecord
	reservations     map[string]struct{}
	claims           map[string]claimRecord
	claimOrder       []string
	viewLocks        keylock.Locks
	claimLocks       keylock.Locks
	workspaceMu      sync.Mutex
	recoveryMu       sync.Mutex // Serializes quota checks and publication across all views.
	activeStreams    map[*activeIdeStream]struct{}
	revision         int64
	closed           bool
	lifetime         context.Context
	cancelLifetime   context.CancelFunc
	idleTimer        IdeTimer
	managerStop      chan struct{}
	managerStopErr   error
	crashEpoch       uint64
	crashRecovery    chan struct{}
	crashRecoveryErr error
	crashUnconverged bool
	closeDone        chan struct{}
	closeErr         error
	operations       sync.WaitGroup
	refreshQueue     chan explorerRefreshRequest
	refreshPending   map[explorerRefreshKey]struct{}
	background       sync.WaitGroup
}

// NewService creates the managed IDE subsystem without starting code-server.
func NewService(cfg config.DaemonConfig, processes native.Supervisor, tracker native.DescendantTracker, options ServiceOptions) (*Service, error) {
	if !validUUID(options.DaemonInstanceID) {
		return nil, NewError("daemonInstanceId must be a canonical lowercase UUID", "UNAVAILABLE")
	}
	bridge := options.Bridge
	if bridge == nil {
		var err error
		bridge, err = NewBridge()
		if err != nil {
			return nil, err
		}
	}
	manager := options.Manager
	if manager == nil {
		manager = NewCodeServerManager(cfg, processes, BridgeCredentials{URL: bridge.URL, Token: bridge.Secret})
	}
	scheduler := options.Scheduler
	if scheduler == nil {
		scheduler = systemIdeScheduler{}
	}
	maxViews := options.MaxViews
	if maxViews == 0 {
		maxViews = defaultMaxViews
	}
	quota := options.RecoveryQuotaBytes
	if quota == 0 {
		quota = defaultRecoveryQuota
	}
	lifetime, cancel := context.WithCancel(context.Background())
	s := &Service{
		cfg: cfg, paths: IDEPathsFor(cfg), processes: processes, tracker: tracker, manager: manager,
		bridge: bridge, resolver: NewResolver(), scheduler: scheduler, onEvent: options.OnEvent,
		recoveryQuota: quota, maxViews: maxViews, daemonInstance: options.DaemonInstanceID,
		views: make(map[string]*viewRecord), reservations: make(map[string]struct{}),
		claims: make(map[string]claimRecord), activeStreams: make(map[*activeIdeStream]struct{}),
		refreshQueue:   make(chan explorerRefreshRequest, maxQueuedExplorerRefreshes),
		refreshPending: make(map[explorerRefreshKey]struct{}),
		lifetime:       lifetime, cancelLifetime: cancel,
	}
	s.background.Add(1)
	go s.runExplorerRefreshes()
	manager.OnUnexpectedExit(s.processExited)
	bridge.OnDirectoryReconciled(s.reconcileDirectoryChange)
	bridge.OnPTY(func(_ string, pid int) error {
		if tracker == nil {
			return NewError("terminal supervision is unavailable", "UNAVAILABLE")
		}
		session := manager.Session()
		if session == nil {
			return NewError("terminal supervision is unavailable", "UNAVAILABLE")
		}
		return tracker.TrackDescendant(context.Background(), session.PID, pid)
	})
	return s, nil
}

func (s *Service) beginOperation(parent context.Context) (context.Context, func(), error) {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil, nil, NewError("IDE service is closed", "UNAVAILABLE")
	}
	s.operations.Add(1)
	s.mu.Unlock()
	ctx, cancel := context.WithCancel(parent)
	stop := context.AfterFunc(s.lifetime, cancel)
	return ctx, func() {
		stop()
		cancel()
		s.operations.Done()
	}, nil
}

func (s *Service) HandleCommand(ctx context.Context, command *protocol.IdeCommand) *protocol.IdeResult {
	if command == nil {
		return failureResult(NewError("unknown ide action", "UNAVAILABLE"), s.snapshot(), true)
	}
	opCtx, finish, err := s.beginOperation(ctx)
	if err != nil {
		return failureResult(err, s.snapshot(), true)
	}
	defer finish()
	ctx = opCtx
	if command.GetAction() != protocol.IdeAction_OPEN_ATTACHMENT && (command.AttachmentId != nil || command.AttachmentText != nil) {
		return failureResult(NewError("attachment fields are only valid for OPEN_ATTACHMENT", "UNAVAILABLE"), s.snapshot(), true)
	}
	var result *protocol.IdeResult
	switch command.GetAction() {
	case protocol.IdeAction_OPEN_VIEW:
		result, err = s.openCommand(ctx, command)
	case protocol.IdeAction_CHANGE_DIRECTORY:
		result, err = s.changeDirectory(ctx, command)
	case protocol.IdeAction_FOCUS_TERMINAL:
		result, err = s.focusTerminal(ctx, command)
	case protocol.IdeAction_OPEN_ATTACHMENT:
		result, err = s.openAttachment(ctx, command)
	case protocol.IdeAction_CLOSE_VIEW:
		result, err = s.closeView(ctx, command)
	case protocol.IdeAction_LIST_VIEWS:
		return successSnapshot(s.snapshot())
	case protocol.IdeAction_COLLAPSE_MOBILE:
		result, err = s.collapseMobile(ctx, command)
	default:
		err = NewError("unknown ide action", "UNAVAILABLE")
	}
	if err != nil {
		return failureResult(err, s.snapshot(), true)
	}
	return result
}

// ResolveView authorizes the current view binding and returns a cancellable local upstream.
func (s *Service) ResolveView(ctx context.Context, request transport.ViewRequest) (transport.ViewUpstream, error) {
	if request.BindingEpoch <= 0 {
		return transport.ViewUpstream{}, NewError("bindingEpoch is invalid", "UNAVAILABLE")
	}
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return transport.ViewUpstream{}, NewError("IDE service is closed", "UNAVAILABLE")
	}
	view := s.views[request.ViewID]
	if view == nil || view.state != viewOpen || view.bindingPending || view.bindingEpoch != request.BindingEpoch {
		s.mu.Unlock()
		return transport.ViewUpstream{}, NewError("view is not open for this binding epoch", "VIEW_GONE")
	}
	if s.manager.Session() == nil {
		s.mu.Unlock()
		return transport.ViewUpstream{}, NewError("code-server is not running", "UNAVAILABLE")
	}
	streamCtx, cancel := context.WithCancel(ctx)
	upstream, err := s.resolver.ResolveView(streamCtx, request)
	if err != nil {
		cancel()
		s.mu.Unlock()
		return transport.ViewUpstream{}, err
	}
	stream := &activeIdeStream{viewID: view.viewID, epoch: view.bindingEpoch, cancel: cancel, release: upstream.Release, done: make(chan struct{})}
	view.streams[stream] = struct{}{}
	s.activeStreams[stream] = struct{}{}
	s.cancelIdleLocked()
	s.mu.Unlock()
	// Wait for the transport's Release after cancellation so idle stop follows actual stream teardown.
	upstream.Context = streamCtx
	upstream.Release = func() { s.releaseStream(stream) }
	return upstream, nil
}

func (s *Service) CanStartUpdate() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return !s.closed && len(s.views) == 0 && len(s.reservations) == 0 && len(s.activeStreams) == 0 && s.crashRecovery == nil && !s.crashUnconverged
}

func (s *Service) StopForUpdate(ctx context.Context) error {
	ctx, finish, err := s.beginOperation(ctx)
	if err != nil {
		return err
	}
	defer finish()
	if !s.CanStartUpdate() {
		return NewError("IDE views, cleanup or data streams are active", "BUSY")
	}
	s.mu.Lock()
	s.cancelIdleLocked()
	s.mu.Unlock()
	return s.stopManager(ctx)
}

func (s *Service) HasActiveViews() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.views) > 0 || len(s.reservations) > 0
}

func (s *Service) ActiveStreamCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.activeStreams)
}

func (s *Service) Close(ctx context.Context) error {
	s.mu.Lock()
	if s.closed {
		done := s.closeDone
		s.mu.Unlock()
		if done == nil {
			return nil
		}
		select {
		case <-done:
			s.mu.Lock()
			err := s.closeErr
			s.mu.Unlock()
			return err
		case <-ctx.Done():
			return ErrCancelled
		}
	}
	s.closed = true
	s.closeDone = make(chan struct{})
	done := s.closeDone
	s.cancelIdleLocked()
	s.cancelLifetime()
	streams := make([]*activeIdeStream, 0, len(s.activeStreams))
	for stream := range s.activeStreams {
		streams = append(streams, stream)
	}
	managerStop, crashRecovery := s.managerStop, s.crashRecovery
	s.mu.Unlock()
	for _, stream := range streams {
		stream.cancel()
	}
	s.operations.Wait()
	s.background.Wait()
	if managerStop != nil {
		<-managerStop
	}
	if crashRecovery != nil {
		<-crashRecovery
	}
	s.mu.Lock()
	views := make([]*viewRecord, 0, len(s.views))
	for _, view := range s.views {
		s.clearExpiryLocked(view)
		views = append(views, view)
	}
	s.mu.Unlock()
	for _, view := range views {
		s.bridge.Revoke(view.viewID, view.bindingEpoch)
		s.resolver.UnbindView(view.viewID, view.bindingEpoch)
	}
	var failures []error
	if err := s.bridge.Close(ctx); err != nil {
		failures = append(failures, err)
	}
	if err := s.manager.Close(ctx); err != nil {
		failures = append(failures, err)
	}
	s.mu.Lock()
	clear(s.views)
	clear(s.reservations)
	clear(s.claims)
	if len(failures) > 0 {
		s.closeErr = errors.Join(append([]error{fmt.Errorf("ide service shutdown failed")}, failures...)...)
	}
	close(done)
	result := s.closeErr
	s.mu.Unlock()
	return result
}

func (s *Service) processExited(_ uint64) {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.crashEpoch++
	epoch := s.crashEpoch
	views := make([]*viewRecord, 0, len(s.views))
	for _, view := range s.views {
		views = append(views, view)
	}
	if len(views) > 0 {
		_, _ = s.commitRevisionLocked()
	}
	for _, view := range views {
		view.state, view.cleanupFailed = viewClosing, true
		s.clearExpiryLocked(view)
	}
	if s.crashRecovery != nil {
		s.mu.Unlock()
		return
	}
	done := make(chan struct{})
	s.crashRecovery = done
	s.mu.Unlock()
	for _, view := range views {
		s.abortViewStreams(view)
		s.bridge.Revoke(view.viewID, view.bindingEpoch)
		s.resolver.UnbindView(view.viewID, view.bindingEpoch)
	}
	s.emit(IdeServiceEvent{Kind: "process-exit", Message: "code-server exited; IDE views were invalidated"})
	go func() {
		err := s.manager.Stop(context.Background())
		if err != nil {
			s.mu.Lock()
			s.crashUnconverged = true
			s.crashRecoveryErr = err
			s.mu.Unlock()
			s.emit(IdeServiceEvent{Kind: "cleanup-failed", Message: "code-server process scope did not converge after exit"})
		} else {
			s.mu.Lock()
			s.crashUnconverged = false
			s.crashRecoveryErr = nil
			s.mu.Unlock()
			s.releaseInvalidatedViews(views)
		}
		s.mu.Lock()
		if s.crashEpoch == epoch {
			s.crashRecovery = nil
		}
		close(done)
		s.mu.Unlock()
	}()
}

func (s *Service) releaseInvalidatedViews(views []*viewRecord) {
	s.mu.Lock()
	seen := make(map[*viewRecord]struct{}, len(views))
	for _, view := range views {
		seen[view] = struct{}{}
	}
	for _, view := range s.views {
		if view.state == viewClosing {
			if _, ok := seen[view]; !ok {
				views = append(views, view)
			}
		}
	}
	s.mu.Unlock()
	for _, view := range views {
		s.mu.Lock()
		present := s.views[view.viewID] == view
		s.mu.Unlock()
		if !present {
			continue
		}
		if view.workspacePath != "" {
			if err := os.Remove(view.workspacePath); err != nil && !errors.Is(err, os.ErrNotExist) {
				s.emit(IdeServiceEvent{Kind: "cleanup-failed", ViewID: view.viewID, Message: "managed workspace could not be removed"})
			}
		}
		s.mu.Lock()
		if s.views[view.viewID] == view {
			_, _ = s.commitRevisionLocked()
			delete(s.views, view.viewID)
		}
		s.mu.Unlock()
	}
	s.scheduleIdleStop()
}

func (s *Service) awaitReady(ctx context.Context) error {
	s.mu.Lock()
	crash := s.crashRecovery
	stop := s.managerStop
	s.mu.Unlock()
	for _, wait := range []chan struct{}{crash, stop} {
		if wait == nil {
			continue
		}
		select {
		case <-wait:
		case <-ctx.Done():
			return ErrCancelled
		}
	}
	s.mu.Lock()
	crashErr, unconverged := s.crashRecoveryErr, s.crashUnconverged
	s.mu.Unlock()
	if crashErr != nil || unconverged {
		if err := s.convergeAfterCrash(ctx); err != nil {
			return err
		}
	}
	return nil
}

func (s *Service) convergeAfterCrash(ctx context.Context) error {
	s.mu.Lock()
	if !s.crashUnconverged {
		s.mu.Unlock()
		return nil
	}
	views := make([]*viewRecord, 0, len(s.views))
	for _, view := range s.views {
		views = append(views, view)
	}
	s.mu.Unlock()
	if err := s.manager.Stop(ctx); err != nil {
		return NewError("code-server cleanup did not converge after exit", "CLEANUP_FAILED")
	}
	s.mu.Lock()
	s.crashUnconverged = false
	s.crashRecoveryErr = nil
	s.mu.Unlock()
	s.releaseInvalidatedViews(views)
	return nil
}

func (s *Service) stopManager(ctx context.Context) error {
	s.mu.Lock()
	if s.managerStop != nil {
		done := s.managerStop
		s.mu.Unlock()
		select {
		case <-done:
			s.mu.Lock()
			err := s.managerStopErr
			s.mu.Unlock()
			return err
		case <-ctx.Done():
			return ErrCancelled
		}
	}
	done := make(chan struct{})
	s.managerStop = done
	s.managerStopErr = nil
	s.mu.Unlock()
	err := s.manager.Stop(ctx)
	s.mu.Lock()
	s.managerStopErr = err
	if s.managerStop == done {
		s.managerStop = nil
	}
	close(done)
	s.mu.Unlock()
	return err
}

func (s *Service) scheduleIdleStop() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || s.idleTimer != nil || len(s.views) > 0 || len(s.reservations) > 0 || len(s.activeStreams) > 0 || s.managerStop != nil {
		return
	}
	s.idleTimer = s.scheduler.AfterFunc(idleStopDuration, func() { s.idleTimerFired() })
}

func (s *Service) idleTimerFired() {
	s.mu.Lock()
	s.idleTimer = nil
	if s.closed || len(s.views) > 0 || len(s.reservations) > 0 || len(s.activeStreams) > 0 {
		s.mu.Unlock()
		s.scheduleIdleStop()
		return
	}
	s.mu.Unlock()
	if err := s.stopManager(context.Background()); err != nil {
		s.emit(IdeServiceEvent{Kind: "idle-stop-failed", Message: "idle code-server stop failed"})
		s.scheduleIdleStop()
	}
}

func (s *Service) cancelIdleLocked() {
	if s.idleTimer != nil {
		s.idleTimer.Stop()
		s.idleTimer = nil
	}
}

func (s *Service) emit(event IdeServiceEvent) {
	if s.onEvent != nil {
		func() { defer func() { _ = recover() }(); s.onEvent(event) }()
	}
}

var _ transport.ViewResolver = (*Service)(nil)
