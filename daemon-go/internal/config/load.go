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

// configEnvelope is the config file header: which environment and config version it describes.
// version carries the header value as the number the applied snapshot holds.
type configEnvelope struct {
	EnvironmentID string          `json:"environmentId"`
	Version       string          `json:"version"`
	DaemonConfig  json.RawMessage `json:"daemonConfig"`
	version       int64
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
	document, err := readConfigDocument(path)
	if err != nil {
		return DaemonConfig{}, err
	}
	env, err := decodeConfigEnvelope(document)
	if err != nil {
		return DaemonConfig{}, err
	}
	lspOrder, err := validateLSPDeclaration(env.DaemonConfig)
	if err != nil {
		return DaemonConfig{}, err
	}
	wire, err := decodeWireConfig(env.DaemonConfig)
	if err != nil {
		return DaemonConfig{}, err
	}
	studio, proxy, err := validateConnectionSettings(wire)
	if err != nil {
		return DaemonConfig{}, err
	}
	lsp, err := buildLSPConfig(wire.LSP.Servers, lspOrder)
	if err != nil {
		return DaemonConfig{}, err
	}
	dataDir := filepath.Dir(path)
	tokenPath := filepath.Join(dataDir, "daemon.token")
	if err := validateRegistrationTokenFile(tokenPath); err != nil {
		return DaemonConfig{}, err
	}
	var bash *string
	if resolved, err := ResolveBash(wire.BashExecutable); err == nil {
		bash = &resolved
	}
	return DaemonConfig{
		EnvironmentID: env.EnvironmentID, ConfigVersion: int64(env.version), ConfigPath: path, DataDir: dataDir,
		TokenPath: tokenPath, StudioURL: studio, GatewayURL: gatewayURL(studio), BashExecutable: bash,
		BashExecutableSetting: wire.BashExecutable, LSP: lsp, ProxyURL: proxy, DistributionDir: DistributionDir(executablePath()),
	}, nil
}

// readConfigDocument returns the raw bytes of the config file, bounded before parsing.
func readConfigDocument(path string) ([]byte, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, errors.New("config: cannot read configuration file")
	}
	defer f.Close()
	b, err := io.ReadAll(io.LimitReader(f, maxConfigBytes+1))
	if err != nil || len(b) > maxConfigBytes {
		return nil, errors.New("config: configuration file is invalid")
	}
	if err := rejectDuplicateKeys(b); err != nil {
		return nil, errors.New("config: configuration file is invalid")
	}
	var top map[string]json.RawMessage
	if err := jsonv2.Unmarshal(b, &top); err != nil || top == nil || hasUnknown(top, "environmentId", "version", "daemonConfig") {
		return nil, errors.New("config: configuration file is invalid")
	}
	return b, nil
}

// decodeConfigEnvelope validates the envelope header and its config version.
func decodeConfigEnvelope(b []byte) (configEnvelope, error) {
	var env configEnvelope
	if err := jsonv2.Unmarshal(b, &env); err != nil {
		return env, errors.New("daemon: configuration file is invalid")
	}
	if !canonicalUUID.MatchString(env.EnvironmentID) {
		return env, errors.New("daemon.environmentId: must be a canonical lowercase UUID")
	}
	if !decimalVersion.MatchString(env.Version) {
		return env, errors.New("daemon.version: must be a canonical non-negative decimal string")
	}
	version, err := strconv.ParseInt(env.Version, 10, 64)
	if err != nil {
		return env, errors.New("daemon.version: must fit a signed 64-bit integer")
	}
	env.version = version
	return env, nil
}

