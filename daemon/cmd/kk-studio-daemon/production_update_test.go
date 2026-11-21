package main

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"google.golang.org/protobuf/proto"
)

// A failed installer preparation must not disable process-backed capabilities on the live connection.
func TestProductionFailedUpdateResumesProcesses(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	accepted := make(chan net.Conn, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		connection, err := websocket.Accept(w, request, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
		if err != nil {
			return
		}
		defer connection.Close(websocket.StatusNormalClosure, "")
		select {
		case accepted <- websocket.NetConn(request.Context(), connection, websocket.MessageBinary):
		case <-ctx.Done():
			return
		}
		<-ctx.Done()
	}))
	defer func() {
		cancel()
		server.Close()
	}()

	// Outside the managed install layout: the real runner fails before downloading an installer.
	directory := filepath.Join(t.TempDir(), "daemon")
	if err := native.EnsurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	configPath := filepath.Join(directory, "daemon.json")
	body := fmt.Sprintf(`{"environmentId":%q,"version":"0","daemonConfig":{"studioUrl":%q}}`, e2eEnvironmentID, server.URL)
	if err := native.WritePrivateFile(configPath, []byte(body)); err != nil {
		t.Fatal(err)
	}
	if err := native.WritePrivateFile(filepath.Join(directory, "daemon.token"), []byte("e2e-test-token\n")); err != nil {
		t.Fatal(err)
	}
	initial, err := config.LoadDaemonConfig(configPath)
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- (productionRuntime{}).Run(ctx, initial) }()
	defer func() {
		cancel()
		select {
		case err := <-done:
			if err != nil {
				t.Errorf("daemon shutdown: %v", err)
			}
		case <-time.After(5 * time.Second):
			t.Error("daemon did not shut down")
		}
	}()
	var peer net.Conn
	select {
	case peer = <-accepted:
	case <-ctx.Done():
		t.Fatal("daemon did not connect")
	}
	defer peer.Close()
	transport, writer, response := openControlStream(t, ctx, peer)
	defer transport.CloseIdleConnections()
	defer writer.Close()
	defer response.Body.Close()
	writeControlEnvelope(t, writer, &protocol.Envelope{
		MessageType: protocol.MessageType_SETTINGS, EnvironmentId: proto.String(e2eEnvironmentID),
		Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{
			TemporaryResourceTtlSeconds: proto.Int64(60), TemporaryResourceCleanupIntervalSeconds: proto.Int64(60),
		}}},
	})
	writeControlEnvelope(t, writer, &protocol.Envelope{
		MessageType: protocol.MessageType_ENVIRONMENT_CONFIG, EnvironmentId: proto.String(e2eEnvironmentID),
		Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{
			Version: proto.Int64(1), Config: &protocol.DaemonConfiguration{StudioUrl: server.URL},
		}},
	})
	ready, err := readControlEnvelopeResult(response.Body)
	if err != nil || ready.GetMessageType() != protocol.MessageType_READY {
		t.Fatalf("READY=%v err=%v", ready, err)
	}
	const operationID = "123e4567-e89b-42d3-a456-426614174120"
	writeControlEnvelope(t, writer, &protocol.Envelope{
		MessageType: protocol.MessageType_UPDATE, EnvironmentId: proto.String(e2eEnvironmentID),
		Payload: &protocol.Envelope_Update{Update: &protocol.Update{OperationId: proto.String(operationID), TargetVersion: proto.String("2.0.0")}},
	})
	acceptedReceipt := readProductionUpdateReceipt(t, response.Body).GetUpdateResult()
	if acceptedReceipt.GetOperationId() != operationID || acceptedReceipt.GetPhase() != protocol.UpdatePhase_UPDATE_ACCEPTED {
		t.Fatalf("want UPDATE_ACCEPTED, got %v", acceptedReceipt)
	}
	assertProductionUpdateReceipt(t, readProductionUpdateReceipt(t, response.Body), operationID, "official installer preparation failed; check daemon logs and network")

	result := invokeProtocolCapability(t, writer, response.Body, "123e4567-e89b-42d3-a456-426614174102", "process.exec", fmt.Sprintf(`{"command":"printf resumed","workdir":%q}`, directory), nil)
	var output strings.Builder
	for _, item := range result.GetContents() {
		output.WriteString(item.GetText().GetText())
	}
	if result.GetError() || !strings.Contains(output.String(), "resumed") {
		t.Fatalf("process.exec after UPDATE_FAILED: error=%v output=%q", result.GetError(), output.String())
	}
}
