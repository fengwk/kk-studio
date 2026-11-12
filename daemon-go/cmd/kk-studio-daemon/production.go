package main

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"runtime"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/capability"
	"github.com/fengwk/kk-studio/daemon/internal/coding"
	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/ide"
	"github.com/fengwk/kk-studio/daemon/internal/lsp"
	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	daemonruntime "github.com/fengwk/kk-studio/daemon/internal/runtime"
	"github.com/fengwk/kk-studio/daemon/internal/skill"
	"github.com/fengwk/kk-studio/daemon/internal/transport"
	"github.com/fengwk/kk-studio/daemon/internal/update"
)

type productionRuntime struct{}

func (productionRuntime) Run(ctx context.Context, initial config.DaemonConfig) (runErr error) {
	if _, err := config.ReleaseKey(runtime.GOOS, runtime.GOARCH); err != nil {
		return productionFailure("daemon platform is unsupported", err)
	}
	processes, err := native.NewSupervisor()
	if err != nil {
		return productionFailure("native process supervision is unavailable", err)
	}
	defer func() {
		recordCleanupFailure(&runErr, "native process supervision shutdown failed", processes.Close(context.Background()))
	}()
	if err := native.EnsurePrivateDirectory(initial.DataDir); err != nil {
		return productionFailure("daemon data directory is unavailable", err)
	}
	directoryLock, err := native.AcquireDirectoryLock(initial.DataDir, native.DaemonLockFileName)
	if err != nil {
		return productionFailure("daemon directory lock is unavailable", err)
	}
	defer func() { recordCleanupFailure(&runErr, "daemon directory lock release failed", directoryLock.Close()) }()
	tracker, ok := processes.(native.DescendantTracker)
	if !ok {
		return productionFailure("native descendant tracking is unavailable", errors.New("supervisor does not implement descendant tracking"))
	}
	instanceID, err := newInstanceID()
	if err != nil {
		return productionFailure("daemon instance identity could not be created", err)
	}
	lspFacade := lsp.NewFacade(processes, lsp.PoolOptions{})
	initialLSP, err := lspFacade.Prepare(initial.LSP)
	if err != nil {
		return productionFailure("initial language-server configuration is invalid", err)
	}
	if err := initialLSP.Publish(); err != nil {
		return productionFailure("initial language-server configuration could not be published", err)
	}
	defer func() { recordCleanupFailure(&runErr, "language-server shutdown failed", lspFacade.Close()) }()

	ideService, err := ide.NewService(initial, processes, tracker, ide.ServiceOptions{DaemonInstanceID: instanceID})
	if err != nil {
		return productionFailure("managed IDE service could not be initialized", err)
	}
	defer func() {
		closeCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		recordCleanupFailure(&runErr, "managed IDE shutdown failed", ideService.Close(closeCtx))
	}()

	status := daemonruntime.NewStatusTracker()
	environmentConfig, err := daemonruntime.NewEnvironmentConfigRuntime(daemonruntime.EnvironmentConfigOptions{
		Initial: initial,
		Status:  status,
		Prepare: func(_ context.Context, candidate config.DaemonConfig, _ *config.DaemonConfig) (daemonruntime.PreparedConfiguration, error) {
			resolvedBash, err := config.ResolveBash(candidate.BashExecutable)
			if err != nil {
				return nil, fmt.Errorf("configured Bash executable is unavailable: %w", err)
			}
			candidate.BashExecutable = &resolvedBash
			proxyURL := ""
			if candidate.ProxyURL != nil {
				proxyURL = *candidate.ProxyURL
			}
			if _, err := transport.NewProxyPolicy(proxyURL); err != nil {
				return nil, fmt.Errorf("configured proxy is invalid: %w", err)
			}
			generation, err := lspFacade.Prepare(candidate.LSP)
			if err != nil {
				return nil, err
			}
			return &preparedEnvironment{config: candidate, lsp: generation}, nil
		},
		Report: func(err error) { slog.Error("environment configuration could not be applied", "error", err) },
	})
	if err != nil {
		return productionFailure("environment configuration runtime could not be initialized", err)
	}

	connections := connectionFactory(func(ctx context.Context) (daemonruntime.Connection, error) {
		applied := environmentConfig.Current()
		return transport.Dial(ctx, transport.DialConfig{
			Applied: applied, InstanceID: instanceID, Platform: runtime.GOOS,
			Resolver: ideService,
		})
	})
	resourceControl := &runtimeResourceControl{}
	capabilities := coding.Provider{LSPStatus: func(file string) (string, bool) {
		support := lspFacade.Support(file)
		if support.Language == nil {
			return "", false
		}
		return *support.Language, support.Available && support.Supported
	}}.Capabilities()
	for id, handler := range lspFacade.Capabilities() {
		capabilities[id] = handler
	}
	capabilities["skill.install"] = skillCapability(environmentConfig.Current, resourceControl)
	declarations, err := capability.Declare(capabilities)
	if err != nil {
		return productionFailure("daemon capability declarations are incomplete", err)
	}
	capabilities = make(map[string]daemonruntime.CapabilityExecutor, len(declarations))
	for _, declaration := range declarations {
		capabilities[declaration.ID] = declaration.Execute
	}
	updateRunner := &productionUpdateRunner{current: environmentConfig.Current, stop: func(ctx context.Context) error {
		return processes.Close(ctx)
	}}
	uploader := dynamicResourceUploader{current: environmentConfig.Current, control: resourceControl}
	core, err := daemonruntime.NewRuntime(daemonruntime.RuntimeOptions{
		EnvironmentID:     initial.EnvironmentID,
		DaemonVersion:     config.DaemonVersion,
		Connections:       connections,
		Capabilities:      capabilities,
		Processes:         processes,
		FileChanged:       lspFacade.FileChanged,
		Resources:         uploader,
		EnvironmentConfig: environmentConfig,
		Status:            status,
		Update:            updateRunner,
		IDE:               ideService,
		ReportError:       func(err error) { slog.Error("daemon runtime operation failed", "error", err) },
	})
	if err != nil {
		return productionFailure("daemon runtime could not be initialized", err)
	}
	resourceControl.runtime = core
	if err := core.Run(ctx); err != nil {
		return productionFailure("daemon runtime execution failed", err)
	}
	return nil
}

