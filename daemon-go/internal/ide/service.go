package ide

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

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

// IdeCommandService is the command/lease boundary consumed by runtime; transport owns wire encoding.
type IdeCommandService interface {
	HandleCommand(context.Context, *protocol.IdeCommand) *protocol.IdeResult
	ApplyLease(*protocol.IdeLease, time.Time)
	Close(context.Context) error
}

type viewState string

const (
	viewOpen    viewState = "OPEN"
	viewClosing viewState = "CLOSING"
)

type viewRecord struct {
	viewID             string
	browserSession     string
	eventNode          string
	connectionID       string
	claimID            string
	bindingEpoch       int64
	bindingPending     bool
	directory          string
	workspacePath      string
	state              viewState
	cleanupFailed      bool
	createdAt          time.Time
	lastProofAt        time.Time
	hasProof           bool
	expiresAt          time.Time
	expiryTimer        IdeTimer
	streams            map[*activeIdeStream]struct{}
	forceAllowed       bool
	nativeClosePending bool
	sessionGeneration  uint64
}

type activeIdeStream struct {
	viewID  string
	epoch   int64
	cancel  context.CancelFunc
	release func()
	done    chan struct{}
	once    sync.Once
}

type claimRecord struct {
	shape  string
	viewID string
	epoch  int64
}

type parsedOpen struct {
	viewID, browserSession, eventNode, connectionID, claimID string
	mode                                                     protocol.IdeOpenMode
	directory                                                string
	hasDirectory                                             bool
	expectedEpoch                                            int64
}

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
	viewLocks        map[string]*sync.Mutex
	claimLocks       map[string]*sync.Mutex
	workspaceMu      sync.Mutex
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
		claims: make(map[string]claimRecord), viewLocks: make(map[string]*sync.Mutex),
		claimLocks: make(map[string]*sync.Mutex), activeStreams: make(map[*activeIdeStream]struct{}),
		lifetime: lifetime, cancelLifetime: cancel,
	}
	manager.OnUnexpectedExit(s.processExited)
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
	var result *protocol.IdeResult
	switch command.GetAction() {
	case protocol.IdeAction_OPEN_VIEW:
		result, err = s.openCommand(ctx, command)
	case protocol.IdeAction_CHANGE_DIRECTORY:
		result, err = s.changeDirectory(ctx, command)
	case protocol.IdeAction_FOCUS_TERMINAL:
		result, err = s.focusTerminal(ctx, command)
	case protocol.IdeAction_CLOSE_VIEW:
		result, err = s.closeView(ctx, command)
	case protocol.IdeAction_LIST_VIEWS:
		return successSnapshot(s.snapshot())
	case protocol.IdeAction_LIST_DIRECTORIES:
		return s.listDirectories(command)
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

// Handle is a short name for the runtime-owned IDE command entrypoint.
func (s *Service) Handle(ctx context.Context, command *protocol.IdeCommand) *protocol.IdeResult {
	return s.HandleCommand(ctx, command)
}

func (s *Service) writeManagedWorkspace(path, directory string) error {
	s.workspaceMu.Lock()
	defer s.workspaceMu.Unlock()
	if err := native.EnsurePrivateDirectory(s.paths.IDEDir); err != nil {
		return err
	}
	if err := native.EnsurePrivateDirectory(s.paths.ViewsDir); err != nil {
		return err
	}
	return native.WritePrivateFile(path, []byte(WorkspaceFileContent(directory)))
}

func (s *Service) openCommand(ctx context.Context, command *protocol.IdeCommand) (*protocol.IdeResult, error) {
	if err := s.requireDaemonInstance(command); err != nil {
		return nil, err
	}
	parsed, err := s.parseOpen(command)
	if err != nil {
		return nil, err
	}
	shape := openShape(parsed)
	claimLock := s.keyedLock(s.claimLocks, parsed.claimID)
	claimLock.Lock()
	defer claimLock.Unlock()
	if reply, err := s.replayClaim(parsed.claimID, shape); err != nil || reply != nil {
		return reply, err
	}
	viewLock := s.keyedLock(s.viewLocks, parsed.viewID)
	viewLock.Lock()
	defer viewLock.Unlock()
	if reply, err := s.replayClaim(parsed.claimID, shape); err != nil || reply != nil {
		return reply, err
	}
	s.mu.Lock()
	existing := s.views[parsed.viewID]
	if parsed.mode == protocol.IdeOpenMode_RESUME {
		if existing == nil {
			s.mu.Unlock()
			return nil, NewError("view is gone", "VIEW_GONE")
		}
		s.mu.Unlock()
		return s.resumeView(ctx, parsed, existing)
	}
	if existing != nil {
		s.mu.Unlock()
		if existing.browserSession != parsed.browserSession {
			return nil, NewError("view is owned by another browser session", "VIEW_OWNED_BY_OTHER_SESSION")
		}
		return nil, NewError("view already exists for this session", "STALE_BINDING")
	}
	if len(s.views)+len(s.reservations) >= s.maxViews {
		s.mu.Unlock()
		if err := s.awaitReady(ctx); err != nil {
			return nil, err
		}
		s.mu.Lock()
		if len(s.views)+len(s.reservations) >= s.maxViews {
			s.mu.Unlock()
			return nil, NewError("IDE view limit reached", "IDE_CAPACITY_EXCEEDED")
		}
	}
	s.reservations[parsed.viewID] = struct{}{}
	s.cancelIdleLocked()
	crashAtStart := s.crashEpoch
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		delete(s.reservations, parsed.viewID)
		s.mu.Unlock()
		s.scheduleIdleStop()
	}()
	if err := s.awaitReady(ctx); err != nil {
		return nil, err
	}
	if err := s.convergeAfterCrash(ctx); err != nil {
		return nil, err
	}
	directory := parsed.directory
	if !parsed.hasDirectory {
		directory, err = HostHome(hostEnvironment())
		if err != nil {
			return nil, err
		}
	}
	directory, err = CanonicalDirectory(directory)
	if err != nil {
		return nil, err
	}
	workspacePath := filepath.Join(s.paths.ViewsDir, parsed.viewID+".code-workspace")
	if err := s.writeManagedWorkspace(workspacePath, directory); err != nil {
		return nil, NewError("managed workspace could not be written", "UNAVAILABLE")
	}
	workspacePath, err = filepath.EvalSymlinks(workspacePath)
	if err != nil {
		_ = os.Remove(workspacePath)
		return nil, NewError("managed workspace could not be resolved", "UNAVAILABLE")
	}
	s.mu.Lock()
	epoch, err := s.commitRevisionLocked()
	s.mu.Unlock()
	if err != nil {
		_ = os.Remove(workspacePath)
		return nil, err
	}
	session, err := s.manager.EnsureStarted(ctx)
	if err != nil {
		_ = os.Remove(workspacePath)
		return nil, err
	}
	registered, registerErr := s.manager.RegisterView(ctx, parsed.viewID, epoch, workspacePath)
	if registerErr != nil || registered == nil || registered.State != ManagedViewOpen || registered.BindingEpoch != strconv.FormatInt(epoch, 10) || registered.WorkspacePath != workspacePath || s.currentCrashEpoch() != crashAtStart || currentGeneration(s.manager) != session.Generation {
		reason := registerErr
		if s.currentCrashEpoch() != crashAtStart || currentGeneration(s.manager) != session.Generation {
			reason = NewError("code-server generation changed during view registration", "BUSY")
		} else if reason == nil {
			reason = NewError("managed view registration drifted", "CLEANUP_FAILED")
		}
		cleanupFailed := false
		if s.currentCrashEpoch() == crashAtStart && currentGeneration(s.manager) == session.Generation {
			closed, closeErr := s.manager.CloseView(context.Background(), parsed.viewID, epoch, ManagedViewCloseDiscard)
			cleanupFailed = closeErr != nil || closed == nil || closed.State != ManagedViewClosed || !closed.Cleanup.Complete
		} else if s.currentCrashEpoch() == crashAtStart {
			cleanupFailed = true
		}
		s.mu.Lock()
		retainForCrash := s.crashEpoch != crashAtStart && (s.crashRecovery != nil || s.crashUnconverged)
		if cleanupFailed || retainForCrash {
			view := &viewRecord{viewID: parsed.viewID, browserSession: parsed.browserSession, eventNode: parsed.eventNode, connectionID: parsed.connectionID, claimID: parsed.claimID, bindingEpoch: epoch, directory: directory, workspacePath: workspacePath, state: viewClosing, cleanupFailed: true, createdAt: s.scheduler.Now(), streams: make(map[*activeIdeStream]struct{}), sessionGeneration: session.Generation}
			_, _ = s.commitRevisionLocked()
			s.views[view.viewID] = view
			s.mu.Unlock()
			s.emit(IdeServiceEvent{Kind: "cleanup-failed", ViewID: view.viewID, Message: "failed view registration could not be cleaned up"})
		} else {
			s.mu.Unlock()
			_ = os.Remove(workspacePath)
		}
		return nil, reason
	}
	view := &viewRecord{
		viewID: parsed.viewID, browserSession: parsed.browserSession, eventNode: parsed.eventNode,
		connectionID: parsed.connectionID, claimID: parsed.claimID, bindingEpoch: epoch,
		directory: directory, workspacePath: workspacePath, state: viewOpen,
		createdAt: s.scheduler.Now(), streams: make(map[*activeIdeStream]struct{}), sessionGeneration: session.Generation,
	}
	s.bridge.Assign(view.viewID, view.workspacePath, epoch, directory)
	s.resolver.BindView(view.viewID, epoch, session.Port, session.Cookie)
	s.mu.Lock()
	_, _ = s.commitRevisionLocked()
	s.views[view.viewID] = view
	s.scheduleExpiryLocked(view, time.Time{})
	s.rememberClaimLocked(parsed.claimID, claimRecord{shape: shape, viewID: view.viewID, epoch: epoch})
	s.mu.Unlock()
	return openReply(view, s.snapshot()), nil
}

