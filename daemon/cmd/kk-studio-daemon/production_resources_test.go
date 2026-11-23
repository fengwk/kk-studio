package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/lsp"
	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	daemonruntime "github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// A broken profile must not block startup, even when the cache has an absolute LSP command.
func TestProductionStartupRecoversFromLoginEnvironmentFailure(t *testing.T) {
	ctx := context.Background()
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", "")
	dir := filepath.Join(t.TempDir(), "daemon")
	if err := native.EnsurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	initial := config.DaemonConfig{
		EnvironmentID: e2eEnvironmentID, ConfigPath: filepath.Join(dir, "daemon.json"), DataDir: dir,
		StudioURL: "https://studio.example", BashExecutableSetting: &executable,
		LSP: []config.LSPServerConfig{{ID: "cached", Command: []string{executable}, Extensions: []string{".cached"}}},
	}
	attempts := 0
	load := func(context.Context, string) ([]string, error) {
		attempts++
		if attempts <= 2 {
			return nil, errors.New("sample Bash login environment: missing output marker")
		}
		return []string{"PATH=" + filepath.Dir(executable)}, nil
	}
	var runErr error
	facade, cleanupLSP, err := startLanguageServers(ctx, &initial, nil, load, &runErr)
	if err != nil {
		t.Fatalf("startup did not tolerate a failed login sample: %v", err)
	}
	defer func() {
		cleanupLSP()
		if runErr != nil {
			t.Error(runErr)
		}
	}()
	if facade.Support("test.cached").Available || len(initial.LSP) != 1 {
		t.Fatal("startup must disable LSP without discarding cached declarations")
	}
	networks, cleanupNetworks, err := startNetworks(initial, &runErr)
	if err != nil {
		t.Fatal(err)
	}
	defer cleanupNetworks()
	manager, err := startEnvironmentConfig(initial, facade, networks, load)
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()
	snapshot := &protocol.DaemonConfiguration{
		StudioUrl: initial.StudioURL, BashExecutable: &executable,
		Lsp: &protocol.LspConfiguration{Servers: []*protocol.LspServer{{Id: "sampled", Command: []string{filepath.Base(executable)}, Extensions: []string{".sampled"}}}},
	}
	result, err := manager.Submit(ctx, 1, snapshot)
	if err != nil || result.Kind != daemonruntime.ConfigFailed || result.Code != "VALIDATION_FAILED" {
		t.Fatalf("failed sample was not reported as a configuration failure: %v, %v", result.Kind, err)
	}
	if facade.Support("test.sampled").Available {
		t.Fatal("failed application enabled LSP")
	}
	result, err = manager.Submit(ctx, 2, snapshot)
	if err != nil || result.Kind != daemonruntime.ConfigApplied || !facade.Support("test.sampled").Available {
		t.Fatalf("successful re-sample did not enable LSP through the sampled PATH: %v, %v", result.Kind, err)
	}
}

// Prepares resource candidates only when their applied configuration changes.
func TestProductionEnvironmentPreparationUsesResourceDeltas(t *testing.T) {
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	server := config.LSPServerConfig{ID: "text", Command: []string{executable}, Extensions: []string{".txt"}}
	proxyA, proxyB := "http://127.0.0.1:8081", "http://127.0.0.1:8082"
	previous := config.DaemonConfig{BashExecutable: &executable, ProxyURL: &proxyA, LSP: []config.LSPServerConfig{server}}
	environment := []string{"KK_SAMPLE=initial"}
	load := func(context.Context, string) ([]string, error) { return environment, nil }
	if _, err := prepareLoginEnvironment(context.Background(), &previous, load); err != nil {
		t.Fatal(err)
	}
	candidate := cloneProductionConfig(previous)
	facade := lsp.NewFacade(nil, lsp.PoolOptions{})
	initialNetwork := networkForTest(t, proxyA)
	networks := newNetworkRuntimeStore(initialNetwork)
	defer func() {
		if err := networks.close(); err != nil {
			t.Errorf("close network store: %v", err)
		}
	}()
	prepare := prepareProductionEnvironment(facade, networks, load)

	prepared, err := prepare(context.Background(), candidate, &previous)
	if err != nil {
		t.Fatal(err)
	}
	noChange := prepared.(*preparedEnvironment)
	if noChange.network != nil || noChange.lsp != nil {
		t.Fatalf("unchanged resource candidates = network %t, LSP %t", noChange.network != nil, noChange.lsp != nil)
	}

	bash := filepath.Join(t.TempDir(), filepath.Base(executable))
	if err := os.WriteFile(bash, []byte("fixture"), 0700); err != nil {
		t.Fatal(err)
	}
	bashOnly := cloneProductionConfig(previous)
	bashOnly.BashExecutable = &bash
	prepared, err = prepare(context.Background(), bashOnly, &previous)
	if err != nil {
		t.Fatal(err)
	}
	bashChange := prepared.(*preparedEnvironment)
	if bashChange.network != nil || bashChange.lsp == nil {
		t.Fatalf("bash-only candidates = network %t, LSP %t", bashChange.network != nil, bashChange.lsp != nil)
	}

	if err := bashChange.Retire(); err != nil {
		t.Fatal(err)
	}
	environment = []string{"KK_SAMPLE=changed"}
	prepared, err = prepare(context.Background(), candidate, &previous)
	if err != nil {
		t.Fatal(err)
	}
	envChange := prepared.(*preparedEnvironment)
	if envChange.network != nil || envChange.lsp == nil {
		t.Fatal("login environment change did not rebuild only LSP")
	}
	if err := envChange.Retire(); err != nil {
		t.Fatal(err)
	}
	environment = []string{"KK_SAMPLE=initial"}

	candidate.ProxyURL = &proxyB
	prepared, err = prepare(context.Background(), candidate, &previous)
	if err != nil {
		t.Fatal(err)
	}
	proxyChange := prepared.(*preparedEnvironment)
	if proxyChange.network == nil || proxyChange.lsp != nil {
		t.Fatalf("proxy-only candidates = network %t, LSP %t", proxyChange.network != nil, proxyChange.lsp != nil)
	}
	if err := proxyChange.Retire(); err != nil {
		t.Fatal(err)
	}
	if err := proxyChange.network.WithResponse(context.Background(), networkTestRequest(t), func(*http.Response) error { return nil }); err == nil || err.Error() != "outbound network is closed" {
		t.Fatalf("retired proxy candidate exchange error = %v", err)
	}

	candidate = cloneProductionConfig(previous)
	candidate.LSP = []config.LSPServerConfig{{ID: "text", Command: []string{executable}, Extensions: []string{".md"}}}
	prepared, err = prepare(context.Background(), candidate, &previous)
	if err != nil {
		t.Fatal(err)
	}
	lspChange := prepared.(*preparedEnvironment)
	if lspChange.network != nil || lspChange.lsp == nil {
		t.Fatalf("LSP-only candidates = network %t, LSP %t", lspChange.network != nil, lspChange.lsp != nil)
	}
	if err := lspChange.Retire(); err != nil {
		t.Fatal(err)
	}

	failedCandidate := cloneProductionConfig(previous)
	failedCandidate.ProxyURL = &proxyB
	failedCandidate.LSP = []config.LSPServerConfig{
		{ID: "text", Command: []string{executable}, Extensions: []string{".txt"}},
		{ID: "text", Command: []string{executable}, Extensions: []string{".md"}},
	}
	if _, err := prepare(context.Background(), failedCandidate, &previous); err == nil {
		t.Fatal("invalid LSP candidate was prepared")
	}
	current, release, err := networks.acquire()
	if err != nil {
		t.Fatal(err)
	}
	defer release()
	if current != initialNetwork {
		t.Fatal("failed LSP preparation published the network candidate")
	}
}

