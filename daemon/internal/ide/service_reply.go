package ide

import (
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// IdeSnapshot is the frozen control-visible revision and sorted view list.
type IdeSnapshot struct {
	Revision int64
	Views    []*protocol.IdeViewSummary
}

// viewSummary reports one view; a view without a directory omits it instead of inventing one.
func viewSummary(view *viewRecord) *protocol.IdeViewSummary {
	state := protocol.IdeViewState_OPEN
	if view.state == viewClosing {
		state = protocol.IdeViewState_CLOSING
	}
	return &protocol.IdeViewSummary{ViewId: stringPtr(view.viewID), BrowserSessionId: stringPtr(view.browserSession), EventNodeId: stringPtr(view.eventNode), ConnectionId: stringPtr(view.connectionID), ClaimId: stringPtr(view.claimID), BindingEpoch: int64Ptr(view.bindingEpoch), Directory: optionalStringPtr(view.directory), WorkspacePath: stringPtr(view.workspacePath), State: state}
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
	result.Directory = optionalStringPtr(view.directory)
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

func int64Ptr(value int64) *int64 { return &value }

func boolPtr(value bool) *bool { return &value }

func optionalStringPtr(value string) *string {
	if value == "" {
		return nil
	}
	return &value
}

func (r BridgeResult) MessageOr(fallback string) string {
	if r.Message != "" {
		return r.Message
	}
	return fallback
}