func (s *Service) parseOpen(command *protocol.IdeCommand) (parsedOpen, error) {
	if command.GetMode() != protocol.IdeOpenMode_CREATE && command.GetMode() != protocol.IdeOpenMode_RESUME {
		return parsedOpen{}, NewError("mode is invalid", "UNAVAILABLE")
	}
	if command.ExpectedBindingEpoch == nil {
		return parsedOpen{}, NewError("expectedBindingEpoch is required", "UNAVAILABLE")
	}
	expected := command.GetExpectedBindingEpoch()
	if expected < 0 {
		return parsedOpen{}, NewError("expectedBindingEpoch must be a non-negative decimal string", "UNAVAILABLE")
	}
	if command.GetMode() == protocol.IdeOpenMode_CREATE && expected != 0 {
		return parsedOpen{}, NewError("create requires expectedBindingEpoch 0", "UNAVAILABLE")
	}
	if command.GetMode() == protocol.IdeOpenMode_RESUME && expected == 0 {
		return parsedOpen{}, NewError("resume requires a positive expectedBindingEpoch", "UNAVAILABLE")
	}
	viewID, err := requiredString(command.ViewId, "viewId")
	if err != nil {
		return parsedOpen{}, err
	}
	viewID, err = ValidateViewID(viewID)
	if err != nil {
		return parsedOpen{}, err
	}
	browserSession, err := requiredString(command.BrowserSessionId, "browserSessionId")
	if err != nil {
		return parsedOpen{}, err
	}
	browserSession, err = validateUUIDField(browserSession, "browserSessionId")
	if err != nil {
		return parsedOpen{}, err
	}
	eventNode, err := requiredString(command.EventNodeId, "eventNodeId")
	if err != nil {
		return parsedOpen{}, err
	}
	eventNode, err = validateUUIDField(eventNode, "eventNodeId")
	if err != nil {
		return parsedOpen{}, err
	}
	connectionID, err := requiredString(command.ConnectionId, "connectionId")
	if err != nil {
		return parsedOpen{}, err
	}
	connectionID, err = validateUUIDField(connectionID, "connectionId")
	if err != nil {
		return parsedOpen{}, err
	}
	claimID, err := requiredString(command.ClaimId, "claimId")
	if err != nil {
		return parsedOpen{}, err
	}
	claimID, err = validateUUIDField(claimID, "claimId")
	if err != nil {
		return parsedOpen{}, err
	}
	parsed := parsedOpen{viewID: viewID, browserSession: browserSession, eventNode: eventNode, connectionID: connectionID, claimID: claimID, mode: command.GetMode(), expectedEpoch: expected}
	if command.Directory != nil {
		directory := command.GetDirectory()
		if strings.TrimSpace(directory) == "" || len([]rune(directory)) > maxCommandTextCharacters || hasControl(directory) {
			return parsedOpen{}, NewError("directory is invalid", "UNAVAILABLE")
		}
		parsed.directory, parsed.hasDirectory = directory, true
	}
	return parsed, nil
}

