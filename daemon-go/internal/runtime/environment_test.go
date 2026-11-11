package runtime

import (
	"errors"
	"testing"

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

// RUN-T-113 ADAPT rejects unsupported OS and architecture facts instead of returning an unspecified host.
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
