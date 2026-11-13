package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"google.golang.org/protobuf/proto"
)

// TestProductionRuntimeSignalCancelsInFlightProcess starts a long process.exec through the real
// protocol-4 control stream, then sends SIGTERM to the compiled daemon. The daemon must leave its
// process scope, exit cleanly and release the runtime lock (RUN-T-140 / RUN-T-158).
func TestProductionRuntimeSignalCancelsInFlightProcess(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("the signal fixture requires POSIX SIGTERM semantics and process accounting")
	}
	binary := buildDaemonForTest(t)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
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
		case <-request.Context().Done():
		}
		<-request.Context().Done()
	}))
	defer server.Close()

	directory := filepath.Join(t.TempDir(), "daemon")
	if err := native.EnsurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	configPath := filepath.Join(directory, "daemon.json")
	configBody := fmt.Sprintf(`{"environmentId":%q,"version":"0","daemonConfig":{"studioUrl":%q}}`, e2eEnvironmentID, server.URL)
	if err := native.WritePrivateFile(configPath, []byte(configBody)); err != nil {
		t.Fatal(err)
	}
	if err := native.WritePrivateFile(filepath.Join(directory, "daemon.token"), []byte("signal-test-token\n")); err != nil {
		t.Fatal(err)
	}
	markerPath := filepath.Join(directory, "child.pid")

	daemon := exec.Command(binary, "--config", configPath)
	var daemonStderr bytes.Buffer
	daemon.Stderr = &daemonStderr
	if err := daemon.Start(); err != nil {
		t.Fatal(err)
	}
	daemonDone := make(chan error, 1)
	go func() { daemonDone <- daemon.Wait() }()
	exited := false
	defer func() {
		if !exited {
			stopDaemonProcess(t, daemon, daemonDone)
		}
	}()

	var peer net.Conn
	select {
	case peer = <-accepted:
	case <-ctx.Done():
		t.Fatalf("daemon did not open its control WebSocket, stderr=%q", daemonStderr.String())
	}
	defer peer.Close()
	controlTransport, bodyWriter, response := openProtocol4ControlStream(t, ctx, peer)
	defer controlTransport.CloseIdleConnections()
	defer response.Body.Close()
	writeControlEnvelope(t, bodyWriter, &protocol.Envelope{
		MessageType: protocol.MessageType_SETTINGS, EnvironmentId: proto.String(e2eEnvironmentID),
		Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{
			TemporaryResourceTtlSeconds: proto.Int64(60), TemporaryResourceCleanupIntervalSeconds: proto.Int64(60),
		}}},
	})
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	writeControlEnvelope(t, bodyWriter, &protocol.Envelope{
		MessageType: protocol.MessageType_ENVIRONMENT_CONFIG, EnvironmentId: proto.String(e2eEnvironmentID),
		Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{
			Version: proto.Int64(1), Config: &protocol.DaemonConfiguration{StudioUrl: server.URL, Lsp: &protocol.LspConfiguration{
				Servers: []*protocol.LspServer{{Id: "text", Command: []string{executable}, Extensions: []string{".txt"}}},
			}},
		}},
	})
	ready, err := readControlEnvelopeResult(response.Body)
	if err != nil || ready.MessageType != protocol.MessageType_READY {
		t.Fatalf("compiled daemon READY=%v err=%v, stderr=%q", ready, err, daemonStderr.String())
	}

	const invocation = "123e4567-e89b-42d3-a456-426614174099"
	arguments, err := json.Marshal(map[string]string{"command": "echo started; echo $$ > " + markerPath + "; sleep 30", "workdir": directory})
	if err != nil {
		t.Fatal(err)
	}
	writeControlEnvelope(t, bodyWriter, &protocol.Envelope{
		MessageType: protocol.MessageType_INVOKE, EnvironmentId: proto.String(e2eEnvironmentID), InvocationId: proto.String(invocation),
		Payload: &protocol.Envelope_Invoke{Invoke: &protocol.Invoke{
			CapabilityId: proto.String("process.exec"), ArgumentsJson: proto.String(string(arguments)),
			TimeoutMillis: proto.Int64(0), MaxOutputBytes: proto.Int64(1 << 20),
			ImageInputTier: imageTier(protocol.ImageInputTier_P720),
		}},
	})
	for {
		envelope, readErr := readControlEnvelopeResult(response.Body)
		if readErr != nil {
			t.Fatalf("read process output: %v, stderr=%q", readErr, daemonStderr.String())
		}
		if envelope.GetInvocationId() != invocation || envelope.MessageType != protocol.MessageType_PROGRESS {
			continue
		}
		if strings.Contains(envelopeText(envelope.GetProgress().GetResult()), "started") {
			break
		}
	}
	childPID := readChildPID(t, markerPath)

	if err := daemon.Process.Signal(syscall.SIGTERM); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-daemonDone:
		exited = true
		if err != nil {
			t.Fatalf("daemon exited with error after SIGTERM: %v, stderr=%q", err, daemonStderr.String())
		}
	case <-time.After(productionLifecycleTimeout):
		t.Fatalf("daemon did not exit after SIGTERM while a process was in flight, stderr=%q", daemonStderr.String())
	}
	waitForProcessExit(t, childPID)
	lock, err := native.AcquireDirectoryLock(directory, native.DaemonLockFileName)
	if err != nil {
		t.Fatalf("daemon lock was not released after SIGTERM: %v", err)
	}
	if err := lock.Close(); err != nil {
		t.Fatal(err)
	}
}

func readChildPID(t *testing.T, path string) int {
	t.Helper()
	deadline := time.NewTimer(productionLifecycleTimeout)
	defer deadline.Stop()
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()
	for {
		body, err := os.ReadFile(path)
		if err == nil {
			pid, parseErr := strconv.Atoi(strings.TrimSpace(string(body)))
			if parseErr != nil || pid <= 0 {
				t.Fatalf("child pid file %q is invalid: %v", body, parseErr)
			}
			return pid
		}
		select {
		case <-deadline.C:
			t.Fatalf("process.exec did not record its child pid: %v", err)
		case <-ticker.C:
		}
	}
}

func waitForProcessExit(t *testing.T, pid int) {
	t.Helper()
	deadline := time.Now().Add(productionLifecycleTimeout)
	process, err := os.FindProcess(pid)
	if err != nil {
		t.Fatalf("find child process %d: %v", pid, err)
	}
	for {
		signalErr := process.Signal(syscall.Signal(0))
		if errors.Is(signalErr, os.ErrProcessDone) {
			return
		}
		if signalErr != nil {
			t.Fatalf("inspect child process %d: %v", pid, signalErr)
		}
		if time.Now().After(deadline) {
			t.Fatalf("process.exec child %d survived daemon shutdown", pid)
		}
		time.Sleep(20 * time.Millisecond)
	}
}