func (s *Service) resumeView(ctx context.Context, command parsedOpen, view *viewRecord) (*protocol.IdeResult, error) {
	s.mu.Lock()
	if view.state != viewOpen {
		s.mu.Unlock()
		return nil, NewError("view is closing", "BUSY")
	}
	if view.cleanupFailed {
		s.mu.Unlock()
		return nil, NewError("view cleanup is incomplete", "CLEANUP_FAILED")
	}
	if view.browserSession != command.browserSession {
		s.mu.Unlock()
		return nil, NewError("view is owned by another browser session", "VIEW_OWNED_BY_OTHER_SESSION")
	}
	if view.bindingEpoch != command.expectedEpoch {
		s.mu.Unlock()
		return nil, NewError("view binding changed", "STALE_BINDING")
	}
	currentDirectory := view.directory
	s.mu.Unlock()
	if command.hasDirectory {
		directory, err := CanonicalDirectory(command.directory)
		if err != nil {
			return nil, err
		}
		if directory != currentDirectory {
			return nil, NewError("resume must not change the workspace directory", "VIEW_IN_USE")
		}
	}
	if err := s.awaitReady(ctx); err != nil {
		return nil, err
	}
	s.mu.Lock()
	if s.views[view.viewID] != view || view.state != viewOpen || view.bindingPending || view.bindingEpoch != command.expectedEpoch {
		s.mu.Unlock()
		return nil, NewError("view binding changed", "STALE_BINDING")
	}
	oldEpoch := view.bindingEpoch
	crashAtStart := s.crashEpoch
	view.bindingPending = true
	s.clearExpiryLocked(view)
	newEpoch, err := s.commitRevisionLocked()
	if err != nil {
		view.bindingPending = false
		s.scheduleExpiryLocked(view, s.scheduler.Now().Add(viewLeaseDuration))
		s.mu.Unlock()
		return nil, err
	}
	workspacePath, directory := view.workspacePath, view.directory
	s.mu.Unlock()
	session := s.manager.Session()
	if session == nil {
		s.mu.Lock()
		view.bindingPending = false
		s.scheduleExpiryLocked(view, s.scheduler.Now().Add(viewLeaseDuration))
		s.mu.Unlock()
		return nil, NewError("code-server is not running", "UNAVAILABLE")
	}
	s.abortViewStreams(view)
	registered, registerErr := s.manager.RegisterView(ctx, view.viewID, newEpoch, workspacePath)
	if registerErr == nil && registered != nil && registered.State == ManagedViewOpen && registered.BindingEpoch == strconv.FormatInt(newEpoch, 10) && registered.WorkspacePath == workspacePath && s.currentCrashEpoch() == crashAtStart && currentGeneration(s.manager) == session.Generation {
		s.mu.Lock()
		current := s.views[view.viewID] == view && view.state == viewOpen && view.bindingPending && view.bindingEpoch == oldEpoch && s.crashEpoch == crashAtStart
		if current {
			_, err = s.commitRevisionLocked()
			if err == nil {
				view.bindingEpoch = newEpoch
				view.eventNode = command.eventNode
				view.connectionID = command.connectionID
				view.claimID = command.claimID
				view.cleanupFailed = false
				view.forceAllowed = false
				view.hasProof = false
				view.createdAt = s.scheduler.Now()
				view.sessionGeneration = session.Generation
				view.directory = directory
			}
		}
		s.mu.Unlock()
		if current && err == nil {
			s.bridge.Assign(view.viewID, workspacePath, newEpoch, directory)
			s.resolver.BindView(view.viewID, newEpoch, session.Port, session.Cookie)
			s.mu.Lock()
			view.bindingPending = false
			s.scheduleExpiryLocked(view, time.Time{})
			s.rememberClaimLocked(command.claimID, claimRecord{shape: openShape(command), viewID: view.viewID, epoch: newEpoch})
			s.mu.Unlock()
			return openReply(view, s.snapshot()), nil
		}
	}
	if registerErr == nil {
		registerErr = NewError("managed view did not accept the new binding epoch", "CLEANUP_FAILED")
	}
	if s.currentCrashEpoch() != crashAtStart || currentGeneration(s.manager) != session.Generation {
		registerErr = NewError("code-server generation changed during resume", "BUSY")
	}
	// A lost management response is reconciled only from the native registry.
	nativeViews, listErr := s.manager.ListViews(context.Background())
	if listErr == nil && s.currentCrashEpoch() == crashAtStart && currentGeneration(s.manager) == session.Generation {
		for _, nativeView := range nativeViews {
			if nativeView.ViewID != view.viewID || nativeView.State != ManagedViewOpen || nativeView.WorkspacePath != workspacePath {
				continue
			}
			if nativeView.BindingEpoch == strconv.FormatInt(newEpoch, 10) {
				s.mu.Lock()
				current := s.views[view.viewID] == view && view.state == viewOpen && view.bindingPending && view.bindingEpoch == oldEpoch && s.crashEpoch == crashAtStart
				if current {
					_, err = s.commitRevisionLocked()
					if err == nil {
						view.bindingEpoch = newEpoch
						view.eventNode = command.eventNode
						view.connectionID = command.connectionID
						view.claimID = command.claimID
						view.cleanupFailed = false
						view.forceAllowed = false
						view.hasProof = false
						view.createdAt = s.scheduler.Now()
						view.sessionGeneration = session.Generation
					}
				}
				s.mu.Unlock()
				if current && err == nil {
					s.bridge.Assign(view.viewID, workspacePath, newEpoch, directory)
					s.resolver.BindView(view.viewID, newEpoch, session.Port, session.Cookie)
					s.mu.Lock()
					view.bindingPending = false
					s.scheduleExpiryLocked(view, time.Time{})
					s.rememberClaimLocked(command.claimID, claimRecord{shape: openShape(command), viewID: view.viewID, epoch: newEpoch})
					s.mu.Unlock()
					return openReply(view, s.snapshot()), nil
				}
			}
			if nativeView.BindingEpoch == strconv.FormatInt(oldEpoch, 10) {
				s.mu.Lock()
				view.bindingPending = false
				s.scheduleExpiryLocked(view, s.scheduler.Now().Add(viewLeaseDuration))
				s.mu.Unlock()
				return nil, registerErr
			}
		}
	}
	s.mu.Lock()
	if view.state != viewClosing || !view.cleanupFailed {
		_, _ = s.commitRevisionLocked()
		view.state, view.cleanupFailed, view.forceAllowed = viewClosing, true, true
		s.clearExpiryLocked(view)
	}
	view.bindingPending = false
	s.mu.Unlock()
	s.emit(IdeServiceEvent{Kind: "cleanup-failed", ViewID: view.viewID, Message: "resume binding outcome could not be verified"})
	return nil, registerErr
}

