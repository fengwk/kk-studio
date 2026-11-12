package config

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

const maxEnvironmentConfigVersion int64 = 1<<53 - 1

var lspServerID = regexp.MustCompile(`^[A-Za-z0-9_.-]+$`)

// EnvironmentConfigError reports a stable field and rule code without echoing configuration values.
type EnvironmentConfigError struct {
	Code  string
	Field string
}

func (e *EnvironmentConfigError) Error() string { return fmt.Sprintf("%s is invalid", e.Field) }

// BuildEnvironmentConfig validates a complete server snapshot and creates an immutable candidate over base paths.
func BuildEnvironmentConfig(base DaemonConfig, version int64, input *protocol.DaemonConfiguration) (DaemonConfig, error) {
	if version < 0 || version > maxEnvironmentConfigVersion {
		return DaemonConfig{}, &EnvironmentConfigError{Code: "INVALID_VERSION", Field: "version"}
	}
	if input == nil || len(input.ProtoReflect().GetUnknown()) != 0 {
		return DaemonConfig{}, &EnvironmentConfigError{Code: "VALIDATION_FAILED", Field: "config"}
	}
	studio, err := parseStudioURL(input.GetStudioUrl())
	if err != nil {
		return DaemonConfig{}, &EnvironmentConfigError{Code: "VALIDATION_FAILED", Field: "studioUrl"}
	}
	proxy, err := parseProxy(input.ProxyUrl)
	if err != nil {
		return DaemonConfig{}, &EnvironmentConfigError{Code: "VALIDATION_FAILED", Field: "proxyUrl"}
	}
	bashSetting := input.BashExecutable
	if bashSetting != nil && (strings.TrimSpace(*bashSetting) == "" || hasControl(*bashSetting)) {
		return DaemonConfig{}, &EnvironmentConfigError{Code: "VALIDATION_FAILED", Field: "bashExecutable"}
	}
	bash, err := ResolveBash(bashSetting)
	if err != nil {
		return DaemonConfig{}, &EnvironmentConfigError{Code: "VALIDATION_FAILED", Field: "bashExecutable"}
	}
	lsp := make([]LSPServerConfig, 0)
	if input.Lsp != nil {
		if len(input.Lsp.Servers) == 0 || len(input.Lsp.ProtoReflect().GetUnknown()) != 0 {
			return DaemonConfig{}, &EnvironmentConfigError{Code: "VALIDATION_FAILED", Field: "lsp"}
		}
		lsp = make([]LSPServerConfig, 0, len(input.Lsp.Servers))
	}
	if input.Lsp != nil {
		seen := make(map[string]struct{}, len(input.Lsp.Servers))
		for _, server := range input.Lsp.Servers {
			if server == nil || len(server.ProtoReflect().GetUnknown()) != 0 || !lspServerID.MatchString(server.GetId()) {
				return DaemonConfig{}, &EnvironmentConfigError{Code: "VALIDATION_FAILED", Field: "lsp.servers"}
			}
			if _, exists := seen[server.GetId()]; exists {
				return DaemonConfig{}, &EnvironmentConfigError{Code: "VALIDATION_FAILED", Field: "lsp.servers"}
			}
			candidate := LSPServerConfig{ID: server.Id, Command: cloneStrings(server.Command), Extensions: normalizeExtensions(server.Extensions), RootMarkers: normalizeMarkers(server.RootMarkers), FirstMatchMarkers: normalizeMarkers(server.FirstMatchMarkers)}
			if !validStringList(candidate.Command, true) || !validStringList(candidate.Extensions, true) || !validStringList(candidate.RootMarkers, false) || !validStringList(candidate.FirstMatchMarkers, false) {
				return DaemonConfig{}, &EnvironmentConfigError{Code: "VALIDATION_FAILED", Field: "lsp.servers." + server.Id}
			}
			for _, extension := range candidate.Extensions {
				if len(extension) < 2 || !strings.HasPrefix(extension, ".") || hasControl(extension) || strings.ContainsAny(extension, `/\\`) {
					return DaemonConfig{}, &EnvironmentConfigError{Code: "VALIDATION_FAILED", Field: "lsp.servers." + server.Id + ".extensions"}
				}
			}
			for _, marker := range append(append([]string(nil), candidate.RootMarkers...), candidate.FirstMatchMarkers...) {
				if !validRelativePath(marker) {
					return DaemonConfig{}, &EnvironmentConfigError{Code: "VALIDATION_FAILED", Field: "lsp.servers." + server.Id + ".markers"}
				}
			}
			resolved, err := ResolveExecutable(candidate.Command[0])
			if err != nil {
				return DaemonConfig{}, &EnvironmentConfigError{Code: "VALIDATION_FAILED", Field: "lsp.servers." + server.Id + ".command"}
			}
			candidate.Command[0] = resolved
			seen[server.GetId()] = struct{}{}
			lsp = append(lsp, candidate)
		}
	}
	base.ConfigVersion = version
	base.StudioURL = studio
	base.GatewayURL = gatewayURL(studio)
	base.BashExecutable = &bash
	base.BashExecutableSetting = cloneString(bashSetting)
	base.LSP = lsp
	base.ProxyURL = proxy
	return base, nil
}

func gatewayURL(studio string) string {
	gateway := strings.TrimSuffix(studio, "/")
	if strings.HasPrefix(gateway, "https:") {
		gateway = "wss:" + strings.TrimPrefix(gateway, "https:")
	} else {
		gateway = "ws:" + strings.TrimPrefix(gateway, "http:")
	}
	return gateway + "/ws/daemon"
}

func cloneStrings(values []string) []string { return append([]string(nil), values...) }

func cloneString(value *string) *string {
	if value == nil {
		return nil
	}
	copy := *value
	return &copy
}