func productionFailure(message string, cause error) error {
	if cause == nil {
		cause = errors.New("unspecified failure")
	}
	slog.Error("daemon production setup failed", "operation", message, "error", cause)
	return fmt.Errorf("%s: %w", message, cause)
}

func recordCleanupFailure(runErr *error, message string, cause error) {
	if cause == nil {
		return
	}
	slog.Error("daemon resource cleanup failed", "operation", message, "error", cause)
	*runErr = errors.Join(*runErr, fmt.Errorf("%s: %w", message, cause))
}

type connectionFactory func(context.Context) (daemonruntime.Connection, error)

func (f connectionFactory) Connect(ctx context.Context) (daemonruntime.Connection, error) {
	return f(ctx)
}

type preparedEnvironment struct {
	config config.DaemonConfig
	lsp    *lsp.PreparedGeneration
}

func (p *preparedEnvironment) Config() config.DaemonConfig { return p.config }
func (p *preparedEnvironment) Publish() (func() error, error) {
	if err := p.lsp.Publish(); err != nil {
		return nil, err
	}
	return func() error { return nil }, nil
}
func (p *preparedEnvironment) Retire() error { return p.lsp.Retire() }

type runtimeResourceControl struct {
	runtime *daemonruntime.Runtime
}

func (c *runtimeResourceControl) RequestTicket(ctx context.Context, invocationID string, request *protocol.ResourceUploadRequest) (*protocol.ResourceUploadTicket, error) {
	if c.runtime == nil {
		return nil, errors.New("resource control is unavailable")
	}
	return c.runtime.RequestTicket(ctx, invocationID, request)
}
func (c *runtimeResourceControl) Commit(ctx context.Context, invocationID, transferID, uploadID string) (*protocol.ResourceUploadTicket, error) {
	if c.runtime == nil {
		return nil, errors.New("resource control is unavailable")
	}
	return c.runtime.Commit(ctx, invocationID, transferID, uploadID)
}

type ticketRequester struct {
	runtime      *daemonruntime.Runtime
	invocationID string
}

func (r ticketRequester) RequestTicket(ctx context.Context, transferID string) (*protocol.ResourceDownloadTicket, error) {
	return r.runtime.RequestResourceDownloadTicket(ctx, r.invocationID, transferID)
}

