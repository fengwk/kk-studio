package runtime

import (
	"context"
	"crypto/rand"
	"encoding/json"
	jsonv2 "encoding/json/v2"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"google.golang.org/protobuf/proto"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"github.com/fengwk/kk-studio/daemon/internal/update"
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
	FileChanged              func(string)
	Resources                ResourceUploader
	TemporaryResources       TemporaryResourceSweeper
	IDECommandCount          func() int
	IDECanStartUpdate        func() bool
	ApplyConfig              ConfigApplier
	EnvironmentConfig        *EnvironmentConfigRuntime
	Status                   *StatusTracker
	MaxConcurrentInvocations int
	HeartbeatInterval        time.Duration
	IDECommandTimeout        time.Duration
	HostInfo                 *protocol.HostInfo
	Update                   update.Runner
	ReportError              func(error)
	IDE                      IDEService
}

type activeConnection struct {
	generation uint64
	connection Connection
	// goaway records that this generation ended through a control GOAWAY. Its retirement closes the
	// old connection once and reconnects immediately instead of applying the disconnect backoff.
	goaway atomic.Bool
}

// controlAttempt carries transport-level events for one connection attempt back to the runtime. It
// is bound to the attempt's generation after a successful dial so that late events of an older
// generation can never act on the current connection.
type controlAttempt struct {
	runtime *Runtime
	active  atomic.Pointer[activeConnection]
	mu      sync.Mutex
	pending bool
	status  int
	target  string
}

// statusUpgradeRequired is the handshake rejection that asks the daemon to install a newer release.
const statusUpgradeRequired = 426

func newControlAttempt(runtime *Runtime) *controlAttempt {
	return &controlAttempt{runtime: runtime}
}

// bind attaches the generation this attempt produced. A GOAWAY that arrived before the generation
// was known retires it immediately, exactly like a GOAWAY observed on a bound generation. The
// generation and the pending flag share the lock so a GOAWAY racing the bind is never dropped;
// retirement always runs outside it because closing the connection can re-enter the observer.
func (a *controlAttempt) bind(active *activeConnection) {
	a.mu.Lock()
	a.active.Store(active)
	pending := a.pending
	a.mu.Unlock()
	if pending {
		a.runtime.retireConnection(active)
	}
}

// NotifyGoaway retires the generation on a graceful control GOAWAY. Other error codes stay on the
// ordinary disconnect path.
func (a *controlAttempt) NotifyGoaway(code uint32) {
	if code != 0 {
		return
	}
	a.mu.Lock()
	active := a.active.Load()
	if active == nil {
		a.pending = true
		a.mu.Unlock()
		return
	}
	a.mu.Unlock()
	a.runtime.retireConnection(active)
}

// NotifyUpgradeFailed records a handshake rejection so the run loop can apply upgrade policy for the
// attempt that observed it.
func (a *controlAttempt) NotifyUpgradeFailed(statusCode int, upgradeRequired *protocol.UpgradeRequired) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.status = statusCode
	a.target = ""
	if upgradeRequired != nil {
		a.target = upgradeRequired.GetTargetVersion()
	}
}

// upgradeRejection reports whether this attempt observed a 426 rejection and, if the body carried a
// parsable UpgradeRequired, the version the platform requires.
func (a *controlAttempt) upgradeRejection() (rejected bool, targetVersion string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.status != statusUpgradeRequired {
		return false, ""
	}
	a.status = 0
	return true, a.target
}

type runningCall struct {
	cancel         context.CancelFunc
	maxOutputBytes int64
	progressError  string
}

type resourceExchangeKey struct {
	messageType  protocol.MessageType
	invocationID string
	transferID   string
}

// Runtime owns one bound control connection, its handshake gate, in-flight calls and in-memory journal.
type resourceUploadFailure struct {
	cleanupConfirmed bool
}

func (e *resourceUploadFailure) Error() string { return "resource upload failed" }

