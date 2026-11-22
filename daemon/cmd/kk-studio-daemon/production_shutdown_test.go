package main

import (
	"context"
	"errors"
	"path/filepath"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
)

// The directory lock protects the whole daemon lifetime, so a second daemon must still fail to take
// over the environment while the process supervisor is stopping managed children.
func TestProductionShutdownReleasesDirectoryLockAfterProcessSupervision(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "daemon")
	var probeErr error
	startSupervision := func(*error) (native.UpdateSupervisor, func(), error) {
		return inertSupervisor{}, func() { probeErr = probeDirectoryLock(directory) }, nil
	}
	// An invalid proxy fails startup right after both process-lifetime resources are held, so the
	// release order is observable without starting a managed service.
	invalidProxy := "http://127.0.0.1"
	err := (productionRuntime{}).run(context.Background(), config.DaemonConfig{DataDir: directory, EnvironmentID: "123e4567-e89b-42d3-a456-426614174000", ProxyURL: &invalidProxy}, startSupervision)
	if err == nil {
		t.Fatal("production startup accepted an invalid proxy configuration")
	}
	if !errors.Is(probeErr, native.ErrDirectoryLockBusy) {
		t.Fatalf("directory lock probe during process supervision shutdown = %v, want the lock to still be held", probeErr)
	}
	takeover, err := native.AcquireDirectoryLock(directory, native.DaemonLockFileName)
	if err != nil {
		t.Fatalf("directory lock was not released after shutdown: %v", err)
	}
	if err := takeover.Close(); err != nil {
		t.Fatal(err)
	}
}

// probeDirectoryLock reports the takeover error a second daemon would observe.
func probeDirectoryLock(directory string) error {
	lock, err := native.AcquireDirectoryLock(directory, native.DaemonLockFileName)
	if err != nil {
		return err
	}
	return lock.Close()
}

// inertSupervisor stands in for process supervision: the fixture fails startup before any managed
// process could start.
type inertSupervisor struct{}

func (inertSupervisor) Start(context.Context, native.ProcessSpec) (native.ManagedProcess, error) {
	return nil, errors.New("the shutdown fixture starts no managed process")
}
func (inertSupervisor) Close(context.Context) error   { return nil }
func (inertSupervisor) Suspend(context.Context) error { return nil }
func (inertSupervisor) Resume()                       {}
