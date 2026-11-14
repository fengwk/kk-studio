package protocol

// validateMessage dispatches one envelope payload to the validator for its message family. Case
// order and rejection messages mirror the control protocol contract shared with Studio.
func validateMessage(e *Envelope) error {
	switch x := e.Payload.(type) {
	case *Envelope_Settings:
		return validateSettings(x.Settings)
	case *Envelope_EnvironmentConfig:
		return validateEnvironmentConfig(x.EnvironmentConfig)
	case *Envelope_Ready:
		return validateReady(x.Ready)
	case *Envelope_Heartbeat:
		return validateHeartbeat(x.Heartbeat)
	case *Envelope_RuntimeStatus:
		return validateRuntimeStatus(x.RuntimeStatus)
	case *Envelope_Invoke:
		return validateInvoke(x.Invoke)
	case *Envelope_Started:
		if x.Started.Replayed != nil && !x.Started.GetReplayed() {
			return invalid("started.replayed must be true when present")
		}
	case *Envelope_Progress:
		return validateResult(x.Progress.Result, "progress", false)
	case *Envelope_Completed:
		return validateResult(x.Completed.Result, "completed", true)
	case *Envelope_Failed:
		return requireString(x.Failed.Message, "failed.message", true, 4096)
	case *Envelope_Cancelled:
		return requireString(x.Cancelled.Reason, "cancelled.reason", true, 4096)
	case *Envelope_ResourceUploadRequest:
		return validateUploadRequest(x.ResourceUploadRequest)
	case *Envelope_ResourceUploadTicket:
		return validateUploadTicket(x.ResourceUploadTicket)
	case *Envelope_ResourceUploadCommit:
		if err := uuid(x.ResourceUploadCommit.GetTransferId(), "resourceUploadCommit.transferId"); err != nil {
			return err
		}
		return uuid(x.ResourceUploadCommit.GetUploadId(), "resourceUploadCommit.uploadId")
	case *Envelope_ResourceDownloadRequest:
		return uuid(x.ResourceDownloadRequest.GetTransferId(), "resourceDownloadRequest.transferId")
	case *Envelope_ResourceDownloadTicket:
		return validateDownloadTicket(x.ResourceDownloadTicket)
	case *Envelope_TemporaryResourcePolicy:
		return validateTemporaryResourcePolicy(x.TemporaryResourcePolicy)
	case *Envelope_Update:
		if err := uuid(x.Update.GetOperationId(), "update.operationId"); err != nil {
			return err
		}
		return daemonVersion(x.Update.GetTargetVersion(), "update.targetVersion")
	case *Envelope_UpdateResult:
		return validateUpdateResult(x.UpdateResult)
	case *Envelope_IdeCommand:
		return validateIDECommand(x.IdeCommand)
	case *Envelope_IdeResult:
		return validateIDEResult(x.IdeResult)
	case *Envelope_IdeLease:
		return validateIDELease(x.IdeLease)
	case *Envelope_Error:
		return requireString(x.Error.Message, "error.message", true, 4096)
	}
	return nil
}

func validateSettings(s *Settings) error {
	b := s.ResourceBudgets
	if b == nil {
		return invalid("settings.resourceBudgets is required")
	}
	if b.TemporaryResourceTtlSeconds == nil || b.GetTemporaryResourceTtlSeconds() <= 0 {
		return invalid("settings.resourceBudgets.temporaryResourceTtlSeconds must be positive")
	}
	if b.TemporaryResourceCleanupIntervalSeconds == nil || b.GetTemporaryResourceCleanupIntervalSeconds() <= 0 {
		return invalid("settings.resourceBudgets.temporaryResourceCleanupIntervalSeconds must be positive")
	}
	return nil
}

func validateTemporaryResourcePolicy(v *TemporaryResourcePolicy) error {
	if v.TemporaryResourceTtlSeconds == nil || v.GetTemporaryResourceTtlSeconds() <= 0 {
		return invalid("temporaryResourcePolicy.temporaryResourceTtlSeconds must be positive")
	}
	if v.TemporaryResourceCleanupIntervalSeconds == nil || v.GetTemporaryResourceCleanupIntervalSeconds() <= 0 {
		return invalid("temporaryResourcePolicy.temporaryResourceCleanupIntervalSeconds must be positive")
	}
	return nil
}

func validateUpdateResult(v *UpdateResult) error {
	if err := requireString(v.OperationId, "updateResult.operationId", true, 256); err != nil {
		return err
	}
	if v.Phase < UpdatePhase_UPDATE_ACCEPTED || v.Phase > UpdatePhase_UPDATE_FAILED {
		return invalid("updateResult.phase is required")
	}
	if v.Message != nil {
		if err := requireString(v.Message, "updateResult.message", true, 500); err != nil {
			return err
		}
	}
	if v.Message != nil && hasControl(v.GetMessage()) {
		return invalid("updateResult.message must not contain control characters")
	}
	return nil
}

func validateUploadRequest(v *ResourceUploadRequest) error {
	if err := uuid(v.GetTransferId(), "resourceUploadRequest.transferId"); err != nil {
		return err
	}
	if err := mediaType(v.GetMediaType(), "resourceUploadRequest.mediaType"); err != nil {
		return err
	}
	if v.Name != nil {
		if err := requireBytesString(v.Name, "resourceUploadRequest.name", true, 512); err != nil {
			return err
		}
	}
	if v.Size == nil || v.GetSize() < 0 || v.GetSize() > maxSafeInteger {
		return invalid("resourceUploadRequest.size must be a non-negative safe integer")
	}
	return sha(v.GetSha256(), "resourceUploadRequest.sha256")
}

func validateInvoke(v *Invoke) error {
	if v.CapabilityId == nil || !capabilityPattern.MatchString(v.GetCapabilityId()) {
		return invalid("invoke.capabilityId has invalid syntax")
	}
	if v.ArgumentsJson == nil {
		return invalid("invoke.argumentsJson is required")
	}
	if err := jsonField(v.GetArgumentsJson(), "invoke.argumentsJson", MaxFrameSize, true); err != nil {
		return err
	}
	if v.TimeoutMillis == nil || v.GetTimeoutMillis() < 0 || v.GetTimeoutMillis() > maxSafeInteger {
		return invalid("invoke.timeoutMillis must be a non-negative safe integer")
	}
	if v.MaxOutputBytes != nil && (v.GetMaxOutputBytes() < 0 || v.GetMaxOutputBytes() > maxSafeInteger) {
		return invalid("invoke.maxOutputBytes must be a non-negative safe integer")
	}
	if v.ExpectedFile != nil {
		capabilityID := v.GetCapabilityId()
		if capabilityID != "fs.edit" && capabilityID != "fs.write" {
			return invalid("invoke.expectedFile is only valid for fs.edit and fs.write")
		}
		if err := requireString(v.ExpectedFile.Path, "invoke.expectedFile.path", true, 0); err != nil {
			return err
		}
		if v.ExpectedFile.Sha256 == nil {
			if capabilityID == "fs.edit" {
				return invalid("invoke.expectedFile.sha256 is required")
			}
		} else if err := sha(v.ExpectedFile.GetSha256(), "invoke.expectedFile.sha256"); err != nil {
			return err
		}
	}
	return nil
}
