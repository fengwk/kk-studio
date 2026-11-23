package config

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"google.golang.org/protobuf/proto"
)

const maxEnvironmentConfigVersion int64 = 1<<53 - 1

var lspServerID = regexp.MustCompile(`^[A-Za-z0-9_.-]+$`)

// EnvironmentConfigError reports a stable field and rule code without echoing configuration values.
type EnvironmentConfigError struct {
	Code  string
	Field string
}

func (e *EnvironmentConfigError) Error() string { return fmt.Sprintf("%s is invalid", e.Field) }

// CanonicalConfiguration returns the snapshot used to compare configuration identity. It clones the
// input and applies only the URL and LSP spelling rules that loading and building already accept,
// leaving command text, declaration order, duplicates, and unknown fields unchanged. A value the
// existing parsers reject stays untouched so validation can still reject it.
func CanonicalConfiguration(input *protocol.DaemonConfiguration) *protocol.DaemonConfiguration {
	if input == nil {
		return nil
	}
	wire := proto.Clone(input).(*protocol.DaemonConfiguration)
	if studio, err := parseStudioURL(wire.GetStudioUrl()); err == nil {
		wire.StudioUrl = studio
	}
	if proxy, err := parseProxy(wire.ProxyUrl); err == nil {
		wire.ProxyUrl = proxy
	}
	if wire.Lsp != nil && len(wire.Lsp.Servers) == 0 && len(wire.Lsp.ProtoReflect().GetUnknown()) == 0 {
		wire.Lsp = nil
	}
	for _, server := range wire.GetLsp().GetServers() {
		if server == nil {
			continue
		}
		fields := lspServerFields{extensions: server.Extensions, rootMarkers: server.RootMarkers, firstMatchMarkers: server.FirstMatchMarkers}
		fields.normalize()
		server.Extensions = fields.extensions
		server.RootMarkers = fields.rootMarkers
		server.FirstMatchMarkers = fields.firstMatchMarkers
	}
	return wire
}

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
	lsp, err := buildSnapshotLSP(input.Lsp)
	if err != nil {
		return DaemonConfig{}, err
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

// buildSnapshotLSP validates the declared language servers of one environment snapshot.
func buildSnapshotLSP(input *protocol.LspConfiguration) ([]LSPServerConfig, error) {
	lsp := make([]LSPServerConfig, 0)
	if input == nil {
		return lsp, nil
	}
	if len(input.Servers) == 0 || len(input.ProtoReflect().GetUnknown()) != 0 {
		return nil, &EnvironmentConfigError{Code: "VALIDATION_FAILED", Field: "lsp"}
	}
	lsp = make([]LSPServerConfig, 0, len(input.Servers))
	seen := make(map[string]struct{}, len(input.Servers))
	for _, server := range input.Servers {
		if server == nil || len(server.ProtoReflect().GetUnknown()) != 0 || !lspServerID.MatchString(server.GetId()) {
			return nil, &EnvironmentConfigError{Code: "VALIDATION_FAILED", Field: "lsp.servers"}
		}
		if _, exists := seen[server.GetId()]; exists {
			return nil, &EnvironmentConfigError{Code: "VALIDATION_FAILED", Field: "lsp.servers"}
		}
		candidate, err := snapshotLSPServer(server)
		if err != nil {
			return nil, err
		}
		seen[server.GetId()] = struct{}{}
		lsp = append(lsp, candidate)
	}
	return lsp, nil
}

// snapshotLSPServer validates the declaration. Executable validation is done
// during preparation, once the Bash login environment is available.
func snapshotLSPServer(server *protocol.LspServer) (LSPServerConfig, error) {
	fields := lspServerFields{id: server.GetId(), command: cloneStrings(server.Command), extensions: server.Extensions, rootMarkers: server.RootMarkers, firstMatchMarkers: server.FirstMatchMarkers}
	fields.normalize()
	field := func(suffix string) string { return "lsp.servers." + server.GetId() + suffix }
	if violation := fields.check(); violation != lspServerOK {
		suffix := ""
		switch violation {
		case lspServerInvalidExtension:
			suffix = ".extensions"
		case lspServerInvalidMarker:
			suffix = ".markers"
		case lspServerInvalidLists, lspServerOK:
		}
		return LSPServerConfig{}, &EnvironmentConfigError{Code: "VALIDATION_FAILED", Field: field(suffix)}
	}
	return LSPServerConfig{ID: fields.id, Command: fields.command, Extensions: fields.extensions, RootMarkers: fields.rootMarkers, FirstMatchMarkers: fields.firstMatchMarkers}, nil
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
