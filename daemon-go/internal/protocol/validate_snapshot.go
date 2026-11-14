package protocol

import (
	"regexp"
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
