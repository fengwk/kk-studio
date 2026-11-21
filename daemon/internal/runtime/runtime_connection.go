package runtime

import (
	"context"
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

type activeConnection struct {
	generation uint64
	connection Connection
	done       chan struct{}
	resources  resourceExchanges
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
	defer r.shutDown(runCtx, cancel, heartbeatDone, sweepDone, &runErr)
	return r.serveGenerations(runCtx)
}

// shutDown stops the background loops, releases every runtime resource in order and joins the
// recorded failures into the run result.
func (r *Runtime) shutDown(runCtx context.Context, cancel context.CancelFunc, heartbeatDone, sweepDone <-chan struct{}, runErr *error) {
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
	*runErr = errors.Join(*runErr, failure, aggregateShutdownFailures(r.ShutdownFailures()))
}

// serveGenerations dials one generation at a time and applies the retry policy between attempts.
func (r *Runtime) serveGenerations(runCtx context.Context) error {
	delay := r.options.InitialReconnectDelay
	for {
		attempt := newControlAttempt(r)
		connection, err := r.options.Connections.Connect(runCtx, attempt)
		if err != nil {
			done, dialErr := r.handleDialFailure(runCtx, attempt, err, delay)
			if done {
				return dialErr
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

// handleDialFailure applies the registration and upgrade policy for one failed attempt and reports
// whether the run loop must stop, along with the error that ends it.
func (r *Runtime) handleDialFailure(runCtx context.Context, attempt *controlAttempt, dialErr error, delay time.Duration) (bool, error) {
	if runCtx.Err() != nil {
		return true, nil
	}
	if errors.Is(dialErr, ErrRegistrationRejected) {
		return true, dialErr
	}
	if rejected, targetVersion := attempt.upgradeRejection(); rejected {
		if !r.beginManagedUpdate(runCtx, targetVersion) {
			return true, errProtocolUpgradeRequired
		}
	}
	if !waitReconnect(runCtx, delay) {
		return true, nil
	}
	return false, nil
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
		if err := r.handleWithIDEContext(ctx, connectionCtx, active, envelope); err != nil {
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
	active := &activeConnection{generation: r.generation, connection: connection, done: make(chan struct{})}
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
	close(active.done)
	active.resources = resourceExchanges{}
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
