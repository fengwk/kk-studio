package protocol

import (
	"fmt"
	"strings"
	"unicode/utf8"
)

func validateIDECommand(c *IdeCommand) error {
	if c == nil {
		return invalid("ideCommand.action is required")
	}
	switch c.Action {
	case IdeAction_OPEN_VIEW:
		for name, value := range map[string]string{"viewId": c.GetViewId(), "browserSessionId": c.GetBrowserSessionId(), "eventNodeId": c.GetEventNodeId(), "connectionId": c.GetConnectionId(), "claimId": c.GetClaimId(), "expectedDaemonInstanceId": c.GetExpectedDaemonInstanceId()} {
			if err := uuid(value, "ideCommand."+name); err != nil {
				return err
			}
		}
		if c.ExpectedBindingEpoch == nil || c.GetExpectedBindingEpoch() < 0 {
			return invalid("OPEN_VIEW expectedBindingEpoch is required and non-negative")
		}
		if c.BindingEpoch != nil || c.Discard != nil {
			return invalid("OPEN_VIEW must not declare bindingEpoch or discard")
		}
		if c.Mode != IdeOpenMode_CREATE && c.Mode != IdeOpenMode_RESUME {
			return invalid("ideCommand.mode is required for OPEN_VIEW")
		}
		if c.Mode == IdeOpenMode_CREATE && c.GetExpectedBindingEpoch() != 0 {
			return invalid("OPEN_VIEW create requires expectedBindingEpoch 0")
		}
		if c.Mode == IdeOpenMode_RESUME && c.GetExpectedBindingEpoch() <= 0 {
			return invalid("OPEN_VIEW resume requires a positive expectedBindingEpoch")
		}
		if c.Directory != nil {
			return requireIDEPath(c.GetDirectory(), "ideCommand.directory")
		}
		return nil
	case IdeAction_CHANGE_DIRECTORY, IdeAction_FOCUS_TERMINAL, IdeAction_CLOSE_VIEW, IdeAction_COLLAPSE_MOBILE:
		if err := uuid(c.GetViewId(), "ideCommand.viewId"); err != nil {
			return err
		}
		if err := uuid(c.GetBrowserSessionId(), "ideCommand.browserSessionId"); err != nil {
			return err
		}
		if c.BindingEpoch == nil || c.GetBindingEpoch() <= 0 {
			return invalid("ideCommand.bindingEpoch must be positive")
		}
		if err := uuid(c.GetExpectedDaemonInstanceId(), "ideCommand.expectedDaemonInstanceId"); err != nil {
			return err
		}
		if c.EventNodeId != nil || c.ConnectionId != nil || c.ClaimId != nil || c.ExpectedBindingEpoch != nil || c.Mode != IdeOpenMode_IDE_OPEN_MODE_UNSPECIFIED {
			return invalid("ideCommand declares fields that do not belong to its action")
		}
		if c.Action == IdeAction_CHANGE_DIRECTORY {
			if c.Discard != nil {
				return invalid("ideCommand.discard is not valid for this action")
			}
			if c.Directory == nil {
				return invalid("ideCommand.directory is required")
			}
			return requireIDEPath(c.GetDirectory(), "ideCommand.directory")
		}
		if c.Action == IdeAction_CLOSE_VIEW {
			if c.Discard == nil {
				return invalid("ideCommand.discard is required")
			}
			if c.Directory != nil {
				return invalid("CLOSE_VIEW must not declare directory")
			}
			return nil
		}
		if c.Discard != nil {
			return invalid("ideCommand.discard is not valid for this action")
		}
		if c.Directory != nil {
			return invalid("ideCommand.directory is not valid for this action")
		}
		return nil
	case IdeAction_LIST_VIEWS, IdeAction_LIST_DIRECTORIES:
		if c.ViewId != nil || c.BrowserSessionId != nil || c.EventNodeId != nil || c.ConnectionId != nil || c.ClaimId != nil || c.BindingEpoch != nil || c.ExpectedBindingEpoch != nil || c.ExpectedDaemonInstanceId != nil || c.Mode != IdeOpenMode_IDE_OPEN_MODE_UNSPECIFIED || c.Discard != nil || c.Action == IdeAction_LIST_VIEWS && c.Directory != nil {
			return invalid("IDE list command has invalid fields")
		}
		if c.Directory != nil {
			return requireIDEPath(c.GetDirectory(), "ideCommand.directory")
		}
		return nil
	default:
		return invalid("ideCommand.action is required")
	}
}

