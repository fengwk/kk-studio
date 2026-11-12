package main

import (
	"context"
	"net/http"
	"os"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/lsp"
)

// RUN-F-029 prepares resource candidates only when their applied configuration changes.
func TestProductionEnvironmentPreparationUsesResourceDeltas(t *testing.T) {
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	server := config.LSPServerConfig{ID: "text", Command: []string{executable}, Extensions: []string{".txt"}}
	proxyA, proxyB := "http://127.0.0.1:8081", "http://127.0.0.1:8082"
	previous := config.DaemonConfig{BashExecutable: &executable, ProxyURL: &proxyA, LSP: []config.LSPServerConfig{server}}
	candidate := cloneProductionConfig(previous)
	facade := lsp.NewFacade(nil, lsp.PoolOptions{})
	networks := newNetworkRuntimeStore(networkForTest(t, proxyA))
	defer func() {
		if err := networks.close(); err != nil {
			t.Errorf("close network store: %v", err)
		}
	}()
	prepare := prepareProductionEnvironment(facade, networks)

	prepared, err := prepare(context.Background(), candidate, &previous)
	if err != nil {
		t.Fatal(err)
	}
	noChange := prepared.(*preparedEnvironment)
	if noChange.network != nil || noChange.lsp != nil {
		t.Fatalf("unchanged resource candidates = network %t, LSP %t", noChange.network != nil, noChange.lsp != nil)
	}

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
}

// RUN-F-029 publishes changed resource generations and retires the previous network after leases drain.
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
