package runtime

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// RUN-T-189 passes nil as the preparation predecessor until the first cache candidate is applied.
func TestEnvironmentConfigInitialPreparationUsesNilPrevious(t *testing.T) {
	initial := initialRuntimeConfig()
	preparer := &testConfigurationPreparer{}
	manager := newTestEnvironmentConfigRuntime(t, initial, preparer.prepare, &testCacheWriter{})
	result, err := manager.Submit(context.Background(), initial.ConfigVersion, configurationOf(initial))
	if err != nil || result.Kind != ConfigApplied {
		t.Fatalf("initial submission = %+v, %v", result, err)
	}
	preparer.mu.Lock()
	defer preparer.mu.Unlock()
	if len(preparer.previous) != 1 || preparer.previous[0] != nil {
		t.Fatalf("initial preparation previous snapshots = %+v, want one nil", preparer.previous)
	}
}

// RUN-T-190 persists a version-only change without preparing or publishing a new resource generation.
func TestEnvironmentConfigSameContentOnlyAdvancesVersion(t *testing.T) {
	initial := initialRuntimeConfig()
	preparer, cache := &testConfigurationPreparer{}, &testCacheWriter{}
	manager := newTestEnvironmentConfigRuntime(t, initial, preparer.prepare, cache)
	if result, err := manager.Submit(context.Background(), 1, configurationOf(initial)); err != nil || result.Kind != ConfigApplied {
		t.Fatalf("initial apply = %+v, %v", result, err)
	}
	if result, err := manager.Submit(context.Background(), 2, configurationOf(initial)); err != nil || result.Kind != ConfigApplied {
		t.Fatalf("version advance = %+v, %v", result, err)
	}
	preparer.mu.Lock()
	prepareCalls := len(preparer.previous)
	preparer.mu.Unlock()
	cache.mu.Lock()
	writes := len(cache.writes)
	cache.mu.Unlock()
	if prepareCalls != 1 || writes != 2 || manager.Current().ConfigVersion != 2 {
		t.Fatalf("prepares=%d writes=%d currentVersion=%d", prepareCalls, writes, manager.Current().ConfigVersion)
	}
	if result, err := manager.Submit(context.Background(), 2, configurationOf(initial)); err != nil || result.Kind != ConfigConfirmed {
		t.Fatalf("same-version confirmation = %+v, %v", result, err)
	}
	if result, err := manager.Submit(context.Background(), 1, configurationOf(initial)); err != nil || result.Kind != ConfigStale {
		t.Fatalf("stale version = %+v, %v", result, err)
	}
	if _, err := manager.Submit(context.Background(), 2, changedConfiguration(initial, "https://drift.example")); err == nil || err.Error() != "the same environment config version must not change its content" {
		t.Fatalf("same-version content drift error = %v", err)
	}
}

// RUN-T-191 merges concurrent first-version work and retains only the highest pending snapshot.
func TestEnvironmentConfigInitialQueueKeepsHighestPendingVersion(t *testing.T) {
	initial := initialRuntimeConfig()
	started, release := make(chan struct{}), make(chan struct{})
	var mu sync.Mutex
	var preparedVersions []int64
	prepare := func(_ context.Context, candidate config.DaemonConfig, _ *config.DaemonConfig) (PreparedConfiguration, error) {
		mu.Lock()
		preparedVersions = append(preparedVersions, candidate.ConfigVersion)
		mu.Unlock()
		if candidate.ConfigVersion == 1 {
			close(started)
			<-release
		}
		return &testPreparedConfiguration{config: candidate}, nil
	}
	manager := newTestEnvironmentConfigRuntime(t, initial, prepare, &testCacheWriter{})
	firstDone := make(chan ConfigApplyResult, 1)
	go func() {
		result, _ := manager.Submit(context.Background(), 1, configurationOf(initial))
		firstDone <- result
	}()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("first configuration preparation did not start")
	}
	secondDone := make(chan ConfigApplyResult, 1)
	go func() {
		result, _ := manager.Submit(context.Background(), 2, changedConfiguration(initial, "https://v2.example"))
		secondDone <- result
	}()
	waitForPendingVersion(t, manager, 2)
	thirdDone := make(chan ConfigApplyResult, 1)
	go func() {
		result, _ := manager.Submit(context.Background(), 3, changedConfiguration(initial, "https://v3.example"))
		thirdDone <- result
	}()
	waitForPendingVersion(t, manager, 3)
	close(release)
	for _, done := range []<-chan ConfigApplyResult{firstDone, secondDone, thirdDone} {
		select {
		case result := <-done:
			if result.Kind != ConfigApplied {
				t.Fatalf("coalesced configuration result = %+v", result)
			}
		case <-time.After(time.Second):
			t.Fatal("coalesced configuration request did not settle")
		}
	}
	mu.Lock()
	gotVersions := append([]int64(nil), preparedVersions...)
	mu.Unlock()
	if len(gotVersions) != 2 || gotVersions[0] != 1 || gotVersions[1] != 3 {
		t.Fatalf("prepared versions=%v, want [1 3]", gotVersions)
	}
	if manager.Current().ConfigVersion != 3 {
		t.Fatalf("current config version=%d, want 3", manager.Current().ConfigVersion)
	}
}

