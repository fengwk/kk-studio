package protocol

import (
	"fmt"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf16"
	"unicode/utf8"
)

const maxSafeInteger int64 = 1<<53 - 1

var (
	versionPattern    = regexp.MustCompile(`^[0-9A-Za-z._+-]{1,64}$`)
	capabilityPattern = regexp.MustCompile(`^[a-z0-9]+(?:[.-][a-z0-9]+)*$`)
	mediaTypePattern  = regexp.MustCompile(`^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$`)
	ideCodePattern    = regexp.MustCompile(`^[A-Z][A-Z0-9_]{0,63}$`)
	proxyURLPattern   = regexp.MustCompile(`(?i)^http://(\[[^\]]+\]|[^/?#@\[\]:]+):([0-9]{1,5})$`)
	serverIDPattern   = regexp.MustCompile(`^[A-Za-z0-9_.-]+$`)
)

func validateMessage(e *Envelope) error {
	switch x := e.Payload.(type) {
	case *Envelope_Settings:
		b := x.Settings.ResourceBudgets
		if b == nil {
			return invalid("settings.resourceBudgets is required")
		}
		if b.TemporaryResourceTtlSeconds == nil || b.GetTemporaryResourceTtlSeconds() <= 0 {
			return invalid("settings.resourceBudgets.temporaryResourceTtlSeconds must be positive")
		}
		if b.TemporaryResourceCleanupIntervalSeconds == nil || b.GetTemporaryResourceCleanupIntervalSeconds() <= 0 {
			return invalid("settings.resourceBudgets.temporaryResourceCleanupIntervalSeconds must be positive")
		}
	case *Envelope_EnvironmentConfig:
		c := x.EnvironmentConfig
		if c.Version == nil {
			return invalid("environmentConfig.version is required")
		}
		if c.GetVersion() < 0 {
			return invalid("environmentConfig.version must be non-negative")
		}
		if c.Config == nil {
			return invalid("environmentConfig.config is required")
		}
		if err := validateDaemonConfig(c.Config); err != nil {
			return err
		}
	case *Envelope_Ready:
		r := x.Ready
		if err := daemonVersion(r.GetDaemonVersion(), "ready.daemonVersion"); err != nil {
			return err
		}
		if r.HostInfo == nil {
			return invalid("ready.hostInfo is required")
		}
		if err := validateHost(r.HostInfo); err != nil {
			return err
		}
		if r.RuntimeStatus == nil {
			return invalid("ready.runtimeStatus is required")
		}
		if err := validateSnapshot(r.RuntimeStatus, "ready.runtimeStatus"); err != nil {
			return err
		}
		if r.RuntimeStatus.GetStatus().GetDaemonVersion() != r.GetDaemonVersion() {
			return invalid("ready.runtimeStatus.status.daemonVersion must match ready.daemonVersion")
		}
		if r.RuntimeStatus.GetStatus().GetConfiguration() == nil || r.RuntimeStatus.GetStatus().GetConfiguration().AppliedVersion == nil {
			return invalid("ready.runtimeStatus.status.configuration.appliedVersion is required")
		}
	case *Envelope_Heartbeat:
		if x.Heartbeat.RuntimeStatus == nil {
			return invalid("heartbeat.runtimeStatus is required")
		}
		if err := validateSnapshot(x.Heartbeat.RuntimeStatus, "heartbeat.runtimeStatus"); err != nil {
			return err
		}
		if x.Heartbeat.IdeLeaseRound != nil && x.Heartbeat.GetIdeLeaseRound() <= 0 {
			return invalid("heartbeat.ideLeaseRound must be positive")
		}
	case *Envelope_RuntimeStatus:
		if x.RuntimeStatus.RuntimeStatus == nil {
			return invalid("runtimeStatus.runtimeStatus is required")
		}
		if err := validateSnapshot(x.RuntimeStatus.RuntimeStatus, "runtimeStatus.runtimeStatus"); err != nil {
			return err
		}
	case *Envelope_Invoke:
		v := x.Invoke
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
			if v.GetCapabilityId() != "fs.edit" {
				return invalid("invoke.expectedFile is only valid for fs.edit")
			}
			if err := requireString(v.ExpectedFile.Path, "invoke.expectedFile.path", true, 0); err != nil {
				return err
			}
			if err := sha(v.ExpectedFile.GetSha256(), "invoke.expectedFile.sha256"); err != nil {
				return err
			}
		}
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
		v := x.ResourceUploadRequest
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
		v := x.TemporaryResourcePolicy
		if v.TemporaryResourceTtlSeconds == nil || v.GetTemporaryResourceTtlSeconds() <= 0 {
			return invalid("temporaryResourcePolicy.temporaryResourceTtlSeconds must be positive")
		}
		if v.TemporaryResourceCleanupIntervalSeconds == nil || v.GetTemporaryResourceCleanupIntervalSeconds() <= 0 {
			return invalid("temporaryResourcePolicy.temporaryResourceCleanupIntervalSeconds must be positive")
		}
	case *Envelope_Update:
		if err := uuid(x.Update.GetOperationId(), "update.operationId"); err != nil {
			return err
		}
		return daemonVersion(x.Update.GetTargetVersion(), "update.targetVersion")
	case *Envelope_UpdateResult:
		v := x.UpdateResult
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

func invalid(s string) error   { return fmt.Errorf("%s", s) }
func utf16Length(s string) int { return len(utf16.Encode([]rune(s))) }

func validProxyURL(value string) bool {
	if utf16Length(value) > 2048 {
		return false
	}
	match := proxyURLPattern.FindStringSubmatch(value)
	if match == nil {
		return false
	}
	port, err := strconv.Atoi(match[2])
	if err != nil || port < 1 || port > 65535 {
		return false
	}
	host := strings.Trim(match[1], "[]")
	return host != "*"
}

func requireString(value *string, name string, nonblank bool, max int) error {
	if value == nil {
		return invalid(name + " must be a string")
	}
	s := *value
	if nonblank && strings.TrimSpace(s) == "" {
		return invalid(name + " must not be blank")
	}
	if max > 0 && utf16Length(s) > max {
		return invalid(fmt.Sprintf("%s exceeds %d characters", name, max))
	}
	return nil
}
func requireBytesString(value *string, name string, nonblank bool, max int) error {
	if value == nil {
		return invalid(name + " must be a string")
	}
	if nonblank && strings.TrimSpace(*value) == "" {
		return invalid(name + " must not be blank")
	}
	if max > 0 && len(*value) > max {
		return invalid(fmt.Sprintf("%s exceeds %d UTF-8 bytes", name, max))
	}
	return nil
}

func uuid(s, name string) error {
	if !canonicalUUID.MatchString(s) {
		return invalid(name + " must be a canonical lowercase UUID")
	}
	return nil
}
func sha(s, name string) error {
	if len(s) != 64 {
		return invalid(name + " must be a lowercase SHA-256")
	}
	for _, c := range s {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return invalid(name + " must be a lowercase SHA-256")
		}
	}
	return nil
}
func mediaType(s, name string) error {
	if len(s) > 255 || !mediaTypePattern.MatchString(s) {
		return invalid(name + " has invalid syntax")
	}
	return nil
}
func daemonVersion(s, name string) error {
	if !versionPattern.MatchString(s) {
		return invalid(name + " has invalid syntax")
	}
	return nil
}
func jsonField(s, name string, max int, object bool) error {
	if s == "" {
		return invalid(name + " is required")
	}
	if err := ValidateJSON([]byte(s), JSONLimits{MaxBytes: max}); err != nil {
		return invalid(name + " " + err.Error())
	}
	if object && (s[0] != '{' || strings.TrimSpace(s) == "{}") { /* object checked below */
	}
	if object {
		var first byte
		for i := 0; i < len(s); i++ {
			if s[i] != ' ' && s[i] != '\n' && s[i] != '\r' && s[i] != '\t' {
				first = s[i]
				break
			}
		}
		if first != '{' {
			return invalid(name + " must be a JSON object")
		}
	}
	return nil
}
func hasControl(s string) bool {
	for _, r := range s {
		if unicode.IsControl(r) {
			return true
		}
	}
	return false
}
func validateDaemonConfig(c *DaemonConfiguration) error {
	u, err := url.Parse(c.GetStudioUrl())
	if err != nil || (strings.ToLower(u.Scheme) != "http" && strings.ToLower(u.Scheme) != "https") || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return invalid("environmentConfig.config.studioUrl has invalid syntax")
	}
	if c.BashExecutable != nil && (strings.TrimSpace(*c.BashExecutable) == "" || hasControl(*c.BashExecutable)) {
		return invalid("environmentConfig.config.bashExecutable is invalid")
	}
	if c.Lsp != nil && len(c.Lsp.Servers) == 0 {
		return invalid("environmentConfig.config.lsp.servers must declare at least one server")
	}
	seen := map[string]bool{}
	for _, s := range c.GetLsp().GetServers() {
		if s == nil || !serverIDPattern.MatchString(s.Id) || seen[s.Id] {
			return invalid("environmentConfig.config.lsp.servers has invalid or duplicate id")
		}
		seen[s.Id] = true
		if err := validateStringList(s.Command, true, false, false); err != nil {
			return invalid("environmentConfig.config.lsp.server.command must be a nonempty array of strings")
		}
		if err := validateStringList(s.Extensions, true, true, false); err != nil {
			return invalid("environmentConfig.config.lsp.server.extensions must be valid file extensions")
		}
		if err := validateStringList(s.RootMarkers, false, false, true); err != nil {
			return invalid("environmentConfig.config.lsp.server.rootMarkers must be project-relative without traversal")
		}
		if err := validateStringList(s.FirstMatchMarkers, false, false, true); err != nil {
			return invalid("environmentConfig.config.lsp.server.firstMatchMarkers must be project-relative without traversal")
		}
	}
	if c.ProxyUrl != nil && !validProxyURL(*c.ProxyUrl) {
		return invalid("environmentConfig.config.proxyUrl has invalid syntax")
	}
	return nil
}
func validateStringList(values []string, required, extension, marker bool) error {
	if len(values) == 0 {
		if required {
			return invalid("empty list")
		}
		return nil
	}
	for _, value := range values {
		if strings.TrimSpace(value) == "" || hasControl(value) {
			return invalid("blank/control value")
		}
		if extension {
			normalized := strings.ToLower(strings.TrimSpace(value))
			if len(normalized) < 2 || !strings.HasPrefix(normalized, ".") || strings.ContainsAny(normalized, "/\\") {
				return invalid("invalid extension")
			}
		}
		if marker {
			normalized := strings.TrimSpace(strings.ReplaceAll(value, "\\", "/"))
			if strings.HasPrefix(normalized, "/") || strings.Contains(normalized, ":") {
				return invalid("invalid marker")
			}
			for _, part := range strings.Split(normalized, "/") {
				if part == "" || part == "." || part == ".." {
					return invalid("invalid marker")
				}
			}
		}
	}
	return nil
}