func (s *Service) changeDirectory(ctx context.Context, command *protocol.IdeCommand) (*protocol.IdeResult, error) {
	viewID, err := s.requiredViewCommand(command, true)
	if err != nil {
		return nil, err
	}
	lock := s.keyedLock(s.viewLocks, viewID)
	lock.Lock()
	defer lock.Unlock()
	view, err := s.requireBound(command, true)
	if err != nil {
		return nil, err
	}
	directory, err := CanonicalDirectory(command.GetDirectory())
	if err != nil {
		return nil, err
	}
	result, err := s.bridge.Request(ctx, viewID, BridgeCommand{Op: "changeDirectory", Directory: directory}, BridgeRequestOptions{ExpectedDirectory: directory, ExpectedEpoch: view.bindingEpoch})
	if err != nil {
		return nil, err
	}
	if !result.OK {
		return nil, NewError(result.MessageOr("directory change was rejected"), result.Code)
	}
	if !s.isCurrentOpen(view) || result.Directory != directory {
		return nil, NewError("view binding changed", "VIEW_GONE")
	}
	if err := s.writeManagedWorkspace(view.workspacePath, directory); err != nil {
		return nil, NewError("managed workspace could not be updated", "UNAVAILABLE")
	}
	s.mu.Lock()
	_, err = s.commitRevisionLocked()
	if err == nil {
		view.directory = directory
	}
	s.mu.Unlock()
	if err != nil {
		return nil, err
	}
	return changedDirectoryReply(view, s.snapshot()), nil
}

func (s *Service) focusTerminal(ctx context.Context, command *protocol.IdeCommand) (*protocol.IdeResult, error) {
	if s.tracker == nil {
		return nil, NewError("terminal supervision is unavailable", "UNAVAILABLE")
	}
	viewID, err := s.requiredViewCommand(command, true)
	if err != nil {
		return nil, err
	}
	lock := s.keyedLock(s.viewLocks, viewID)
	lock.Lock()
	defer lock.Unlock()
	view, err := s.requireBound(command, true)
	if err != nil {
		return nil, err
	}
	if !s.bridge.IsConnected(viewID, view.bindingEpoch) {
		if err := s.bridge.WaitForConnection(ctx, viewID); err != nil {
			return nil, err
		}
	}
	if !s.isCurrentOpen(view) {
		return nil, NewError("view binding changed", "VIEW_GONE")
	}
	result, err := s.bridge.Request(ctx, viewID, BridgeCommand{Op: "focusTerminal"}, BridgeRequestOptions{ExpectedEpoch: view.bindingEpoch})
	if err != nil {
		return nil, err
	}
	if !result.OK {
		return nil, NewError(result.MessageOr("terminal could not be focused"), result.Code)
	}
	if !s.isCurrentOpen(view) {
		return nil, NewError("view binding changed", "VIEW_GONE")
	}
	return viewAck(viewID), nil
}

func (s *Service) collapseMobile(ctx context.Context, command *protocol.IdeCommand) (*protocol.IdeResult, error) {
	viewID, err := s.requiredViewCommand(command, true)
	if err != nil {
		return nil, err
	}
	lock := s.keyedLock(s.viewLocks, viewID)
	lock.Lock()
	defer lock.Unlock()
	view, err := s.requireBound(command, true)
	if err != nil {
		return nil, err
	}
	if !s.bridge.IsConnected(viewID, view.bindingEpoch) {
		if err := s.bridge.WaitForConnection(ctx, viewID); err != nil {
			return nil, err
		}
	}
	if !s.isCurrentOpen(view) {
		return nil, NewError("view binding changed", "VIEW_GONE")
	}
	result, err := s.bridge.Request(ctx, viewID, BridgeCommand{Op: "collapseMobile"}, BridgeRequestOptions{ExpectedEpoch: view.bindingEpoch})
	if err != nil {
		return nil, err
	}
	if !result.OK {
		return nil, NewError(result.MessageOr("mobile layout could not be applied"), result.Code)
	}
	return viewAck(viewID), nil
}

func (s *Service) listDirectories(command *protocol.IdeCommand) *protocol.IdeResult {
	path := ""
	if command.Directory != nil {
		path = command.GetDirectory()
		if strings.TrimSpace(path) == "" || len([]rune(path)) > maxCommandTextCharacters || hasControl(path) {
			return failureResult(NewError("directory is invalid", "UNAVAILABLE"), IdeSnapshot{}, false)
		}
	} else {
		var err error
		path, err = HostHome(hostEnvironment())
		if err != nil {
			return failureResult(err, IdeSnapshot{}, false)
		}
	}
	listing, err := ListDirectories(path)
	if err != nil {
		return failureResult(err, IdeSnapshot{}, false)
	}
	return directoryListingReply(listing)
}

