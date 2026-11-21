package ide

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
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
	expiryGeneration   uint64
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
	return s.createView(ctx, parsed, shape, crashAtStart)
}

// createView resolves the workspace, starts the session and registers the new view.
func (s *Service) createView(ctx context.Context, parsed parsedOpen, shape string, crashAtStart uint64) (*protocol.IdeResult, error) {
	directory, workspacePath, epoch, err := s.prepareOpenWorkspace(parsed)
	if err != nil {
		return nil, err
	}
	session, err := s.manager.EnsureStarted(ctx)
	if err != nil {
		_ = os.Remove(workspacePath)
		return nil, err
	}
	if err := s.registerOpenView(ctx, parsed, epoch, directory, workspacePath, crashAtStart, session); err != nil {
		return nil, err
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

// prepareOpenWorkspace writes and resolves the managed workspace file under a fresh epoch. A view
// created without a directory keeps an empty folder list; no host home is substituted.
func (s *Service) prepareOpenWorkspace(parsed parsedOpen) (string, string, int64, error) {
	directory := ""
	if parsed.hasDirectory {
		canonical, err := CanonicalDirectory(parsed.directory)
		if err != nil {
			return "", "", 0, err
		}
		directory = canonical
	}
	workspacePath := filepath.Join(s.paths.ViewsDir, parsed.viewID+".code-workspace")
	if err := s.writeManagedWorkspace(workspacePath, directory); err != nil {
		return "", "", 0, NewError("managed workspace could not be written", "UNAVAILABLE")
	}
	workspacePath, err := filepath.EvalSymlinks(workspacePath)
	if err != nil {
		_ = os.Remove(workspacePath)
		return "", "", 0, NewError("managed workspace could not be resolved", "UNAVAILABLE")
	}
	s.mu.Lock()
	epoch, err := s.commitRevisionLocked()
	s.mu.Unlock()
	if err != nil {
		_ = os.Remove(workspacePath)
		return "", "", 0, err
	}
	return directory, workspacePath, epoch, nil
}

// registerOpenView rejects a drifted registration and decides how to retain the failed view.
func (s *Service) registerOpenView(ctx context.Context, parsed parsedOpen, epoch int64, directory, workspacePath string, crashAtStart uint64, session *CodeServerSession) error {
	registered, registerErr := s.manager.RegisterView(ctx, parsed.viewID, epoch, workspacePath)
	if registerErr == nil && registered != nil && registered.State == ManagedViewOpen && registered.BindingEpoch == strconv.FormatInt(epoch, 10) && registered.WorkspacePath == workspacePath && s.currentCrashEpoch() == crashAtStart && currentGeneration(s.manager) == session.Generation {
		return nil
	}
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
	return reason
}

// resumeBinding carries the epoch pair and workspace fixed at the start of one resume attempt.
type resumeBinding struct {
	view          *viewRecord
	command       parsedOpen
	session       *CodeServerSession
	oldEpoch      int64
	newEpoch      int64
	crashAtStart  uint64
	workspacePath string
	directory     string
}

func (s *Service) resumeView(ctx context.Context, command parsedOpen, view *viewRecord) (*protocol.IdeResult, error) {
	currentDirectory, err := s.admitsResume(command, view)
	if err != nil {
		return nil, err
	}
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
	binding, err := s.beginResume(command, view)
	if err != nil {
		return nil, err
	}
	s.abortViewStreams(view)
	registered, registerErr := s.manager.RegisterView(ctx, view.viewID, binding.newEpoch, binding.workspacePath)
	if registerErr == nil && registered != nil && registered.State == ManagedViewOpen && registered.BindingEpoch == strconv.FormatInt(binding.newEpoch, 10) && registered.WorkspacePath == binding.workspacePath && s.currentCrashEpoch() == binding.crashAtStart && currentGeneration(s.manager) == binding.session.Generation {
		if reply := s.commitResumedBinding(binding, true); reply != nil {
			return reply, nil
		}
	}
	registerErr = s.resumeFailure(registerErr, binding)
	return s.reconcileResume(binding, registerErr)
}

// admitsResume reports the current directory when the view may accept a new binding.
func (s *Service) admitsResume(command parsedOpen, view *viewRecord) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if view.state != viewOpen {
		return "", NewError("view is closing", "BUSY")
	}
	if view.cleanupFailed {
		return "", NewError("view cleanup is incomplete", "CLEANUP_FAILED")
	}
	if view.browserSession != command.browserSession {
		return "", NewError("view is owned by another browser session", "VIEW_OWNED_BY_OTHER_SESSION")
	}
	if view.bindingEpoch != command.expectedEpoch {
		return "", NewError("view binding changed", "STALE_BINDING")
	}
	return view.directory, nil
}

// beginResume takes the epoch pair and marks the binding pending until it is settled.
func (s *Service) beginResume(command parsedOpen, view *viewRecord) (resumeBinding, error) {
	s.mu.Lock()
	if s.views[view.viewID] != view || view.state != viewOpen || view.bindingPending || view.bindingEpoch != command.expectedEpoch {
		s.mu.Unlock()
		return resumeBinding{}, NewError("view binding changed", "STALE_BINDING")
	}
	binding := resumeBinding{view: view, command: command, oldEpoch: view.bindingEpoch, crashAtStart: s.crashEpoch}
	view.bindingPending = true
	s.clearExpiryLocked(view)
	newEpoch, err := s.commitRevisionLocked()
	if err != nil {
		view.bindingPending = false
		s.scheduleExpiryLocked(view, s.scheduler.Now().Add(viewLeaseDuration))
		s.mu.Unlock()
		return resumeBinding{}, err
	}
	binding.newEpoch = newEpoch
	binding.workspacePath, binding.directory = view.workspacePath, view.directory
	s.mu.Unlock()
	session := s.manager.Session()
	if session == nil {
		s.mu.Lock()
		view.bindingPending = false
		s.scheduleExpiryLocked(view, s.scheduler.Now().Add(viewLeaseDuration))
		s.mu.Unlock()
		return resumeBinding{}, NewError("code-server is not running", "UNAVAILABLE")
	}
	binding.session = session
	return binding, nil
}

// resumeFailure settles the error reported when the direct registration was not confirmed.
func (s *Service) resumeFailure(registerErr error, binding resumeBinding) error {
	if registerErr == nil {
		registerErr = NewError("managed view did not accept the new binding epoch", "CLEANUP_FAILED")
	}
	if s.currentCrashEpoch() != binding.crashAtStart || currentGeneration(s.manager) != binding.session.Generation {
		return NewError("code-server generation changed during resume", "BUSY")
	}
	return registerErr
}

// reconcileResume settles a lost management response from the native registry alone.
func (s *Service) reconcileResume(binding resumeBinding, registerErr error) (*protocol.IdeResult, error) {
	nativeViews, listErr := s.manager.ListViews(context.Background())
	if listErr != nil || s.currentCrashEpoch() != binding.crashAtStart || currentGeneration(s.manager) != binding.session.Generation {
		return nil, s.markResumeUnverified(binding.view, registerErr)
	}
	for _, nativeView := range nativeViews {
		if nativeView.ViewID != binding.view.viewID || nativeView.State != ManagedViewOpen || nativeView.WorkspacePath != binding.workspacePath {
			continue
		}
		if nativeView.BindingEpoch == strconv.FormatInt(binding.newEpoch, 10) {
			if reply := s.commitResumedBinding(binding, false); reply != nil {
				return reply, nil
			}
		}
		if nativeView.BindingEpoch == strconv.FormatInt(binding.oldEpoch, 10) {
			s.mu.Lock()
			binding.view.bindingPending = false
			s.scheduleExpiryLocked(binding.view, s.scheduler.Now().Add(viewLeaseDuration))
			s.mu.Unlock()
			return nil, registerErr
		}
	}
	return nil, s.markResumeUnverified(binding.view, registerErr)
}

// commitResumedBinding installs the new epoch under the same liveness checks as the caller.
// assignDirectory is false for the reconciliation path, which leaves the directory untouched.
func (s *Service) commitResumedBinding(binding resumeBinding, assignDirectory bool) *protocol.IdeResult {
	command, view, session := binding.command, binding.view, binding.session
	newEpoch, oldEpoch, crashAtStart := binding.newEpoch, binding.oldEpoch, binding.crashAtStart
	workspacePath, directory := binding.workspacePath, binding.directory
	s.mu.Lock()
	current := s.views[view.viewID] == view && view.state == viewOpen && view.bindingPending && view.bindingEpoch == oldEpoch && s.crashEpoch == crashAtStart
	var err error
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
			if assignDirectory {
				view.directory = directory
			}
		}
	}
	s.mu.Unlock()
	if !current || err != nil {
		return nil
	}
	s.bridge.Assign(view.viewID, workspacePath, newEpoch, directory)
	s.resolver.BindView(view.viewID, newEpoch, session.Port, session.Cookie)
	s.mu.Lock()
	view.bindingPending = false
	s.scheduleExpiryLocked(view, time.Time{})
	s.rememberClaimLocked(command.claimID, claimRecord{shape: openShape(command), viewID: view.viewID, epoch: newEpoch})
	s.mu.Unlock()
	return openReply(view, s.snapshot())
}

