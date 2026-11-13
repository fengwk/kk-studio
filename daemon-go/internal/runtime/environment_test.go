package runtime

import (
	"errors"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// RUN-T-113 collects a complete, supported set of host facts for READY.
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

// RUN-N-010 records Go's literal Local timezone identifier when TZ is unset.
func TestDetectHostInfoUsesLocalIdentifierWhenTZUnset(t *testing.T) {
	const probe = "KK_STUDIO_TEST_TZ_UNSET"
	if os.Getenv(probe) == "1" {
		info, err := detectHostInfo()
		if err != nil {
			t.Fatal(err)
		}
		if got := info.GetTimeZone(); got != time.Local.String() || got != "Local" {
			t.Fatalf("Go host time zone with TZ unset = %q, local location = %q; want Local", got, time.Local.String())
		}
		return
	}
	command := exec.Command(os.Args[0], "-test.run=^TestDetectHostInfoUsesLocalIdentifierWhenTZUnset$")
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

// RUN-T-114 detects WSL from either environment markers or case-insensitive proc metadata.
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

// RUN-T-113 verifies supported OS and architecture facts are mapped without losing WSL identity.
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

// RUN-F-035 rejects unsupported platform facts.
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