func (s *Service) closeView(ctx context.Context, command *protocol.IdeCommand) (*protocol.IdeResult, error) {
	viewID, err := s.requiredViewCommand(command, false)
	if err != nil {
		return nil, err
	}
	if command.Discard == nil {
		return nil, NewError("discard is required", "UNAVAILABLE")
	}
	lock := s.keyedLock(s.viewLocks, viewID)
	lock.Lock()
	defer lock.Unlock()
	view, err := s.requireBound(command, false)
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	state, cleanupFailed := view.state, view.cleanupFailed
	epoch := view.bindingEpoch
	s.mu.Unlock()
	if state == viewClosing {
		if !cleanupFailed {
			return nil, NewError("view is closing", "BUSY")
		}
		return s.performClose(ctx, view, ManagedViewCloseDiscard)
	}
	mode := ManagedViewCloseNormal
	if command.GetDiscard() {
		mode = ManagedViewCloseDiscard
	}
	if mode == ManagedViewCloseDiscard && s.bridge.IsConnected(viewID, epoch) {
		s.mu.Lock()
		forceAllowed := view.forceAllowed
		s.mu.Unlock()
		if !forceAllowed {
			return nil, NewError("force close is only available when the window state is unknown", "BUSY")
		}
	}
	if mode == ManagedViewCloseNormal {
		if !s.bridge.IsConnected(viewID, epoch) {
			s.mu.Lock()
			view.forceAllowed = true
			s.scheduleExpiryLocked(view, s.scheduler.Now().Add(viewLeaseDuration))
			s.mu.Unlock()
			return nil, NewError("IDE window is not connected; close outcome is unknown", "UNAVAILABLE")
		}
		s.mu.Lock()
		view.nativeClosePending = true
		s.clearExpiryLocked(view)
		s.mu.Unlock()
		closeCtx, cancel := context.WithCancel(ctx)
		stop := context.AfterFunc(s.lifetime, cancel)
		result, requestErr := s.bridge.Request(closeCtx, viewID, BridgeCommand{Op: "closeView"}, BridgeRequestOptions{ExpectedEpoch: epoch})
		stop()
		cancel()
		s.mu.Lock()
		view.nativeClosePending = false
		s.mu.Unlock()
		if requestErr != nil || ctx.Err() != nil || !s.bridge.IsConnected(viewID, epoch) {
			s.mu.Lock()
			view.forceAllowed = true
			s.scheduleExpiryLocked(view, s.scheduler.Now().Add(viewLeaseDuration))
			s.mu.Unlock()
			return nil, NewError("IDE close outcome is unknown", "UNAVAILABLE")
		}
		if !result.OK {
			s.mu.Lock()
			view.forceAllowed = result.Code != "CANCELLED"
			s.scheduleExpiryLocked(view, s.scheduler.Now().Add(viewLeaseDuration))
			s.mu.Unlock()
			code := result.Code
			if code == "" {
				code = "UNAVAILABLE"
			}
			return nil, NewError(result.MessageOr("IDE close outcome is unknown"), code)
		}
		if !s.isCurrent(view) {
			return nil, NewError("view binding changed", "VIEW_GONE")
		}
		s.mu.Lock()
		view.forceAllowed = false
		_, err = s.commitRevisionLocked()
		if err == nil {
			view.state = viewClosing
		}
		s.clearExpiryLocked(view)
		s.mu.Unlock()
		if err != nil {
			return nil, err
		}
	} else {
		s.mu.Lock()
		_, err = s.commitRevisionLocked()
		if err == nil {
			view.state = viewClosing
		}
		s.clearExpiryLocked(view)
		s.mu.Unlock()
		if err != nil {
			return nil, err
		}
		s.snapshotForClose(ctx, view)
	}
	return s.performClose(ctx, view, mode)
}

func (s *Service) performClose(ctx context.Context, view *viewRecord, mode ManagedViewCloseMode) (*protocol.IdeResult, error) {
	epoch := view.bindingEpoch
	s.abortViewStreams(view)
	if err := s.waitViewStreams(ctx, view); err != nil {
		return nil, s.cleanupFailed(view, err)
	}
	if mode != ManagedViewCloseNormal && s.bridge.IsConnected(view.viewID, epoch) {
		result, err := s.bridge.Request(ctx, view.viewID, BridgeCommand{Op: "closeView", Discard: true}, BridgeRequestOptions{ExpectedEpoch: epoch})
		if err != nil || !result.OK {
			message := "ide window did not confirm close"
			if result.Message != "" {
				message = result.Message
			}
			return nil, s.cleanupFailed(view, NewError(message, "CLEANUP_FAILED"))
		}
	}
	if !s.isCurrent(view) {
		return nil, NewError("view binding changed", "VIEW_GONE")
	}
	serverMode := mode
	if mode == ManagedViewCloseNormal {
		serverMode = ManagedViewCloseDiscard
	}
	result, err := s.manager.CloseView(ctx, view.viewID, epoch, serverMode)
	if err != nil {
		return nil, s.cleanupFailed(view, err)
	}
	if result == nil || !result.Cleanup.Complete || result.State != ManagedViewClosed {
		return nil, s.cleanupFailed(view, NewError("managed view resources did not report exit", "CLEANUP_FAILED"))
	}
	s.clearExpiry(view)
	s.bridge.Revoke(view.viewID, epoch)
	s.resolver.UnbindView(view.viewID, epoch)
	if err := os.Remove(view.workspacePath); err != nil && !errors.Is(err, os.ErrNotExist) {
		return nil, s.cleanupFailed(view, NewError("managed workspace could not be removed", "CLEANUP_FAILED"))
	}
	s.mu.Lock()
	if s.views[view.viewID] == view && view.bindingEpoch == epoch {
		_, err = s.commitRevisionLocked()
		if err == nil {
			delete(s.views, view.viewID)
		}
	}
	s.mu.Unlock()
	if err != nil {
		return nil, err
	}
	s.scheduleIdleStop()
	return successSnapshot(s.snapshot()), nil
}

func (s *Service) snapshotForClose(ctx context.Context, view *viewRecord) {
	if !s.bridge.IsConnected(view.viewID, view.bindingEpoch) {
		s.emit(IdeServiceEvent{Kind: "backup-failed", ViewID: view.viewID, Message: "recovery bridge is not connected"})
		return
	}
	limit := s.recoveryQuota
	if limit > 4<<20 {
		limit = 4 << 20
	}
	signal, cancel := context.WithTimeout(ctx, closeOperationTimeout)
	defer cancel()
	result, err := s.bridge.Request(signal, view.viewID, BridgeCommand{Op: "snapshot", MaxBytes: limit}, BridgeRequestOptions{ExpectedEpoch: view.bindingEpoch})
	if err != nil || !result.OK {
		message := "dirty text snapshot failed"
		if err != nil {
			message = SafeMessage(err)
		} else if result.Message != "" {
			message = result.Message
		}
		s.emit(IdeServiceEvent{Kind: "backup-failed", ViewID: view.viewID, Message: message})
		return
	}
	complete := result.Complete == nil || *result.Complete
	if result.Omitted != nil && *result.Omitted != 0 {
		complete = false
	}
	documents := make([]RecoveryDocument, len(result.Documents))
	for i, document := range result.Documents {
		documents[i] = RecoveryDocument(document)
	}
	backup := RecoveryBackup{ViewID: view.viewID, BindingEpoch: strconv.FormatInt(view.bindingEpoch, 10), Directory: view.directory, CreatedAt: s.scheduler.Now().UTC().Format(time.RFC3339Nano), Complete: complete, Documents: documents}
	if backup.Documents == nil {
		backup.Documents = []RecoveryDocument{}
	}
	if _, err := WriteRecoveryBackup(s.cfg, backup, s.recoveryQuota); err != nil {
		s.emit(IdeServiceEvent{Kind: "backup-failed", ViewID: view.viewID, Message: SafeMessage(err)})
		return
	}
	if !complete {
		s.emit(IdeServiceEvent{Kind: "backup-incomplete", ViewID: view.viewID, Message: "dirty text snapshot was incomplete"})
	}
}