// Publishes changed resource generations and retires the previous network after leases drain.
func TestProductionEnvironmentPublishesAndRetiresNetworkCandidate(t *testing.T) {
	proxyA, proxyB := "http://127.0.0.1:8081", "http://127.0.0.1:8082"
	initial := networkForTest(t, proxyA)
	networks := newNetworkRuntimeStore(initial)
	defer func() {
		if err := networks.close(); err != nil {
			t.Errorf("close network store: %v", err)
		}
	}()
	previous, releasePrevious, err := networks.acquire()
	if err != nil {
		t.Fatal(err)
	}
	if previous != initial {
		releasePrevious()
		t.Fatal("initial network is not current")
	}
	candidate := networkForTest(t, proxyB)
	prepared := &preparedEnvironment{networks: networks, network: candidate}
	cleanup, err := prepared.Publish()
	if err != nil {
		releasePrevious()
		t.Fatal(err)
	}
	current, releaseCurrent, err := networks.acquire()
	if err != nil {
		releasePrevious()
		t.Fatal(err)
	}
	if current != candidate {
		releaseCurrent()
		releasePrevious()
		t.Fatal("published network candidate is not current")
	}
	releaseCurrent()
	cleanupDone := make(chan error, 1)
	go func() { cleanupDone <- cleanup() }()
	select {
	case err := <-cleanupDone:
		releasePrevious()
		t.Fatalf("previous generation cleanup finished before active lease drained: %v", err)
	case <-time.After(20 * time.Millisecond):
	}
	releasePrevious()
	select {
	case err := <-cleanupDone:
		if err != nil {
			t.Fatalf("retire previous network: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("previous network did not retire after lease release")
	}
	if err := initial.WithResponse(context.Background(), networkTestRequest(t), func(*http.Response) error { return nil }); err == nil || err.Error() != "outbound network is closed" {
		t.Fatalf("previous network exchange error = %v", err)
	}
}

func networkTestRequest(t *testing.T) *http.Request {
	t.Helper()
	request, err := http.NewRequest(http.MethodGet, "http://127.0.0.1:1", nil)
	if err != nil {
		t.Fatal(err)
	}
	return request
}

func cloneProductionConfig(value config.DaemonConfig) config.DaemonConfig {
	value.BashExecutable = cloneProductionString(value.BashExecutable)
	value.ProxyURL = cloneProductionString(value.ProxyURL)
	servers := make([]config.LSPServerConfig, len(value.LSP))
	for index, server := range value.LSP {
		servers[index] = config.LSPServerConfig{
			ID:                server.ID,
			Command:           append([]string(nil), server.Command...),
			Extensions:        append([]string(nil), server.Extensions...),
			RootMarkers:       append([]string(nil), server.RootMarkers...),
			FirstMatchMarkers: append([]string(nil), server.FirstMatchMarkers...),
		}
	}
	value.LSP = servers
	return value
}

func cloneProductionString(value *string) *string {
	if value == nil {
		return nil
	}
	copy := *value
	return &copy
}