func requireIDEPath(s, name string) error {
	if strings.TrimSpace(s) == "" {
		return invalid(name + " must not be blank")
	}
	if utf8.RuneCountInString(s) > 4096 {
		return invalid(fmt.Sprintf("%s exceeds 4096 characters", name))
	}
	if hasControl(s) {
		return invalid(name + " must not contain control characters")
	}
	return nil
}

func validateSnapshotViews(views []*IdeViewSummary, revision int64) error {
	if revision < 0 {
		return invalid("ideResult.revision must be non-negative")
	}
	if len(views) > 64 {
		return invalid("ideResult.views must not exceed 64 entries")
	}
	last := ""
	seen := map[string]bool{}
	for _, v := range views {
		if v == nil {
			return invalid("ideResult.views is invalid")
		}
		if err := validateViewSummary(v, "ideResult.views"); err != nil {
			return err
		}
		if v.GetBindingEpoch() > revision {
			return invalid("ideResult.views.bindingEpoch must not exceed revision")
		}
		if seen[v.GetViewId()] {
			return invalid("ideResult.views must not repeat viewId")
		}
		if last != "" && v.GetViewId() < last {
			return invalid("ideResult.views must be sorted by viewId")
		}
		seen[v.GetViewId()] = true
		last = v.GetViewId()
	}
	return nil
}

func validateViewSummary(v *IdeViewSummary, path string) error {
	for n, s := range map[string]string{"viewId": v.GetViewId(), "browserSessionId": v.GetBrowserSessionId(), "eventNodeId": v.GetEventNodeId(), "connectionId": v.GetConnectionId(), "claimId": v.GetClaimId()} {
		if err := uuid(s, path+"."+n); err != nil {
			return err
		}
	}
	if v.GetBindingEpoch() <= 0 {
		return invalid(path + ".bindingEpoch must be positive")
	}
	if err := requireIDEPath(v.GetDirectory(), path+".directory"); err != nil {
		return err
	}
	if err := requireIDEPath(v.GetWorkspacePath(), path+".workspacePath"); err != nil {
		return err
	}
	if v.State != IdeViewState_OPEN && v.State != IdeViewState_CLOSING {
		return invalid(path + ".state is required")
	}
	return nil
}

func validateIDELease(l *IdeLease) error {
	if l.Round == nil || l.GetRound() <= 0 {
		return invalid("ideLease.round must be positive")
	}
	if len(l.Bindings) > 64 {
		return invalid("ideLease.bindings must not exceed 64")
	}
	seen := map[string]bool{}
	for _, b := range l.Bindings {
		if b == nil {
			return invalid("ideLease.bindings is invalid")
		}
		if err := uuid(b.GetViewId(), "ideLease.binding.viewId"); err != nil {
			return err
		}
		if err := uuid(b.GetBrowserSessionId(), "ideLease.binding.browserSessionId"); err != nil {
			return err
		}
		if b.BindingEpoch == nil || b.GetBindingEpoch() <= 0 {
			return invalid("ideLease.binding.bindingEpoch must be positive")
		}
		if seen[b.GetViewId()] {
			return invalid("ideLease.bindings must not repeat viewId")
		}
		seen[b.GetViewId()] = true
	}
	return nil
}

