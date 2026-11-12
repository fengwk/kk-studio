package runtime

import (
	"bytes"
	"context"
	"errors"
	"sync"

	"google.golang.org/protobuf/proto"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

type ConfigApplyKind string

const (
	ConfigApplied   ConfigApplyKind = "applied"
	ConfigConfirmed ConfigApplyKind = "confirmed"
	ConfigStale     ConfigApplyKind = "stale"
	ConfigFailed    ConfigApplyKind = "failed"
	ConfigFatal     ConfigApplyKind = "fatal"
)

type ConfigApplyResult struct {
	Kind    ConfigApplyKind
	Config  config.DaemonConfig
	Version int64
	Code    string
	Field   string
	Message string
}

// ConfigCacheWriter must atomically replace a private bootstrap cache at path.
type ConfigCacheWriter interface {
	WritePrivateAtomic(context.Context, string, []byte) error
}

type PreparedConfiguration interface {
	Config() config.DaemonConfig
	// Publish synchronously swaps resource generations and returns asynchronous cleanup for the previous generation.
	Publish() (func() error, error)
	Retire() error
}

type ConfigurationPreparer func(context.Context, config.DaemonConfig, *config.DaemonConfig) (PreparedConfiguration, error)

// EnvironmentConfigOptions injects side effects while this runtime owns version arbitration and snapshot publication.
type EnvironmentConfigOptions struct {
	Initial     config.DaemonConfig
	Cache       ConfigCacheWriter
	Prepare     ConfigurationPreparer
	Status      *StatusTracker
	OnCandidate func(config.DaemonConfig, config.DaemonConfig)
	OnAborted   func(config.DaemonConfig, config.DaemonConfig)
	OnApplied   func(config.DaemonConfig, config.DaemonConfig)
	Report      func(error)
}

type configRequest struct {
	version int64
	wire    *protocol.DaemonConfiguration
	content []byte
	waiters []chan ConfigApplyResult
}

// EnvironmentConfigRuntime serializes server snapshots and publishes only candidates whose private cache is durable.
type EnvironmentConfigRuntime struct {
	options       EnvironmentConfigOptions
	mu            sync.Mutex
	current       config.DaemonConfig
	acceptedBytes []byte
	initialized   bool
	closed        bool
	processing    bool
	active        *configRequest
	pending       *configRequest
	workerDone    chan struct{}
	cleanupWG     sync.WaitGroup
}

func NewEnvironmentConfigRuntime(options EnvironmentConfigOptions) (*EnvironmentConfigRuntime, error) {
	if options.Initial.EnvironmentID == "" || options.Initial.ConfigPath == "" || options.Prepare == nil {
		return nil, errors.New("environment config runtime requires initial paths and a preparer")
	}
	if options.Cache == nil {
		options.Cache = FilePrivateCacheWriter{}
	}
	if options.Status == nil {
		options.Status = NewStatusTracker()
	}
	if options.Report == nil {
		options.Report = func(error) {}
	}
	initial := configurationOf(options.Initial)
	content, err := configContent(initial)
	if err != nil {
		return nil, err
	}
	return &EnvironmentConfigRuntime{options: options, current: options.Initial, acceptedBytes: content}, nil
}

func (r *EnvironmentConfigRuntime) Current() config.DaemonConfig {
	r.mu.Lock()
	defer r.mu.Unlock()
	return cloneDaemonConfig(r.current)
}

func (r *EnvironmentConfigRuntime) HasAppliedConfiguration() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.initialized
}

func (r *EnvironmentConfigRuntime) StatusTracker() *StatusTracker { return r.options.Status }

// AddSessionHooks binds connection lifecycle behavior before the runtime starts accepting snapshots.
func (r *EnvironmentConfigRuntime) AddSessionHooks(onCandidate, onAborted, onApplied func(config.DaemonConfig, config.DaemonConfig)) {
	if onCandidate != nil {
		previous := r.options.OnCandidate
		r.options.OnCandidate = func(old, candidate config.DaemonConfig) {
			if previous != nil {
				previous(old, candidate)
			}
			onCandidate(old, candidate)
		}
	}
	if onAborted != nil {
		previous := r.options.OnAborted
		r.options.OnAborted = func(old, candidate config.DaemonConfig) {
			if previous != nil {
				previous(old, candidate)
			}
			onAborted(old, candidate)
		}
	}
	if onApplied != nil {
		previous := r.options.OnApplied
		r.options.OnApplied = func(old, next config.DaemonConfig) {
			if previous != nil {
				previous(old, next)
			}
			onApplied(old, next)
		}
	}
}

