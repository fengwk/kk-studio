package ide

import (
	"encoding/json"
	"math"
	"sort"
	"strconv"
	"strings"

	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

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