// markResumeUnverified retains the view as a cleanup failure that needs an explicit close.
func (s *Service) markResumeUnverified(view *viewRecord, registerErr error) error {
	s.mu.Lock()
	if view.state != viewClosing || !view.cleanupFailed {
		_, _ = s.commitRevisionLocked()
		view.state, view.cleanupFailed, view.forceAllowed = viewClosing, true, true
		s.clearExpiryLocked(view)
	}
	view.bindingPending = false
	s.mu.Unlock()
	s.emit(IdeServiceEvent{Kind: "cleanup-failed", ViewID: view.viewID, Message: "resume binding outcome could not be verified"})
	return registerErr
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
	return successSnapshot(s.snapshot()), nil
}

// reconcileDirectoryChange converges service metadata and the managed workspace after a late host confirmation.
func (s *Service) reconcileDirectoryChange(viewID string, epoch int64, operationID, directory string) {
	lock := s.keyedLock(s.viewLocks, viewID)
	lock.Lock()
	defer lock.Unlock()
	s.mu.Lock()
	view := s.views[viewID]
	current := view != nil && view.state == viewOpen && view.bindingEpoch == epoch
	s.mu.Unlock()
	if !current || !s.bridge.IsCurrentDirectoryOperation(viewID, epoch, operationID, directory) {
		return
	}
	if err := s.writeManagedWorkspace(view.workspacePath, directory); err != nil {
		s.cleanupFailed(view, NewError("directory reconciliation failed", "CLEANUP_FAILED"))
		return
	}
	if !s.bridge.IsCurrentDirectoryOperation(viewID, epoch, operationID, directory) {
		return
	}
	var commitErr error
	s.mu.Lock()
	if s.views[viewID] == view && view.bindingEpoch == epoch && view.state == viewOpen && view.directory != directory {
		_, commitErr = s.commitRevisionLocked()
		if commitErr == nil {
			view.directory = directory
		}
	}
	s.mu.Unlock()
	if commitErr != nil {
		s.cleanupFailed(view, NewError("directory reconciliation state could not be committed", "CLEANUP_FAILED"))
	}
}

