package lsp

import (
	"context"
	"encoding/json"
	"errors"
	"math"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

type generation struct {
	mu        sync.Mutex
	service   *Service
	active    int
	retired   bool
	closeOnce sync.Once
	done      chan struct{}
	closeErr  error
}

func newGeneration(service *Service) *generation {
	return &generation{service: service, done: make(chan struct{})}
}
func (g *generation) acquire() (*Service, func()) {
	g.mu.Lock()
	g.active++
	s := g.service
	g.mu.Unlock()
	return s, func() {
		g.mu.Lock()
		g.active--
		closeNow := g.retired && g.active == 0
		g.mu.Unlock()
		if closeNow {
			g.close()
		}
	}
}
func (g *generation) retire() {
	g.mu.Lock()
	g.retired = true
	closeNow := g.active == 0
	g.mu.Unlock()
	if closeNow {
		g.close()
	}
}
func (g *generation) close() {
	g.closeOnce.Do(func() {
		defer close(g.done)
		if g.service != nil {
			g.closeErr = g.service.Close()
		}
	})
	<-g.done
}

// Facade keeps capability executors stable while configurations hot-swap generations.
type Facade struct {
	mu           sync.RWMutex
	current      *generation
	processes    native.Supervisor
	options      PoolOptions
	capabilities map[string]runtime.CapabilityExecutor
}

func NewFacade(processes native.Supervisor, options PoolOptions) *Facade {
	f := &Facade{current: newGeneration(nil), processes: processes, options: options}
	f.capabilities = map[string]runtime.CapabilityExecutor{"lsp.goto-definition": f.gotoDefinition, "lsp.workspace-symbols": f.workspaceSymbols, "lsp.java-decompile": f.javaDecompile}
	return f
}
func (f *Facade) Capabilities() map[string]runtime.CapabilityExecutor { return f.capabilities }
func (f *Facade) currentService() (*Service, func()) {
	f.mu.RLock()
	service, release := f.current.acquire()
	f.mu.RUnlock()
	return service, release
}
func (f *Facade) Support(path string) Support {
	service, release := f.currentService()
	defer release()
	if service == nil {
		return Support{}
	}
	return service.Support(path)
}
func (f *Facade) FileChanged(path string) {
	service, release := f.currentService()
	defer release()
	if service != nil {
		service.FileChanged(path)
	}
}

// PreparedGeneration separates candidate construction from the atomic publish step.
type PreparedGeneration struct {
	facade     *Facade
	generation *generation
	once       sync.Once
}

func (f *Facade) Prepare(servers []config.LSPServerConfig, environment []string) (*PreparedGeneration, error) {
	d, err := NewDiscovery(servers, ResolverOptionsForEnvironment(environment))
	if err != nil {
		return nil, err
	}
	d.environment = append([]string{}, environment...)
	var service *Service
	if len(servers) > 0 {
		service = NewService(d, f.processes, f.options)
	}
	return &PreparedGeneration{facade: f, generation: newGeneration(service)}, nil
}
func (p *PreparedGeneration) Publish() error {
	if p == nil || p.facade == nil {
		return errors.New("invalid LSP generation")
	}
	var err error
	p.once.Do(func() {
		f := p.facade
		f.mu.Lock()
		old := f.current
		f.current = p.generation
		f.mu.Unlock()
		old.retire()
	})
	return err
}
func (p *PreparedGeneration) Retire() error {
	if p == nil || p.generation == nil {
		return nil
	}
	p.generation.retire()
	<-p.generation.done
	return p.generation.closeErr
}
func (f *Facade) Close() error {
	f.mu.Lock()
	old := f.current
	f.current = newGeneration(nil)
	f.mu.Unlock()
	old.retire()
	<-old.done
	return old.closeErr
}

func (f *Facade) execute(ctx context.Context, call runtime.CapabilityCall, capctx runtime.CapabilityContext, kind string) (runtime.CapabilityResult, error) {
	confirm := onceConfirm(capctx.ConfirmExecutionExit)
	fail := func(message string) runtime.CapabilityResult {
		confirm()
		return capabilityFailure(call.InvocationID, message)
	}
	if ctx.Err() != nil {
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return fail("Operation timed out"), nil
		}
		return fail("Operation cancelled"), nil
	}
	realPath, arguments, message := targetArguments(kind, call.Arguments)
	if message != "" {
		return fail(message), nil
	}
	service, release := f.currentService()
	defer release()
	if service == nil {
		return fail("LSP is not configured for this environment"), nil
	}
	requestCtx, cancel := requestContext(ctx, call.TimeoutMillis)
	defer cancel()
	output, err := dispatch(kind, service, requestCtx, realPath, arguments, confirm)
	if err != nil {
		return capabilityFailure(call.InvocationID, capabilityErrorMessage(err)), nil
	}
	confirm()
	return runtime.CapabilityResult{CallID: call.InvocationID, Contents: []runtime.ResultContent{{Type: "text", Text: output}}}, nil
}

// lspArguments carries the validated kind-specific arguments.
type lspArguments struct {
	query     string
	target    string
	line      int
	character int
	limit     int
}

