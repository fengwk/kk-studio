package runtime

import (
	"context"
	"encoding/json"
	jsonv2 "encoding/json/v2"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"google.golang.org/protobuf/proto"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

const (
	defaultInvocationLimit = 64
	maxArgumentsBytes      = 16 << 20
	maxDetailsBytes        = 1 << 20
	maxContentJSONBytes    = 1 << 20
	maxResultContents      = 64
)

type ConfigApplier func(context.Context, int64, *protocol.DaemonConfiguration) (config.DaemonConfig, error)

type RuntimeOptions struct {
	EnvironmentID            string
	DaemonVersion            string
	Connections              ConnectionFactory
	InitialReconnectDelay    time.Duration
	MaxReconnectDelay        time.Duration
	Capabilities             map[string]CapabilityExecutor
	Processes                native.Supervisor
	Resources                ResourceUploader
	ApplyConfig              ConfigApplier
	EnvironmentConfig        *EnvironmentConfigRuntime
	Status                   *StatusTracker
	MaxConcurrentInvocations int
	HeartbeatInterval        time.Duration
	HostInfo                 *protocol.HostInfo
}

type activeConnection struct {
	generation uint64
	connection Connection
}

type runningCall struct {
	cancel context.CancelFunc
}

// Runtime owns one bound control connection, its handshake gate, in-flight calls and in-memory journal.
type Runtime struct {
	options          RuntimeOptions
	hostInfo         *protocol.HostInfo
	gate             OutboundGate
	journal          *InvocationJournal
	status           *StatusTracker
	mu               sync.RWMutex
	connection       *activeConnection
	generation       uint64
	config           config.DaemonConfig
	configProto      *protocol.DaemonConfiguration
	bound            bool
	ready            bool
	maxResourceBytes int64
	sendMu           sync.Mutex
	callsMu          sync.Mutex
	calls            map[string]*runningCall
	exitRequested    map[string]bool
	wg               sync.WaitGroup
	runMu            sync.Mutex
	running          bool
	hasRun           bool
	sendCtx          context.Context
}

// NewRuntime validates the required injectable boundaries and creates an in-memory session runtime.
func NewRuntime(options RuntimeOptions) (*Runtime, error) {
	if options.EnvironmentID == "" || options.Connections == nil || options.ApplyConfig == nil && options.EnvironmentConfig == nil {
		return nil, errors.New("runtime requires environmentId, connection factory, and configuration applier")
	}
	if options.Status == nil {
		if options.EnvironmentConfig != nil {
			options.Status = options.EnvironmentConfig.StatusTracker()
		} else {
			options.Status = NewStatusTracker()
		}
	}
	if options.EnvironmentConfig != nil {
		if options.EnvironmentConfig.StatusTracker() != options.Status {
			return nil, errors.New("runtime and environment config runtime must share a status tracker")
		}
		if options.EnvironmentConfig.Current().EnvironmentID != options.EnvironmentID {
			return nil, errors.New("runtime and environment config runtime must share an environmentId")
		}
	}
	if options.InitialReconnectDelay == 0 {
		options.InitialReconnectDelay = 250 * time.Millisecond
	}
	if options.MaxReconnectDelay == 0 {
		options.MaxReconnectDelay = 30 * time.Second
	}
	if options.InitialReconnectDelay < 0 || options.MaxReconnectDelay < options.InitialReconnectDelay {
		return nil, errors.New("invalid reconnect delay bounds")
	}
	if options.MaxConcurrentInvocations == 0 {
		options.MaxConcurrentInvocations = defaultInvocationLimit
	}
	if options.MaxConcurrentInvocations < 1 || options.MaxConcurrentInvocations > 64 {
		return nil, errors.New("maxConcurrentInvocations must be between 1 and 64")
	}
	if options.HeartbeatInterval == 0 {
		options.HeartbeatInterval = 15 * time.Second
	}
	if options.HeartbeatInterval < 0 {
		return nil, errors.New("heartbeat interval must not be negative")
	}
	var err error
	hostInfo := options.HostInfo
	if hostInfo == nil {
		hostInfo, err = detectHostInfo()
		if err != nil {
			return nil, err
		}
	} else {
		hostInfo = proto.Clone(hostInfo).(*protocol.HostInfo)
	}
	journal, err := NewInvocationJournal(1024)
	if err != nil {
		return nil, err
	}
	runtime := &Runtime{options: options, hostInfo: hostInfo, journal: journal, status: options.Status, config: config.DaemonConfig{EnvironmentID: options.EnvironmentID}, calls: make(map[string]*runningCall), exitRequested: make(map[string]bool)}
	if options.EnvironmentConfig != nil {
		options.EnvironmentConfig.AddSessionHooks(nil, nil, func(previous, next config.DaemonConfig) {
			if previous.StudioURL != next.StudioURL || !sameOptionalString(previous.ProxyURL, next.ProxyURL) {
				runtime.dropActiveConnection()
			}
		})
	}
	return runtime, nil
}

// Run reconnects the injected control transport and retains accepted invocations across connection generations.
func (r *Runtime) Run(ctx context.Context) error {
	r.runMu.Lock()
	if r.hasRun {
		r.runMu.Unlock()
		return errors.New("runtime can only be run once")
	}
	r.running = true
	r.hasRun = true
	r.runMu.Unlock()
	defer func() { r.runMu.Lock(); r.running = false; r.runMu.Unlock() }()
	runCtx, cancel := context.WithCancel(ctx)
	r.mu.Lock()
	r.sendCtx = runCtx
	r.mu.Unlock()
	heartbeatDone := make(chan struct{})
	go r.heartbeatLoop(runCtx, heartbeatDone)
	defer func() {
		cancel()
		<-heartbeatDone
		r.dropActiveConnection()
		if r.options.EnvironmentConfig != nil {
			r.options.EnvironmentConfig.Close()
		}
		r.cancelAndWait()
	}()

	delay := r.options.InitialReconnectDelay
	for {
		connection, err := r.options.Connections.Connect(runCtx)
		if err != nil {
			if runCtx.Err() != nil {
				return nil
			}
			if errors.Is(err, ErrRegistrationRejected) {
				return err
			}
			if !waitReconnect(runCtx, delay) {
				return nil
			}
			delay = doubledDelay(delay, r.options.MaxReconnectDelay)
			continue
		}
		if connection == nil {
			return errors.New("connection factory returned an empty connection")
		}
		active := r.attachConnection(connection)
		err = r.serveConnection(runCtx, active)
		r.dropConnection(active)
		if runCtx.Err() != nil {
			return nil
		}
		if errors.Is(err, ErrRegistrationRejected) {
			return err
		}
		if !waitReconnect(runCtx, delay) {
			return nil
		}
		delay = doubledDelay(delay, r.options.MaxReconnectDelay)
	}
}

func (r *Runtime) serveConnection(ctx context.Context, active *activeConnection) error {
	for {
		envelope, err := active.connection.Receive(ctx)
		if err != nil {
			return err
		}
		if envelope == nil {
			return errors.New("control connection returned an empty envelope")
		}
		if !r.isActiveConnection(active) {
			return errors.New("stale control connection generation")
		}
		if err := r.handle(ctx, envelope); err != nil {
			return err
		}
	}
}

func waitReconnect(ctx context.Context, delay time.Duration) bool {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

func doubledDelay(delay, maximum time.Duration) time.Duration {
	if delay >= maximum || delay > maximum/2 {
		return maximum
	}
	return delay * 2
}

func (r *Runtime) attachConnection(connection Connection) *activeConnection {
	r.mu.Lock()
	r.generation++
	active := &activeConnection{generation: r.generation, connection: connection}
	r.connection = active
	r.bound = false
	r.ready = false
	r.mu.Unlock()
	r.gate.Clear()
	return active
}

func (r *Runtime) isActiveConnection(active *activeConnection) bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.connection == active
}

func (r *Runtime) dropConnection(active *activeConnection) {
	r.mu.Lock()
	if r.connection != active {
		r.mu.Unlock()
		return
	}
	r.connection = nil
	r.bound = false
	r.ready = false
	r.mu.Unlock()
	r.gate.Clear()
	_ = active.connection.Close()
}

func (r *Runtime) dropActiveConnection() {
	r.mu.RLock()
	active := r.connection
	r.mu.RUnlock()
	if active != nil {
		r.dropConnection(active)
	}
}

func (r *Runtime) handle(ctx context.Context, e *protocol.Envelope) error {
	if e.MessageType == protocol.MessageType_SETTINGS {
		return r.handleSettings(e)
	}
	r.mu.RLock()
	bound, ready := r.bound, r.ready
	r.mu.RUnlock()
	if !bound {
		return errors.New("environment scope is not bound")
	}
	if e.EnvironmentId != nil && *e.EnvironmentId != r.options.EnvironmentID {
		return errors.New("envelope is bound to a different environment")
	}
	switch e.MessageType {
	case protocol.MessageType_ERROR:
		return errors.New("control server rejected the connection")
	case protocol.MessageType_ENVIRONMENT_CONFIG:
		return r.handleEnvironmentConfig(ctx, e)
	case protocol.MessageType_INVOKE:
		if !ready {
			return r.sendError(e.InvocationId, "INVOKE requires a READY bound connection")
		}
		return r.handleInvoke(ctx, e)
	case protocol.MessageType_CANCEL:
		if !ready {
			return nil
		}
		return r.handleCancel(e)
	case protocol.MessageType_RECOVER:
		if !ready {
			return nil
		}
		return r.handleRecover(e)
	case protocol.MessageType_UPDATE:
		if !ready {
			return r.sendError(e.InvocationId, "UPDATE requires a READY bound connection")
		}
		return r.sendError(e.InvocationId, "managed update is not configured")
	default:
		return r.sendError(e.InvocationId, "unsupported inbound message type")
	}
}

func (r *Runtime) handleSettings(e *protocol.Envelope) error {
	if e.EnvironmentId == nil || *e.EnvironmentId == "" || *e.EnvironmentId != r.options.EnvironmentID {
		return errors.New("settings environmentId is missing or does not match")
	}
	if e.Payload == nil {
		return errors.New("settings.resourceBudgets is required")
	}
	settings, ok := e.Payload.(*protocol.Envelope_Settings)
	if !ok || settings.Settings == nil || settings.Settings.ResourceBudgets == nil {
		return errors.New("settings.resourceBudgets is required")
	}
	budgets := settings.Settings.ResourceBudgets
	if (budgets.TemporaryResourceTtlSeconds != nil && budgets.GetTemporaryResourceTtlSeconds() < 0) || (budgets.TemporaryResourceCleanupIntervalSeconds != nil && budgets.GetTemporaryResourceCleanupIntervalSeconds() < 0) {
		return errors.New("settings.resourceBudgets is invalid")
	}
	if err := r.gate.Bind(r.options.EnvironmentID); err != nil {
		return err
	}
	r.mu.Lock()
	r.bound = true
	r.ready = false
	r.maxResourceBytes = budgets.GetMaxResourceBytes()
	r.mu.Unlock()
	return nil
}

func (r *Runtime) handleEnvironmentConfig(ctx context.Context, e *protocol.Envelope) error {
	payload, ok := e.Payload.(*protocol.Envelope_EnvironmentConfig)
	if !ok || payload.EnvironmentConfig == nil || payload.EnvironmentConfig.Config == nil || payload.EnvironmentConfig.Version == nil {
		return r.sendError(nil, "environment configuration is invalid")
	}
	version := payload.EnvironmentConfig.GetVersion()
	if version < 0 {
		return r.sendError(nil, "environment configuration is invalid")
	}
	r.mu.RLock()
	previous := r.config
	previousProto := r.configProto
	ready := r.ready
	r.mu.RUnlock()
	if ready && version < previous.ConfigVersion {
		return nil
	}
	if ready && version == previous.ConfigVersion {
		if proto.Equal(previousProto, payload.EnvironmentConfig.Config) {
			return nil
		}
		return errors.New("the same environment config version must not change its content")
	}
	var candidate config.DaemonConfig
	if r.options.EnvironmentConfig != nil {
		result, err := r.options.EnvironmentConfig.Submit(ctx, version, payload.EnvironmentConfig.Config)
		if err != nil {
			return err
		}
		switch result.Kind {
		case ConfigFailed:
			if ready {
				return r.sendStatus()
			}
			return nil
		case ConfigFatal:
			return errors.New(result.Message)
		default:
			candidate = result.Config
		}
	} else {
		var err error
		candidate, err = r.options.ApplyConfig(ctx, version, payload.EnvironmentConfig.Config)
		if err != nil {
			if !ready {
				_ = r.sendError(nil, "environment configuration could not be applied")
				return nil
			}
			return r.sendError(nil, "environment configuration could not be applied")
		}
	}
	if candidate.EnvironmentID != "" && candidate.EnvironmentID != r.options.EnvironmentID {
		return errors.New("applied configuration changed environment scope")
	}
	candidate.EnvironmentID = r.options.EnvironmentID
	candidate.ConfigVersion = version
	r.mu.Lock()
	if err := r.status.ApplyConfiguration(version, candidate.ProxyURL); err != nil {
		r.mu.Unlock()
		return err
	}
	r.config = candidate
	r.configProto = proto.Clone(payload.EnvironmentConfig.Config).(*protocol.DaemonConfiguration)
	first := !r.ready
	r.ready = true
	r.mu.Unlock()
	r.gate.MarkReady()
	if first {
		return r.sendReady()
	}
	return r.sendStatus()
}

func (r *Runtime) handleInvoke(ctx context.Context, e *protocol.Envelope) error {
	if e.InvocationId == nil || *e.InvocationId == "" {
		return r.sendError(nil, "invocationId is required")
	}
	id := *e.InvocationId
	start := r.journal.Start(id)
	if !start.Created {
		return r.replay(start.Entry)
	}
	invoke, ok := e.Payload.(*protocol.Envelope_Invoke)
	if !ok || invoke.Invoke == nil {
		return r.rejectCall(id, "invoke payload is required")
	}
	capabilityID := invoke.Invoke.GetCapabilityId()
	if capabilityID == "" {
		return r.rejectCall(id, "capabilityId is required")
	}
	if invoke.Invoke.ExpectedFile != nil && capabilityID != "fs.edit" {
		return r.rejectCall(id, "expectedFile is only valid for fs.edit")
	}
	executor, ok := r.options.Capabilities[capabilityID]
	if !ok {
		return r.rejectCall(id, "unknown Environment capability: "+capabilityID)
	}
	arguments := []byte(invoke.Invoke.GetArgumentsJson())
	if len(arguments) == 0 || len(arguments) > maxArgumentsBytes {
		return r.rejectCall(id, "arguments must be a JSON object")
	}
	var parsed map[string]json.RawMessage
	if err := jsonv2.Unmarshal(arguments, &parsed); err != nil || parsed == nil {
		return r.rejectCall(id, "arguments must be a JSON object")
	}
	r.callsMu.Lock()
	if len(r.calls) >= r.options.MaxConcurrentInvocations {
		r.callsMu.Unlock()
		return r.rejectCall(id, "too many concurrent capability invocations")
	}
	millis := invoke.Invoke.GetTimeoutMillis()
	if millis < 0 || millis > int64((time.Duration(1<<63-1))/time.Millisecond) {
		r.callsMu.Unlock()
		return r.rejectCall(id, "timeoutMillis must be a non-negative duration")
	}
	var callCtx context.Context
	var cancel context.CancelFunc
	if millis > 0 {
		callCtx, cancel = context.WithTimeout(ctx, time.Duration(millis)*time.Millisecond)
	} else {
		callCtx, cancel = context.WithCancel(ctx)
	}
	active := &runningCall{cancel: cancel}
	r.calls[id] = active
	r.wg.Add(1)
	r.callsMu.Unlock()
	if err := r.sendStarted(id, false); err != nil {
		cancel()
		r.callsMu.Lock()
		delete(r.calls, id)
		r.callsMu.Unlock()
		r.wg.Done()
		return r.rejectCall(id, "control connection send failed")
	}
	r.mu.RLock()
	snapshot := r.config
	budget := r.maxResourceBytes
	r.mu.RUnlock()
	call := CapabilityCall{InvocationID: id, CapabilityID: capabilityID, Arguments: arguments, TimeoutMillis: invoke.Invoke.GetTimeoutMillis()}
	if f := invoke.Invoke.ExpectedFile; f != nil {
		call.ExpectedFile = &ExpectedFile{Path: f.GetPath(), SHA256: f.GetSha256()}
	}
	resources := r.options.Resources
	capctx := CapabilityContext{Config: snapshot, Processes: r.options.Processes, Resources: resources, Progress: func(progress CapabilityResult) error { return r.sendProgress(id, progress) }, ConfirmExecutionExit: func() { r.noteExecutionExit(id) }}
	go r.execute(callCtx, id, call, executor, capctx, budget, active)
	return nil
}

func (r *Runtime) execute(ctx context.Context, id string, call CapabilityCall, executor CapabilityExecutor, capctx CapabilityContext, budget int64, active *runningCall) {
	defer r.wg.Done()
	defer active.cancel()
	defer func() { r.callsMu.Lock(); delete(r.calls, id); r.callsMu.Unlock() }()
	defer r.publishRequestedExit(id)
	result, err := invokeCapability(ctx, call, capctx, executor)
	if err != nil {
		if ctx.Err() != nil {
			reason := "cancelled"
			if errors.Is(ctx.Err(), context.DeadlineExceeded) {
				reason = fmt.Sprintf("capability execution timed out after %dms", call.TimeoutMillis)
				_ = r.finish(id, protocol.MessageType_FAILED, &protocol.Envelope_Failed{Failed: &protocol.Failed{Message: stringPtr(reason)}}, InvocationFailed)
			} else {
				_ = r.finish(id, protocol.MessageType_CANCELLED, &protocol.Envelope_Cancelled{Cancelled: &protocol.Cancelled{Reason: stringPtr(reason)}}, InvocationCancelled)
			}
		} else {
			_ = r.finish(id, protocol.MessageType_FAILED, &protocol.Envelope_Failed{Failed: &protocol.Failed{Message: stringPtr(safeCapabilityError(err))}}, InvocationFailed)
		}
		return
	}
	encoded, encodeErr := r.encodeResult(ctx, id, result, budget, false)
	if encodeErr != nil {
		_ = r.finish(id, protocol.MessageType_FAILED, &protocol.Envelope_Failed{Failed: &protocol.Failed{Message: stringPtr(encodeErr.Error())}}, InvocationFailed)
		return
	}
	if err := r.finish(id, protocol.MessageType_COMPLETED, &protocol.Envelope_Completed{Completed: &protocol.Completed{Result: encoded}}, InvocationCompleted); err != nil {
		return
	}
}

func (r *Runtime) encodeResult(ctx context.Context, id string, result CapabilityResult, budget int64, progress bool) (*protocol.CapabilityResult, error) {
	if result.CallID != id {
		return nil, errors.New("capability result callId does not match invocationId")
	}
	details := result.Details
	if len(details) == 0 {
		details = []byte("{}")
	}
	if len(details) > maxDetailsBytes {
		return nil, errors.New("capability details JSON exceeds 1 MiB")
	}
	var parsed map[string]json.RawMessage
	if err := jsonv2.Unmarshal(details, &parsed); err != nil || parsed == nil {
		return nil, errors.New("capability details is not a JSON object")
	}
	if len(result.Contents) > maxResultContents {
		return nil, errors.New("capability result contains too many content items")
	}
	if !progress && budget <= 0 {
		return nil, errors.New("maximumResourceBytes must be positive")
	}
	var total int64
	for _, content := range result.Contents {
		var size int64
		switch content.Type {
		case "binary":
			size = int64(len(content.Bytes))
		case "resource":
			size = content.Size
			if size < 0 {
				return nil, errors.New("resource size must not be negative")
			}
		case "text", "json":
		default:
			return nil, errors.New("capability result content type is invalid")
		}
		if progress && content.Type != "text" && content.Type != "json" {
			return nil, errors.New("progress does not allow binary content")
		}
		if size > budget-total {
			return nil, fmt.Errorf("aggregate resource bytes exceed maximumResourceBytes: maximum=%d", budget)
		}
		total += size
	}
	out := &protocol.CapabilityResult{CallId: stringPtr(result.CallID), Error: boolPtr(result.Error), DetailsJson: stringPtr(string(details))}
	for _, content := range result.Contents {
		item := &protocol.Content{}
		switch content.Type {
		case "text":
			item.Body = &protocol.Content_Text{Text: &protocol.TextContent{Text: stringPtr(content.Text)}}
		case "json":
			if len(content.JSON) > maxContentJSONBytes {
				return nil, errors.New("capability JSON content exceeds 1 MiB")
			}
			var v any
			if err := jsonv2.Unmarshal(content.JSON, &v); err != nil {
				return nil, errors.New("capability JSON content is invalid")
			}
			item.Body = &protocol.Content_Json{Json: &protocol.JsonContent{Json: stringPtr(string(content.JSON))}}
		case "binary":
			if progress {
				return nil, errors.New("progress does not allow binary content")
			}
			if r.options.Resources == nil {
				return nil, errors.New("resource upload is not configured")
			}
			uploaded, err := r.options.Resources.Upload(ctx, id, content)
			if err != nil {
				return nil, errors.New("resource upload failed")
			}
			item.Body = &protocol.Content_Resource{Resource: &protocol.ResourceContent{UploadId: stringPtr(uploaded.UploadID), MediaType: stringPtr(uploaded.MediaType), Name: uploaded.Name, Size: int64Ptr(uploaded.Size), Sha256: stringPtr(uploaded.SHA256), Preview: stringPtr(uploaded.Preview)}}
		case "resource":
			item.Body = &protocol.Content_Resource{Resource: &protocol.ResourceContent{UploadId: stringPtr(content.UploadID), MediaType: stringPtr(content.MediaType), Name: content.Name, Size: int64Ptr(content.Size), Sha256: stringPtr(content.SHA256), Preview: stringPtr(content.Preview)}}
		default:
			return nil, errors.New("capability result content type is invalid")
		}
		out.Contents = append(out.Contents, item)
	}
	return out, nil
}

func (r *Runtime) handleCancel(e *protocol.Envelope) error {
	if e.InvocationId == nil {
		return nil
	}
	id := *e.InvocationId
	r.callsMu.Lock()
	active := r.calls[id]
	r.callsMu.Unlock()
	if active != nil {
		active.cancel()
		return nil
	}
	entry, ok := r.journal.Get(id)
	if ok && entry.State != InvocationRunning {
		return r.replay(entry)
	}
	return nil
}
func (r *Runtime) handleRecover(e *protocol.Envelope) error {
	if e.InvocationId == nil {
		return nil
	}
	entry, ok := r.journal.Get(*e.InvocationId)
	if !ok {
		return r.sendRecoverNotFound(*e.InvocationId)
	}
	return r.replay(entry)
}
func (r *Runtime) replay(entry JournalEntry) error {
	if entry.State == InvocationRunning {
		return r.sendStarted(entry.InvocationID, true)
	}
	var frame protocol.Envelope
	if err := proto.Unmarshal(entry.Terminal, &frame); err != nil {
		return errors.New("journal terminal frame is invalid")
	}
	if err := r.sendBound(&frame, true); err != nil {
		return err
	}
	if entry.Exited {
		return r.sendExecutionExited(entry.InvocationID)
	}
	return nil
}
func (r *Runtime) rejectCall(id, msg string) error {
	frame := &protocol.Envelope{MessageType: protocol.MessageType_FAILED, InvocationId: stringPtr(id), Payload: &protocol.Envelope_Failed{Failed: &protocol.Failed{Message: stringPtr(msg)}}}
	sendErr := r.finish(id, protocol.MessageType_FAILED, frame.Payload, InvocationFailed)
	if r.journal.ConfirmExit(id) {
		exitErr := r.sendExecutionExited(id)
		if sendErr != nil {
			return sendErr
		}
		return exitErr
	}
	return sendErr
}
func (r *Runtime) finish(id string, kind protocol.MessageType, payload any, state InvocationState) error {
	frame := &protocol.Envelope{MessageType: kind, EnvironmentId: stringPtr(r.options.EnvironmentID), InvocationId: stringPtr(id)}
	switch value := payload.(type) {
	case *protocol.Envelope_Failed:
		frame.Payload = value
	case *protocol.Envelope_Cancelled:
		frame.Payload = value
	case *protocol.Envelope_Completed:
		frame.Payload = value
	default:
		return errors.New("unsupported terminal payload")
	}
	data, err := proto.Marshal(frame)
	if err != nil {
		return err
	}
	if !r.journal.Complete(id, state, data) {
		return nil
	}
	return r.sendBound(frame, true)
}
func (r *Runtime) sendStarted(id string, replayed bool) error {
	return r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_STARTED, InvocationId: stringPtr(id), Payload: &protocol.Envelope_Started{Started: &protocol.Started{Replayed: boolPtr(replayed)}}}, true)
}
func (r *Runtime) sendProgress(id string, result CapabilityResult) error {
	entry, ok := r.journal.Get(id)
	if !ok || entry.State != InvocationRunning {
		return nil
	}
	r.mu.RLock()
	budget := r.maxResourceBytes
	r.mu.RUnlock()
	encoded, err := r.encodeResult(context.Background(), id, result, budget, true)
	if err != nil {
		return err
	}
	return r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_PROGRESS, InvocationId: stringPtr(id), Payload: &protocol.Envelope_Progress{Progress: &protocol.Progress{Result: encoded}}}, true)
}
func (r *Runtime) sendRecoverNotFound(id string) error {
	return r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_RECOVER_NOT_FOUND, InvocationId: stringPtr(id), Payload: &protocol.Envelope_RecoverNotFound{RecoverNotFound: &protocol.RecoverNotFound{}}}, true)
}
func (r *Runtime) sendExecutionExited(id string) error {
	return r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_EXECUTION_EXITED, InvocationId: stringPtr(id), Payload: &protocol.Envelope_ExecutionExited{ExecutionExited: &protocol.ExecutionExited{}}}, true)
}
func (r *Runtime) sendError(id *string, message string) error {
	return r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_ERROR, InvocationId: id, Payload: &protocol.Envelope_Error{Error: &protocol.Error{Message: stringPtr(message)}}}, false)
}
func (r *Runtime) runtimeSnapshot() *protocol.RuntimeSnapshot {
	status := r.status.Snapshot()
	body := &protocol.RuntimeBody{DaemonVersion: stringPtr(r.options.DaemonVersion)}
	r.mu.RLock()
	ready, snapshot := r.ready, r.config
	r.mu.RUnlock()
	configurationStatus := &protocol.ConfigurationStatus{}
	if ready {
		configurationStatus.AppliedVersion = int64Ptr(snapshot.ConfigVersion)
		configurationStatus.ProxyUrl = snapshot.ProxyURL
	}
	if failure, ok := r.status.ConfigurationError(); ok {
		configurationStatus.Error = &protocol.ConfigurationError{Version: int64Ptr(failure.Version), Code: failure.Code}
		if failure.Field != "" {
			configurationStatus.Error.Field = stringPtr(failure.Field)
		}
	}
	if ready || configurationStatus.Error != nil {
		body.Configuration = configurationStatus
	}
	if progress, ok := r.status.Progress(); ok {
		body.Update = &protocol.UpdateProgress{DownloadedBytes: int64Ptr(progress.DownloadedBytes), TotalBytes: progress.TotalBytes}
	}
	return &protocol.RuntimeSnapshot{Revision: int64Ptr(status.Revision), Status: body}
}
func (r *Runtime) sendReady() error {
	return r.sendFrame(&protocol.Envelope{MessageType: protocol.MessageType_READY, EnvironmentId: stringPtr(r.options.EnvironmentID), Payload: &protocol.Envelope_Ready{Ready: &protocol.Ready{DaemonVersion: stringPtr(r.options.DaemonVersion), HostInfo: r.hostInfo, RuntimeStatus: r.runtimeSnapshot()}}})
}
func (r *Runtime) sendStatus() error {
	frame := &protocol.Envelope{MessageType: protocol.MessageType_RUNTIME_STATUS, EnvironmentId: stringPtr(r.options.EnvironmentID), Payload: &protocol.Envelope_RuntimeStatus{RuntimeStatus: &protocol.RuntimeStatus{RuntimeStatus: r.runtimeSnapshot()}}}
	return r.sendBound(frame, false)
}
func (r *Runtime) sendBound(e *protocol.Envelope, readyRequired bool) error {
	var err error
	if readyRequired {
		err = r.gate.RequireReadyFrame()
	} else {
		err = r.gate.RequireControlFrame()
	}
	if err != nil {
		return err
	}
	e.EnvironmentId = stringPtr(r.options.EnvironmentID)
	return r.sendFrame(e)
}
func (r *Runtime) sendFrame(e *protocol.Envelope) error {
	r.sendMu.Lock()
	defer r.sendMu.Unlock()
	r.mu.RLock()
	ctx, active := r.sendCtx, r.connection
	r.mu.RUnlock()
	if active == nil {
		return errors.New("control connection is disconnected")
	}
	if ctx == nil {
		ctx = context.Background()
	}
	if err := active.connection.Send(ctx, e); err != nil {
		r.dropConnection(active)
		return err
	}
	return nil
}
func (r *Runtime) heartbeatLoop(ctx context.Context, done chan<- struct{}) {
	defer close(done)
	if r.options.HeartbeatInterval == 0 {
		return
	}
	ticker := time.NewTicker(r.options.HeartbeatInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			r.mu.RLock()
			bound, ready := r.bound, r.ready
			r.mu.RUnlock()
			if bound && ready {
				h := &protocol.Heartbeat{RuntimeStatus: r.runtimeSnapshot()}
				_ = r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_HEARTBEAT, Payload: &protocol.Envelope_Heartbeat{Heartbeat: h}}, true)
			}
		}
	}
}
func (r *Runtime) noteExecutionExit(id string) {
	r.callsMu.Lock()
	r.exitRequested[id] = true
	r.callsMu.Unlock()
}
func (r *Runtime) publishRequestedExit(id string) {
	r.callsMu.Lock()
	requested := r.exitRequested[id]
	delete(r.exitRequested, id)
	r.callsMu.Unlock()
	if requested && r.journal.ConfirmExit(id) {
		_ = r.sendExecutionExited(id)
	}
}
func (r *Runtime) cancelAndWait() {
	r.callsMu.Lock()
	for _, call := range r.calls {
		call.cancel()
	}
	r.callsMu.Unlock()
	r.wg.Wait()
}
func invokeCapability(ctx context.Context, call CapabilityCall, capabilityContext CapabilityContext, executor CapabilityExecutor) (result CapabilityResult, err error) {
	defer func() {
		if recover() != nil {
			result = CapabilityResult{}
			err = errors.New("capability execution failed")
		}
	}()
	return executor(ctx, call, capabilityContext)
}

func safeCapabilityError(err error) string {
	if err == nil || strings.TrimSpace(err.Error()) == "" {
		return "capability execution failed"
	}
	return err.Error()
}
func stringPtr(s string) *string { return &s }

func sameOptionalString(left, right *string) bool {
	if left == nil || right == nil {
		return left == nil && right == nil
	}
	return *left == *right
}
func boolPtr(b bool) *bool    { return &b }
func int64Ptr(v int64) *int64 { return &v }