// RUN-T-197 retains the applied snapshot after validation failure and records a repairable configuration error.
func TestEnvironmentConfigValidationFailureKeepsCurrentSnapshot(t *testing.T) {
	initial := initialRuntimeConfig()
	manager := newTestEnvironmentConfigRuntime(t, initial, (&testConfigurationPreparer{}).prepare, &testCacheWriter{})
	if result, err := manager.Submit(context.Background(), 1, configurationOf(initial)); err != nil || result.Kind != ConfigApplied {
		t.Fatalf("initial apply = %+v, %v", result, err)
	}
	invalid := configurationOf(initial)
	invalid.StudioUrl = "file:///not-an-origin"
	result, err := manager.Submit(context.Background(), 2, invalid)
	if err != nil || result.Kind != ConfigFailed || result.Code != "VALIDATION_FAILED" || result.Field != "studioUrl" {
		t.Fatalf("invalid candidate = %+v, %v", result, err)
	}
	if manager.Current().ConfigVersion != 1 {
		t.Fatalf("failed candidate changed current version to %d", manager.Current().ConfigVersion)
	}
	failure, ok := manager.options.Status.ConfigurationError()
	if !ok || failure.Version != 2 || failure.Field != "studioUrl" {
		t.Fatalf("configuration status error = %+v, present=%t", failure, ok)
	}
}

// RUN-T-195 retires prepared resources when the preparation result carries a different version.
func TestEnvironmentConfigPreparedVersionMismatchRetiresCandidate(t *testing.T) {
	initial := initialRuntimeConfig()
	candidate := initial
	candidate.ConfigVersion = 99
	prepared := &testPreparedConfiguration{config: candidate}
	manager := newTestEnvironmentConfigRuntime(t, initial, func(_ context.Context, _ config.DaemonConfig, _ *config.DaemonConfig) (PreparedConfiguration, error) {
		return prepared, nil
	}, &testCacheWriter{})
	result, err := manager.Submit(context.Background(), 1, configurationOf(initial))
	if err != nil || result.Kind != ConfigFailed || result.Code != "VALIDATION_FAILED" || result.Field != "" {
		t.Fatalf("mismatched prepared version = %+v, %v", result, err)
	}
	if prepared.publishCalls != 0 || prepared.retireCalls != 1 || manager.HasAppliedConfiguration() {
		t.Fatalf("published=%d retired=%d initialized=%t", prepared.publishCalls, prepared.retireCalls, manager.HasAppliedConfiguration())
	}
}

// RUN-T-199 does not publish a prepared candidate if private cache persistence fails.
func TestEnvironmentConfigPersistenceFailureRetiresCandidate(t *testing.T) {
	initial := initialRuntimeConfig()
	prepared := &testPreparedConfiguration{config: initial}
	preparer := &testConfigurationPreparer{prepared: prepared}
	cache := &testCacheWriter{err: errors.New("disk unavailable")}
	manager := newTestEnvironmentConfigRuntime(t, initial, preparer.prepare, cache)
	result, err := manager.Submit(context.Background(), 1, configurationOf(initial))
	if err != nil || result.Kind != ConfigFailed || result.Code != "PERSIST_FAILED" {
		t.Fatalf("cache failure = %+v, %v", result, err)
	}
	if prepared.publishCalls != 0 || prepared.retireCalls != 1 || manager.HasAppliedConfiguration() {
		t.Fatalf("published=%d retired=%d initialized=%t", prepared.publishCalls, prepared.retireCalls, manager.HasAppliedConfiguration())
	}
}

// RUN-T-200 and RUN-T-201 make close wait for active preparation and reject work that has not started.
func TestEnvironmentConfigCloseWaitsAndRejectsPendingWork(t *testing.T) {
	initial := initialRuntimeConfig()
	release, started := make(chan struct{}), make(chan struct{})
	prepared := &testPreparedConfiguration{config: initial}
	preparer := &testConfigurationPreparer{started: started, release: release, prepared: prepared}
	manager := newTestEnvironmentConfigRuntime(t, initial, preparer.prepare, &testCacheWriter{})
	applyDone := make(chan ConfigApplyResult, 1)
	go func() {
		result, _ := manager.Submit(context.Background(), 1, configurationOf(initial))
		applyDone <- result
	}()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("candidate preparation did not start")
	}
	closeDone := make(chan struct{})
	go func() { manager.Close(); close(closeDone) }()
	waitUntilClosed(t, manager)
	select {
	case <-closeDone:
		t.Fatal("close returned before active preparation settled")
	default:
	}
	result, err := manager.Submit(context.Background(), 2, configurationOf(initial))
	if err != nil || result.Kind != ConfigFatal || result.Message != "configuration runtime is closed" {
		t.Fatalf("submission after close = %+v, %v", result, err)
	}
	close(release)
	select {
	case result := <-applyDone:
		if result.Kind != ConfigFatal || result.Message != "configuration runtime is closed" {
			t.Fatalf("active candidate after close = %+v", result)
		}
	case <-time.After(time.Second):
		t.Fatal("active submission did not settle after release")
	}
	if prepared.publishCalls != 0 || prepared.retireCalls != 1 || manager.HasAppliedConfiguration() {
		t.Fatalf("closed candidate published=%d retired=%d initialized=%t", prepared.publishCalls, prepared.retireCalls, manager.HasAppliedConfiguration())
	}
	select {
	case <-closeDone:
	case <-time.After(time.Second):
		t.Fatal("close did not wait for active candidate to converge")
	}
}