func (r *EnvironmentConfigRuntime) Submit(ctx context.Context, version int64, wire *protocol.DaemonConfiguration) (ConfigApplyResult, error) {
	if wire == nil {
		return ConfigApplyResult{Kind: ConfigFailed, Version: version, Code: "VALIDATION_FAILED", Field: "config"}, nil
	}
	wire = proto.Clone(wire).(*protocol.DaemonConfiguration)
	content, err := configContent(wire)
	if err != nil {
		return ConfigApplyResult{}, err
	}
	waiter := make(chan ConfigApplyResult, 1)
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return ConfigApplyResult{Kind: ConfigFatal, Message: "configuration runtime is closed"}, nil
	}
	if version == r.current.ConfigVersion && !bytes.Equal(content, r.acceptedBytes) {
		r.mu.Unlock()
		return ConfigApplyResult{}, errors.New("the same environment config version must not change its content")
	}
	if version < r.current.ConfigVersion {
		if !r.initialized {
			r.mu.Unlock()
			return ConfigApplyResult{Kind: ConfigFatal, Message: "Studio configuration version is older than the daemon cache"}, nil
		}
		result := ConfigApplyResult{Kind: ConfigStale, Config: cloneDaemonConfig(r.current)}
		r.mu.Unlock()
		return result, nil
	}
	if version == r.current.ConfigVersion && r.initialized {
		result := ConfigApplyResult{Kind: ConfigConfirmed, Config: cloneDaemonConfig(r.current)}
		r.mu.Unlock()
		return result, nil
	}
	if request := sameRequest(r.active, r.pending, version); request != nil {
		if !bytes.Equal(request.content, content) {
			r.mu.Unlock()
			return ConfigApplyResult{}, errors.New("the same environment config version must not change its content")
		}
		request.waiters = append(request.waiters, waiter)
	} else if r.pending != nil && version < r.pending.version {
		if r.initialized {
			result := ConfigApplyResult{Kind: ConfigStale, Config: cloneDaemonConfig(r.current)}
			r.mu.Unlock()
			return result, nil
		}
		r.pending.waiters = append(r.pending.waiters, waiter)
	} else if r.active != nil && version < r.active.version {
		if r.initialized {
			result := ConfigApplyResult{Kind: ConfigStale, Config: cloneDaemonConfig(r.current)}
			r.mu.Unlock()
			return result, nil
		}
		r.active.waiters = append(r.active.waiters, waiter)
	} else {
		request := &configRequest{version: version, wire: wire, content: content, waiters: []chan ConfigApplyResult{waiter}}
		if r.pending != nil {
			if r.initialized {
				r.settleLocked(r.pending, ConfigApplyResult{Kind: ConfigStale, Config: cloneDaemonConfig(r.current)})
			} else {
				request.waiters = append(request.waiters, r.pending.waiters...)
			}
		}
		r.pending = request
		if !r.processing {
			r.startWorkerLocked()
		}
	}
	r.mu.Unlock()
	select {
	case result := <-waiter:
		return result, nil
	case <-ctx.Done():
		return ConfigApplyResult{}, ctx.Err()
	}
}

func sameRequest(active, pending *configRequest, version int64) *configRequest {
	if active != nil && active.version == version {
		return active
	}
	if pending != nil && pending.version == version {
		return pending
	}
	return nil
}

func (r *EnvironmentConfigRuntime) startWorkerLocked() {
	r.processing = true
	r.workerDone = make(chan struct{})
	go r.drain(r.workerDone)
}

func (r *EnvironmentConfigRuntime) drain(done chan struct{}) {
	defer close(done)
	for {
		r.mu.Lock()
		request := r.pending
		r.pending = nil
		if request == nil || r.closed {
			r.processing = false
			r.active = nil
			r.mu.Unlock()
			return
		}
		r.active = request
		r.mu.Unlock()
		result := r.apply(request)
		r.mu.Lock()
		r.active = nil
		r.settleLocked(request, result)
		r.mu.Unlock()
	}
}