func (s *Service) cleanupFailed(view *viewRecord, err error) error {
	s.mu.Lock()
	if s.views[view.viewID] == view {
		if view.state != viewClosing {
			_, _ = s.commitRevisionLocked()
		}
		view.state, view.cleanupFailed = viewClosing, true
		s.clearExpiryLocked(view)
	}
	s.mu.Unlock()
	message := SafeMessage(err)
	var ideErr *Error
	if errors.As(err, &ideErr) {
		message = ideErr.Message
	}
	s.emit(IdeServiceEvent{Kind: "cleanup-failed", ViewID: view.viewID, Message: message})
	return err
}

func (s *Service) expireView(viewID string, epoch int64) {
	lock := s.keyedLock(s.viewLocks, viewID)
	lock.Lock()
	defer lock.Unlock()
	s.mu.Lock()
	view := s.views[viewID]
	if view == nil || view.bindingEpoch != epoch || view.state != viewOpen || view.bindingPending || view.nativeClosePending || s.closed {
		s.mu.Unlock()
		return
	}
	_, err := s.commitRevisionLocked()
	if err == nil {
		view.state = viewClosing
		view.expiryTimer = nil
	}
	s.mu.Unlock()
	if err != nil {
		return
	}
	confirmed := false
	ctx, cancel := context.WithTimeout(s.lifetime, closeOperationTimeout)
	defer cancel()
	if s.bridge.IsConnected(viewID, epoch) {
		inspection, inspectErr := s.bridge.Request(ctx, viewID, BridgeCommand{Op: "inspect"}, BridgeRequestOptions{ExpectedEpoch: epoch})
		if inspectErr == nil && inspection.OK && inspection.Clean != nil && *inspection.Clean && s.bridge.IsConnected(viewID, epoch) {
			result, closeErr := s.bridge.Request(ctx, viewID, BridgeCommand{Op: "closeView"}, BridgeRequestOptions{ExpectedEpoch: epoch})
			confirmed = closeErr == nil && result.OK && s.bridge.IsConnected(viewID, epoch) && ctx.Err() == nil
		}
	}
	if !confirmed {
		s.mu.Lock()
		if s.views[viewID] == view && view.bindingEpoch == epoch {
			_, _ = s.commitRevisionLocked()
			view.state = viewOpen
			view.forceAllowed = !s.bridge.IsConnected(viewID, epoch)
			view.expiresAt = time.Time{}
		}
		s.mu.Unlock()
		s.scheduleExpiry(view, s.scheduler.Now().Add(viewLeaseDuration))
		s.emit(IdeServiceEvent{Kind: "expiry-deferred", ViewID: viewID, Message: "view expiry requires an explicit close"})
		return
	}
	_, err = s.performClose(context.Background(), view, ManagedViewCloseNormal)
	_ = err
}

// ApplyLease updates expiry only from a fresh, identity-matching daemon lease proof.
func (s *Service) ApplyLease(lease *protocol.IdeLease, roundStartedAt time.Time) {
	if lease == nil || roundStartedAt.IsZero() {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return
	}
	for _, binding := range lease.GetBindings() {
		if binding == nil || binding.ViewId == nil || binding.BrowserSessionId == nil || binding.BindingEpoch == nil {
			continue
		}
		view := s.views[binding.GetViewId()]
		if view == nil || view.state != viewOpen || view.bindingPending || view.browserSession != binding.GetBrowserSessionId() || view.bindingEpoch != binding.GetBindingEpoch() || roundStartedAt.Before(view.createdAt) || view.hasProof && !roundStartedAt.After(view.lastProofAt) {
			continue
		}
		view.lastProofAt, view.hasProof = roundStartedAt, true
		s.scheduleExpiryLocked(view, time.Time{})
	}
}

