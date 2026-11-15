package runtime

import (
	"errors"
	"fmt"
	"os"
	"os/user"
	"regexp"
	"runtime"
	"strings"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// zoneRegionName matches the IANA region identifiers Java's ZoneId resolves.
var zoneRegionName = regexp.MustCompile(`^[A-Za-z]+(/[A-Za-z0-9_+-]+)+$`)

func localHostInfo(goos, goarch string, wsl bool, timeZone, userName, home, temp string) (*protocol.HostInfo, error) {
	var operatingSystem protocol.OperatingSystem
	switch goos {
	case "windows":
		operatingSystem = protocol.OperatingSystem_WINDOWS
	case "darwin":
		operatingSystem = protocol.OperatingSystem_MACOS
	case "linux":
		if wsl {
			operatingSystem = protocol.OperatingSystem_WSL
		} else {
			operatingSystem = protocol.OperatingSystem_LINUX
		}
	default:
		return nil, errors.New("unsupported operating system")
	}
	var architecture protocol.Architecture
	switch goarch {
	case "amd64":
		architecture = protocol.Architecture_AMD64
	case "arm64":
		architecture = protocol.Architecture_ARM64
	default:
		return nil, errors.New("unsupported architecture")
	}
	info := &protocol.HostInfo{OperatingSystem: operatingSystem, Architecture: architecture, TimeZone: &timeZone, UserName: &userName, HomeDirectory: &home, TempDirectory: &temp}
	return info, nil
}

// detectTimeZone reports the host time zone in a form Java's ZoneId resolves: the IANA name the
// host configures, or the current UTC offset when no name is available. Windows only offers a
// fixed offset, so it always uses the offset form.
func detectTimeZone(goos string, lookupEnv func(string) (string, bool), readlink func(string) (string, error), offsetSeconds func() int) string {
	name := ""
	if goos != "windows" {
		name = systemTimeZoneName(lookupEnv, readlink)
	}
	if !validTimeZoneName(name) {
		return offsetTimeZoneName(offsetSeconds())
	}
	return name
}

// systemTimeZoneName resolves the IANA name the host configures, preferring TZ over the
// /etc/localtime link. An empty result means the host exposed no usable name.
func systemTimeZoneName(lookupEnv func(string) (string, bool), readlink func(string) (string, error)) string {
	if value, ok := lookupEnv("TZ"); ok {
		if name, resolved := timeZoneFromTZ(value); resolved {
			return name
		}
	}
	target, err := readlink("/etc/localtime")
	if err != nil {
		return ""
	}
	return timeZoneNameFromPath(target)
}

// timeZoneFromTZ interprets one TZ value. A value naming the local link itself is unresolved so
// that it is read through /etc/localtime, exactly like an unset TZ. A leading posix/ or right/
// names a zoneinfo layout directory, not a region, so it is dropped like it is for file paths.
func timeZoneFromTZ(value string) (name string, resolved bool) {
	if value == "" {
		return "UTC", true
	}
	value = strings.TrimPrefix(value, ":")
	if strings.HasPrefix(value, "/") {
		if value == "/etc/localtime" {
			return "", false
		}
		return timeZoneNameFromPath(value), true
	}
	return stripZoneVariantPrefix(value), true
}

// timeZoneNameFromPath takes the IANA name a zoneinfo file path ends with, dropping the posix/
// and right/ variant directory.
func timeZoneNameFromPath(path string) string {
	index := strings.LastIndex(path, "zoneinfo/")
	if index < 0 {
		return ""
	}
	return stripZoneVariantPrefix(path[index+len("zoneinfo/"):])
}

// stripZoneVariantPrefix removes one leading posix/ or right/ layout directory.
func stripZoneVariantPrefix(name string) string {
	for _, variant := range []string{"posix/", "right/"} {
		if trimmed, ok := strings.CutPrefix(name, variant); ok {
			return trimmed
		}
	}
	return name
}

// validTimeZoneName accepts the identifiers Java's ZoneId resolves, so a host name is published
// only when the control server can read it. A name that still starts with a zoneinfo layout
// directory is never published.
func validTimeZoneName(name string) bool {
	if name == "UTC" {
		return true
	}
	if !zoneRegionName.MatchString(name) || hasZoneVariantPrefix(name) {
		return false
	}
	_, err := time.LoadLocation(name)
	return err == nil
}

// hasZoneVariantPrefix reports a region name whose first segment names a zoneinfo layout directory.
func hasZoneVariantPrefix(name string) bool {
	segment, _, _ := strings.Cut(name, "/")
	return segment == "posix" || segment == "right"
}

// offsetTimeZoneName renders a zone offset as the UTC±HH:MM identifier Java accepts.
func offsetTimeZoneName(seconds int) string {
	if seconds == 0 {
		return "UTC"
	}
	sign := "+"
	if seconds < 0 {
		sign, seconds = "-", -seconds
	}
	return fmt.Sprintf("UTC%s%02d:%02d", sign, seconds/3600, seconds%3600/60)
}

// currentZoneOffset reports the current host zone offset in seconds east of UTC.
func currentZoneOffset() int {
	_, seconds := time.Now().Zone()
	return seconds
}

func isWSL(getenv func(string) string, readFile func(string) ([]byte, error)) bool {
	if getenv("WSL_DISTRO_NAME") != "" || getenv("WSL_INTEROP") != "" {
		return true
	}
	for _, path := range []string{"/proc/version", "/proc/sys/kernel/osrelease"} {
		data, err := readFile(path)
		if err == nil && strings.Contains(strings.ToLower(string(data)), "microsoft") {
			return true
		}
	}
	return false
}

func detectHostInfo() (*protocol.HostInfo, error) {
	wsl := runtime.GOOS == "linux" && isWSL(os.Getenv, os.ReadFile)
	identity, _ := user.Current()
	userName, home := "", ""
	if identity != nil {
		userName, home = identity.Username, identity.HomeDir
	}
	timeZone := detectTimeZone(runtime.GOOS, os.LookupEnv, os.Readlink, currentZoneOffset)
	return localHostInfo(runtime.GOOS, runtime.GOARCH, wsl, timeZone, userName, home, os.TempDir())
}
