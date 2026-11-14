package main

import (
	"context"
	"encoding/binary"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"github.com/fengwk/kk-studio/daemon/internal/update"
	"google.golang.org/protobuf/proto"
)

// RUN-T-055 proves the production assembly passes the runtime's per-attempt observer into the real
// transport: a peer GOAWAY(NO_ERROR) makes the assembled daemon reconnect with a new generation, and
// it stops on the journal/reconnect path instead of ignoring the drain.
func TestProductionRuntimeReconnectsOnGoaway(t *testing.T) {
	if testing.Short() {
		t.Skip("the assembly fixture drives a real WebSocket control channel")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	accepted := make(chan net.Conn, 2)
	platform := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		connection, err := websocket.Accept(w, request, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
		if err != nil {
			return
		}
		defer connection.Close(websocket.StatusNormalClosure, "")
		accepted <- websocket.NetConn(request.Context(), connection, websocket.MessageBinary)
		<-request.Context().Done()
	}))
	defer platform.Close()

	initial, cleanup := prepareProductionAssemblyFixture(t, platform.URL)
	defer cleanup()
	runtimeDone := make(chan error, 1)
	go func() { runtimeDone <- (productionRuntime{updateRunner: failingUpdateRunner{}}).Run(ctx, initial) }()

	var peer net.Conn
	select {
	case peer = <-accepted:
	case <-ctx.Done():
		t.Fatal("assembled daemon did not establish its control WebSocket")
	}
	defer peer.Close()
	controlTransport, bodyWriter, response := openProtocol4ControlStream(t, ctx, peer)
	defer controlTransport.CloseIdleConnections()
	defer response.Body.Close()
	writeControlEnvelope(t, bodyWriter, productionSettings())
	writeControlEnvelope(t, bodyWriter, productionConfiguration(platform.URL))
	ready, err := readControlEnvelopeResult(response.Body)
	if err != nil || ready.MessageType != protocol.MessageType_READY {
		t.Fatalf("first generation READY=%v err=%v", ready, err)
	}

	// A peer GOAWAY(NO_ERROR) is a graceful drain: the assembled daemon must open a new generation.
	goaway := make([]byte, 9+8)
	goaway[2] = 8
	goaway[3] = 7
	binary.BigEndian.PutUint32(goaway[9+4:], 0)
	if _, err := peer.Write(goaway); err != nil {
		t.Fatalf("write GOAWAY frame: %v", err)
	}
	var reconnected net.Conn
	select {
	case reconnected = <-accepted:
	case runtimeErr := <-runtimeDone:
		t.Fatalf("assembled daemon stopped on a graceful GOAWAY: %v", runtimeErr)
	case <-time.After(10 * time.Second):
		t.Fatal("assembled daemon did not reconnect after GOAWAY")
	}
	defer reconnected.Close()
	reconnectTransport, reconnectWriter, reconnectResponse := openProtocol4ControlStream(t, ctx, reconnected)
	defer reconnectTransport.CloseIdleConnections()
	defer reconnectResponse.Body.Close()
	writeControlEnvelope(t, reconnectWriter, productionSettings())
	writeControlEnvelope(t, reconnectWriter, productionConfiguration(platform.URL))
	reconnectedReady, err := readControlEnvelopeResult(reconnectResponse.Body)
	if err != nil || reconnectedReady.MessageType != protocol.MessageType_READY {
		t.Fatalf("GOAWAY reconnect READY=%v err=%v", reconnectedReady, err)
	}
	cancel()
	_ = bodyWriter.Close()
	_ = reconnectWriter.Close()
	select {
	case err := <-runtimeDone:
		if err != nil {
			t.Fatalf("daemon shutdown after GOAWAY reconnect: %v", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("daemon did not shut down after GOAWAY reconnect")
	}
}

// RUN-T-057 proves the production assembly passes the runtime's observer into the real transport:
// an HTTP 426 with an UpgradeRequired body makes the assembled daemon run the managed update with
// that target version instead of treating the rejection as a terminal registration failure.
func TestProductionRuntimeRunsManagedUpdateOnUpgradeRequired(t *testing.T) {
	if testing.Short() {
		t.Skip("the assembly fixture drives a real WebSocket control channel")
	}
	// Keep the rejected target distinct from the running version across releases.
	targetVersion := config.DaemonVersion + "-upgrade-test"
	upgradeBody, err := proto.Marshal(&protocol.UpgradeRequired{TargetVersion: proto.String(targetVersion)})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	var rejections atomic.Int32
	platform := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		rejections.Add(1)
		w.WriteHeader(http.StatusUpgradeRequired)
		_, _ = w.Write(upgradeBody)
	}))
	defer platform.Close()

	initial, cleanup := prepareProductionAssemblyFixture(t, platform.URL)
	defer cleanup()
	runner := &recordingUpdateRunner{started: make(chan update.Command, 1)}
	runtimeDone := make(chan error, 1)
	go func() { runtimeDone <- (productionRuntime{updateRunner: runner}).Run(ctx, initial) }()
	select {
	case command := <-runner.started:
		if command.TargetVersion != targetVersion {
			cancel()
			t.Fatalf("assembled daemon updated to %q, want the 426 target version", command.TargetVersion)
		}
	case runtimeErr := <-runtimeDone:
		t.Fatalf("assembled daemon exited instead of running the managed update: %v", runtimeErr)
	case <-time.After(10 * time.Second):
		cancel()
		t.Fatal("assembled daemon did not run the managed update for a 426 rejection")
	}
	cancel()
	select {
	case <-runtimeDone:
	case <-time.After(10 * time.Second):
		t.Fatal("daemon did not shut down after the managed update")
	}
}