func (s *Service) scheduleExpiry(view *viewRecord, notBefore time.Time) {
	s.mu.Lock()
	s.scheduleExpiryLocked(view, notBefore)
	s.mu.Unlock()
}
func (s *Service) scheduleExpiryLocked(view *viewRecord, notBefore time.Time) {
	s.clearExpiryLocked(view)
	if view.state != viewOpen || view.bindingPending || view.nativeClosePending || s.closed {
		return
	}
	base := view.createdAt
	if view.hasProof {
		base = view.lastProofAt
	}
	expires := base.Add(viewLeaseDuration)
	if notBefore.After(expires) {
		expires = notBefore
	}
	view.expiresAt = expires
	delay := expires.Sub(s.scheduler.Now())
	if delay < 0 {
		delay = 0
	}
	view.expiryTimer = s.scheduler.AfterFunc(delay, func() { s.expireView(view.viewID, view.bindingEpoch) })
}
func (s *Service) clearExpiry(view *viewRecord) {
	s.mu.Lock()
	s.clearExpiryLocked(view)
	s.mu.Unlock()
}
func (s *Service) clearExpiryLocked(view *viewRecord) {
	if view.expiryTimer != nil {
		view.expiryTimer.Stop()
		view.expiryTimer = nil
	}
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

func (s *Service) releaseStream(stream *activeIdeStream) {
	stream.once.Do(func() {
		stream.cancel()
		stream.release()
		s.mu.Lock()
		delete(s.activeStreams, stream)
		if view := s.views[stream.viewID]; view != nil && view.bindingEpoch == stream.epoch {
			delete(view.streams, stream)
		}
		last := len(s.activeStreams) == 0
		close(stream.done)
		s.mu.Unlock()
		if last {
			s.scheduleIdleStop()
		}
	})
}

func (s *Service) abortViewStreams(view *viewRecord) {
	s.mu.Lock()
	streams := make([]*activeIdeStream, 0, len(view.streams))
	for stream := range view.streams {
		streams = append(streams, stream)
	}
	s.mu.Unlock()
	for _, stream := range streams {
		stream.cancel()
	}
}
func (s *Service) waitViewStreams(ctx context.Context, view *viewRecord) error {
	s.abortViewStreams(view)
	s.mu.Lock()
	streams := make([]*activeIdeStream, 0, len(view.streams))
	for stream := range view.streams {
		streams = append(streams, stream)
	}
	s.mu.Unlock()
	if len(streams) == 0 {
		return nil
	}
	timer := time.NewTimer(closeOperationTimeout)
	defer timer.Stop()
	for _, stream := range streams {
		select {
		case <-stream.done:
		case <-ctx.Done():
			return ErrCancelled
		case <-timer.C:
			return NewError("ide data streams did not close before the cleanup budget", "CLEANUP_FAILED")
		}
	}
	return nil
}

func (s *Service) RestoreBackup(ctx context.Context, viewID string) (int, int, error) {
	ctx, finish, err := s.beginOperation(ctx)
	if err != nil {
		return 0, 0, err
	}
	defer finish()
	viewID, err = ValidateViewID(viewID)
	if err != nil {
		return 0, 0, err
	}
	backup, err := ReadRecoveryBackup(s.cfg, viewID, s.recoveryQuota)
	if err != nil {
		return 0, 0, NewError(SafeMessage(err), "UNAVAILABLE")
	}
	if backup == nil {
		return 0, 0, nil
	}
	s.mu.Lock()
	view := s.views[viewID]
	if view == nil || view.state != viewOpen {
		s.mu.Unlock()
		return 0, 0, NewError("view is not open", "VIEW_GONE")
	}
	epoch := view.bindingEpoch
	s.mu.Unlock()
	if !s.bridge.IsConnected(viewID, epoch) {
		return 0, 0, NewError("IDE window is not connected", "UNAVAILABLE")
	}
	documents := make([]BridgeDocument, len(backup.Documents))
	for i, document := range backup.Documents {
		documents[i] = BridgeDocument(document)
	}
	result, err := s.bridge.Request(ctx, viewID, BridgeCommand{Op: "restore", Documents: documents}, BridgeRequestOptions{ExpectedEpoch: epoch})
	if err != nil {
		return 0, 0, err
	}
	if !result.OK {
		return 0, 0, NewError(result.MessageOr("recovery restore failed"), result.Code)
	}
	restored, skipped := 0, 0
	if result.Restored != nil {
		restored = int(*result.Restored)
	}
	if result.Skipped != nil {
		skipped = int(*result.Skipped)
	}
	return restored, skipped, nil
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
func (s *Service) GetViewState(viewID string) *protocol.IdeViewSummary {
	if _, err := ValidateViewID(viewID); err != nil {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	view := s.views[viewID]
	if view == nil {
		return nil
	}
	return viewSummary(view)
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

func (s *Service) requireDaemonInstance(command *protocol.IdeCommand) error {
	if command.GetExpectedDaemonInstanceId() != s.daemonInstance {
		return NewError("daemon instance changed", "VIEW_GONE")
	}
	return nil
}
func (s *Service) requiredViewCommand(command *protocol.IdeCommand, requireOpen bool) (string, error) {
	if err := s.requireDaemonInstance(command); err != nil {
		return "", err
	}
	if command.ViewId == nil {
		return "", NewError("viewId is required", "UNAVAILABLE")
	}
	viewID, err := ValidateViewID(command.GetViewId())
	if err != nil {
		return "", err
	}
	if command.BrowserSessionId == nil {
		return "", NewError("browserSessionId is required", "UNAVAILABLE")
	}
	browser, err := validateUUIDField(command.GetBrowserSessionId(), "browserSessionId")
	if err != nil {
		return "", err
	}
	if command.BindingEpoch == nil || command.GetBindingEpoch() <= 0 {
		return "", NewError("bindingEpoch must be a positive decimal string", "UNAVAILABLE")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	view := s.views[viewID]
	if view == nil {
		return "", NewError("view is not open", "VIEW_GONE")
	}
	if view.browserSession != browser {
		return "", NewError("view is owned by another browser session", "VIEW_OWNED_BY_OTHER_SESSION")
	}
	if view.bindingEpoch != command.GetBindingEpoch() {
		return "", NewError("view binding changed", "STALE_BINDING")
	}
	if requireOpen && view.state != viewOpen {
		return "", NewError("view is closing", "BUSY")
	}
	return viewID, nil
}
func (s *Service) requireBound(command *protocol.IdeCommand, requireOpen bool) (*viewRecord, error) {
	viewID, err := s.requiredViewCommand(command, requireOpen)
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	view := s.views[viewID]
	if view == nil {
		return nil, NewError("view is not open", "VIEW_GONE")
	}
	return view, nil
}
func (s *Service) isCurrent(view *viewRecord) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.views[view.viewID] == view && s.views[view.viewID].bindingEpoch == view.bindingEpoch
}
func (s *Service) isCurrentOpen(view *viewRecord) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.views[view.viewID] == view && view.state == viewOpen && !view.bindingPending
}
func (s *Service) keyedLock(locks map[string]*sync.Mutex, key string) *sync.Mutex {
	s.mu.Lock()
	defer s.mu.Unlock()
	lock := locks[key]
	if lock == nil {
		lock = &sync.Mutex{}
		locks[key] = lock
	}
	return lock
}
func (s *Service) replayClaim(claimID, shape string) (*protocol.IdeResult, error) {
	s.mu.Lock()
	cached, ok := s.claims[claimID]
	if !ok {
		s.mu.Unlock()
		return nil, nil
	}
	if cached.shape != shape {
		s.mu.Unlock()
		return nil, NewError("claimId was reused with different request fields", "CLAIM_CONFLICT")
	}
	view := s.views[cached.viewID]
	if view != nil && view.state == viewOpen && view.bindingEpoch == cached.epoch && view.claimID == claimID {
		s.mu.Unlock()
		return openReply(view, s.snapshot()), nil
	}
	s.mu.Unlock()
	return nil, NewError("claim was superseded by a newer binding", "STALE_BINDING")
}
func (s *Service) rememberClaimLocked(claimID string, record claimRecord) {
	if _, ok := s.claims[claimID]; ok {
		delete(s.claims, claimID)
		for i, key := range s.claimOrder {
			if key == claimID {
				s.claimOrder = append(s.claimOrder[:i], s.claimOrder[i+1:]...)
				break
			}
		}
	}
	s.claims[claimID] = record
	s.claimOrder = append(s.claimOrder, claimID)
	for len(s.claimOrder) > maxCachedClaims {
		oldest := s.claimOrder[0]
		s.claimOrder = s.claimOrder[1:]
		delete(s.claims, oldest)
	}
}
func (s *Service) commitRevisionLocked() (int64, error) {
	if s.revision == math.MaxInt64 {
		return 0, NewError("IDE revision space is exhausted", "BUSY")
	}
	s.revision++
	return s.revision, nil
}
func (s *Service) snapshot() IdeSnapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	ids := make([]string, 0, len(s.views))
	for id := range s.views {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	views := make([]*protocol.IdeViewSummary, 0, len(ids))
	for _, id := range ids {
		views = append(views, viewSummary(s.views[id]))
	}
	return IdeSnapshot{Revision: s.revision, Views: views}
}
func (s *Service) emit(event IdeServiceEvent) {
	if s.onEvent != nil {
		func() { defer func() { _ = recover() }(); s.onEvent(event) }()
	}
}

// IdeSnapshot is the frozen control-visible revision and sorted view list.
type IdeSnapshot struct {
	Revision int64
	Views    []*protocol.IdeViewSummary
}

func (s *Service) currentCrashEpoch() uint64 { s.mu.Lock(); defer s.mu.Unlock(); return s.crashEpoch }
func currentGeneration(manager CodeServerManager) uint64 {
	session := manager.Session()
	if session == nil {
		return 0
	}
	return session.Generation
}
func openShape(command parsedOpen) string {
	mode := "create"
	if command.mode == protocol.IdeOpenMode_RESUME {
		mode = "resume"
	}
	var directory any
	if command.hasDirectory {
		directory = command.directory
	}
	shape := struct {
		Action               string `json:"action"`
		ViewID               string `json:"viewId"`
		BrowserSessionID     string `json:"browserSessionId"`
		EventNodeID          string `json:"eventNodeId"`
		ConnectionID         string `json:"connectionId"`
		ClaimID              string `json:"claimId"`
		Mode                 string `json:"mode"`
		Directory            any    `json:"directory"`
		ExpectedBindingEpoch string `json:"expectedBindingEpoch"`
	}{"OPEN_VIEW", command.viewID, command.browserSession, command.eventNode, command.connectionID, command.claimID, mode, directory, strconv.FormatInt(command.expectedEpoch, 10)}
	encoded, _ := json.Marshal(shape)
	return string(encoded)
}
func validateUUIDField(value, field string) (string, error) {
	if validUUID(value) {
		return value, nil
	}
	return "", NewError(field+" must be a canonical lowercase UUID", "UNAVAILABLE")
}
func requiredString(value *string, field string) (string, error) {
	if value == nil {
		return "", NewError(field+" is required", "UNAVAILABLE")
	}
	return *value, nil
}
func hostEnvironment() map[string]string {
	env := make(map[string]string)
	for _, entry := range os.Environ() {
		key, value, ok := strings.Cut(entry, "=")
		if ok {
			env[key] = value
		}
	}
	return env
}
func (r BridgeResult) MessageOr(fallback string) string {
	if r.Message != "" {
		return r.Message
	}
	return fallback
}

func viewSummary(view *viewRecord) *protocol.IdeViewSummary {
	state := protocol.IdeViewState_OPEN
	if view.state == viewClosing {
		state = protocol.IdeViewState_CLOSING
	}
	return &protocol.IdeViewSummary{ViewId: stringPtr(view.viewID), BrowserSessionId: stringPtr(view.browserSession), EventNodeId: stringPtr(view.eventNode), ConnectionId: stringPtr(view.connectionID), ClaimId: stringPtr(view.claimID), BindingEpoch: int64Ptr(view.bindingEpoch), Directory: stringPtr(view.directory), WorkspacePath: stringPtr(view.workspacePath), State: state}
}
func successSnapshot(snapshot IdeSnapshot) *protocol.IdeResult {
	return &protocol.IdeResult{Ok: boolPtr(true), Revision: int64Ptr(snapshot.Revision), Views: snapshot.Views}
}
func openReply(view *viewRecord, snapshot IdeSnapshot) *protocol.IdeResult {
	result := successSnapshot(snapshot)
	result.ViewId = stringPtr(view.viewID)
	result.BrowserSessionId = stringPtr(view.browserSession)
	result.EventNodeId = stringPtr(view.eventNode)
	result.ConnectionId = stringPtr(view.connectionID)
	result.ClaimId = stringPtr(view.claimID)
	result.BindingEpoch = int64Ptr(view.bindingEpoch)
	result.Directory = stringPtr(view.directory)
	result.WorkspacePath = stringPtr(view.workspacePath)
	return result
}
func changedDirectoryReply(view *viewRecord, snapshot IdeSnapshot) *protocol.IdeResult {
	result := successSnapshot(snapshot)
	result.ViewId = stringPtr(view.viewID)
	result.Directory = stringPtr(view.directory)
	return result
}
func viewAck(viewID string) *protocol.IdeResult {
	return &protocol.IdeResult{Ok: boolPtr(true), ViewId: stringPtr(viewID)}
}
func directoryListingReply(listing DirectoryListing) *protocol.IdeResult {
	children := make([]*protocol.IdeDirectoryEntry, 0, len(listing.Children))
	for _, child := range listing.Children {
		children = append(children, &protocol.IdeDirectoryEntry{Name: stringPtr(child.Name), Path: stringPtr(child.Path)})
	}
	return &protocol.IdeResult{Ok: boolPtr(true), DirectoryListing: &protocol.IdeDirectoryListing{Path: stringPtr(listing.Path), ParentPath: optionalStringPtr(listing.ParentPath), Children: children, Truncated: boolPtr(listing.Truncated)}}
}
func failureResult(err error, snapshot IdeSnapshot, includeSnapshot bool) *protocol.IdeResult {
	code, message := FailureCode(err), SafeMessage(err)
	result := &protocol.IdeResult{Ok: boolPtr(false), Code: stringPtr(code), Message: stringPtr(message)}
	if includeSnapshot {
		result.Revision = int64Ptr(snapshot.Revision)
		result.Views = snapshot.Views
	}
	return result
}
func stringPtr(value string) *string { return &value }
func int64Ptr(value int64) *int64    { return &value }
func boolPtr(value bool) *bool       { return &value }
func optionalStringPtr(value string) *string {
	if value == "" {
		return nil
	}
	return &value
}

var _ IdeCommandService = (*Service)(nil)
var _ transport.ViewResolver = (*Service)(nil)