func validateHost(h *HostInfo) error {
	if h.OperatingSystem < OperatingSystem_WINDOWS || h.OperatingSystem > OperatingSystem_MACOS {
		return invalid("ready.hostInfo.operatingSystem is required")
	}
	if h.Architecture < Architecture_AMD64 || h.Architecture > Architecture_ARM64 {
		return invalid("ready.hostInfo.architecture is required")
	}
	if h.TimeZone != nil {
		if len(*h.TimeZone) == 0 || len(*h.TimeZone) > 128 {
			return invalid("ready.hostInfo.timeZone has invalid syntax")
		}
		if _, err := time.LoadLocation(*h.TimeZone); err != nil {
			return invalid("ready.hostInfo.timeZone must be a valid ZoneId")
		}
	}
	for n, v := range map[string]*string{"userName": h.UserName, "homeDirectory": h.HomeDirectory, "tempDirectory": h.TempDirectory} {
		if v != nil && (strings.TrimSpace(*v) != *v || hasControl(*v)) {
			return invalid("ready.hostInfo." + n + " must be a single line without surrounding whitespace")
		}
	}
	return nil
}
func validateSnapshot(s *RuntimeSnapshot, path string) error {
	if s.Revision == nil || s.GetRevision() <= 0 {
		return invalid(path + ".revision must be positive")
	}
	if s.Status == nil {
		return invalid(path + ".status is required")
	}
	if err := daemonVersion(s.Status.GetDaemonVersion(), path+".status.daemonVersion"); err != nil {
		return err
	}
	if s.Status.Configuration != nil {
		c := s.Status.Configuration
		if c.AppliedVersion != nil && c.GetAppliedVersion() < 0 {
			return invalid(path + ".status.configuration.appliedVersion must be non-negative")
		}
		if c.ProxyUrl != nil {
			if c.AppliedVersion == nil {
				return invalid(path + ".status.configuration.proxyUrl requires appliedVersion")
			}
			if !validProxyURL(*c.ProxyUrl) {
				return invalid(path + ".status.configuration.proxyUrl has invalid syntax")
			}
		}
		if c.Error != nil {
			if c.Error.Version == nil || c.Error.GetVersion() < 0 {
				return invalid(path + ".status.configuration.error.version is required")
			}
			if !ideCodePattern.MatchString(c.Error.Code) {
				return invalid(path + ".status.configuration.error.code has invalid syntax")
			}
			if c.Error.Field != nil && (utf16Length(c.Error.GetField()) > 256 || !regexp.MustCompile(`^[A-Za-z0-9_.\[\]-]+$`).MatchString(c.Error.GetField())) {
				return invalid(path + ".status.configuration.error.field has invalid syntax")
			}
		}
	}
	if s.Status.Update != nil {
		u := s.Status.Update
		if err := daemonVersion(u.GetTargetVersion(), path+".status.update.targetVersion"); err != nil {
			return err
		}
		if u.DownloadedBytes == nil || u.GetDownloadedBytes() < 0 || u.GetDownloadedBytes() > maxSafeInteger {
			return invalid(path + ".status.update.downloadedBytes must be a non-negative safe integer")
		}
		if u.TotalBytes != nil && u.GetTotalBytes() < u.GetDownloadedBytes() {
			return invalid(path + ".status.update.totalBytes must be at least downloadedBytes")
		}
	}
	return nil
}
func validateResult(r *CapabilityResult, path string, allowResource bool) error {
	if r == nil {
		return invalid(path + ".result is required")
	}
	if err := requireString(r.CallId, path+".result.callId", true, 256); err != nil {
		return err
	}
	if r.Error == nil {
		return invalid(path + ".result.error is required")
	}
	if err := jsonField(r.GetDetailsJson(), path+".result.detailsJson", 1<<20, true); err != nil {
		return err
	}
	if len(r.Contents) > 64 {
		return invalid(path + ".result.contents must not exceed 64 items")
	}
	for i, c := range r.Contents {
		if c == nil {
			return invalid(fmt.Sprintf("%s.result.contents[%d] must declare exactly one content body", path, i))
		}
		switch b := c.Body.(type) {
		case *Content_Text:
			if b.Text == nil {
				return invalid(path + ".result.content.text must be a string")
			}
		case *Content_Json:
			if b.Json == nil {
				return invalid(path + ".result.content.json is required")
			}
			if err := jsonField(c.GetJson().GetJson(), path+".result.content.json", 1<<20, false); err != nil {
				return err
			}
		case *Content_Resource:
			if !allowResource {
				return invalid(path + ".result.contents must not declare a resource")
			}
			if err := validateResourceContent(b.Resource, path+".result.contents.resource"); err != nil {
				return err
			}
		default:
			return invalid(path + ".result.contents must declare exactly one content body")
		}
	}
	return nil
}
func validateResourceContent(r *ResourceContent, path string) error {
	if r == nil {
		return invalid(path + " is required")
	}
	if err := uuid(r.GetUploadId(), path+".uploadId"); err != nil {
		return err
	}
	if err := mediaType(r.GetMediaType(), path+".mediaType"); err != nil {
		return err
	}
	if r.Name != nil {
		if err := requireBytesString(r.Name, path+".name", true, 512); err != nil {
			return err
		}
	}
	if r.Name != nil && hasControl(r.GetName()) {
		return invalid(path + ".name must not contain control characters")
	}
	if r.Size == nil || r.GetSize() < 0 || r.GetSize() > maxSafeInteger {
		return invalid(path + ".size must be a non-negative safe integer")
	}
	if err := sha(r.GetSha256(), path+".sha256"); err != nil {
		return err
	}
	if len(r.GetPreview()) > 16384 {
		return invalid(path + ".preview exceeds 16384 UTF-8 bytes")
	}
	return nil
}
func validateUploadTicket(t *ResourceUploadTicket) error {
	if err := uuid(t.GetTransferId(), "uploadTicket.transferId"); err != nil {
		return err
	}
	switch t.State {
	case TicketState_TICKET_PENDING:
		if t.UploadId == nil || t.PresignedPut == nil || t.Message != nil {
			return invalid("PENDING ticket requires uploadId and presignedPut only")
		}
		if err := uuid(t.GetUploadId(), "uploadTicket.uploadId"); err != nil {
			return err
		}
		p := t.PresignedPut
		if p.GetMethod() != "PUT" {
			return invalid("presignedPut.method must be PUT")
		}
		if err := requireString(p.Url, "presignedPut.url", true, 4096); err != nil {
			return err
		}
		if len(p.Headers) > 16 {
			return invalid("presignedPut.headers must not exceed 16 entries")
		}
		for k, v := range p.Headers {
			if strings.TrimSpace(k) == "" || len(k) > 1024 || strings.TrimSpace(v) == "" || len(v) > 1024 {
				return invalid("presignedPut.header is invalid")
			}
		}
		if p.ExpiresAtEpochMs == nil || p.GetExpiresAtEpochMs() <= 0 {
			return invalid("presignedPut.expiresAtEpochMs must be positive")
		}
	case TicketState_TICKET_READY:
		if t.UploadId == nil || t.PresignedPut != nil || t.Message != nil {
			return invalid("READY ticket requires uploadId only")
		}
		return uuid(t.GetUploadId(), "uploadTicket.uploadId")
	case TicketState_TICKET_FAILED:
		if t.UploadId != nil || t.PresignedPut != nil {
			return invalid("FAILED ticket requires a message only")
		}
		return requireString(t.Message, "uploadTicket.message", true, 512)
	default:
		return invalid("uploadTicket.state is required")
	}
	return nil
}
func validateDownloadTicket(t *ResourceDownloadTicket) error {
	if err := uuid(t.GetTransferId(), "downloadTicket.transferId"); err != nil {
		return err
	}
	switch t.State {
	case TicketState_TICKET_READY:
		if t.Message != nil {
			return invalid("READY download ticket must not declare a message")
		}
		if t.PresignedGet == nil {
			return invalid("downloadTicket.presignedGet is required")
		}
		if t.PresignedGet.GetMethod() != "GET" {
			return invalid("presignedGet.method must be GET")
		}
		if err := requireString(t.PresignedGet.Url, "presignedGet.url", true, 4096); err != nil {
			return err
		}
		if t.PresignedGet.ExpiresAtEpochMs == nil || t.PresignedGet.GetExpiresAtEpochMs() <= 0 {
			return invalid("presignedGet.expiresAtEpochMs must be positive")
		}
		if t.Size == nil || t.GetSize() < 0 || t.GetSize() > maxSafeInteger {
			return invalid("downloadTicket.size must be non-negative")
		}
		return sha(t.GetSha256(), "downloadTicket.sha256")
	case TicketState_TICKET_FAILED:
		if t.PresignedGet != nil || t.Size != nil || t.Sha256 != nil {
			return invalid("FAILED download ticket requires a message only")
		}
		return requireString(t.Message, "downloadTicket.message", true, 512)
	default:
		return invalid("downloadTicket.state is required")
	}
}

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
