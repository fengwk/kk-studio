package protocol

import (
	"net/url"
	"strings"
)

func validateDaemonConfig(c *DaemonConfiguration) error {
	u, err := url.Parse(c.GetStudioUrl())
	if err != nil || (strings.ToLower(u.Scheme) != "http" && strings.ToLower(u.Scheme) != "https") || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return invalid("environmentConfig.config.studioUrl has invalid syntax")
	}
	if c.BashExecutable != nil && (strings.TrimSpace(*c.BashExecutable) == "" || hasControl(*c.BashExecutable)) {
		return invalid("environmentConfig.config.bashExecutable is invalid")
	}
	if c.Lsp != nil && len(c.Lsp.Servers) == 0 {
		return invalid("environmentConfig.config.lsp.servers must declare at least one server")
	}
	seen := map[string]bool{}
	for _, s := range c.GetLsp().GetServers() {
		if s == nil || !serverIDPattern.MatchString(s.Id) || seen[s.Id] {
			return invalid("environmentConfig.config.lsp.servers has invalid or duplicate id")
		}
		seen[s.Id] = true
		if err := validateStringList(s.Command, true, false, false); err != nil {
			return invalid("environmentConfig.config.lsp.server.command must be a nonempty array of strings")
		}
		if err := validateStringList(s.Extensions, true, true, false); err != nil {
			return invalid("environmentConfig.config.lsp.server.extensions must be valid file extensions")
		}
		if err := validateStringList(s.RootMarkers, false, false, true); err != nil {
			return invalid("environmentConfig.config.lsp.server.rootMarkers must be project-relative without traversal")
		}
		if err := validateStringList(s.FirstMatchMarkers, false, false, true); err != nil {
			return invalid("environmentConfig.config.lsp.server.firstMatchMarkers must be project-relative without traversal")
		}
	}
	if c.ProxyUrl != nil && !validProxyURL(*c.ProxyUrl) {
		return invalid("environmentConfig.config.proxyUrl has invalid syntax")
	}
	return nil
}

func validateStringList(values []string, required, extension, marker bool) error {
	if len(values) == 0 {
		if required {
			return invalid("empty list")
		}
		return nil
	}
	for _, value := range values {
		if strings.TrimSpace(value) == "" || hasControl(value) {
			return invalid("blank/control value")
		}
		if extension {
			normalized := strings.ToLower(strings.TrimSpace(value))
			if len(normalized) < 2 || !strings.HasPrefix(normalized, ".") || strings.ContainsAny(normalized, "/\\") {
				return invalid("invalid extension")
			}
		}
		if marker {
			normalized := strings.TrimSpace(strings.ReplaceAll(value, "\\", "/"))
			if strings.HasPrefix(normalized, "/") || strings.Contains(normalized, ":") {
				return invalid("invalid marker")
			}
			for _, part := range strings.Split(normalized, "/") {
				if part == "" || part == "." || part == ".." {
					return invalid("invalid marker")
				}
			}
		}
	}
	return nil
}

func validateEnvironmentConfig(c *EnvironmentConfig) error {
	if c.Version == nil {
		return invalid("environmentConfig.version is required")
	}
	if c.GetVersion() < 0 {
		return invalid("environmentConfig.version must be non-negative")
	}
	if c.Config == nil {
		return invalid("environmentConfig.config is required")
	}
	return validateDaemonConfig(c.Config)
}