type Runtime struct {
	options                RuntimeOptions
	hostInfo               *protocol.HostInfo
	gate                   OutboundGate
	journal                *InvocationJournal
	status                 *StatusTracker
	mu                     sync.RWMutex
	connection             *activeConnection
	generation             uint64
	config                 config.DaemonConfig
	configProto            *protocol.DaemonConfiguration
	bound                  bool
	ready                  bool
	sendMu                 sync.Mutex
	callsMu                sync.Mutex
	calls                  map[string]*runningCall
	exitRequested          map[string]bool
	exitCleanupUnconfirmed map[string]bool
	ideCommandCount        atomic.Int32
	ideMu                  sync.Mutex
	ideCommands            map[string]*ideCommandCall
	ideWG                  sync.WaitGroup
	ideLeaseMu             sync.Mutex
	ideLeaseRound          int64
	ideLeaseSentAt         time.Time
	ideLeaseConsumed       bool
	resourceMu             sync.Mutex
	resourceRequests       map[resourceExchangeKey]chan *protocol.Envelope
	wg                     sync.WaitGroup
	runMu                  sync.Mutex
	running                bool
	hasRun                 bool
	sendCtx                context.Context
	runCancel              context.CancelFunc
	runFailure             error
	updateOps              *update.Operations
	updateMu               sync.Mutex
	updateCancel           context.CancelFunc
	updateWatch            func()
	updatePermit           func() error
	admissionMu            sync.Mutex
	admissionPaused        bool
	configReconnectPending bool
	candidateSuspension    *bool
	resourcePolicyMu       sync.Mutex
	resourceTTL            time.Duration
	resourceSweepInterval  time.Duration
	resourcePolicyActive   bool
	resourcePolicyChanged  chan struct{}
	shutdown               *shutdownFailureTracker
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
		options.InitialReconnectDelay = time.Second
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
	if options.IDECommandTimeout == 0 {
		options.IDECommandTimeout = 30 * time.Second
	}
	if options.IDECommandTimeout < 0 {
		return nil, errors.New("IDE command timeout must be positive")
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
	runtime := &Runtime{options: options, hostInfo: hostInfo, journal: journal, status: options.Status, config: config.DaemonConfig{EnvironmentID: options.EnvironmentID}, calls: make(map[string]*runningCall), exitRequested: make(map[string]bool), exitCleanupUnconfirmed: make(map[string]bool), ideCommands: make(map[string]*ideCommandCall), resourceRequests: make(map[resourceExchangeKey]chan *protocol.Envelope), updateOps: update.NewOperations(), resourcePolicyChanged: make(chan struct{}, 1), shutdown: newShutdownFailureTracker(nil)}
	if options.IDECommandCount == nil {
		runtime.options.IDECommandCount = func() int { return int(runtime.ideCommandCount.Load()) }
	}
	if options.IDECanStartUpdate == nil {
		if admission, ok := options.IDE.(IDEUpdateAdmission); ok {
			runtime.options.IDECanStartUpdate = admission.CanStartUpdate
		}
	}
	if options.EnvironmentConfig != nil {
		options.EnvironmentConfig.AddSessionHooks(
			func(previous, candidate config.DaemonConfig) {
				if previous.GatewayURL == candidate.GatewayURL && sameOptionalString(previous.ProxyURL, candidate.ProxyURL) {
					return
				}
				runtime.admissionMu.Lock()
				if runtime.candidateSuspension == nil {
					wasPaused := runtime.admissionPaused
					runtime.candidateSuspension = &wasPaused
				}
				runtime.admissionPaused = true
				runtime.admissionMu.Unlock()
			},
			func(previous, candidate config.DaemonConfig) {
				if previous.GatewayURL == candidate.GatewayURL && sameOptionalString(previous.ProxyURL, candidate.ProxyURL) {
					return
				}
				runtime.admissionMu.Lock()
				if runtime.candidateSuspension != nil {
					runtime.admissionPaused = *runtime.candidateSuspension
					runtime.candidateSuspension = nil
				}
				runtime.admissionMu.Unlock()
			},
			func(previous, next config.DaemonConfig) {
				reconnect := previous.GatewayURL != next.GatewayURL || !sameOptionalString(previous.ProxyURL, next.ProxyURL)
				runtime.admissionMu.Lock()
				runtime.admissionPaused = reconnect
				runtime.configReconnectPending = reconnect
				runtime.candidateSuspension = nil
				runtime.admissionMu.Unlock()
				if reconnect {
					runtime.dropActiveConnection()
				}
			},
		)
	}
	return runtime, nil
}

// ShutdownFailures returns the shutdown release and diagnostic failures recorded by this runtime.
func (r *Runtime) ShutdownFailures() []error {
	return r.shutdown.snapshot()
}

// Run reconnects the injected control transport and retains accepted invocations across connection generations.
func (r *Runtime) Run(ctx context.Context) (runErr error) {
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
	r.runCancel = cancel
	r.mu.Unlock()
	heartbeatDone := make(chan struct{})
	sweepDone := make(chan struct{})
	go r.heartbeatLoop(runCtx, heartbeatDone)
	go r.temporaryResourceSweepLoop(runCtx, sweepDone)
	defer func() {
		cancel()
		<-heartbeatDone
		<-sweepDone
		r.shutdown.release("connection close", r.closeActiveConnection)
		r.shutdown.release("IDE command stop", func() error {
			r.cancelIDECommands(nil)
			r.ideWG.Wait()
			return nil
		})
		r.shutdown.release("managed update stop", func() error {
			r.stopUpdateForShutdown()
			return nil
		})
		r.shutdown.release("environment config close", func() error {
			if r.options.EnvironmentConfig != nil {
				r.options.EnvironmentConfig.Close()
			}
			return nil
		})
		r.shutdown.release("in-flight execution did not converge", func() error {
			r.cancelAndWait()
			return nil
		})
		r.mu.RLock()
		failure := r.runFailure
		r.mu.RUnlock()
		runErr = errors.Join(runErr, failure, aggregateShutdownFailures(r.ShutdownFailures()))
	}()

	delay := r.options.InitialReconnectDelay
	for {
		attempt := newControlAttempt(r)
		connection, err := r.options.Connections.Connect(runCtx, attempt)
		if err != nil {
			if runCtx.Err() != nil {
				return nil
			}
			if errors.Is(err, ErrRegistrationRejected) {
				return err
			}
			if rejected, targetVersion := attempt.upgradeRejection(); rejected {
				if !r.beginManagedUpdate(runCtx, targetVersion) {
					return errProtocolUpgradeRequired
				}
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
		attempt.bind(active)
		err = r.serveConnection(runCtx, active)
		if runCtx.Err() == nil && r.options.ReportError != nil {
			r.options.ReportError(err)
		}
		stopping := runCtx.Err() != nil || errors.Is(err, ErrRuntimeFatal) || errors.Is(err, ErrRegistrationRejected)
		if !stopping {
			r.dropConnection(active)
		}
		if runCtx.Err() != nil {
			return nil
		}
		if errors.Is(err, ErrRuntimeFatal) {
			return err
		}
		if errors.Is(err, ErrRegistrationRejected) {
			return err
		}
		if active.goaway.Load() {
			// A graceful GOAWAY already retired this generation: reconnect immediately and keep the
			// existing backoff state instead of waiting on it.
			continue
		}
		if !waitReconnect(runCtx, delay) {
			return nil
		}
		delay = doubledDelay(delay, r.options.MaxReconnectDelay)
	}
}

// errProtocolUpgradeRequired is the terminal failure for a 426 the daemon cannot upgrade out of.
var errProtocolUpgradeRequired = errors.New("daemon requires protocol upgrade")

// stopRun records the terminal runtime failure and stops the run loop. It serves paths that are not
// driven by an inbound frame, such as a managed update a 426 rejection required.
func (r *Runtime) stopRun(cause error) {
	r.mu.Lock()
	if r.runFailure == nil {
		r.runFailure = cause
	}
	cancel := r.runCancel
	r.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

// retireConnection closes one generation exactly once, releasing its binding and IDE work, and
// marks it as GOAWAY-retired so the run loop reconnects immediately.
func (r *Runtime) retireConnection(active *activeConnection) {
	active.goaway.Store(true)
	_ = r.dropConnection(active)
}

func (r *Runtime) serveConnection(ctx context.Context, active *activeConnection) error {
	connectionCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	defer r.cancelIDECommands(active)
	for {
		envelope, err := active.connection.Receive(connectionCtx)
		if err != nil {
			return err
		}
		if envelope == nil {
			return errors.New("control connection returned an empty envelope")
		}
		if !r.isActiveConnection(active) {
			return errors.New("stale control connection generation")
		}
		if err := r.handleWithIDEContext(ctx, connectionCtx, envelope); err != nil {
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
	r.setTemporaryResourcePolicyActive(false)
	r.clearIDELeaseRound()
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

func (r *Runtime) dropConnection(active *activeConnection) error {
	r.cancelIDECommands(active)
	r.mu.Lock()
	if r.connection != active {
		r.mu.Unlock()
		return nil
	}
	r.clearIDELeaseRound()
	r.connection = nil
	r.bound = false
	r.ready = false
	r.mu.Unlock()
	r.gate.Clear()
	return active.connection.Close()
}

func (r *Runtime) closeActiveConnection() error {
	r.mu.RLock()
	active := r.connection
	r.mu.RUnlock()
	if active == nil {
		return nil
	}
	err := r.dropConnection(active)
	if errors.Is(err, net.ErrClosed) {
		return nil
	}
	return err
}

func (r *Runtime) dropActiveConnection() {
	_ = r.closeActiveConnection()
}

func (r *Runtime) handle(ctx context.Context, e *protocol.Envelope) error {
	return r.handleWithIDEContext(ctx, ctx, e)
}

func (r *Runtime) handleWithIDEContext(ctx, ideCtx context.Context, e *protocol.Envelope) error {
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
			return r.sendBoundError(e.InvocationId, "INVOKE requires a READY bound connection")
		}
		return r.handleInvoke(ctx, e)
	case protocol.MessageType_CANCEL:
		return r.handleCancel(e)
	case protocol.MessageType_RECOVER:
		return r.handleRecover(e)
	case protocol.MessageType_UPDATE:
		if !ready || e.EnvironmentId == nil || *e.EnvironmentId == "" {
			return r.sendBoundError(e.InvocationId, "UPDATE requires a READY bound connection")
		}
		return r.handleUpdate(ctx, e)
	case protocol.MessageType_RESOURCE_UPLOAD_TICKET, protocol.MessageType_RESOURCE_DOWNLOAD_TICKET:
		if !ready {
			return errors.New("resource ticket requires a READY bound connection")
		}
		return r.routeResourceTicket(e)
	case protocol.MessageType_IDE_COMMAND:
		if !ready {
			return r.sendBoundError(e.InvocationId, "IDE command requires a READY bound connection")
		}
		request, ok := e.Payload.(*protocol.Envelope_IdeCommand)
		if !ok || request.IdeCommand == nil {
			return errors.New("IDE command payload is invalid")
		}
		r.admissionMu.Lock()
		if r.admissionPaused {
			r.admissionMu.Unlock()
			return r.sendBoundError(e.InvocationId, "environment configuration is switching; new work is not admitted")
		}
		err := r.acceptIDECommand(ideCtx, e.GetInvocationId(), request.IdeCommand)
		r.admissionMu.Unlock()
		return err
	case protocol.MessageType_IDE_LEASE:
		if !ready || r.options.IDE == nil {
			return errors.New("IDE lease requires a READY bound connection")
		}
		r.admissionMu.Lock()
		if r.admissionPaused {
			r.admissionMu.Unlock()
			return r.sendBoundError(nil, "environment configuration is switching; new work is not admitted")
		}
		if r.updateIsHeld() {
			r.admissionMu.Unlock()
			return errors.New("IDE_LEASE is not allowed while environment update admission is held")
		}
		request, ok := e.Payload.(*protocol.Envelope_IdeLease)
		if !ok || request.IdeLease == nil {
			r.admissionMu.Unlock()
			return errors.New("IDE lease payload is invalid")
		}
		roundStartedAt, err := r.consumeIDELease(request.IdeLease, time.Now())
		if err == nil {
			r.options.IDE.ApplyLease(request.IdeLease, roundStartedAt)
		}
		r.admissionMu.Unlock()
		return err
	case protocol.MessageType_TEMPORARY_RESOURCE_POLICY:
		if !ready {
			return r.sendBoundError(e.InvocationId, "TEMPORARY_RESOURCE_POLICY requires a READY bound connection")
		}
		return r.handleTemporaryResourcePolicy(e)
	case protocol.MessageType_READY:
		if err := r.sendBoundError(nil, "daemon must not receive READY from server"); err != nil {
			return err
		}
		return fmt.Errorf("daemon must not receive READY from server: %w", ErrForbiddenInboundFrame)
	default:
		return r.sendBoundError(e.InvocationId, "unsupported inbound message type")
	}
}

// RequestTicket requests one binary upload ticket and waits for its correlated control response.
func (r *Runtime) RequestTicket(ctx context.Context, invocationID string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
	if request == nil {
		return nil, errors.New("resource upload request is required")
	}
	envelope := &protocol.Envelope{MessageType: protocol.MessageType_RESOURCE_UPLOAD_REQUEST, InvocationId: stringPtr(invocationID), Payload: &protocol.Envelope_ResourceUploadRequest{ResourceUploadRequest: request}}
	response, err := r.resourceExchange(ctx, protocol.MessageType_RESOURCE_UPLOAD_TICKET, invocationID, request.GetTransferId(), envelope)
	if err != nil {
		return nil, err
	}
	return response.GetResourceUploadTicket(), nil
}

// Commit requests publication of one uploaded resource and waits for its correlated ticket.
func (r *Runtime) Commit(ctx context.Context, invocationID, transferID, uploadID string) (*protocol.ResourceUploadTicket, error) {
	envelope := &protocol.Envelope{MessageType: protocol.MessageType_RESOURCE_UPLOAD_COMMIT, InvocationId: stringPtr(invocationID), Payload: &protocol.Envelope_ResourceUploadCommit{ResourceUploadCommit: &protocol.ResourceUploadCommit{TransferId: stringPtr(transferID), UploadId: stringPtr(uploadID)}}}
	response, err := r.resourceExchange(ctx, protocol.MessageType_RESOURCE_UPLOAD_TICKET, invocationID, transferID, envelope)
	if err != nil {
		return nil, err
	}
	return response.GetResourceUploadTicket(), nil
}

// RequestResourceDownloadTicket requests a skill or other invocation-bound resource ticket.
func (r *Runtime) RequestResourceDownloadTicket(ctx context.Context, invocationID, transferID string) (*protocol.ResourceDownloadTicket, error) {
	envelope := &protocol.Envelope{MessageType: protocol.MessageType_RESOURCE_DOWNLOAD_REQUEST, InvocationId: stringPtr(invocationID), Payload: &protocol.Envelope_ResourceDownloadRequest{ResourceDownloadRequest: &protocol.ResourceDownloadRequest{TransferId: stringPtr(transferID)}}}
	response, err := r.resourceExchange(ctx, protocol.MessageType_RESOURCE_DOWNLOAD_TICKET, invocationID, transferID, envelope)
	if err != nil {
		return nil, err
	}
	return response.GetResourceDownloadTicket(), nil
}

func (r *Runtime) resourceExchange(ctx context.Context, responseType protocol.MessageType, invocationID, transferID string, request *protocol.Envelope) (*protocol.Envelope, error) {
	key := resourceExchangeKey{messageType: responseType, invocationID: invocationID, transferID: transferID}
	response := make(chan *protocol.Envelope, 1)
	r.resourceMu.Lock()
	if _, exists := r.resourceRequests[key]; exists {
		r.resourceMu.Unlock()
		return nil, errors.New("duplicate resource transfer request")
	}
	r.resourceRequests[key] = response
	r.resourceMu.Unlock()
	defer func() {
		r.resourceMu.Lock()
		if r.resourceRequests[key] == response {
			delete(r.resourceRequests, key)
		}
		r.resourceMu.Unlock()
	}()
	if err := r.sendBound(request, true); err != nil {
		return nil, err
	}
	select {
	case envelope := <-response:
		return envelope, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func (r *Runtime) routeResourceTicket(envelope *protocol.Envelope) error {
	var transferID string
	switch ticket := envelope.Payload.(type) {
	case *protocol.Envelope_ResourceUploadTicket:
		transferID = ticket.ResourceUploadTicket.GetTransferId()
	case *protocol.Envelope_ResourceDownloadTicket:
		transferID = ticket.ResourceDownloadTicket.GetTransferId()
	default:
		return errors.New("resource ticket payload is invalid")
	}
	key := resourceExchangeKey{messageType: envelope.MessageType, invocationID: envelope.GetInvocationId(), transferID: transferID}
	r.resourceMu.Lock()
	response := r.resourceRequests[key]
	if response != nil {
		delete(r.resourceRequests, key)
	}
	r.resourceMu.Unlock()
	if response == nil {
		return errors.New("unsolicited resource ticket")
	}
	response <- envelope
	return nil
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
	ttl, interval := time.Duration(budgets.GetTemporaryResourceTtlSeconds())*time.Second, time.Duration(budgets.GetTemporaryResourceCleanupIntervalSeconds())*time.Second
	r.setTemporaryResourcePolicy(ttl, interval, false)
	if err := r.gate.Bind(r.options.EnvironmentID); err != nil {
		return err
	}
	r.mu.Lock()
	r.bound = true
	r.ready = false
	r.mu.Unlock()
	return nil
}

func (r *Runtime) handleTemporaryResourcePolicy(e *protocol.Envelope) error {
	payload, ok := e.Payload.(*protocol.Envelope_TemporaryResourcePolicy)
	if !ok || payload.TemporaryResourcePolicy == nil {
		return r.sendBoundError(e.InvocationId, "temporary resource policy is invalid")
	}
	policy := payload.TemporaryResourcePolicy
	if policy.TemporaryResourceTtlSeconds == nil || policy.TemporaryResourceCleanupIntervalSeconds == nil || policy.GetTemporaryResourceTtlSeconds() <= 0 || policy.GetTemporaryResourceCleanupIntervalSeconds() <= 0 {
		return r.sendBoundError(e.InvocationId, "temporary resource policy is invalid")
	}
	r.setTemporaryResourcePolicy(time.Duration(policy.GetTemporaryResourceTtlSeconds())*time.Second, time.Duration(policy.GetTemporaryResourceCleanupIntervalSeconds())*time.Second, true)
	return nil
}

func (r *Runtime) handleEnvironmentConfig(ctx context.Context, e *protocol.Envelope) error {
	payload, ok := e.Payload.(*protocol.Envelope_EnvironmentConfig)
	if !ok || payload.EnvironmentConfig == nil || payload.EnvironmentConfig.Config == nil || payload.EnvironmentConfig.Version == nil {
		return r.sendBoundError(nil, "environment configuration is invalid")
	}
	version := payload.EnvironmentConfig.GetVersion()
	if version < 0 {
		return r.sendBoundError(nil, "environment configuration is invalid")
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
			return r.sendStatus()
		case ConfigFatal:
			return fmt.Errorf("%w: environment configuration is inconsistent", ErrRuntimeFatal)
		default:
			candidate = result.Config
		}
	} else {
		var err error
		candidate, err = r.options.ApplyConfig(ctx, version, payload.EnvironmentConfig.Config)
		if err != nil {
			if !ready {
				_ = r.sendBoundError(nil, "environment configuration could not be applied")
				return nil
			}
			return r.sendBoundError(nil, "environment configuration could not be applied")
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
	r.setTemporaryResourcePolicyActive(true)
	r.gate.MarkReady()
	if first {
		return r.sendReady()
	}
	return r.sendStatus()
}

func (r *Runtime) handleUpdate(ctx context.Context, envelope *protocol.Envelope) error {
	payload, ok := envelope.Payload.(*protocol.Envelope_Update)
	if !ok || payload.Update == nil || payload.Update.OperationId == nil || payload.Update.TargetVersion == nil || len(payload.Update.ProtoReflect().GetUnknown()) != 0 {
		return r.sendError(nil, "invalid update command")
	}
	command := update.Command{OperationID: payload.Update.GetOperationId(), TargetVersion: payload.Update.GetTargetVersion()}
	if err := update.ValidateCommand(command); err != nil {
		return r.sendError(nil, err.Error())
	}
	r.mu.RLock()
	configuration := cloneDaemonConfig(r.config)
	r.mu.RUnlock()
	runner := r.options.Update
	configured := runner != nil && runner.Configured()
	r.admissionMu.Lock()
	decision, err := r.updateOps.Decide(command, r.options.DaemonVersion, configured, r.updateBusyReason(runner))
	if err != nil {
		r.admissionMu.Unlock()
		return err
	}
	switch decision.Action {
	case update.ActionStatus:
		r.admissionMu.Unlock()
		return r.sendStatus()
	case update.ActionReplayActive, update.ActionReplayCache:
		r.admissionMu.Unlock()
		resultErr := r.sendUpdateReceipt(*decision.Receipt)
		statusErr := r.sendStatus()
		releaseErr := r.releaseUpdatePermit()
		return firstError(resultErr, statusErr, releaseErr)
	case update.ActionReject:
		r.admissionMu.Unlock()
		return r.sendUpdateReceipt(*decision.Receipt)
	case update.ActionAdmit:
		updateCtx, cancel := context.WithCancel(ctx)
		r.updateMu.Lock()
		r.updateCancel = cancel
		r.updateMu.Unlock()
		if err := r.status.BeginUpdate(command.TargetVersion); err != nil {
			cancel()
			failed, _ := r.updateOps.Complete(command.OperationID, update.PhaseFailed, "managed update failed")
			r.admissionMu.Unlock()
			return firstError(err, r.sendUpdateReceipt(failed))
		}
		r.admissionMu.Unlock()
		statusErr := r.sendStatus()
		acceptedErr := r.sendUpdateReceipt(*decision.Receipt)
		r.wg.Add(1)
		go r.executeUpdate(updateCtx, cancel, command, configuration)
		return firstError(statusErr, acceptedErr)
	default:
		r.admissionMu.Unlock()
		return errors.New("invalid update admission decision")
	}
}

// beginManagedUpdate starts the managed update a 426 handshake rejection demands and reports whether
// the daemon may keep running. It reuses the admission bookkeeping, runner, receipts and shutdown
// cancellation of a server-sent UPDATE, but the operation id is generated locally because no UPDATE
// frame exists. An update that is already active or already prepared keeps the daemon alive; only a
// rejection the daemon cannot act on, or an update that never prepared, stops it.
func (r *Runtime) beginManagedUpdate(ctx context.Context, targetVersion string) bool {
	runner := r.options.Update
	if targetVersion == "" || runner == nil || !runner.Configured() {
		if r.options.ReportError != nil {
			r.options.ReportError(errProtocolUpgradeRequired)
		}
		return false
	}
	operationID, err := newUpdateOperationID()
	if err != nil {
		if r.options.ReportError != nil {
			r.options.ReportError(err)
		}
		return false
	}
	command := update.Command{OperationID: operationID, TargetVersion: targetVersion}
	r.mu.RLock()
	configuration := cloneDaemonConfig(r.config)
	r.mu.RUnlock()
	// A rejection-driven upgrade is not turned down by in-flight work: like the TS beginUpgrade it
	// hands the runner the stop signal instead of waiting for admission to free up.
	r.admissionMu.Lock()
	decision, err := r.updateOps.Decide(command, r.options.DaemonVersion, true, "")
	if err != nil {
		r.admissionMu.Unlock()
		if r.options.ReportError != nil {
			r.options.ReportError(err)
		}
		return false
	}
	if decision.Action != update.ActionAdmit {
		// The target is already installed, the same target is already being handled, or the daemon is
		// busy. None of those is a reason to stop: no update is started twice.
		r.admissionMu.Unlock()
		return true
	}
	updateCtx, cancel := context.WithCancel(ctx)
	r.updateMu.Lock()
	r.updateCancel = cancel
	r.updateMu.Unlock()
	if err := r.status.BeginUpdate(command.TargetVersion); err != nil {
		cancel()
		_, _ = r.updateOps.Complete(command.OperationID, update.PhaseFailed, "managed update failed")
		r.admissionMu.Unlock()
		if r.options.ReportError != nil {
			r.options.ReportError(err)
		}
		return false
	}
	r.admissionMu.Unlock()
	_ = r.sendStatus()
	_ = r.sendUpdateReceipt(*decision.Receipt)
	r.wg.Add(1)
	go r.executeUpgradeUpdate(updateCtx, cancel, command, configuration)
	return true
}

// executeUpgradeUpdate runs one rejection-triggered update and stops the daemon when the installer
// never accepted the handover, mirroring the TS onUnprepared callback.
func (r *Runtime) executeUpgradeUpdate(ctx context.Context, cancel context.CancelFunc, command update.Command, configuration config.DaemonConfig) {
	if r.runUpdate(ctx, cancel, command, configuration) == updateRunFailed {
		r.stopRun(errProtocolUpgradeRequired)
	}
}

func (r *Runtime) updateBusyReason(runner update.Runner) string {
	r.callsMu.Lock()
	protected := len(r.calls) > 0
	r.callsMu.Unlock()
	if protected {
		return "protected execution is still running"
	}
	if r.options.IDECommandCount != nil && r.options.IDECommandCount() > 0 {
		return "IDE command is in progress"
	}
	if r.options.IDECanStartUpdate != nil && !r.options.IDECanStartUpdate() {
		return "IDE views, cleanup or data streams are active"
	}
	if runner != nil {
		return runner.BusyReason()
	}
	return ""
}

func (r *Runtime) executeUpdate(ctx context.Context, cancel context.CancelFunc, command update.Command, configuration config.DaemonConfig) {
	r.runUpdate(ctx, cancel, command, configuration)
}

// runUpdate drives one admitted managed update through the runner and publishes its receipts. It
// reports whether the installer accepted the handover, which the 426 path uses to decide whether the
// daemon may keep running.
func (r *Runtime) runUpdate(ctx context.Context, cancel context.CancelFunc, command update.Command, configuration config.DaemonConfig) updateRunResult {
	defer r.wg.Done()
	defer cancel()
	runner := r.options.Update
	if runner == nil {
		r.finishUpdate(command.OperationID, update.PhaseFailed, "managed update is not configured")
		return updateRunFailed
	}
	if err := runner.StopForUpdate(ctx); err != nil {
		if ctx.Err() == nil {
			message := "capability execution failed"
			if !errors.Is(err, context.Canceled) && strings.TrimSpace(err.Error()) != "" {
				message = safeUpdateError(err)
			}
			r.finishUpdate(command.OperationID, update.PhaseFailed, message)
		}
		return updateRunFailed
	}
	outcome, err := runner.HandleUpdate(ctx, command, configuration, func(progress update.Progress) {
		if active, ok := r.updateOps.Active(); !ok || active.Command.OperationID != command.OperationID {
			return
		}
		value := UpdateProgress{TargetVersion: command.TargetVersion, DownloadedBytes: progress.DownloadedBytes, TotalBytes: progress.TotalBytes}
		if err := r.status.UpdateProgress(value); err == nil {
			_ = r.sendStatus()
		}
	})
	if ctx.Err() != nil {
		return updateRunCancelled
	}
	if err != nil || outcome.Phase == update.PhaseFailed || outcome.Phase != update.PhasePrepared {
		message := "capability execution failed"
		if err != nil {
			message = safeUpdateError(err)
		} else if outcome.Phase == update.PhaseFailed {
			message = outcome.Message
			if message == "" {
				message = "managed update failed"
			} else {
				message = safeUpdateError(errors.New(message))
			}
		}
		r.finishUpdate(command.OperationID, update.PhaseFailed, message)
		return updateRunFailed
	}
	receipt, ok := r.updateOps.Complete(command.OperationID, update.PhasePrepared, outcome.Message)
	if !ok {
		return updateRunFailed
	}
	var stopWatching func()
	if outcome.WatchFailure != nil {
		stopWatching = outcome.WatchFailure(func(message string) {
			r.failActiveUpdate(command.OperationID, safeUpdateError(errors.New(message)))
		})
	}
	r.updateMu.Lock()
	if active, ok := r.updateOps.Active(); !ok || active.Command.OperationID != command.OperationID {
		r.updateMu.Unlock()
		if stopWatching != nil {
			stopWatching()
		}
		return updateRunFailed
	}
	r.updateCancel = nil
	r.updatePermit = outcome.ReleasePermit
	r.updateWatch = stopWatching
	r.updateMu.Unlock()
	_ = r.sendStatus()
	_ = r.deliverUpdateReceipt(receipt)
	return updateRunPrepared
}

// updateRunResult distinguishes a prepared handover from a failure the daemon must react to and from
// an update the shutdown cancelled, which is not a failure.
type updateRunResult int

const (
	updateRunPrepared updateRunResult = iota
	updateRunFailed
	updateRunCancelled
)

// newUpdateOperationID creates the canonical operation identity for a locally demanded update; a 426
// rejection carries no UPDATE envelope, so no platform-supplied id exists.
func newUpdateOperationID() (string, error) {
	var id [16]byte
	if _, err := rand.Read(id[:]); err != nil {
		return "", err
	}
	id[6] = id[6]&0x0f | 0x40
	id[8] = id[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", id[0:4], id[4:6], id[6:8], id[8:10], id[10:16]), nil
}

func (r *Runtime) finishUpdate(operationID string, phase update.Phase, message string) {
	receipt, ok := r.updateOps.Complete(operationID, phase, message)
	if !ok {
		return
	}
	r.status.EndUpdate()
	r.clearUpdateWatch()
	_ = r.sendStatus()
	_ = r.sendUpdateReceipt(receipt)
}

func (r *Runtime) failActiveUpdate(operationID, message string) {
	active, ok := r.updateOps.Active()
	if !ok || active.Command.OperationID != operationID {
		return
	}
	receipt, ok := r.updateOps.FailActive(message)
	if !ok {
		return
	}
	r.status.EndUpdate()
	r.clearUpdateWatch()
	_ = r.sendStatus()
	_ = r.sendUpdateReceipt(receipt)
}

func (r *Runtime) clearUpdateWatch() {
	r.updateMu.Lock()
	stop := r.updateWatch
	r.updateWatch = nil
	r.updateCancel = nil
	r.updatePermit = nil
	r.updateMu.Unlock()
	if stop != nil {
		stop()
	}
}

func (r *Runtime) sendUpdateReceipt(receipt update.Receipt) error {
	phase := protocol.UpdatePhase_UPDATE_PHASE_UNSPECIFIED
	switch receipt.Phase {
	case update.PhaseAccepted:
		phase = protocol.UpdatePhase_UPDATE_ACCEPTED
	case update.PhasePrepared:
		phase = protocol.UpdatePhase_UPDATE_PREPARED
	case update.PhaseFailed:
		phase = protocol.UpdatePhase_UPDATE_FAILED
	default:
		return errors.New("invalid update receipt phase")
	}
	result := &protocol.UpdateResult{OperationId: stringPtr(receipt.Command.OperationID), Phase: phase}
	if receipt.Message != "" {
		result.Message = stringPtr(receipt.Message)
	}
	return r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_UPDATE_RESULT, Payload: &protocol.Envelope_UpdateResult{UpdateResult: result}}, true)
}

func (r *Runtime) deliverUpdateReceipt(receipt update.Receipt) error {
	resultErr := r.sendUpdateReceipt(receipt)
	releaseErr := r.releaseUpdatePermit()
	return firstError(resultErr, releaseErr)
}

func (r *Runtime) releaseUpdatePermit() error {
	r.updateMu.Lock()
	permit := r.updatePermit
	r.updateMu.Unlock()
	if permit == nil {
		return nil
	}
	if err := permit(); err != nil {
		if r.options.ReportError != nil {
			r.options.ReportError(err)
		}
	}
	return nil
}

func (r *Runtime) resendUpdateReceipt() error {
	receipt, ok := r.updateOps.ReceiptToResend()
	if !ok {
		return nil
	}
	resultErr := r.sendUpdateReceipt(receipt)
	var releaseErr error
	if receipt.Phase == update.PhasePrepared {
		releaseErr = r.releaseUpdatePermit()
	}
	return firstError(resultErr, releaseErr)
}

func firstError(errors ...error) error {
	for _, err := range errors {
		if err != nil {
			return err
		}
	}
	return nil
}

func safeUpdateError(err error) string {
	if err == nil || strings.TrimSpace(err.Error()) == "" {
		return "capability execution failed"
	}
	return err.Error()
}

func (r *Runtime) stopUpdateForShutdown() {
	r.updateMu.Lock()
	cancel := r.updateCancel
	stop := r.updateWatch
	r.updateCancel = nil
	r.updateWatch = nil
	r.updateMu.Unlock()
	if cancel != nil {
		cancel()
	}
	if stop != nil {
		stop()
	}
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
	tier, tierErr := mapImageInputTier(invoke.Invoke.ImageInputTier)
	if tierErr != nil {
		return r.rejectCall(id, tierErr.Error())
	}
	budget := invoke.Invoke.GetMaxOutputBytes()
	if invoke.Invoke.MaxOutputBytes == nil || budget <= 0 {
		return r.rejectCall(id, "maxOutputBytes must be a positive integer")
	}
	capabilityID := invoke.Invoke.GetCapabilityId()
	if capabilityID == "" {
		return r.rejectCall(id, "capabilityId is required")
	}
	if invoke.Invoke.ExpectedFile != nil && capabilityID != "fs.edit" && capabilityID != "fs.write" {
		return r.rejectCall(id, "expectedFile is only valid for fs.edit and fs.write")
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
	r.admissionMu.Lock()
	if r.admissionPaused {
		r.admissionMu.Unlock()
		return r.rejectCall(id, "environment configuration is switching; new work is not admitted")
	}
	if _, held := r.updateOps.Active(); held {
		r.admissionMu.Unlock()
		return r.rejectCall(id, "environment update admission is held")
	}
	r.callsMu.Lock()
	if len(r.calls) >= r.options.MaxConcurrentInvocations {
		r.callsMu.Unlock()
		r.admissionMu.Unlock()
		return r.rejectCall(id, "too many concurrent capability invocations")
	}
	millis := invoke.Invoke.GetTimeoutMillis()
	if millis < 0 || millis > int64((time.Duration(1<<63-1))/time.Millisecond) {
		r.callsMu.Unlock()
		r.admissionMu.Unlock()
		return r.rejectCall(id, "timeoutMillis must be a non-negative duration")
	}
	var callCtx context.Context
	var cancel context.CancelFunc
	if millis > 0 {
		callCtx, cancel = context.WithTimeout(ctx, time.Duration(millis)*time.Millisecond)
	} else {
		callCtx, cancel = context.WithCancel(ctx)
	}
	active := &runningCall{cancel: cancel, maxOutputBytes: budget}
	r.calls[id] = active
	r.wg.Add(1)
	r.callsMu.Unlock()
	r.admissionMu.Unlock()
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
	r.mu.RUnlock()
	call := CapabilityCall{InvocationID: id, CapabilityID: capabilityID, Arguments: arguments, TimeoutMillis: invoke.Invoke.GetTimeoutMillis(), MaxOutputBytes: budget, ImageInputTier: tier}
	if f := invoke.Invoke.ExpectedFile; f != nil {
		call.ExpectedFile = &ExpectedFile{Path: f.GetPath(), SHA256: f.GetSha256()}
	}
	resources := r.options.Resources
	capctx := CapabilityContext{Config: snapshot, Processes: r.options.Processes, FileChanged: r.options.FileChanged, Resources: resources, Progress: func(progress CapabilityResult) error { return r.sendProgress(id, progress) }, ConfirmExecutionExit: func() { r.noteExecutionExit(id) }}
	go r.execute(callCtx, id, call, executor, capctx, budget, active)
	return nil
}

func (r *Runtime) execute(ctx context.Context, id string, call CapabilityCall, executor CapabilityExecutor, capctx CapabilityContext, budget int64, active *runningCall) {
	defer r.wg.Done()
	defer active.cancel()
	defer r.publishRequestedExit(id)
	defer func() { r.callsMu.Lock(); delete(r.calls, id); r.callsMu.Unlock() }()
	result, err := invokeCapability(ctx, call, capctx, executor)
	r.callsMu.Lock()
	progressError := active.progressError
	r.callsMu.Unlock()
	if progressError != "" {
		_ = r.finish(id, protocol.MessageType_FAILED, &protocol.Envelope_Failed{Failed: &protocol.Failed{Message: stringPtr(progressError)}}, InvocationFailed)
		return
	}
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
	if message, failed := failedCapabilityResultMessage(result); failed {
		_ = r.finish(id, protocol.MessageType_FAILED, &protocol.Envelope_Failed{Failed: &protocol.Failed{Message: stringPtr(message)}}, InvocationFailed)
		return
	}
	if cancelledCapabilityResult(result) {
		_ = r.finish(id, protocol.MessageType_CANCELLED, &protocol.Envelope_Cancelled{Cancelled: &protocol.Cancelled{Reason: stringPtr("cancelled")}}, InvocationCancelled)
		return
	}
	encoded, encodeErr := r.encodeResult(ctx, id, result, budget, false)
	if encodeErr != nil {
		var uploadFailure *resourceUploadFailure
		if errors.As(encodeErr, &uploadFailure) && !uploadFailure.cleanupConfirmed {
			r.callsMu.Lock()
			r.exitCleanupUnconfirmed[id] = true
			r.callsMu.Unlock()
		}
		_ = r.finish(id, protocol.MessageType_FAILED, &protocol.Envelope_Failed{Failed: &protocol.Failed{Message: stringPtr(encodeErr.Error())}}, InvocationFailed)
		return
	}
	if err := r.finish(id, protocol.MessageType_COMPLETED, &protocol.Envelope_Completed{Completed: &protocol.Completed{Result: encoded}}, InvocationCompleted); err != nil {
		return
	}
}

func failedCapabilityResultMessage(result CapabilityResult) (string, bool) {
	if !result.Error {
		return "", false
	}
	var details struct {
		Code string `json:"code"`
	}
	if err := json.Unmarshal(result.Details, &details); err != nil || details.Code != "FAILED" {
		return "", false
	}
	for _, content := range result.Contents {
		if content.Type == "text" && content.Text != "" {
			return content.Text, true
		}
	}
	return "capability execution failed", true
}

// cancelledCapabilityResult reports an executor-observed CALLER cancellation. Executors
// finish their cleanup and preserve partial output before returning this result, so the
// runtime arbitrates it into the CANCELLED terminal instead of COMPLETED(error=true).
func cancelledCapabilityResult(result CapabilityResult) bool {
	if !result.Error {
		return false
	}
	var details struct {
		Code string `json:"code"`
	}
	return json.Unmarshal(result.Details, &details) == nil && details.Code == "CANCELLED"
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
			actual := size
			if size <= maxSignedInt64-total {
				actual += total
			} else {
				actual = maxSignedInt64
			}
			return nil, errors.New(toolOutputExceededMessage(actual, budget))
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
				var uploadErr *ResourceUploadError
				cleanupConfirmed := errors.As(err, &uploadErr) && uploadErr.CleanupConfirmed
				return nil, &resourceUploadFailure{cleanupConfirmed: cleanupConfirmed}
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
		r.mu.RLock()
		ready := r.ready
		r.mu.RUnlock()
		if ready {
			return r.replay(entry)
		}
	}
	return nil
}
func (r *Runtime) handleRecover(e *protocol.Envelope) error {
	if e.InvocationId == nil {
		return nil
	}
	id := *e.InvocationId
	r.mu.RLock()
	ready := r.ready
	r.mu.RUnlock()
	entry, ok := r.journal.Get(id)
	if !ready {
		if !ok || entry.State != InvocationRunning {
			return nil
		}
		r.callsMu.Lock()
		_, active := r.calls[id]
		r.callsMu.Unlock()
		if !active {
			return nil
		}
		return r.replay(entry)
	}
	if !ok {
		return r.sendRecoverNotFound(id)
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
	if err := r.sendBound(&frame, false); err != nil {
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
	// A delivered terminal ends protected execution immediately; update admission must
	// never observe a call whose result the client has already received.
	r.callsMu.Lock()
	delete(r.calls, id)
	r.callsMu.Unlock()
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
	return r.sendBound(frame, false)
}
func (r *Runtime) sendStarted(id string, replayed bool) error {
	started := &protocol.Started{}
	if replayed {
		started.Replayed = boolPtr(true)
	}
	return r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_STARTED, InvocationId: stringPtr(id), Payload: &protocol.Envelope_Started{Started: started}}, false)
}
func (r *Runtime) sendProgress(id string, result CapabilityResult) error {
	entry, ok := r.journal.Get(id)
	if !ok || entry.State != InvocationRunning {
		return nil
	}
	r.callsMu.Lock()
	active := r.calls[id]
	r.callsMu.Unlock()
	if active == nil {
		return nil
	}
	encoded, err := r.encodeResult(context.Background(), id, result, active.maxOutputBytes, true)
	if err != nil {
		r.callsMu.Lock()
		active.progressError = err.Error()
		r.callsMu.Unlock()
		active.cancel()
		return err
	}
	return r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_PROGRESS, InvocationId: stringPtr(id), Payload: &protocol.Envelope_Progress{Progress: &protocol.Progress{Result: encoded}}}, false)
}
func (r *Runtime) sendRecoverNotFound(id string) error {
	return r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_RECOVER_NOT_FOUND, InvocationId: stringPtr(id), Payload: &protocol.Envelope_RecoverNotFound{RecoverNotFound: &protocol.RecoverNotFound{}}}, false)
}
func (r *Runtime) sendExecutionExited(id string) error {
	return r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_EXECUTION_EXITED, InvocationId: stringPtr(id), Payload: &protocol.Envelope_ExecutionExited{ExecutionExited: &protocol.ExecutionExited{}}}, false)
}
func (r *Runtime) sendError(id *string, message string) error {
	return r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_ERROR, InvocationId: id, Payload: &protocol.Envelope_Error{Error: &protocol.Error{Message: stringPtr(message)}}}, true)
}
func (r *Runtime) sendBoundError(id *string, message string) error {
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
		body.Update = &protocol.UpdateProgress{TargetVersion: stringPtr(progress.TargetVersion), DownloadedBytes: int64Ptr(progress.DownloadedBytes), TotalBytes: progress.TotalBytes}
	}
	return &protocol.RuntimeSnapshot{Revision: int64Ptr(status.Revision), Status: body}
}
func (r *Runtime) sendReady() error {
	if err := r.sendFrame(&protocol.Envelope{MessageType: protocol.MessageType_READY, EnvironmentId: stringPtr(r.options.EnvironmentID), Payload: &protocol.Envelope_Ready{Ready: &protocol.Ready{DaemonVersion: stringPtr(r.options.DaemonVersion), HostInfo: r.hostInfo, RuntimeStatus: r.runtimeSnapshot()}}}); err != nil {
		return err
	}
	r.admissionMu.Lock()
	r.admissionPaused = false
	r.configReconnectPending = false
	r.candidateSuspension = nil
	r.admissionMu.Unlock()
	return r.resendUpdateReceipt()
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
	if err := ctx.Err(); err != nil {
		return err
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
			activeConnection := r.connection
			r.mu.RUnlock()
			if !bound || activeConnection == nil {
				continue
			}
			heartbeat := &protocol.Heartbeat{RuntimeStatus: r.runtimeSnapshot()}
			if ready && r.options.IDE != nil && r.options.IDE.HasActiveViews() {
				round, _ := r.newIDELeaseRound()
				heartbeat.IdeLeaseRound = int64Ptr(round)
			} else {
				r.clearIDELeaseRound()
			}
			if err := r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_HEARTBEAT, Payload: &protocol.Envelope_Heartbeat{Heartbeat: heartbeat}}, false); err != nil {
				r.clearIDELeaseRound()
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
	cleanupUnconfirmed := r.exitCleanupUnconfirmed[id]
	delete(r.exitRequested, id)
	delete(r.exitCleanupUnconfirmed, id)
	r.callsMu.Unlock()
	if requested && !cleanupUnconfirmed && r.journal.ConfirmExit(id) {
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

func mapImageInputTier(value *protocol.ImageInputTier) (ImageInputTier, error) {
	if value == nil || *value == protocol.ImageInputTier_IMAGE_INPUT_TIER_UNSPECIFIED {
		return "", errors.New("imageInputTier is required")
	}
	switch *value {
	case protocol.ImageInputTier_P720:
		return ImageInputTierP720, nil
	case protocol.ImageInputTier_P1080:
		return ImageInputTierP1080, nil
	case protocol.ImageInputTier_ORIGINAL:
		return ImageInputTierOriginal, nil
	default:
		return "", errors.New("imageInputTier is invalid")
	}
}

func sameOptionalString(left, right *string) bool {
	if left == nil || right == nil {
		return left == nil && right == nil
	}
	return *left == *right
}
func boolPtr(b bool) *bool    { return &b }
func int64Ptr(v int64) *int64 { return &v }
