package runtime

import (
	"context"
	"errors"
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
	InvocationJournalPath    string
	MaxConcurrentInvocations int
	HeartbeatInterval        time.Duration
	IDECommandTimeout        time.Duration
	HostInfo                 *protocol.HostInfo
	Update                   update.Runner
	ReportError              func(error)
	IDE                      IDEService
}

// Runtime owns one bound control connection, its handshake gate, in-flight calls and invocation journal.
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

// NewRuntime validates required boundaries and creates a runtime with an optional durable invocation journal.
func NewRuntime(options RuntimeOptions) (*Runtime, error) {
	if options.EnvironmentID == "" || options.Connections == nil || options.ApplyConfig == nil && options.EnvironmentConfig == nil {
		return nil, errors.New("runtime requires environmentId, connection factory, and configuration applier")
	}
	if err := applyOptionDefaults(&options); err != nil {
		return nil, err
	}
	hostInfo, err := resolveHostInfo(options.HostInfo)
	if err != nil {
		return nil, err
	}
	var journal *InvocationJournal
	if options.InvocationJournalPath == "" {
		journal, err = NewInvocationJournal(1024)
	} else {
		journal, err = NewPersistentInvocationJournal(options.InvocationJournalPath, 1024)
	}
	if err != nil {
		return nil, err
	}
	runtime := &Runtime{options: options, hostInfo: hostInfo, journal: journal, status: options.Status, config: config.DaemonConfig{EnvironmentID: options.EnvironmentID}, calls: make(map[string]*runningCall), exitRequested: make(map[string]bool), exitCleanupUnconfirmed: make(map[string]bool), ideCommands: make(map[string]*ideCommandCall), resourceRequests: make(map[resourceExchangeKey]chan *protocol.Envelope), updateOps: update.NewOperations(), resourcePolicyChanged: make(chan struct{}, 1), shutdown: newShutdownFailureTracker(nil)}
	runtime.bindOptionCallbacks()
	if options.EnvironmentConfig != nil {
		runtime.bindEnvironmentHooks(options.EnvironmentConfig)
	}
	return runtime, nil
}

// applyOptionDefaults validates the option set and fills every unset bound with its documented
// default; the status tracker is shared with the environment config runtime when one is injected.
func applyOptionDefaults(options *RuntimeOptions) error {
	if options.Status == nil {
		if options.EnvironmentConfig != nil {
			options.Status = options.EnvironmentConfig.StatusTracker()
		} else {
			options.Status = NewStatusTracker()
		}
	}
	if options.EnvironmentConfig != nil {
		if options.EnvironmentConfig.StatusTracker() != options.Status {
			return errors.New("runtime and environment config runtime must share a status tracker")
		}
		if options.EnvironmentConfig.Current().EnvironmentID != options.EnvironmentID {
			return errors.New("runtime and environment config runtime must share an environmentId")
		}
	}
	if options.InitialReconnectDelay == 0 {
		options.InitialReconnectDelay = time.Second
	}
	if options.MaxReconnectDelay == 0 {
		options.MaxReconnectDelay = 30 * time.Second
	}
	if options.InitialReconnectDelay < 0 || options.MaxReconnectDelay < options.InitialReconnectDelay {
		return errors.New("invalid reconnect delay bounds")
	}
	if options.MaxConcurrentInvocations == 0 {
		options.MaxConcurrentInvocations = defaultInvocationLimit
	}
	if options.MaxConcurrentInvocations < 1 || options.MaxConcurrentInvocations > 64 {
		return errors.New("maxConcurrentInvocations must be between 1 and 64")
	}
	if options.HeartbeatInterval == 0 {
		options.HeartbeatInterval = 15 * time.Second
	}
	if options.HeartbeatInterval < 0 {
		return errors.New("heartbeat interval must not be negative")
	}
	if options.IDECommandTimeout == 0 {
		options.IDECommandTimeout = 30 * time.Second
	}
	if options.IDECommandTimeout < 0 {
		return errors.New("IDE command timeout must be positive")
	}
	return nil
}

// resolveHostInfo reports the host facts published in READY, cloned so later option changes cannot
// reach the published snapshot.
func resolveHostInfo(hostInfo *protocol.HostInfo) (*protocol.HostInfo, error) {
	if hostInfo == nil {
		return detectHostInfo()
	}
	return proto.Clone(hostInfo).(*protocol.HostInfo), nil
}

// bindOptionCallbacks derives the runtime counts and update admission predicates the IDE service
// exposes when the caller did not inject its own.
func (r *Runtime) bindOptionCallbacks() {
	if r.options.IDECommandCount == nil {
		r.options.IDECommandCount = func() int { return int(r.ideCommandCount.Load()) }
	}
	if r.options.IDECanStartUpdate == nil {
		if admission, ok := r.options.IDE.(IDEUpdateAdmission); ok {
			r.options.IDECanStartUpdate = admission.CanStartUpdate
		}
	}
}

// bindEnvironmentHooks suspends new work while a candidate configuration is prepared and reconnects
// once the applied endpoints change.
func (r *Runtime) bindEnvironmentHooks(environmentConfig *EnvironmentConfigRuntime) {
	environmentConfig.AddSessionHooks(
		func(previous, candidate config.DaemonConfig) {
			if !endpointsChanged(previous, candidate) {
				return
			}
			r.admissionMu.Lock()
			if r.candidateSuspension == nil {
				wasPaused := r.admissionPaused
				r.candidateSuspension = &wasPaused
			}
			r.admissionPaused = true
			r.admissionMu.Unlock()
		},
		func(previous, candidate config.DaemonConfig) {
			if !endpointsChanged(previous, candidate) {
				return
			}
			r.admissionMu.Lock()
			if r.candidateSuspension != nil {
				r.admissionPaused = *r.candidateSuspension
				r.candidateSuspension = nil
			}
			r.admissionMu.Unlock()
		},
		func(previous, next config.DaemonConfig) {
			reconnect := endpointsChanged(previous, next)
			r.admissionMu.Lock()
			r.admissionPaused = reconnect
			r.configReconnectPending = reconnect
			r.candidateSuspension = nil
			r.admissionMu.Unlock()
			if reconnect {
				r.dropActiveConnection()
			}
		},
	)
}

// endpointsChanged reports whether a configuration swap changes the control connection target.
func endpointsChanged(previous, next config.DaemonConfig) bool {
	return previous.GatewayURL != next.GatewayURL || !sameOptionalString(previous.ProxyURL, next.ProxyURL)
}

// ShutdownFailures returns the shutdown release and diagnostic failures recorded by this runtime.
func (r *Runtime) ShutdownFailures() []error {
	return r.shutdown.snapshot()
}