const maxAttachmentTextBytes = 64 * 1024

func (s *Service) openAttachment(ctx context.Context, command *protocol.IdeCommand) (*protocol.IdeResult, error) {
	if command.AttachmentId == nil || !validUUID(command.GetAttachmentId()) {
		return nil, NewError("attachmentId must be a canonical lowercase UUID", "UNAVAILABLE")
	}
	if command.AttachmentText == nil {
		return nil, NewError("attachmentText is required", "UNAVAILABLE")
	}
	text := command.GetAttachmentText()
	if len(text) > maxAttachmentTextBytes || !utf8.ValidString(text) || strings.IndexByte(text, 0) >= 0 {
		return nil, NewError("attachment text is not previewable", "UNAVAILABLE")
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
	result, err := s.bridge.Request(ctx, viewID, BridgeCommand{
		Op: "openAttachment", AttachmentID: command.AttachmentId, AttachmentText: command.AttachmentText,
	}, BridgeRequestOptions{ExpectedEpoch: view.bindingEpoch})
	if err != nil {
		return nil, err
	}
	if !result.OK {
		return nil, NewError(result.MessageOr("attachment could not be opened"), result.Code)
	}
	if !s.isCurrentOpen(view) {
		return nil, NewError("view binding changed", "VIEW_GONE")
	}
	return viewAck(viewID), nil
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
			s.deferUnknownOutcome(view)
			return nil, NewError("IDE control channel is unavailable; confirm force close to continue", "CONTROL_UNAVAILABLE")
		}
		result, err := s.requestNativeClose(ctx, view, epoch)
		if err != nil || result == nil {
			return nil, err
		}
		if !result.OK {
			s.mu.Lock()
			view.forceAllowed = result.Code == "OUTCOME_UNKNOWN" || result.Code == "CONTROL_UNAVAILABLE"
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
		forceAllowed := false
		if err := s.enterClosing(view, &forceAllowed); err != nil {
			return nil, err
		}
	} else {
		if err := s.enterClosing(view, nil); err != nil {
			return nil, err
		}
		s.snapshotForClose(ctx, view)
	}
	return s.performClose(ctx, view, mode)
}

