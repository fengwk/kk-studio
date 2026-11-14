package lsp

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"

	"github.com/fengwk/kk-studio/daemon/internal/config"
)

// ServerConfig is an immutable language-server declaration.
type ServerConfig struct {
	ID                                                  string
	Command, Extensions, RootMarkers, FirstMatchMarkers []string
}

// Support is the three-state LSP support result for one path.
type Support struct {
	Language             *string
	Available, Supported bool
}

// ResolverOptions permits deterministic executable resolution in tests.
type ResolverOptions struct {
	Windows                      bool
	Path, PathExt, Home          string
	PathSet, PathExtSet, HomeSet bool
}

// ExecutableResolver resolves configured commands without launching them.
type ExecutableResolver struct{ options ResolverOptions }

func NewExecutableResolver(options ResolverOptions) *ExecutableResolver {
	if !options.Windows && runtime.GOOS == "windows" {
		options.Windows = true
	}
	if options.Path == "" && !options.PathSet {
		options.Path = os.Getenv("PATH")
	}
	if options.PathExt == "" && !options.PathExtSet {
		options.PathExt = os.Getenv("PATHEXT")
	}
	if options.Home == "" && !options.HomeSet {
		options.Home, _ = os.UserHomeDir()
	}
	return &ExecutableResolver{options: options}
}
func (r *ExecutableResolver) expand(path string) string {
	suffix, ok := config.HomePathSuffix(path)
	if !ok {
		return path
	}
	if r.options.Home == "" {
		return ""
	}
	return filepath.Join(r.options.Home, suffix)
}
func (r *ExecutableResolver) Resolve(command string) (string, bool) {
	command = r.expand(command)
	if command == "" {
		return "", false
	}
	if strings.ContainsAny(command, `/\\`) {
		if !filepath.IsAbs(command) && !(r.options.Windows && len(command) >= 3 && command[1] == ':' && (command[2] == '\\' || command[2] == '/')) {
			return "", false
		}
		return r.executable(command)
	}
	if strings.TrimSpace(r.options.Path) == "" {
		return "", false
	}
	sep := string(os.PathListSeparator)
	if r.options.Windows {
		sep = ";"
	}
	for _, dir := range strings.Split(r.options.Path, sep) {
		dir = strings.TrimSpace(dir)
		if dir == "" {
			continue
		}
		dir = r.expand(dir)
		candidates := []string{command}
		if r.options.Windows {
			ext := strings.ToLower(filepath.Ext(command))
			pathext := r.options.PathExt
			if pathext == "" {
				pathext = ".EXE;.CMD;.BAT;.COM"
			}
			known := false
			for _, x := range splitExts(pathext) {
				if ext == x {
					known = true
				}
			}
			if !known {
				candidates = candidates[:0]
				for _, x := range splitExts(pathext) {
					candidates = append(candidates, command+x)
				}
			}
		}
		for _, candidate := range candidates {
			if found, ok := r.executable(filepath.Join(dir, candidate)); ok {
				return found, true
			}
		}
	}
	return "", false
}
func splitExts(value string) []string {
	var out []string
	for _, item := range strings.Split(value, ";") {
		item = strings.ToLower(strings.TrimSpace(item))
		if item == "" {
			continue
		}
		if !strings.HasPrefix(item, ".") {
			item = "." + item
		}
		out = append(out, item)
	}
	return out
}
func (r *ExecutableResolver) executable(path string) (string, bool) {
	info, err := os.Stat(path)
	if err != nil || !info.Mode().IsRegular() {
		return "", false
	}
	if !r.options.Windows && info.Mode().Perm()&0111 == 0 {
		return "", false
	}
	abs, err := filepath.Abs(path)
	if err != nil {
		return "", false
	}
	clean, err := filepath.EvalSymlinks(abs)
	if err == nil {
		abs = clean
	}
	return filepath.Clean(abs), true
}

// Discovery owns ordered server declarations and lazy executable resolution.
type Discovery struct {
	servers    []ServerConfig
	resolver   *ExecutableResolver
	mu         sync.Mutex
	executable map[string]resolution
}
type resolution struct {
	path string
	ok   bool
}

