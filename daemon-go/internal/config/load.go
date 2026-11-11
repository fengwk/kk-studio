package config

import (
	"bytes"
	"encoding/json"
	jsonv2 "encoding/json/v2"
	"errors"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"unicode"
)

const maxConfigBytes = 1 << 20

var (
	canonicalUUID  = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
	decimalVersion = regexp.MustCompile(`^(0|[1-9][0-9]*)$`)
)

type configEnvelope struct {
	EnvironmentID string          `json:"environmentId"`
	Version       string          `json:"version"`
	DaemonConfig  json.RawMessage `json:"daemonConfig"`
}

type wireDaemonConfig struct {
	StudioURL      string  `json:"studioUrl"`
	BashExecutable *string `json:"bashExecutable"`
	LSP            wireLSP `json:"lsp"`
	ProxyURL       *string `json:"proxyUrl"`
}

type wireLSP struct {
	Servers map[string]wireLSPConfig `json:"servers"`
}

type wireLSPConfig struct {
	Command           []string `json:"command"`
	Extensions        []string `json:"extensions"`
	RootMarkers       []string `json:"rootMarkers"`
	FirstMatchMarkers []string `json:"firstMatchMarkers"`
}

// LoadDaemonConfig reads and validates a config file without creating any local resources.
func LoadDaemonConfig(path string) (DaemonConfig, error) {
	if !filepath.IsAbs(path) {
		return DaemonConfig{}, errors.New("config: path must be absolute")
	}
	f, err := os.Open(path)
	if err != nil {
		return DaemonConfig{}, errors.New("config: cannot read configuration file")
	}
	defer f.Close()
	b, err := io.ReadAll(io.LimitReader(f, maxConfigBytes+1))
	if err != nil || len(b) > maxConfigBytes {
		return DaemonConfig{}, errors.New("config: configuration file is invalid")
	}
	if err := rejectDuplicateKeys(b); err != nil {
		return DaemonConfig{}, errors.New("config: configuration file is invalid")
	}
	var top map[string]json.RawMessage
	if err := jsonv2.Unmarshal(b, &top); err != nil || top == nil || hasUnknown(top, "environmentId", "version", "daemonConfig") {
		return DaemonConfig{}, errors.New("config: configuration file is invalid")
	}
	var env configEnvelope
	if err := jsonv2.Unmarshal(b, &env); err != nil || !canonicalUUID.MatchString(env.EnvironmentID) || !decimalVersion.MatchString(env.Version) {
		return DaemonConfig{}, errors.New("config: configuration file is invalid")
	}
	version, err := strconv.ParseInt(env.Version, 10, 64)
	if err != nil {
		return DaemonConfig{}, errors.New("config: configuration file is invalid")
	}
	var raw map[string]json.RawMessage
	if err := jsonv2.Unmarshal(env.DaemonConfig, &raw); err != nil || raw == nil || hasUnknown(raw, "studioUrl", "bashExecutable", "lsp", "proxyUrl") {
		return DaemonConfig{}, errors.New("config: daemonConfig is invalid")
	}
	if lspRaw, ok := raw["lsp"]; ok {
		var lspObject map[string]json.RawMessage
		if jsonv2.Unmarshal(lspRaw, &lspObject) != nil || lspObject == nil || hasUnknown(lspObject, "servers") {
			return DaemonConfig{}, errors.New("config: lsp is invalid")
		}
		var servers map[string]json.RawMessage
		if jsonv2.Unmarshal(lspObject["servers"], &servers) != nil || servers == nil {
			return DaemonConfig{}, errors.New("config: lsp servers are invalid")
		}
		for _, serverRaw := range servers {
			var fields map[string]json.RawMessage
			if jsonv2.Unmarshal(serverRaw, &fields) != nil || fields == nil || hasUnknown(fields, "command", "extensions", "rootMarkers", "firstMatchMarkers") {
				return DaemonConfig{}, errors.New("config: lsp server is invalid")
			}
		}
	}
	var wire wireDaemonConfig
	if err := jsonv2.Unmarshal(env.DaemonConfig, &wire); err != nil {
		return DaemonConfig{}, errors.New("config: daemonConfig is invalid")
	}
	studio, err := parseStudioURL(wire.StudioURL)
	if err != nil {
		return DaemonConfig{}, errors.New("config: studioUrl is invalid")
	}
	proxy, err := parseProxy(wire.ProxyURL)
	if err != nil {
		return DaemonConfig{}, errors.New("config: proxyUrl is invalid")
	}
	if wire.BashExecutable != nil && (strings.TrimSpace(*wire.BashExecutable) == "" || hasControl(*wire.BashExecutable)) {
		return DaemonConfig{}, errors.New("config: bashExecutable is invalid")
	}
	if len(wire.LSP.Servers) == 0 {
		return DaemonConfig{}, errors.New("config: lsp must contain at least one server")
	}
	lsp := make(map[string]LSPServerConfig, len(wire.LSP.Servers))
	for id, s := range wire.LSP.Servers {
		if id == "" || !regexp.MustCompile(`^[A-Za-z0-9_.-]+$`).MatchString(id) || !validStringList(s.Command, true) || !validStringList(s.Extensions, true) || !validStringList(s.RootMarkers, false) || !validStringList(s.FirstMatchMarkers, false) {
			return DaemonConfig{}, errors.New("config: lsp server is invalid")
		}
		for _, ext := range s.Extensions {
			if len(ext) < 2 || !strings.HasPrefix(ext, ".") || hasControl(ext) || strings.ContainsAny(ext, `/\\`) {
				return DaemonConfig{}, errors.New("config: lsp extension is invalid")
			}
		}
		for _, marker := range append(append([]string{}, s.RootMarkers...), s.FirstMatchMarkers...) {
			if !validRelativePath(marker) {
				return DaemonConfig{}, errors.New("config: lsp marker is invalid")
			}
		}
		lsp[id] = LSPServerConfig(s)
	}
	dataDir := filepath.Dir(path)
	bash := wire.BashExecutable
	if bash == nil {
		defaultBash := "bash"
		bash = &defaultBash
	}
	gateway := gatewayURL(studio)
	return DaemonConfig{EnvironmentID: env.EnvironmentID, ConfigVersion: int64(version), ConfigPath: path, DataDir: dataDir, TokenPath: filepath.Join(dataDir, "daemon.token"), StudioURL: studio, GatewayURL: gateway, BashExecutable: bash, BashExecutableSetting: wire.BashExecutable, LSP: lsp, ProxyURL: proxy, DistributionDir: DistributionDir(executablePath())}, nil
}

