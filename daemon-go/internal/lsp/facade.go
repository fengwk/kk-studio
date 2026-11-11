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
func (f *Facade) Support(path string) Support {
	f.mu.RLock()
	g := f.current
	f.mu.RUnlock()
	if g.service == nil {
		return Support{}
	}
	return g.service.Support(path)
}
func (f *Facade) FileChanged(path string) {
	f.mu.RLock()
	g := f.current
	f.mu.RUnlock()
	if g.service != nil {
		g.service.FileChanged(path)
	}
}
func (f *Facade) ActiveClientCount() int {
	f.mu.RLock()
	g := f.current
	f.mu.RUnlock()
	if g.service == nil {
		return 0
	}
	return g.service.ActiveClientCount()
}

// PreparedGeneration separates candidate construction from the atomic publish step.
type PreparedGeneration struct {
	facade     *Facade
	generation *generation
	once       sync.Once
}

func (f *Facade) Prepare(servers map[string]config.LSPServerConfig) (*PreparedGeneration, error) {
	d, err := NewDiscovery(servers, ResolverOptions{})
	if err != nil {
		return nil, err
	}
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
func (f *Facade) Update(servers map[string]config.LSPServerConfig) error {
	p, err := f.Prepare(servers)
	if err != nil {
		return err
	}
	if err = p.Publish(); err != nil {
		return err
	}
	return nil
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
		return runtime.CapabilityResult{CallID: call.InvocationID, Error: true, Contents: []runtime.ResultContent{{Type: "text", Text: message}}}
	}
	if ctx.Err() != nil {
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return fail("Operation timed out"), nil
		}
		return fail("Operation cancelled"), nil
	}
	args, err := decodeObject(call.Arguments)
	if err != nil {
		return fail(err.Error()), nil
	}
	path, err := stringArg(args, "path")
	if err != nil {
		return fail(err.Error()), nil
	}
	realPath, err := validatePath(path)
	if err != nil {
		return fail(err.Error()), nil
	}
	if kind == "definition" {
		info, statErr := os.Stat(realPath)
		if statErr != nil {
			return fail("path must exist: " + realPath), nil
		}
		if info.IsDir() {
			return fail("path must be a file: " + realPath), nil
		}
	}
	var line, character, limit int
	var query, target string
	switch kind {
	case "definition":
		line, err = integerArg(args, "line", true, 0)
		if err != nil {
			return fail("line is required and must be a positive integer"), nil
		}
		if line < 1 {
			return fail("line is required and must be a positive integer"), nil
		}
		character, err = integerArg(args, "character", false, 0)
		if err != nil || character < 0 {
			return fail("character must be a non-negative integer"), nil
		}
	case "symbols":
		query, err = stringArg(args, "query")
		if err != nil {
			return fail("query is required and must be a string"), nil
		}
		if strings.TrimSpace(query) == "" {
			return fail("query must not be blank"), nil
		}
		limit = 50
		if value, ok := args["limit"]; ok && value != nil {
			limit, err = integerArg(args, "limit", false, 0)
			if err != nil || limit < 0 {
				return fail("limit must be a non-negative integer"), nil
			}
			if limit > 500 {
				return fail("limit must be <= 500"), nil
			}
		}
	case "decompile":
		target, err = stringArg(args, "target")
		if err != nil {
			return fail("target is required and must be a string"), nil
		}
		if strings.TrimSpace(target) == "" {
			return fail("target must not be blank"), nil
		}
	}
	f.mu.RLock()
	g := f.current
	f.mu.RUnlock()
	service, release := g.acquire()
	defer release()
	if service == nil {
		return fail("LSP is not configured for this environment"), nil
	}
	requestCtx, cancel := requestContext(ctx, call.TimeoutMillis)
	defer cancel()
	var output string
	switch kind {
	case "definition":
		output, err = service.GotoDefinition(requestCtx, realPath, line, character, confirm)
	case "symbols":
		output, err = service.WorkspaceSymbols(requestCtx, realPath, query, limit, confirm)
	case "decompile":
		output, err = service.JavaDecompile(requestCtx, realPath, target, confirm)
	}
	if err != nil {
		var timeout *LspTimeoutError
		var lspErr *LspError
		if errors.As(err, &timeout) {
			return capabilityFailure(call.InvocationID, "Operation timed out"), nil
		}
		if errors.Is(err, context.Canceled) {
			return capabilityFailure(call.InvocationID, "Operation cancelled"), nil
		}
		if errors.As(err, &lspErr) {
			return capabilityFailure(call.InvocationID, lspErr.Error()), nil
		}
		return capabilityFailure(call.InvocationID, "The capability failed while running and the underlying error is not repeated to avoid leaking details"), nil
	}
	confirm()
	return runtime.CapabilityResult{CallID: call.InvocationID, Contents: []runtime.ResultContent{{Type: "text", Text: output}}}, nil
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
	if path == "" {
		return "", &LspError{Message: "path is required"}
	}
	if !filepath.IsAbs(path) {
		return "", &LspError{Message: "path must be an absolute path (a relative or non-absolute value was given)"}
	}
	real, err := filepath.Abs(path)
	if err != nil {
		return "", &LspError{Message: "path does not exist: " + path}
	}
	if _, err = os.Stat(real); err != nil {
		return "", &LspError{Message: "path does not exist: " + path}
	}
	if _, err = os.Stat(real); err != nil {
		return "", &LspError{Message: "path must exist: " + real}
	}
	return real, nil
}