func NewDiscovery(configured []config.LSPServerConfig, options ResolverOptions) (*Discovery, error) {
	servers := make([]ServerConfig, len(configured))
	for i, c := range configured {
		servers[i] = ServerConfig{ID: c.ID, Command: c.Command, Extensions: c.Extensions, RootMarkers: c.RootMarkers, FirstMatchMarkers: c.FirstMatchMarkers}
	}
	return NewDiscoveryFromServers(servers, options)
}
func NewDiscoveryFromServers(servers []ServerConfig, options ResolverOptions) (*Discovery, error) {
	seen := map[string]bool{}
	copyServers := make([]ServerConfig, len(servers))
	for i, s := range servers {
		if seen[s.ID] {
			return nil, &LspError{Message: "duplicate lsp server id: " + s.ID}
		}
		seen[s.ID] = true
		copyServers[i] = s
		copyServers[i].Command = append([]string(nil), s.Command...)
		copyServers[i].Extensions = append([]string(nil), s.Extensions...)
		for j := range copyServers[i].Extensions {
			copyServers[i].Extensions[j] = strings.ToLower(copyServers[i].Extensions[j])
		}
		copyServers[i].RootMarkers = append([]string(nil), s.RootMarkers...)
		copyServers[i].FirstMatchMarkers = append([]string(nil), s.FirstMatchMarkers...)
	}
	return &Discovery{servers: copyServers, resolver: NewExecutableResolver(options), executable: map[string]resolution{}}, nil
}
func cloneServer(server ServerConfig) ServerConfig {
	server.Command = append([]string(nil), server.Command...)
	server.Extensions = append([]string(nil), server.Extensions...)
	server.RootMarkers = append([]string(nil), server.RootMarkers...)
	server.FirstMatchMarkers = append([]string(nil), server.FirstMatchMarkers...)
	return server
}

func (d *Discovery) Servers() []ServerConfig {
	servers := make([]ServerConfig, len(d.servers))
	for i, server := range d.servers {
		servers[i] = cloneServer(server)
	}
	return servers
}
func (d *Discovery) Server(path string) (ServerConfig, bool) {
	name := filepath.Base(path)
	dot := strings.LastIndexByte(name, '.')
	if dot <= 0 || dot == len(name)-1 {
		return ServerConfig{}, false
	}
	ext := strings.ToLower(name[dot:])
	for _, s := range d.servers {
		for _, candidate := range s.Extensions {
			if ext == candidate {
				return cloneServer(s), true
			}
		}
	}
	return ServerConfig{}, false
}
func (d *Discovery) Executable(s ServerConfig) (string, bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if len(s.Command) == 0 {
		return "", false
	}
	command := s.Command[0]
	if value, ok := d.executable[command]; ok {
		return value.path, value.ok
	}
	path, ok := d.resolver.Resolve(command)
	d.executable[command] = resolution{path, ok}
	return path, ok
}
func (d *Discovery) RequireExecutable(s ServerConfig) (string, error) {
	path, ok := d.Executable(s)
	if !ok {
		cmd := ""
		if len(s.Command) > 0 {
			cmd = s.Command[0]
		}
		return "", &LspError{Message: fmt.Sprintf("LSP server '%s' is not installed. Command '%s' must be available on PATH or be an absolute executable path.", s.ID, cmd)}
	}
	return path, nil
}
func (d *Discovery) Support(path string) Support {
	s, ok := d.Server(path)
	if !ok {
		return Support{}
	}
	id := s.ID
	_, available := d.Executable(s)
	return Support{Language: &id, Available: available, Supported: true}
}
func (d *Discovery) RequireServer(path string) (ServerConfig, error) {
	s, ok := d.Server(path)
	if !ok {
		return ServerConfig{}, &LspError{Message: fmt.Sprintf("No LSP server configured for %s. Add the server to lsp.servers in the daemon configuration.", path)}
	}
	return s, nil
}
func (d *Discovery) WorkspaceRoot(s ServerConfig, file string) (string, error) {
	abs, err := filepath.Abs(file)
	if err != nil {
		return "", err
	}
	parent := filepath.Dir(abs)
	current := parent
	boundary := ""
	for {
		if _, err := os.Lstat(filepath.Join(current, ".git")); err == nil {
			boundary = filepath.Dir(current)
			break
		}
		next := filepath.Dir(current)
		if next == current {
			break
		}
		current = next
	}
	var shallow string
	first := ""
	for current = parent; ; current = filepath.Dir(current) {
		if boundary != "" && current == boundary {
			break
		}
		if hasAny(current, s.RootMarkers) {
			shallow = current
		}
		if first == "" && hasAny(current, s.FirstMatchMarkers) {
			first = current
		}
		next := filepath.Dir(current)
		if next == current {
			break
		}
	}
	if shallow != "" {
		return shallow, nil
	}
	if first != "" {
		return first, nil
	}
	return parent, nil
}
func hasAny(dir string, markers []string) bool {
	for _, marker := range markers {
		if _, err := os.Stat(filepath.Join(dir, marker)); err == nil {
			return true
		}
	}
	return false
}