type testCacheWriter struct {
	mu     sync.Mutex
	writes [][]byte
	err    error
}

func (w *testCacheWriter) WritePrivateAtomic(_ context.Context, _ string, data []byte) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.writes = append(w.writes, append([]byte(nil), data...))
	return w.err
}

type testConfigurationPreparer struct {
	mu       sync.Mutex
	previous []*config.DaemonConfig
	prepared *testPreparedConfiguration
	started  chan struct{}
	release  chan struct{}
}

func (p *testConfigurationPreparer) prepare(ctx context.Context, candidate config.DaemonConfig, previous *config.DaemonConfig) (PreparedConfiguration, error) {
	p.mu.Lock()
	if previous == nil {
		p.previous = append(p.previous, nil)
	} else {
		copy := cloneDaemonConfig(*previous)
		p.previous = append(p.previous, &copy)
	}
	prepared := p.prepared
	if prepared == nil {
		prepared = &testPreparedConfiguration{config: candidate}
	}
	started, release := p.started, p.release
	p.mu.Unlock()
	if started != nil {
		close(started)
	}
	if release != nil {
		select {
		case <-release:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	return prepared, nil
}

type testPreparedConfiguration struct {
	config       config.DaemonConfig
	publishCalls int
	retireCalls  int
}

func (p *testPreparedConfiguration) Config() config.DaemonConfig { return cloneDaemonConfig(p.config) }
func (p *testPreparedConfiguration) Publish() (func() error, error) {
	p.publishCalls++
	return nil, nil
}
func (p *testPreparedConfiguration) Retire() error {
	p.retireCalls++
	return nil
}

func newTestEnvironmentConfigRuntime(t *testing.T, initial config.DaemonConfig, prepare ConfigurationPreparer, cache ConfigCacheWriter) *EnvironmentConfigRuntime {
	t.Helper()
	manager, err := NewEnvironmentConfigRuntime(EnvironmentConfigOptions{
		Initial: initial,
		Cache:   cache,
		Prepare: prepare,
		Status:  NewStatusTracker(),
	})
	if err != nil {
		t.Fatal(err)
	}
	return manager
}

func initialRuntimeConfig() config.DaemonConfig {
	bash := "/bin/bash"
	bashSetting := "bash"
	return config.DaemonConfig{
		EnvironmentID:         "env",
		ConfigVersion:         1,
		ConfigPath:            "/data/daemon.json",
		DataDir:               "/data",
		TokenPath:             "/data/daemon.token",
		StudioURL:             "https://studio.example",
		GatewayURL:            "wss://studio.example/ws/daemon",
		BashExecutable:        &bash,
		BashExecutableSetting: &bashSetting,
		LSP:                   []config.LSPServerConfig{{ID: "go", Command: []string{"gopls"}, Extensions: []string{".go"}}},
		DistributionDir:       "/app",
	}
}

func changedConfiguration(initial config.DaemonConfig, studioURL string) *protocol.DaemonConfiguration {
	wire := configurationOf(initial)
	wire.StudioUrl = studioURL
	return wire
}

func waitForPendingVersion(t *testing.T, manager *EnvironmentConfigRuntime, version int64) {
	t.Helper()
	deadline := time.After(time.Second)
	ticker := time.NewTicker(time.Millisecond)
	defer ticker.Stop()
	for {
		manager.mu.Lock()
		pending := manager.pending
		matched := pending != nil && pending.version == version
		manager.mu.Unlock()
		if matched {
			return
		}
		select {
		case <-deadline:
			t.Fatalf("pending version %d was not queued", version)
		case <-ticker.C:
		}
	}
}

func waitUntilClosed(t *testing.T, manager *EnvironmentConfigRuntime) {
	t.Helper()
	deadline := time.After(time.Second)
	ticker := time.NewTicker(time.Millisecond)
	defer ticker.Stop()
	for {
		manager.mu.Lock()
		closed := manager.closed
		manager.mu.Unlock()
		if closed {
			return
		}
		select {
		case <-deadline:
			t.Fatal("configuration runtime close did not begin")
		case <-ticker.C:
		}
	}
}