type dynamicResourceUploader struct {
	current func() config.DaemonConfig
	control *runtimeResourceControl
}

func (u dynamicResourceUploader) Upload(ctx context.Context, invocationID string, content daemonruntime.ResultContent) (daemonruntime.ResultContent, error) {
	policy, err := proxyPolicy(u.current())
	if err != nil {
		return daemonruntime.ResultContent{}, err
	}
	network := transport.NewNetworkRuntime(policy)
	defer network.Close(context.Background())
	return (&transport.ResourceUploader{Control: u.control, Network: network}).Upload(ctx, invocationID, content)
}

func skillCapability(current func() config.DaemonConfig, control *runtimeResourceControl) daemonruntime.CapabilityExecutor {
	return func(ctx context.Context, call daemonruntime.CapabilityCall, _ daemonruntime.CapabilityContext) (daemonruntime.CapabilityResult, error) {
		applied := current()
		policy, err := proxyPolicy(applied)
		if err != nil {
			return skillFailure(call.InvocationID, "skill.install: configured proxy is invalid"), nil
		}
		network := transport.NewNetworkRuntime(policy)
		defer network.Close(context.Background())
		installer := skill.Installer{DataDir: applied.DataDir, Downloader: &transport.ResourceDownloader{
			Network: network, Requester: ticketRequester{runtime: control.runtime, invocationID: call.InvocationID},
		}}
		result, _, err := installer.Install(ctx, json.RawMessage(call.Arguments))
		if err != nil {
			return skillFailure(call.InvocationID, err.Error()), nil
		}
		return daemonruntime.CapabilityResult{CallID: call.InvocationID, Details: result, Contents: []daemonruntime.ResultContent{{Type: "text", Text: string(result)}}}, nil
	}
}

func skillFailure(callID, message string) daemonruntime.CapabilityResult {
	return daemonruntime.CapabilityResult{CallID: callID, Error: true, Details: json.RawMessage(`{"code":"FAILED"}`), Contents: []daemonruntime.ResultContent{{Type: "text", Text: message}}}
}

type productionUpdateRunner struct {
	current func() config.DaemonConfig
	stop    func(context.Context) error
}

func (r *productionUpdateRunner) runner() *update.InstallerRunner {
	policy, err := proxyPolicy(r.current())
	if err != nil {
		return nil
	}
	return update.NewInstallerRunner(update.InstallerRunnerOptions{HTTPClient: policy.HTTPClient(), Stop: r.stop})
}
func (r *productionUpdateRunner) Configured() bool {
	runner := r.runner()
	return runner != nil && runner.Configured()
}
func (r *productionUpdateRunner) BusyReason() string                      { return "" }
func (r *productionUpdateRunner) StopForUpdate(ctx context.Context) error { return r.stop(ctx) }
func (r *productionUpdateRunner) HandleUpdate(ctx context.Context, command update.Command, applied config.DaemonConfig, report func(update.Progress)) (update.Outcome, error) {
	policy, err := proxyPolicy(applied)
	if err != nil {
		return update.Outcome{}, err
	}
	runner := update.NewInstallerRunner(update.InstallerRunnerOptions{HTTPClient: policy.HTTPClient(), Stop: r.stop})
	return runner.HandleUpdate(ctx, command, applied, report)
}

func proxyPolicy(applied config.DaemonConfig) (*transport.ProxyPolicy, error) {
	proxyURL := ""
	if applied.ProxyURL != nil {
		proxyURL = *applied.ProxyURL
	}
	return transport.NewProxyPolicy(proxyURL)
}

func newInstanceID() (string, error) {
	var id [16]byte
	if _, err := rand.Read(id[:]); err != nil {
		return "", err
	}
	id[6] = (id[6] & 0x0f) | 0x40
	id[8] = (id[8] & 0x3f) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x", id[0:4], id[4:6], id[6:8], id[8:10], id[10:16]), nil
}

var _ daemonruntime.ConnectionFactory = connectionFactory(nil)
var _ daemonruntime.ResourceUploader = dynamicResourceUploader{}
var _ transport.UploadControl = (*runtimeResourceControl)(nil)
var _ skill.ResourceDownloader = (*transport.ResourceDownloader)(nil)