// validateLSPDeclaration checks the shape of the raw lsp object and returns the declared server
// order, which the first-match rule depends on.
func validateLSPDeclaration(daemonConfig json.RawMessage) ([]string, error) {
	var raw map[string]json.RawMessage
	if err := jsonv2.Unmarshal(daemonConfig, &raw); err != nil || raw == nil || hasUnknown(raw, "studioUrl", "bashExecutable", "lsp", "proxyUrl") {
		return nil, errors.New("config: daemonConfig is invalid")
	}
	lspRaw, ok := raw["lsp"]
	if !ok || bytes.Equal(bytes.TrimSpace(lspRaw), []byte("null")) {
		return nil, nil
	}
	var lspObject map[string]json.RawMessage
	if jsonv2.Unmarshal(lspRaw, &lspObject) != nil || lspObject == nil || hasUnknown(lspObject, "servers") {
		return nil, errors.New("config: lsp is invalid")
	}
	serversRaw, ok := lspObject["servers"]
	if !ok {
		return nil, errors.New("config: lsp servers are invalid")
	}
	var servers map[string]json.RawMessage
	if jsonv2.Unmarshal(serversRaw, &servers) != nil || servers == nil || len(servers) == 0 {
		return nil, errors.New("config: lsp servers are invalid")
	}
	lspOrder, err := orderedJSONKeys(serversRaw)
	if err != nil {
		return nil, errors.New("config: lsp servers are invalid")
	}
	for _, serverRaw := range servers {
		var fields map[string]json.RawMessage
		if jsonv2.Unmarshal(serverRaw, &fields) != nil || fields == nil || hasUnknown(fields, "command", "extensions", "rootMarkers", "firstMatchMarkers") {
			return nil, errors.New("config: lsp server is invalid")
		}
	}
	return lspOrder, nil
}

// decodeWireConfig decodes the daemon config into its typed shape.
func decodeWireConfig(daemonConfig json.RawMessage) (wireDaemonConfig, error) {
	var wire wireDaemonConfig
	if err := jsonv2.Unmarshal(daemonConfig, &wire); err != nil {
		return wire, errors.New("config: daemonConfig is invalid")
	}
	return wire, nil
}

// validateConnectionSettings normalizes the studio and proxy URLs and rejects a blank bash value.
func validateConnectionSettings(wire wireDaemonConfig) (string, *string, error) {
	studio, err := parseStudioURL(wire.StudioURL)
	if err != nil {
		return "", nil, errors.New("config: studioUrl is invalid")
	}
	proxy, err := parseProxy(wire.ProxyURL)
	if err != nil {
		return "", nil, errors.New("config: proxyUrl is invalid")
	}
	if wire.BashExecutable != nil && (strings.TrimSpace(*wire.BashExecutable) == "" || hasControl(*wire.BashExecutable)) {
		return "", nil, errors.New("config: bashExecutable is invalid")
	}
	return studio, proxy, nil
}

