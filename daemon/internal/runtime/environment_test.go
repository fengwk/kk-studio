package runtime

import (
	"errors"
	"os"
	"os/exec"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// Collects a complete, supported set of host facts for READY.
func TestDetectHostInfoCollectsHostFacts(t *testing.T) {
	info, err := detectHostInfo()
	if err != nil {
		t.Fatal(err)
	}
	if info.GetOperatingSystem() == protocol.OperatingSystem_OPERATING_SYSTEM_UNSPECIFIED || info.GetArchitecture() == protocol.Architecture_ARCHITECTURE_UNSPECIFIED {
		t.Fatalf("host platform was not identified: %+v", info)
	}
	if info.TimeZone == nil || info.UserName == nil || info.HomeDirectory == nil || info.TempDirectory == nil || *info.TempDirectory == "" {
		t.Fatalf("host environment facts are incomplete: %+v", info)
	}
}

// A real host without TZ must report an identifier the control server accepts, on every platform.
func TestDetectHostInfoReportsJavaZoneIDWhenTZUnset(t *testing.T) {
	const probe = "KK_STUDIO_TEST_TZ_UNSET"
	if os.Getenv(probe) == "1" {
		info, err := detectHostInfo()
		if err != nil {
			t.Fatal(err)
		}
		got := info.GetTimeZone()
		if got == "Local" || got == "" {
			t.Fatalf("host time zone with TZ unset = %q, want an IANA name or a UTC offset", got)
		}
		if err := protocol.ValidateEnvelope(&protocol.Envelope{MessageType: protocol.MessageType_READY, EnvironmentId: stringPtr("123e4567-e89b-42d3-a456-426614174000"), Payload: &protocol.Envelope_Ready{Ready: &protocol.Ready{DaemonVersion: stringPtr("1.0.0"), HostInfo: info, RuntimeStatus: &protocol.RuntimeSnapshot{Revision: int64Ptr(1), Status: &protocol.RuntimeBody{DaemonVersion: stringPtr("1.0.0"), Configuration: &protocol.ConfigurationStatus{AppliedVersion: int64Ptr(1)}}}}}}); err != nil {
			t.Fatalf("host time zone %q failed READY validation: %v", got, err)
		}
		return
	}
	command := exec.Command(os.Args[0], "-test.run=^TestDetectHostInfoReportsJavaZoneIDWhenTZUnset$")
	for _, value := range os.Environ() {
		if strings.HasPrefix(value, "TZ=") || strings.HasPrefix(value, probe+"=") {
			continue
		}
		command.Env = append(command.Env, value)
	}
	command.Env = append(command.Env, probe+"=1")
	if output, err := command.CombinedOutput(); err != nil {
		t.Fatalf("time-zone probe with TZ unset failed: %v: %s", err, output)
	}
}

// Covers every host time-zone source and its fallback to the current offset.
func TestDetectTimeZoneSources(t *testing.T) {
	for _, tc := range []struct {
		name   string
		goos   string
		env    map[string]string
		link   string
		offset int
		want   string
	}{
		{name: "windows always offsets", goos: "windows", env: map[string]string{"TZ": "Asia/Shanghai"}, link: "/usr/share/zoneinfo/Asia/Shanghai", offset: 28800, want: "UTC+08:00"},
		{name: "windows zero offset", goos: "windows", offset: 0, want: "UTC"},
		{name: "TZ empty is UTC", goos: "linux", env: map[string]string{"TZ": ""}, offset: 28800, want: "UTC"},
		{name: "TZ region name", goos: "linux", env: map[string]string{"TZ": "Asia/Shanghai"}, offset: 0, want: "Asia/Shanghai"},
		{name: "TZ colon region name", goos: "linux", env: map[string]string{"TZ": ":Europe/Berlin"}, offset: 0, want: "Europe/Berlin"},
		{name: "TZ absolute zoneinfo path", goos: "linux", env: map[string]string{"TZ": "/usr/share/zoneinfo/America/New_York"}, offset: 0, want: "America/New_York"},
		{name: "TZ absolute posix path", goos: "linux", env: map[string]string{"TZ": ":/usr/share/zoneinfo/posix/Europe/Berlin"}, offset: 0, want: "Europe/Berlin"},
		{name: "TZ right variant path", goos: "linux", env: map[string]string{"TZ": "/usr/share/zoneinfo/right/Asia/Tokyo"}, offset: 0, want: "Asia/Tokyo"},
		{name: "TZ posix region name", goos: "linux", env: map[string]string{"TZ": "posix/Asia/Shanghai"}, offset: 0, want: "Asia/Shanghai"},
		{name: "TZ colon right region name", goos: "linux", env: map[string]string{"TZ": ":right/Europe/Berlin"}, offset: 0, want: "Europe/Berlin"},
		{name: "TZ double posix prefix is rejected", goos: "linux", env: map[string]string{"TZ": "posix/posix/Europe/Berlin"}, offset: 28800, want: "UTC+08:00"},
		{name: "TZ localtime falls back to the link", goos: "linux", env: map[string]string{"TZ": "/etc/localtime"}, link: "/usr/share/zoneinfo/Asia/Shanghai", offset: 0, want: "Asia/Shanghai"},
		{name: "unset TZ reads the link", goos: "linux", link: "/usr/share/zoneinfo/Asia/Shanghai", offset: 0, want: "Asia/Shanghai"},
		{name: "relative link target", goos: "linux", link: "../usr/share/zoneinfo/Asia/Shanghai", offset: 0, want: "Asia/Shanghai"},
		{name: "macOS link target", goos: "darwin", link: "/var/db/timezone/zoneinfo/America/New_York", offset: 0, want: "America/New_York"},
		{name: "NixOS link target", goos: "linux", link: "/etc/zoneinfo/Europe/Berlin", offset: 0, want: "Europe/Berlin"},
		{name: "unreadable link uses the offset", goos: "linux", offset: -12600, want: "UTC-03:30"},
		{name: "link outside zoneinfo uses the offset", goos: "linux", link: "/usr/share/zoneinfo", offset: 20700, want: "UTC+05:45"},
		{name: "unknown TZ region uses the offset", goos: "linux", env: map[string]string{"TZ": "Mars/Olympus"}, offset: 28800, want: "UTC+08:00"},
		{name: "wrong-case TZ region uses the offset", goos: "darwin", env: map[string]string{"TZ": "Asia/shanghai"}, offset: 28800, want: "UTC+08:00"},
		{name: "TZ abbreviation uses the offset", goos: "linux", env: map[string]string{"TZ": "CST"}, offset: 28800, want: "UTC+08:00"},
		{name: "TZ posixrules uses the offset", goos: "linux", env: map[string]string{"TZ": "posixrules"}, offset: 0, want: "UTC"},
		{name: "TZ localtime name uses the offset", goos: "linux", env: map[string]string{"TZ": "localtime"}, offset: 0, want: "UTC"},
		{name: "TZ posix without a region uses the offset", goos: "linux", env: map[string]string{"TZ": "posix"}, offset: 0, want: "UTC"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			lookupEnv := func(key string) (string, bool) {
				value, ok := tc.env[key]
				return value, ok
			}
			readlink := func(string) (string, error) {
				if tc.link == "" {
					return "", errors.New("no link")
				}
				return tc.link, nil
			}
			got := detectTimeZone(tc.goos, lookupEnv, readlink, func() int { return tc.offset })
			if got != tc.want {
				t.Fatalf("detectTimeZone(%s, %v, %q, %d) = %q, want %q", tc.goos, tc.env, tc.link, tc.offset, got, tc.want)
			}
			if len(got) > 128 || strings.ContainsAny(got, " \t") {
				t.Fatalf("detected time zone %q is not a publishable identifier", got)
			}
		})
	}
}

