package protocol

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
