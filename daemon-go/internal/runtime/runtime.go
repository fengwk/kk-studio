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
	MaxConcurrentInvocations int
	HeartbeatInterval        time.Duration
	IDECommandTimeout        time.Duration
	HostInfo                 *protocol.HostInfo
	Update                   update.Runner
	ReportError              func(error)
	IDE                      IDEService
}

// Runtime owns one bound control connection, its handshake gate, in-flight calls and in-memory journal.
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