func validateIDEResult(r *IdeResult) error {
	if r.Ok == nil {
		return invalid("ideResult.ok is required")
	}
	if !r.GetOk() {
		if r.Revision != nil {
			if r.ViewId != nil || r.BrowserSessionId != nil || r.EventNodeId != nil || r.ConnectionId != nil || r.ClaimId != nil || r.BindingEpoch != nil || r.Directory != nil || r.WorkspacePath != nil || r.DirectoryListing != nil {
				return invalid("failed IDE result with a snapshot must only declare code, message, revision and views")
			}
		} else if len(r.Views) > 0 {
			return invalid("ideResult.revision is required with views")
		} else if r.ViewId != nil || r.BrowserSessionId != nil || r.EventNodeId != nil || r.ConnectionId != nil || r.ClaimId != nil || r.BindingEpoch != nil || r.Directory != nil || r.WorkspacePath != nil || r.DirectoryListing != nil {
			return invalid("failed IDE result must only declare code and message")
		}
		if r.Code == nil || !ideCodePattern.MatchString(r.GetCode()) {
			return invalid("ideResult.code has invalid syntax")
		}
		return requireString(r.Message, "ideResult.message", true, 500)
	}
	if r.Code != nil || r.Message != nil {
		return invalid("successful IDE result must not declare code or message")
	}
	if r.DirectoryListing != nil {
		if r.Revision != nil || len(r.Views) > 0 || r.ViewId != nil || r.BrowserSessionId != nil || r.EventNodeId != nil || r.ConnectionId != nil || r.ClaimId != nil || r.BindingEpoch != nil || r.Directory != nil || r.WorkspacePath != nil {
			return invalid("directory listing result must not contain view or snapshot fields")
		}
		d := r.DirectoryListing
		if err := requireIDEPath(d.GetPath(), "directoryListing.path"); err != nil {
			return err
		}
		if d.ParentPath != nil {
			if err := requireIDEPath(d.GetParentPath(), "directoryListing.parentPath"); err != nil {
				return err
			}
		}
		if d.Truncated == nil || len(d.Children) > 1000 {
			return invalid("directory listing requires truncated and at most 1000 children")
		}
		for _, child := range d.Children {
			if child == nil {
				return invalid("directoryEntry is invalid")
			}
			if err := requireIDEPath(child.GetName(), "directoryEntry.name"); err != nil {
				return err
			}
			if err := requireIDEPath(child.GetPath(), "directoryEntry.path"); err != nil {
				return err
			}
		}
		return nil
	}
	if r.Revision != nil {
		if err := validateSnapshotViews(r.Views, r.GetRevision()); err != nil {
			return err
		}
	} else if len(r.Views) > 0 {
		return invalid("ideResult.revision is required with views")
	}
	binding := r.BrowserSessionId != nil || r.EventNodeId != nil || r.ConnectionId != nil || r.ClaimId != nil || r.BindingEpoch != nil || r.WorkspacePath != nil
	if binding {
		for name, value := range map[string]string{"viewId": r.GetViewId(), "browserSessionId": r.GetBrowserSessionId(), "eventNodeId": r.GetEventNodeId(), "connectionId": r.GetConnectionId(), "claimId": r.GetClaimId()} {
			if err := uuid(value, "ideResult."+name); err != nil {
				return err
			}
		}
		if r.BindingEpoch == nil || r.GetBindingEpoch() <= 0 {
			return invalid("ideResult.bindingEpoch must be positive")
		}
		if err := requireIDEPath(r.GetDirectory(), "ideResult.directory"); err != nil {
			return err
		}
		if err := requireIDEPath(r.GetWorkspacePath(), "ideResult.workspacePath"); err != nil {
			return err
		}
		if r.Revision != nil {
			var found *IdeViewSummary
			for _, v := range r.Views {
				if v.GetViewId() == r.GetViewId() {
					found = v
					break
				}
			}
			if found == nil {
				return invalid("ideResult snapshot must contain the top-level binding view")
			}
			if found.GetBrowserSessionId() != r.GetBrowserSessionId() || found.GetEventNodeId() != r.GetEventNodeId() || found.GetConnectionId() != r.GetConnectionId() || found.GetClaimId() != r.GetClaimId() || found.GetBindingEpoch() != r.GetBindingEpoch() || found.GetDirectory() != r.GetDirectory() || found.GetWorkspacePath() != r.GetWorkspacePath() {
				return invalid("ideResult snapshot view must match the top-level binding")
			}
		}
		return nil
	}
	if r.Directory != nil {
		if err := uuid(r.GetViewId(), "ideResult.viewId"); err != nil {
			return err
		}
		return requireIDEPath(r.GetDirectory(), "ideResult.directory")
	}
	if r.ViewId != nil {
		return uuid(r.GetViewId(), "ideResult.viewId")
	}
	if r.Revision == nil {
		return invalid("successful IDE result must declare an outcome")
	}
	return nil
}