func prepareProductionAssemblyFixture(t *testing.T, studioURL string) (config.DaemonConfig, func()) {
	t.Helper()
	directory := filepath.Join(t.TempDir(), "daemon")
	if err := native.EnsurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	configPath := filepath.Join(directory, "daemon.json")
	body := fmt.Sprintf(`{"environmentId":%q,"version":"0","daemonConfig":{"studioUrl":%q}}`, e2eEnvironmentID, studioURL)
	if err := native.WritePrivateFile(configPath, []byte(body)); err != nil {
		t.Fatal(err)
	}
	if err := native.WritePrivateFile(filepath.Join(directory, "daemon.token"), []byte("assembly-token\n")); err != nil {
		t.Fatal(err)
	}
	initial, err := config.LoadDaemonConfig(configPath)
	if err != nil {
		t.Fatal(err)
	}
	return initial, func() {
		if err := os.RemoveAll(directory); err != nil {
			t.Logf("remove assembly fixture: %v", err)
		}
	}
}

func productionSettings() *protocol.Envelope {
	return &protocol.Envelope{
		MessageType:   protocol.MessageType_SETTINGS,
		EnvironmentId: proto.String(e2eEnvironmentID),
		Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{
			TemporaryResourceTtlSeconds: proto.Int64(60), TemporaryResourceCleanupIntervalSeconds: proto.Int64(60),
		}}},
	}
}

func productionConfiguration(studioURL string) *protocol.Envelope {
	return &protocol.Envelope{
		MessageType:   protocol.MessageType_ENVIRONMENT_CONFIG,
		EnvironmentId: proto.String(e2eEnvironmentID),
		Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{
			Version: proto.Int64(1),
			Config:  &protocol.DaemonConfiguration{StudioUrl: studioURL},
		}},
	}
}

// recordingUpdateRunner reports the first managed update it is asked to run and then holds it until
// the daemon shuts down, so the assembly test can assert the target version of a 426-driven upgrade.
type recordingUpdateRunner struct {
	started chan update.Command
	once    sync.Once
}

func (r *recordingUpdateRunner) Configured() bool   { return true }
func (r *recordingUpdateRunner) BusyReason() string { return "" }
func (r *recordingUpdateRunner) StopForUpdate(context.Context) error {
	return nil
}
func (r *recordingUpdateRunner) HandleUpdate(ctx context.Context, command update.Command, _ config.DaemonConfig, _ func(update.Progress)) (update.Outcome, error) {
	r.once.Do(func() { r.started <- command })
	<-ctx.Done()
	return update.Outcome{}, ctx.Err()
}
