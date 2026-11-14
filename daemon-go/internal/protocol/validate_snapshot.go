package protocol

import (
	"strings"
	"time"
)

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
		if err := validateConfigurationStatus(s.Status.Configuration, path+".status.configuration"); err != nil {
			return err
		}
	}
	if s.Status.Update != nil {
		if err := validateUpdateProgress(s.Status.Update, path+".status.update"); err != nil {
			return err
		}
	}
	return nil
}

func validateConfigurationStatus(c *ConfigurationStatus, path string) error {
	if c.AppliedVersion != nil && c.GetAppliedVersion() < 0 {
		return invalid(path + ".appliedVersion must be non-negative")
	}
	if c.ProxyUrl != nil {
		if c.AppliedVersion == nil {
			return invalid(path + ".proxyUrl requires appliedVersion")
		}
		if !validProxyURL(*c.ProxyUrl) {
			return invalid(path + ".proxyUrl has invalid syntax")
		}
	}
	if c.Error != nil {
		if err := validateConfigurationError(c.Error, path+".error"); err != nil {
			return err
		}
	}
	return nil
}

func validateConfigurationError(e *ConfigurationError, path string) error {
	if e.Version == nil || e.GetVersion() < 0 {
		return invalid(path + ".version is required")
	}
	if !ideCodePattern.MatchString(e.Code) {
		return invalid(path + ".code has invalid syntax")
	}
	if e.Field != nil && (utf16Length(e.GetField()) > 256 || !fieldPathPattern.MatchString(e.GetField())) {
		return invalid(path + ".field has invalid syntax")
	}
	return nil
}

func validateUpdateProgress(u *UpdateProgress, path string) error {
	if err := daemonVersion(u.GetTargetVersion(), path+".targetVersion"); err != nil {
		return err
	}
	if u.DownloadedBytes == nil || u.GetDownloadedBytes() < 0 || u.GetDownloadedBytes() > maxSafeInteger {
		return invalid(path + ".downloadedBytes must be a non-negative safe integer")
	}
	if u.TotalBytes != nil && u.GetTotalBytes() < u.GetDownloadedBytes() {
		return invalid(path + ".totalBytes must be at least downloadedBytes")
	}
	return nil
}

func validateReady(r *Ready) error {
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
	return nil
}

func validateHeartbeat(h *Heartbeat) error {
	if h.RuntimeStatus == nil {
		return invalid("heartbeat.runtimeStatus is required")
	}
	if err := validateSnapshot(h.RuntimeStatus, "heartbeat.runtimeStatus"); err != nil {
		return err
	}
	if h.IdeLeaseRound != nil && h.GetIdeLeaseRound() <= 0 {
		return invalid("heartbeat.ideLeaseRound must be positive")
	}
	return nil
}

func validateRuntimeStatus(s *RuntimeStatus) error {
	if s.RuntimeStatus == nil {
		return invalid("runtimeStatus.runtimeStatus is required")
	}
	return validateSnapshot(s.RuntimeStatus, "runtimeStatus.runtimeStatus")
}
