package protocol

import (
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// maxZoneOffsetSeconds is the largest offset Java's ZoneId accepts for a UTC-based identifier.
const maxZoneOffsetSeconds = 18 * 60 * 60

var (
	// zoneOffsetPattern matches the UTC±HH:MM identifier Java's ZoneId accepts.
	zoneOffsetPattern = regexp.MustCompile(`^UTC([+-])([0-9]{2}):([0-9]{2})$`)
	// zoneRegionPattern matches region identifiers such as Asia/Shanghai; the location database
	// decides whether one exists.
	zoneRegionPattern = regexp.MustCompile(`^[A-Za-z]+(/[A-Za-z0-9_+-]+)+$`)
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
		if !validZoneID(*h.TimeZone) {
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

// validZoneID accepts the identifiers Java's ZoneId resolves: UTC, a UTC offset within 18:00, or a
// region name the IANA database holds.
func validZoneID(value string) bool {
	if value == "UTC" {
		return true
	}
	if match := zoneOffsetPattern.FindStringSubmatch(value); match != nil {
		hours, err := strconv.Atoi(match[2])
		if err != nil {
			return false
		}
		minutes, err := strconv.Atoi(match[3])
		if err != nil || minutes > 59 || hours*3600+minutes*60 > maxZoneOffsetSeconds {
			return false
		}
		return true
	}
	return ValidRegionZoneID(value)
}

// ValidRegionZoneID reports whether name is an IANA region identifier, such as Asia/Shanghai, that
// both Java's ZoneId and the host location database resolve. A first segment of posix or right names
// a zoneinfo layout directory, which Java rejects even though the location database resolves it.
func ValidRegionZoneID(name string) bool {
	if !zoneRegionPattern.MatchString(name) {
		return false
	}
	if segment, _, _ := strings.Cut(name, "/"); segment == "posix" || segment == "right" {
		return false
	}
	if _, err := time.LoadLocation(name); err != nil {
		return false
	}
	// Directory-backed location lookup follows the filesystem's case rules, whereas Java's
	// ZoneId is always case-sensitive. Zip-backed lookup already compares names exactly.
	for _, root := range []string{os.Getenv("ZONEINFO"), "/usr/share/zoneinfo", "/usr/share/lib/zoneinfo", "/usr/lib/locale/TZ", "/etc/zoneinfo"} {
		if root == "" {
			continue
		}
		if _, err := os.Stat(filepath.Join(root, filepath.FromSlash(name))); err == nil {
			return exactZonePath(root, name)
		}
	}
	return true
}

// exactZonePath checks every component against directory entries, including on case-insensitive hosts.
func exactZonePath(root, name string) bool {
	for _, part := range strings.Split(name, "/") {
		entries, err := os.ReadDir(root)
		if err != nil {
			return false
		}
		found := false
		for _, entry := range entries {
			if entry.Name() == part {
				found = true
				break
			}
		}
		if !found {
			return false
		}
		root = filepath.Join(root, part)
	}
	return true
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
