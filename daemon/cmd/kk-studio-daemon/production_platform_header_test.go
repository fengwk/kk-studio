package main

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	daemonruntime "github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// The admission endpoint resolves the 426 target version from `KK-Daemon-Platform`, so the daemon
// must send the `<os>-<arch>` wire value it derives from the host facts it reports in READY.
func TestProductionRuntimeSendsHostPlatformHeader(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	headers := make(chan http.Header, 1)
	var once sync.Once
	platform := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		once.Do(func() { headers <- r.Header.Clone() })
		// A rejected registration is terminal, so the daemon reports the failure and stops.
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer platform.Close()

	initial, cleanup := prepareProductionAssemblyFixture(t, platform.URL)
	defer cleanup()
	err := (productionRuntime{}).Run(ctx, initial)
	if !errors.Is(err, daemonruntime.ErrRegistrationRejected) {
		t.Fatalf("Run = %v, want registration rejection", err)
	}
	var handshake http.Header
	select {
	case handshake = <-headers:
	default:
		t.Fatal("assembled daemon did not attempt its control handshake")
	}
	hostInfo, err := daemonruntime.DetectHostInfo()
	if err != nil {
		t.Fatal(err)
	}
	want, err := daemonruntime.HostPlatform(hostInfo)
	if err != nil {
		t.Fatal(err)
	}
	if got := handshake.Get("KK-Daemon-Platform"); got != want {
		t.Fatalf("KK-Daemon-Platform = %q, want the host platform %q", got, want)
	}
}