// CheckConfig validates config and its sibling token without creating directories or locks.
func CheckConfig(path string) error {
	_, err := ReadRegistrationToken(path)
	return err
}

func parseStudioURL(s string) (string, error) {
	u, err := url.Parse(s)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return "", errors.New("invalid")
	}
	return u.Scheme + "://" + u.Host, nil
}
func parseProxy(p *string) (*string, error) {
	if p == nil {
		return nil, nil
	}
	u, err := url.Parse(*p)
	if err != nil || u.Scheme != "http" || u.Hostname() == "" || u.Port() == "" || u.User != nil || u.Path != "" || u.RawQuery != "" || u.Fragment != "" {
		return nil, errors.New("invalid")
	}
	v := "http://" + u.Host
	return &v, nil
}
func validStringList(values []string, required bool) bool {
	if required && len(values) == 0 {
		return false
	}
	for _, value := range values {
		if strings.TrimSpace(value) == "" || hasControl(value) {
			return false
		}
	}
	return true
}

func validRelativePath(s string) bool {
	if s == "" || filepath.IsAbs(s) || strings.ContainsAny(s, `\\:`) || hasControl(s) {
		return false
	}
	for _, part := range strings.Split(s, "/") {
		if part == "" || part == "." || part == ".." {
			return false
		}
	}
	return true
}
func hasControl(s string) bool {
	for _, r := range s {
		if unicode.IsControl(r) || r == '\u2028' || r == '\u2029' {
			return true
		}
	}
	return false
}
func hasUnknown(m map[string]json.RawMessage, allowed ...string) bool {
	set := make(map[string]bool, len(allowed))
	for _, k := range allowed {
		set[k] = true
	}
	for k := range m {
		if !set[k] {
			return true
		}
	}
	return false
}
func executablePath() string {
	p, err := os.Executable()
	if err != nil {
		return ""
	}
	return p
}

// ReadRegistrationToken reads and trims the sibling registration token without retaining it in config.
func ReadRegistrationToken(path string) (string, error) {
	c, err := LoadDaemonConfig(path)
	if err != nil {
		return "", err
	}
	st, err := os.Lstat(c.TokenPath)
	if err != nil || !st.Mode().IsRegular() {
		return "", errors.New("daemon.token: cannot read registration token file")
	}
	if runtime.GOOS != "windows" && (st.Mode().Perm()&0077 != 0 || st.Mode().Perm()&0400 == 0) {
		return "", errors.New("daemon.token: registration token file must be owner-only")
	}
	b, err := os.ReadFile(c.TokenPath)
	if err != nil {
		return "", errors.New("daemon.token: cannot read registration token file")
	}
	token := strings.TrimSpace(string(b))
	if token == "" {
		return "", errors.New("daemon.token: registration token file must not be empty")
	}
	return token, nil
}

// rejectDuplicateKeys walks JSON tokens so duplicate keys cannot be normalized away by Unmarshal.
func rejectDuplicateKeys(b []byte) error {
	d := json.NewDecoder(bytes.NewReader(b))
	d.UseNumber()
	var walk func(int) error
	walk = func(depth int) error {
		if depth > 20 {
			return errors.New("depth")
		}
		t, err := d.Token()
		if err != nil {
			return err
		}
		del, ok := t.(json.Delim)
		if !ok {
			if n, ok := t.(json.Number); ok && len(n.String()) > 64 {
				return errors.New("number")
			}
			return nil
		}
		switch del {
		case '{':
			seen := map[string]bool{}
			for d.More() {
				k, err := d.Token()
				if err != nil {
					return err
				}
				key, ok := k.(string)
				if !ok || seen[key] {
					return errors.New("duplicate")
				}
				seen[key] = true
				if err := walk(depth + 1); err != nil {
					return err
				}
			}
			_, err = d.Token()
			return err
		case '[':
			for d.More() {
				if err := walk(depth + 1); err != nil {
					return err
				}
			}
			_, err = d.Token()
			return err
		default:
			return errors.New("delimiter")
		}
	}
	if err := walk(0); err != nil {
		return err
	}
	_, err := d.Token()
	if err == io.EOF {
		return nil
	}
	return errors.New("trailing data")
}