// requestNativeClose asks the connected window to close, returning the window result or an outcome error.
func (s *Service) requestNativeClose(ctx context.Context, view *viewRecord, epoch int64) (*BridgeResult, error) {
	s.mu.Lock()
	view.nativeClosePending = true
	s.clearExpiryLocked(view)
	s.mu.Unlock()
	closeCtx, cancel := context.WithCancel(ctx)
	stop := context.AfterFunc(s.lifetime, cancel)
	result, requestErr := s.bridge.Request(closeCtx, view.viewID, BridgeCommand{Op: "closeView"}, BridgeRequestOptions{ExpectedEpoch: epoch})
	stop()
	cancel()
	s.mu.Lock()
	view.nativeClosePending = false
	s.mu.Unlock()
	if requestErr == nil && result.Code == "CANCELLED" {
		return &result, nil
	}
	if requestErr != nil || ctx.Err() != nil || !s.bridge.IsConnected(view.viewID, epoch) {
		s.deferUnknownOutcome(view)
		code := "OUTCOME_UNKNOWN"
		var bridgeErr *Error
		if !result.sent && (result.Code == "VIEW_GONE" || (errors.As(requestErr, &bridgeErr) && bridgeErr.Code == "UNAVAILABLE")) {
			code = "CONTROL_UNAVAILABLE"
		}
		if code == "CONTROL_UNAVAILABLE" {
			return nil, NewError("IDE control channel is unavailable; confirm force close to continue", code)
		}
		return nil, NewError("IDE close outcome is unknown", code)
	}
	return &result, nil
}

// deferUnknownOutcome keeps the view leased and allows a later forced close.
func (s *Service) deferUnknownOutcome(view *viewRecord) {
	s.mu.Lock()
	view.forceAllowed = true
	s.scheduleExpiryLocked(view, s.scheduler.Now().Add(viewLeaseDuration))
	s.mu.Unlock()
}

// enterClosing commits the revision and marks the view closing in one critical section.
// forceAllowed is only assigned when supplied, matching the two close modes' distinct intent.
func (s *Service) enterClosing(view *viewRecord, forceAllowed *bool) error {
	s.mu.Lock()
	if forceAllowed != nil {
		view.forceAllowed = *forceAllowed
	}
	_, err := s.commitRevisionLocked()
	if err == nil {
		view.state = viewClosing
	}
	s.clearExpiryLocked(view)
	s.mu.Unlock()
	return err
}

func (s *Service) performClose(ctx context.Context, view *viewRecord, mode ManagedViewCloseMode) (*protocol.IdeResult, error) {
	if err := s.waitViewStreams(ctx, view); err != nil {
		return nil, s.cleanupFailed(view, err)
	}
	return s.finishClose(ctx, view, mode)
}

