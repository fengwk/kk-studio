package runtime

import (
	"errors"
	"os"
	"os/user"
	"runtime"
	"strings"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

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
	return localHostInfo(runtime.GOOS, runtime.GOARCH, wsl, time.Local.String(), userName, home, os.TempDir())
}