func (r *EnvironmentConfigRuntime) settleLocked(request *configRequest, result ConfigApplyResult) {
	for _, waiter := range request.waiters {
		waiter <- result
	}
	request.waiters = nil
}

func (r *EnvironmentConfigRuntime) apply(request *configRequest) ConfigApplyResult {
	r.mu.Lock()
	current := cloneDaemonConfig(r.current)
	initialized := r.initialized
	acceptedVersion := r.current.ConfigVersion
	acceptedBytes := append([]byte(nil), r.acceptedBytes...)
	closed := r.closed
	r.mu.Unlock()
	if closed {
		return ConfigApplyResult{Kind: ConfigFatal, Message: "configuration runtime is closed"}
	}
	if request.version == acceptedVersion {
		if !bytes.Equal(request.content, acceptedBytes) {
			return ConfigApplyResult{Kind: ConfigFatal, Message: "the same environment config version must not change its content"}
		}
		if initialized {
			return ConfigApplyResult{Kind: ConfigConfirmed, Config: current}
		}
	}
	if request.version < acceptedVersion {
		if initialized {
			return ConfigApplyResult{Kind: ConfigStale, Config: current}
		}
		return ConfigApplyResult{Kind: ConfigFatal, Message: "Studio configuration version is older than the daemon cache"}
	}
	candidate, err := config.BuildEnvironmentConfig(current, request.version, request.wire)
	if err != nil {
		return r.failed(request.version, err)
	}
	if initialized && bytes.Equal(request.content, acceptedBytes) {
		candidate = current
		candidate.ConfigVersion = request.version
		return r.persistAndPublish(request, current, candidate, nil, false)
	}
	previous := current
	var runtimePrevious *config.DaemonConfig
	if initialized {
		runtimePrevious = &previous
	}
	if r.options.OnCandidate != nil {
		r.options.OnCandidate(previous, candidate)
	}
	prepared, err := r.options.Prepare(context.Background(), candidate, runtimePrevious)
	if err == nil && prepared == nil {
		err = errors.New("configuration preparer returned no candidate")
	}
	if err != nil {
		r.abort(previous, candidate, nil)
		return r.failed(request.version, err)
	}
	if prepared.Config().ConfigVersion != request.version {
		r.abort(previous, candidate, prepared)
		return r.failed(request.version, &config.EnvironmentConfigError{Code: "VALIDATION_FAILED", Field: "config"})
	}
	candidate = cloneDaemonConfig(prepared.Config())
	return r.persistAndPublish(request, previous, candidate, prepared, true)
}

func (r *EnvironmentConfigRuntime) persistAndPublish(request *configRequest, previous, candidate config.DaemonConfig, prepared PreparedConfiguration, publishResources bool) ConfigApplyResult {
	r.mu.Lock()
	closed := r.closed
	r.mu.Unlock()
	if closed {
		r.abort(previous, candidate, prepared)
		return ConfigApplyResult{Kind: ConfigFatal, Message: "configuration runtime is closed"}
	}
	data, err := config.SerializeDaemonCache(candidate)
	if err == nil {
		err = r.options.Cache.WritePrivateAtomic(context.Background(), candidate.ConfigPath, data)
	}
	if err != nil {
		r.mu.Lock()
		closed := r.closed
		r.mu.Unlock()
		if closed {
			r.abort(previous, candidate, prepared)
			return ConfigApplyResult{Kind: ConfigFatal, Message: "configuration runtime is closed"}
		}
		r.recordFailure(request.version, "PERSIST_FAILED", "")
		r.report(err)
		r.abort(previous, candidate, prepared)
		return ConfigApplyResult{Kind: ConfigFailed, Version: request.version, Code: "PERSIST_FAILED"}
	}
	r.mu.Lock()
	closed = r.closed
	r.mu.Unlock()
	if closed {
		r.abort(previous, candidate, prepared)
		return ConfigApplyResult{Kind: ConfigFatal, Message: "configuration runtime is closed"}
	}
	if publishResources {
		cleanup, publishErr := prepared.Publish()
		if publishErr != nil {
			r.report(publishErr)
			r.abort(previous, candidate, prepared)
			return ConfigApplyResult{Kind: ConfigFatal, Message: "prepared environment resources could not be published"}
		}
		r.trackCleanup(cleanup)
	}
	r.mu.Lock()
	r.current = cloneDaemonConfig(candidate)
	r.acceptedBytes = append([]byte(nil), request.content...)
	r.initialized = true
	r.mu.Unlock()
	if err := r.options.Status.ApplyConfiguration(request.version, candidate.ProxyURL); err != nil {
		r.report(err)
		return ConfigApplyResult{Kind: ConfigFatal, Message: "runtime status could not be updated"}
	}
	if r.options.OnApplied != nil {
		r.options.OnApplied(previous, candidate)
	}
	return ConfigApplyResult{Kind: ConfigApplied, Config: cloneDaemonConfig(candidate)}
}