// buildLSPConfig validates every declared server and returns them in declaration order.
func buildLSPConfig(servers map[string]wireLSPConfig, order []string) ([]LSPServerConfig, error) {
	lsp := make([]LSPServerConfig, 0, len(servers))
	for _, id := range order {
		s := servers[id]
		fields := lspServerFields{id: id, command: s.Command, extensions: s.Extensions, rootMarkers: s.RootMarkers, firstMatchMarkers: s.FirstMatchMarkers}
		fields.normalize()
		if id == "" || !lspServerID.MatchString(id) {
			return nil, errors.New("config: lsp server is invalid")
		}
		switch fields.check() {
		case lspServerInvalidLists:
			return nil, errors.New("config: lsp server is invalid")
		case lspServerInvalidExtension:
			return nil, errors.New("config: lsp extension is invalid")
		case lspServerInvalidMarker:
			return nil, errors.New("config: lsp marker is invalid")
		case lspServerOK:
		}
		lsp = append(lsp, LSPServerConfig{ID: id, Command: fields.command, Extensions: fields.extensions, RootMarkers: fields.rootMarkers, FirstMatchMarkers: fields.firstMatchMarkers})
	}
	return lsp, nil
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
	if err != nil || u.Scheme != "http" || u.Hostname() == "" || u.Hostname() == "*" || u.Port() == "" || u.User != nil || u.Path != "" || u.RawQuery != "" || u.Fragment != "" {
		return nil, errors.New("invalid")
	}
	port, err := strconv.Atoi(u.Port())
	if err != nil || port < 1 || port > 65535 {
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

func normalizeExtensions(values []string) []string {
	normalized := make([]string, len(values))
	for i, value := range values {
		normalized[i] = strings.ToLower(strings.TrimSpace(value))
	}
	return normalized
}

func normalizeMarkers(values []string) []string {
	normalized := make([]string, len(values))
	for i, value := range values {
		normalized[i] = strings.ReplaceAll(strings.TrimSpace(value), `\`, "/")
	}
	return normalized
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

func orderedJSONKeys(data []byte) ([]string, error) {
	decoder := json.NewDecoder(bytes.NewReader(data))
	token, err := decoder.Token()
	if err != nil || token != json.Delim('{') {
		return nil, errors.New("expected JSON object")
	}
	keys := make([]string, 0)
	for decoder.More() {
		token, err := decoder.Token()
		if err != nil {
			return nil, err
		}
		key, ok := token.(string)
		if !ok {
			return nil, errors.New("invalid JSON object key")
		}
		var value json.RawMessage
		if err := decoder.Decode(&value); err != nil {
			return nil, err
		}
		keys = append(keys, key)
	}
	token, err = decoder.Token()
	if err != nil || token != json.Delim('}') {
		return nil, errors.New("incomplete JSON object")
	}
	if _, err := decoder.Token(); err != io.EOF {
		return nil, errors.New("trailing JSON data")
	}
	return keys, nil
}

func executablePath() string {
	p, err := os.Executable()
	if err != nil {
		return ""
	}
	return p
}

func validateRegistrationTokenFile(path string) error {
	st, err := os.Lstat(path)
	if err != nil || !st.Mode().IsRegular() {
		return errors.New("daemon.token: cannot read registration token file")
	}
	if runtime.GOOS != "windows" && (st.Mode().Perm()&0077 != 0 || st.Mode().Perm()&0400 == 0) {
		return errors.New("daemon.token: registration token file must be owner-only")
	}
	return nil
}

// ReadRegistrationToken reads and trims the sibling registration token without retaining it in config.
func ReadRegistrationToken(path string) (string, error) {
	c, err := LoadDaemonConfig(path)
	if err != nil {
		return "", err
	}
	if err := validateRegistrationTokenFile(c.TokenPath); err != nil {
		return "", err
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

// maxConfigDepth bounds JSON nesting so an adversarially deep document cannot exhaust the stack.
const maxConfigDepth = 20

// rejectDuplicateKeys walks JSON tokens so duplicate keys cannot be normalized away by Unmarshal.
func rejectDuplicateKeys(b []byte) error {
	d := json.NewDecoder(bytes.NewReader(b))
	d.UseNumber()
	if err := walkConfigValue(d, 0); err != nil {
		return err
	}
	if _, err := d.Token(); err == io.EOF {
		return nil
	}
	return errors.New("trailing data")
}

// walkConfigValue consumes one JSON value and rejects duplicates, deep nesting and long numbers.
func walkConfigValue(d *json.Decoder, depth int) error {
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
	if depth >= maxConfigDepth {
		return errors.New("depth")
	}
	switch del {
	case '{':
		return walkConfigObject(d, depth)
	case '[':
		return walkConfigArray(d, depth)
	default:
		return errors.New("delimiter")
	}
}

// walkConfigObject consumes the members of one object and rejects a repeated key.
func walkConfigObject(d *json.Decoder, depth int) error {
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
		if err := walkConfigValue(d, depth+1); err != nil {
			return err
		}
	}
	_, err := d.Token()
	return err
}

// walkConfigArray consumes the elements of one array.
func walkConfigArray(d *json.Decoder, depth int) error {
	for d.More() {
		if err := walkConfigValue(d, depth+1); err != nil {
			return err
		}
	}
	_, err := d.Token()
	return err
}