// targetArguments validates the shared and kind-specific arguments before any service is acquired.
func targetArguments(kind string, raw json.RawMessage) (string, lspArguments, string) {
	args, err := decodeObject(raw)
	if err != nil {
		return "", lspArguments{}, err.Error()
	}
	path, err := stringArg(args, "path")
	if err != nil {
		return "", lspArguments{}, err.Error()
	}
	realPath, err := validatePath(path)
	if err != nil {
		return "", lspArguments{}, err.Error()
	}
	if kind == "definition" {
		info, statErr := os.Stat(realPath)
		if statErr != nil {
			return "", lspArguments{}, "path must exist: " + realPath
		}
		if info.IsDir() {
			return "", lspArguments{}, "path must be a file: " + realPath
		}
	}
	switch kind {
	case "definition":
		line, err := integerArg(args, "line", true, 0)
		if err != nil || line < 1 {
			return "", lspArguments{}, "line is required and must be a positive integer"
		}
		character, err := integerArg(args, "character", false, 1)
		if err != nil || character < 1 {
			return "", lspArguments{}, "character must be a positive integer"
		}
		return realPath, lspArguments{line: line, character: character}, ""
	case "symbols":
		query, err := stringArg(args, "query")
		if err != nil {
			return "", lspArguments{}, "query is required and must be a string"
		}
		if strings.TrimSpace(query) == "" {
			return "", lspArguments{}, "query must not be blank"
		}
		limit := 50
		if value, ok := args["limit"]; ok && value != nil {
			limit, err = integerArg(args, "limit", false, 0)
			if err != nil || limit < 0 {
				return "", lspArguments{}, "limit must be a non-negative integer"
			}
			if limit > 500 {
				return "", lspArguments{}, "limit must be <= 500"
			}
		}
		return realPath, lspArguments{query: query, limit: limit}, ""
	case "decompile":
		target, err := stringArg(args, "target")
		if err != nil {
			return "", lspArguments{}, "target is required and must be a string"
		}
		if strings.TrimSpace(target) == "" {
			return "", lspArguments{}, "target must not be blank"
		}
		return realPath, lspArguments{target: target}, ""
	}
	return realPath, lspArguments{}, ""
}

// dispatch runs the capability through the acquired generation.
func dispatch(kind string, service *Service, ctx context.Context, path string, arguments lspArguments, confirm func()) (string, error) {
	switch kind {
	case "definition":
		return service.GotoDefinition(ctx, path, arguments.line, arguments.character, confirm)
	case "symbols":
		return service.WorkspaceSymbols(ctx, path, arguments.query, arguments.limit, confirm)
	case "decompile":
		return service.JavaDecompile(ctx, path, arguments.target, confirm)
	}
	return "", nil
}
func (f *Facade) gotoDefinition(ctx context.Context, c runtime.CapabilityCall, x runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	return f.execute(ctx, c, x, "definition")
}
func (f *Facade) workspaceSymbols(ctx context.Context, c runtime.CapabilityCall, x runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	return f.execute(ctx, c, x, "symbols")
}
func (f *Facade) javaDecompile(ctx context.Context, c runtime.CapabilityCall, x runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	return f.execute(ctx, c, x, "decompile")
}
func capabilityErrorMessage(err error) string {
	var timeout *LspTimeoutError
	if errors.As(err, &timeout) {
		return "Operation timed out"
	}
	if errors.Is(err, context.Canceled) {
		return "Operation cancelled"
	}
	var lspErr *LspError
	if errors.As(err, &lspErr) {
		return lspErr.Error()
	}
	var bootstrap *LspBootstrapError
	if errors.As(err, &bootstrap) {
		return bootstrap.Error()
	}
	return "The capability failed while running and the underlying error is not repeated to avoid leaking details"
}

func capabilityFailure(callID, message string) runtime.CapabilityResult {
	return runtime.CapabilityResult{CallID: callID, Error: true, Contents: []runtime.ResultContent{{Type: "text", Text: message}}}
}

func onceConfirm(fn func()) func() {
	var once sync.Once
	return func() {
		if fn != nil {
			once.Do(fn)
		}
	}
}
func decodeObject(raw json.RawMessage) (map[string]any, error) {
	var v any
	dec := json.NewDecoder(strings.NewReader(string(raw)))
	dec.UseNumber()
	if err := dec.Decode(&v); err != nil {
		return nil, &LspError{Message: "tool arguments must be an object"}
	}
	obj, ok := v.(map[string]any)
	if !ok || obj == nil {
		return nil, &LspError{Message: "tool arguments must be an object"}
	}
	return obj, nil
}
func stringArg(args map[string]any, key string) (string, error) {
	v, ok := args[key]
	s, isString := v.(string)
	if !ok || v == nil || !isString {
		return "", &LspError{Message: key + " is required and must be a string"}
	}
	return s, nil
}
func integerArg(args map[string]any, key string, required bool, fallback int) (int, error) {
	v, ok := args[key]
	if !ok || v == nil {
		if required {
			return 0, errors.New("required")
		}
		return fallback, nil
	}
	n, ok := v.(json.Number)
	if !ok {
		return 0, errors.New("not integer")
	}
	i, err := n.Int64()
	if err != nil || i > math.MaxInt || i < math.MinInt {
		return 0, errors.New("not integer")
	}
	return int(i), nil
}
func validatePath(path string) (string, error) {
	if strings.TrimSpace(path) == "" {
		return "", &LspError{Message: "path is required"}
	}
	if !filepath.IsAbs(path) {
		return "", &LspError{Message: "path must be an absolute path (a relative or non-absolute value was given)"}
	}
	candidate, err := filepath.Abs(path)
	if err != nil {
		return "", &LspError{Message: "path does not exist: " + filepath.ToSlash(filepath.Clean(path))}
	}
	if _, err = os.Stat(candidate); err != nil {
		return "", &LspError{Message: "path does not exist: " + filepath.ToSlash(candidate)}
	}
	real, err := filepath.EvalSymlinks(candidate)
	if err != nil {
		return "", &LspError{Message: "path must exist: " + candidate}
	}
	return real, nil
}
