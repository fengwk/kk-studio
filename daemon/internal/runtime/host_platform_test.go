package runtime

import (
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// Renders every accepted host fact pair as the platform's `<os>-<arch>` wire value and refuses
// host facts the platform cannot resolve a release target for.
func TestHostPlatformRendersPlatformWireValue(t *testing.T) {
	for _, tc := range []struct {
		operatingSystem protocol.OperatingSystem
		architecture    protocol.Architecture
		want            string
	}{
		{protocol.OperatingSystem_WINDOWS, protocol.Architecture_AMD64, "windows-amd64"},
		{protocol.OperatingSystem_WSL, protocol.Architecture_ARM64, "wsl-arm64"},
		{protocol.OperatingSystem_LINUX, protocol.Architecture_AMD64, "linux-amd64"},
		{protocol.OperatingSystem_LINUX, protocol.Architecture_ARM64, "linux-arm64"},
		{protocol.OperatingSystem_MACOS, protocol.Architecture_ARM64, "macos-arm64"},
	} {
		got, err := HostPlatform(&protocol.HostInfo{OperatingSystem: tc.operatingSystem, Architecture: tc.architecture})
		if err != nil || got != tc.want {
			t.Fatalf("HostPlatform(%v, %v) = %q, %v; want %q", tc.operatingSystem, tc.architecture, got, err, tc.want)
		}
	}
	for _, info := range []*protocol.HostInfo{
		{Architecture: protocol.Architecture_AMD64},
		{OperatingSystem: protocol.OperatingSystem_LINUX},
	} {
		if got, err := HostPlatform(info); err == nil {
			t.Fatalf("HostPlatform(%v) = %q, want an unsupported host fact error", info, got)
		}
	}
}