func (r *EnvironmentConfigRuntime) abort(previous, candidate config.DaemonConfig, prepared PreparedConfiguration) {
	if r.options.OnAborted != nil {
		r.options.OnAborted(previous, candidate)
	}
	if prepared != nil {
		if err := prepared.Retire(); err != nil {
			r.report(err)
		}
	}
}

func (r *EnvironmentConfigRuntime) failed(version int64, err error) ConfigApplyResult {
	code, field := "VALIDATION_FAILED", ""
	var validation *config.EnvironmentConfigError
	if errors.As(err, &validation) {
		field = validation.Field
	}
	r.recordFailure(version, code, field)
	r.report(err)
	return ConfigApplyResult{Kind: ConfigFailed, Version: version, Code: code, Field: field}
}

func (r *EnvironmentConfigRuntime) recordFailure(version int64, code, field string) {
	_, err := r.options.Status.FailConfiguration(ConfigurationError{Version: version, Code: code, Field: field})
	if err != nil {
		r.report(err)
	}
}

func (r *EnvironmentConfigRuntime) report(err error) {
	if err != nil {
		r.options.Report(err)
	}
}

func (r *EnvironmentConfigRuntime) trackCleanup(cleanup func() error) {
	if cleanup == nil {
		return
	}
	r.cleanupWG.Add(1)
	go func() {
		defer r.cleanupWG.Done()
		if err := cleanup(); err != nil {
			r.report(err)
		}
	}()
}

func (r *EnvironmentConfigRuntime) Close() {
	r.mu.Lock()
	r.closed = true
	if r.pending != nil {
		r.settleLocked(r.pending, ConfigApplyResult{Kind: ConfigFatal, Message: "configuration runtime is closed"})
		r.pending = nil
	}
	done := r.workerDone
	r.mu.Unlock()
	if done != nil {
		<-done
	}
	r.cleanupWG.Wait()
}

func configContent(configuration *protocol.DaemonConfiguration) ([]byte, error) {
	return proto.MarshalOptions{Deterministic: true}.Marshal(configuration)
}

func configurationOf(value config.DaemonConfig) *protocol.DaemonConfiguration {
	wire := &protocol.DaemonConfiguration{
		StudioUrl:      value.StudioURL,
		BashExecutable: cloneConfigString(value.BashExecutableSetting),
		ProxyUrl:       cloneConfigString(value.ProxyURL),
		Lsp:            &protocol.LspConfiguration{},
	}
	for _, server := range value.LSP {
		wire.Lsp.Servers = append(wire.Lsp.Servers, &protocol.LspServer{
			Id:                server.ID,
			Command:           append([]string(nil), server.Command...),
			Extensions:        append([]string(nil), server.Extensions...),
			RootMarkers:       append([]string(nil), server.RootMarkers...),
			FirstMatchMarkers: append([]string(nil), server.FirstMatchMarkers...),
		})
	}
	return wire
}

func cloneDaemonConfig(value config.DaemonConfig) config.DaemonConfig {
	value.BashExecutable = cloneConfigString(value.BashExecutable)
	value.BashExecutableSetting = cloneConfigString(value.BashExecutableSetting)
	value.ProxyURL = cloneConfigString(value.ProxyURL)
	servers := make([]config.LSPServerConfig, len(value.LSP))
	for index, server := range value.LSP {
		servers[index] = config.LSPServerConfig{
			ID:                server.ID,
			Command:           append([]string(nil), server.Command...),
			Extensions:        append([]string(nil), server.Extensions...),
			RootMarkers:       append([]string(nil), server.RootMarkers...),
			FirstMatchMarkers: append([]string(nil), server.FirstMatchMarkers...),
		}
	}
	value.LSP = servers
	return value
}

func cloneConfigString(value *string) *string {
	if value == nil {
		return nil
	}
	copy := *value
	return &copy
}