func (s *Service) finishClose(ctx context.Context, view *viewRecord, mode ManagedViewCloseMode) (*protocol.IdeResult, error) {
	epoch := view.bindingEpoch
	if mode == ManagedViewCloseDiscard && s.bridge.IsConnected(view.viewID, epoch) {
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
	complete, err := s.snapshotRecovery(ctx, view, false)
	if err != nil {
		s.emit(IdeServiceEvent{Kind: "backup-failed", ViewID: view.viewID, Message: SafeMessage(err)})
	} else if !complete {
		s.emit(IdeServiceEvent{Kind: "backup-incomplete", ViewID: view.viewID, Message: "dirty text snapshot was incomplete"})
	}
}

// Automatic cleanup requires explicit, complete proof; explicit force close is best-effort.
func (s *Service) snapshotRecovery(ctx context.Context, view *viewRecord, requireComplete bool) (bool, error) {
	if !s.bridge.IsConnected(view.viewID, view.bindingEpoch) {
		return false, NewError("recovery bridge is not connected", "UNAVAILABLE")
	}
	limit := s.recoveryQuota
	if limit > 4<<20 {
		limit = 4 << 20
	}
	signal, cancel := context.WithTimeout(ctx, closeOperationTimeout)
	defer cancel()
	result, err := s.bridge.Request(signal, view.viewID, BridgeCommand{Op: "snapshot", MaxBytes: limit}, BridgeRequestOptions{ExpectedEpoch: view.bindingEpoch})
	if err != nil {
		return false, err
	}
	if !result.OK {
		if requireComplete {
			return false, NewError("dirty text snapshot failed", "UNAVAILABLE")
		}
		return false, NewError(result.MessageOr("dirty text snapshot failed"), "UNAVAILABLE")
	}
	complete := result.Complete != nil && *result.Complete && (result.Omitted == nil || *result.Omitted == 0)
	// A view without a directory may hold drafts from unrelated files, so a force close also needs
	// a captured, provably complete set of absolute-path documents.
	if (requireComplete || view.directory == "") && (!complete || result.Documents == nil) {
		return false, NewError("dirty text snapshot was incomplete", "UNAVAILABLE")
	}
	if len(result.Documents) == 0 {
		return complete, nil
	}
	documents := make([]RecoveryDocument, len(result.Documents))
	for i, document := range result.Documents {
		documents[i] = RecoveryDocument(document)
	}
	backup := RecoveryBackup{ViewID: view.viewID, BindingEpoch: strconv.FormatInt(view.bindingEpoch, 10), Directory: view.directory, CreatedAt: s.scheduler.Now().UTC().Format(time.RFC3339Nano), Complete: complete, Documents: documents}
	s.recoveryMu.Lock()
	defer s.recoveryMu.Unlock()
	if requireComplete {
		_, err = writeDurableRecoveryBackup(s.cfg, backup, s.recoveryQuota)
	} else {
		_, err = WriteRecoveryBackup(s.cfg, backup, s.recoveryQuota)
	}
	if err != nil {
		// Recovery writers return path-free diagnostics, unlike raw filesystem errors.
		return false, NewError(err.Error(), "UNAVAILABLE")
	}
	return complete, nil
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

func (s *Service) expireView(view *viewRecord, epoch int64, generation uint64) {
	lock := s.keyedLock(s.viewLocks, view.viewID)
	lock.Lock()
	defer lock.Unlock()
	s.mu.Lock()
	if s.closed || s.views[view.viewID] != view || view.bindingEpoch != epoch || view.expiryGeneration != generation ||
		view.state != viewOpen || view.bindingPending || view.nativeClosePending || s.scheduler.Now().Before(view.expiresAt) {
		s.mu.Unlock()
		return
	}
	s.clearExpiryLocked(view)
	if _, err := s.commitRevisionLocked(); err != nil {
		s.mu.Unlock()
		return
	}
	view.state = viewClosing
	s.operations.Add(1)
	s.mu.Unlock()
	defer s.operations.Done()

	// CLOSING fences new streams. Drain this binding before asking its host for proof.
	ctx, cancel := context.WithTimeout(s.lifetime, closeOperationTimeout)
	err := s.waitViewStreams(ctx, view)
	cancel()
	if err == nil {
		_, err = s.snapshotRecovery(s.lifetime, view, true)
	}
	if err != nil {
		s.mu.Lock()
		if !s.closed && s.views[view.viewID] == view && !view.cleanupFailed {
			_, _ = s.commitRevisionLocked()
			view.state = viewOpen
			s.scheduleExpiryLocked(view, s.scheduler.Now().Add(viewLeaseDuration))
		}
		s.mu.Unlock()
		s.emit(IdeServiceEvent{Kind: "expiry-deferred", ViewID: view.viewID, Message: SafeMessage(err)})
		return
	}
	s.mu.Lock()
	current := !s.closed && s.views[view.viewID] == view && !view.cleanupFailed
	s.mu.Unlock()
	if current {
		ctx, cancel := context.WithTimeout(s.lifetime, closeOperationTimeout)
		defer cancel()
		_, _ = s.finishClose(ctx, view, ManagedViewCloseExpired)
	}
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
	epoch, generation := view.bindingEpoch, view.expiryGeneration
	view.expiryTimer = s.scheduler.AfterFunc(delay, func() { s.expireView(view, epoch, generation) })
}

func (s *Service) clearExpiry(view *viewRecord) {
	s.mu.Lock()
	s.clearExpiryLocked(view)
	s.mu.Unlock()
}

func (s *Service) clearExpiryLocked(view *viewRecord) {
	view.expiryGeneration++
	if view.expiryTimer != nil {
		view.expiryTimer.Stop()
		view.expiryTimer = nil
	}
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
