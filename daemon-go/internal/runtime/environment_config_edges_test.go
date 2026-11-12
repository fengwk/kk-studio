package runtime

import (
	"context"
	"errors"
	"math"
	"sync/atomic"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
)

// RUN-T-189/194 validates bootstrap cache ordering and empty/missing configuration boundaries.
func TestEnvironmentConfigConstructorAndSubmitEdges(t *testing.T) {
	initial := initialRuntimeConfig()
	if _, err := NewEnvironmentConfigRuntime(EnvironmentConfigOptions{Initial: initial}); err == nil {
		t.Fatal("constructor accepted missing preparer")
	}
	missingPath := initial
	missingPath.ConfigPath = ""
	if _, err := NewEnvironmentConfigRuntime(EnvironmentConfigOptions{Initial: missingPath, Prepare: (&testConfigurationPreparer{}).prepare}); err == nil {
		t.Fatal("constructor accepted missing cache path")
	}
	manager := newTestEnvironmentConfigRuntime(t, initial, (&testConfigurationPreparer{}).prepare, &testCacheWriter{})
	if result, err := manager.Submit(context.Background(), 2, nil); err != nil || result.Kind != ConfigFailed || result.Field != "config" {
		t.Fatalf("nil configuration result = %+v, %v", result, err)
	}
	if result, err := manager.Submit(context.Background(), 0, configurationOf(initial)); err != nil || result.Kind != ConfigFatal || result.Message != "Studio configuration version is older than the daemon cache" {
		t.Fatalf("old bootstrap version = %+v, %v", result, err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := manager.Submit(ctx, 1, configurationOf(initial)); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled submission error = %v", err)
	}
	manager.Close()
}

// RUN-T-150/152 verifies candidate failure reports, retirement, and cleanup convergence.
func TestEnvironmentConfigHooksAndCleanupErrors(t *testing.T) {
	initial := initialRuntimeConfig()
	prepareErr := errors.New("resource preparation failed")
	var reports atomic.Int32
	var candidateCalls, abortCalls, appliedCalls atomic.Int32
	manager, err := NewEnvironmentConfigRuntime(EnvironmentConfigOptions{
		Initial: initial,
		Prepare: func(context.Context, config.DaemonConfig, *config.DaemonConfig) (PreparedConfiguration, error) {
			return nil, prepareErr
		},
		Cache:  &testCacheWriter{},
		Report: func(error) { reports.Add(1) },
		OnCandidate: func(config.DaemonConfig, config.DaemonConfig) {
			candidateCalls.Add(1)
		},
		OnAborted: func(config.DaemonConfig, config.DaemonConfig) {
			abortCalls.Add(1)
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	manager.AddSessionHooks(func(config.DaemonConfig, config.DaemonConfig) { candidateCalls.Add(10) }, func(config.DaemonConfig, config.DaemonConfig) { abortCalls.Add(10) }, func(config.DaemonConfig, config.DaemonConfig) { appliedCalls.Add(1) })
	result, err := manager.Submit(context.Background(), 2, changedConfiguration(initial, "https://candidate.example"))
	if err != nil || result.Kind != ConfigFailed {
		t.Fatalf("prepare failure = %+v, %v", result, err)
	}
	if candidateCalls.Load() != 11 || abortCalls.Load() != 11 || appliedCalls.Load() != 0 || reports.Load() != 1 {
		t.Fatalf("hooks: candidate=%d abort=%d applied=%d reports=%d", candidateCalls.Load(), abortCalls.Load(), appliedCalls.Load(), reports.Load())
	}
	manager.Close()

	cleanupErr := errors.New("old resources did not close")
	prepared := &testPreparedConfiguration{config: func() config.DaemonConfig {
		candidate := initial
		candidate.ConfigVersion = 2
		candidate.StudioURL = "https://candidate.example"
		return candidate
	}(), cleanup: func() error { return cleanupErr }}
	var cleanupReported atomic.Int32
	manager = newTestEnvironmentConfigRuntime(t, initial, func(context.Context, config.DaemonConfig, *config.DaemonConfig) (PreparedConfiguration, error) {
		return prepared, nil
	}, &testCacheWriter{})
	manager.options.Report = func(got error) {
		if errors.Is(got, cleanupErr) {
			cleanupReported.Add(1)
		}
	}
	result, err = manager.Submit(context.Background(), 2, changedConfiguration(initial, "https://candidate.example"))
	if err != nil || result.Kind != ConfigApplied {
		t.Fatalf("cleanup candidate apply = %+v, %v", result, err)
	}
	manager.Close()
	if cleanupReported.Load() != 1 {
		t.Fatalf("cleanup error reports=%d, want 1", cleanupReported.Load())
	}
}

// RUN-T-196 reports publish/retire failures and does not acknowledge a rejected resource generation.
func TestEnvironmentConfigPublishAndRetireFailures(t *testing.T) {
	initial := initialRuntimeConfig()
	publishErr := errors.New("publish failed")
	retireErr := errors.New("retire failed")
	prepared := &testPreparedConfiguration{
		config:     initial,
		publishErr: publishErr,
		retireErr:  retireErr,
	}
	var reports atomic.Int32
	manager, err := NewEnvironmentConfigRuntime(EnvironmentConfigOptions{
		Initial: initial,
		Prepare: func(context.Context, config.DaemonConfig, *config.DaemonConfig) (PreparedConfiguration, error) {
			return prepared, nil
		},
		Cache:  &testCacheWriter{},
		Report: func(error) { reports.Add(1) },
	})
	if err != nil {
		t.Fatal(err)
	}
	result, err := manager.Submit(context.Background(), 1, configurationOf(initial))
	if err != nil || result.Kind != ConfigFatal || result.Message != "prepared environment resources could not be published" {
		t.Fatalf("publish failure = %+v, %v", result, err)
	}
	if prepared.publishCalls != 1 || prepared.retireCalls != 1 || reports.Load() != 2 {
		t.Fatalf("publish=%d retire=%d reports=%d", prepared.publishCalls, prepared.retireCalls, reports.Load())
	}
	manager.Close()
}

// RUN-T-123 surfaces status revision exhaustion instead of publishing a false configuration status.
func TestEnvironmentConfigReportsStatusRevisionExhaustion(t *testing.T) {
	initial := initialRuntimeConfig()
	status := NewStatusTracker()
	status.mu.Lock()
	status.revision = math.MaxInt64
	status.mu.Unlock()
	var reports atomic.Int32
	manager, err := NewEnvironmentConfigRuntime(EnvironmentConfigOptions{
		Initial: initial,
		Prepare: (&testConfigurationPreparer{}).prepare,
		Cache:   &testCacheWriter{},
		Status:  status,
		Report:  func(error) { reports.Add(1) },
	})
	if err != nil {
		t.Fatal(err)
	}
	result, err := manager.Submit(context.Background(), 1, configurationOf(initial))
	if err != nil || result.Kind != ConfigFatal || result.Message != "runtime status could not be updated" {
		t.Fatalf("status overflow result = %+v, %v", result, err)
	}
	if reports.Load() == 0 {
		t.Fatal("status revision failure was not reported")
	}
	manager.Close()
}

// RUN-T-121 preserves a previous immutable snapshot when a new candidate is applied.
func TestEnvironmentConfigPreparationReceivesPreviousSnapshot(t *testing.T) {
	initial := initialRuntimeConfig()
	preparer := &testConfigurationPreparer{}
	manager := newTestEnvironmentConfigRuntime(t, initial, preparer.prepare, &testCacheWriter{})
	if result, err := manager.Submit(context.Background(), 1, configurationOf(initial)); err != nil || result.Kind != ConfigApplied {
		t.Fatalf("initial configuration = %+v, %v", result, err)
	}
	changed := changedConfiguration(initial, "https://next.example")
	if result, err := manager.Submit(context.Background(), 2, changed); err != nil || result.Kind != ConfigApplied {
		t.Fatalf("changed configuration = %+v, %v", result, err)
	}
	preparer.mu.Lock()
	defer preparer.mu.Unlock()
	if len(preparer.previous) != 2 || preparer.previous[1] == nil || preparer.previous[1].ConfigVersion != 1 {
		t.Fatalf("preparation predecessors = %+v", preparer.previous)
	}
	manager.Close()
}

func TestRuntimeWaitForConfigCleanup(t *testing.T) {
	started, finish := make(chan struct{}), make(chan struct{})
	manager := newTestEnvironmentConfigRuntime(t, initialRuntimeConfig(), (&testConfigurationPreparer{}).prepare, &testCacheWriter{})
	manager.trackCleanup(func() error {
		close(started)
		<-finish
		return nil
	})
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("cleanup did not start")
	}
	done := make(chan struct{})
	go func() { manager.Close(); close(done) }()
	select {
	case <-done:
		t.Fatal("configuration close did not wait for tracked cleanup")
	case <-time.After(time.Millisecond * 10):
	}
	close(finish)
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("configuration close did not converge after cleanup")
	}
}