// Formats every offset shape the control server accepts.
func TestOffsetTimeZoneName(t *testing.T) {
	for _, tc := range []struct {
		seconds int
		want    string
	}{
		{0, "UTC"},
		{28800, "UTC+08:00"},
		{-12600, "UTC-03:30"},
		{20700, "UTC+05:45"},
		{-3600, "UTC-01:00"},
		{3600 + 59, "UTC+01:00"},
	} {
		if got := offsetTimeZoneName(tc.seconds); got != tc.want {
			t.Fatalf("offsetTimeZoneName(%d) = %q, want %q", tc.seconds, got, tc.want)
		}
	}
}

// Detects WSL from either environment markers or case-insensitive proc metadata.
func TestIsWSLRecognizesMarkersAndProcMetadata(t *testing.T) {
	readErr := func(string) ([]byte, error) { return nil, errors.New("not available") }
	if !isWSL(func(key string) string {
		if key == "WSL_DISTRO_NAME" {
			return "Ubuntu"
		}
		return ""
	}, readErr) {
		t.Fatal("WSL_DISTRO_NAME was not recognized")
	}
	if !isWSL(func(key string) string {
		if key == "WSL_INTEROP" {
			return "/run/WSL/1_interop"
		}
		return ""
	}, readErr) {
		t.Fatal("WSL_INTEROP was not recognized")
	}
	if !isWSL(func(string) string { return "" }, func(path string) ([]byte, error) {
		if path == "/proc/sys/kernel/osrelease" {
			return []byte("Linux Microsoft WSL2"), nil
		}
		return nil, errors.New("not available")
	}) {
		t.Fatal("proc metadata was not recognized")
	}
	if isWSL(func(string) string { return "" }, readErr) {
		t.Fatal("ordinary Linux was incorrectly recognized as WSL")
	}
}

// Verifies supported OS and architecture facts are mapped without losing WSL identity.
func TestLocalHostInfoMapsSupportedPlatformFacts(t *testing.T) {
	for _, tc := range []struct {
		goos, goarch string
		wsl          bool
		os           protocol.OperatingSystem
		arch         protocol.Architecture
	}{
		{"windows", "amd64", false, protocol.OperatingSystem_WINDOWS, protocol.Architecture_AMD64},
		{"darwin", "arm64", false, protocol.OperatingSystem_MACOS, protocol.Architecture_ARM64},
		{"linux", "amd64", false, protocol.OperatingSystem_LINUX, protocol.Architecture_AMD64},
		{"linux", "arm64", true, protocol.OperatingSystem_WSL, protocol.Architecture_ARM64},
	} {
		info, err := localHostInfo(tc.goos, tc.goarch, tc.wsl, "UTC", "user", "/home/user", "/tmp")
		if err != nil || info.GetOperatingSystem() != tc.os || info.GetArchitecture() != tc.arch {
			t.Fatalf("localHostInfo(%s,%s,%t) = %+v, %v", tc.goos, tc.goarch, tc.wsl, info, err)
		}
	}
}

// Rejects unsupported platform facts.
func TestLocalHostInfoRejectsUnsupportedPlatformAndArchitecture(t *testing.T) {
	for _, test := range []struct{ name, goos, goarch string }{
		{name: "unknown OS", goos: "plan9", goarch: "amd64"},
		{name: "unknown architecture", goos: "linux", goarch: "386"},
	} {
		t.Run(test.name, func(t *testing.T) {
			if _, err := localHostInfo(test.goos, test.goarch, false, "UTC", "user", "/home/user", "/tmp"); err == nil {
				t.Fatal("expected unsupported host rejection")
			}
		})
	}
}
