package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/fengwk/kk-studio/daemon/internal/coding"
	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"google.golang.org/protobuf/proto"
)

const e2eEnvironmentID = "123e4567-e89b-42d3-a456-426614174000"

func TestMain(m *testing.M) {
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "__native-helper":
			os.Exit(native.HelperMain(os.Args[2:]))
		case "__lock-helper":
			os.Exit(native.LockHelperMain(os.Args[2:]))
		}
	}
	os.Exit(m.Run())
}

// TestProtocol4ProductionRuntime exercises the assembled daemon against a real protocol-4 HTTP/2-over-WebSocket control channel.
func TestProtocol4ProductionRuntime(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	accepted := make(chan net.Conn, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		connection, err := websocket.Accept(w, request, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
		if err != nil {
			return
		}
		defer connection.Close(websocket.StatusNormalClosure, "")
		accepted <- websocket.NetConn(request.Context(), connection, websocket.MessageBinary)
		<-request.Context().Done()
	}))
	defer server.Close()

	directory := filepath.Join(t.TempDir(), "daemon")
	if err := native.EnsurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	configPath := filepath.Join(directory, "daemon.json")
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	configBody := fmt.Sprintf(`{"environmentId":%q,"version":"0","daemonConfig":{"studioUrl":%q,"lsp":{"servers":{"text":{"command":[%q],"extensions":[".txt"]}}}}}`, e2eEnvironmentID, server.URL, executable)
	if err := native.WritePrivateFile(configPath, []byte(configBody)); err != nil {
		t.Fatal(err)
	}
	if err := native.WritePrivateFile(filepath.Join(directory, "daemon.token"), []byte("protocol4-test-token\n")); err != nil {
		t.Fatal(err)
	}
	initial, err := config.LoadDaemonConfig(configPath)
	if err != nil {
		t.Fatal(err)
	}
	stagingPath := filepath.Join(initial.DataDir, "skills", ".staging-interrupted")
	if err := os.MkdirAll(stagingPath, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(stagingPath, "partial.zip"), []byte("partial"), 0600); err != nil {
		t.Fatal(err)
	}
	outputStore, err := coding.TextOutputStoreForDataDir(initial.DataDir)
	if err != nil {
		t.Fatal(err)
	}
	expiredWorkspace := filepath.Join(outputStore.Root(), "123e4567-e89b-42d3-a456-426614174010")
	if err := os.MkdirAll(expiredWorkspace, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(expiredWorkspace, "created-at"), []byte("1"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(expiredWorkspace, "result.log"), []byte("expired"), 0600); err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(directory, "sample.txt")
	if err := os.WriteFile(file, []byte("needle original\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(directory, "workspace"), 0700); err != nil {
		t.Fatal(err)
	}

	runtimeDone := make(chan error, 1)
	go func() { runtimeDone <- (productionRuntime{}).Run(ctx, initial) }()
	var peer net.Conn
	select {
	case peer = <-accepted:
	case <-ctx.Done():
		t.Fatal("daemon did not establish its control WebSocket")
	}
	defer peer.Close()

	protocols := new(http.Protocols)
	protocols.SetUnencryptedHTTP2(true)
	controlTransport := &http.Transport{Protocols: protocols, DialContext: func(context.Context, string, string) (net.Conn, error) { return peer, nil }}
	defer controlTransport.CloseIdleConnections()
	controlClient := &http.Client{Transport: controlTransport}
	bodyReader, bodyWriter := io.Pipe()
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, "http://daemon/control", bodyReader)
	if err != nil {
		t.Fatal(err)
	}
	response, err := controlClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.ProtoMajor != 2 || response.StatusCode != http.StatusOK {
		t.Fatalf("control stream response=%s status=%d", response.Proto, response.StatusCode)
	}

	settings := &protocol.Envelope{
		MessageType:   protocol.MessageType_SETTINGS,
		EnvironmentId: proto.String(e2eEnvironmentID),
		Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{
			TemporaryResourceTtlSeconds: proto.Int64(60), TemporaryResourceCleanupIntervalSeconds: proto.Int64(60),
		}}},
	}
	configuration := &protocol.Envelope{
		MessageType:   protocol.MessageType_ENVIRONMENT_CONFIG,
		EnvironmentId: proto.String(e2eEnvironmentID),
		Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{
			Version: proto.Int64(1),
			Config:  &protocol.DaemonConfiguration{StudioUrl: server.URL, Lsp: &protocol.LspConfiguration{Servers: []*protocol.LspServer{{Id: "text", Command: []string{executable}, Extensions: []string{".txt"}}}}},
		}},
	}
	writeControlEnvelope(t, bodyWriter, settings)
	writeControlEnvelope(t, bodyWriter, configuration)
	select {
	case runtimeErr := <-runtimeDone:
		t.Fatalf("daemon exited before READY: %v", runtimeErr)
	case <-time.After(100 * time.Millisecond):
	}
	ready, err := readControlEnvelopeResult(response.Body)
	if err != nil {
		select {
		case runtimeErr := <-runtimeDone:
			t.Fatalf("read READY: %v; daemon exited: %v", err, runtimeErr)
		default:
			t.Fatalf("read READY: %v; daemon is reconnecting", err)
		}
	}
	if ready.MessageType != protocol.MessageType_READY || ready.GetReady().GetDaemonVersion() != config.DaemonVersion {
		t.Fatalf("first runtime response=%v; want READY", ready)
	}
	if _, err := os.Stat(stagingPath); !os.IsNotExist(err) {
		t.Fatalf("startup skill staging path still exists: err=%v", err)
	}
	writeControlEnvelope(t, bodyWriter, &protocol.Envelope{
		MessageType: protocol.MessageType_TEMPORARY_RESOURCE_POLICY, EnvironmentId: proto.String(e2eEnvironmentID),
		Payload: &protocol.Envelope_TemporaryResourcePolicy{TemporaryResourcePolicy: &protocol.TemporaryResourcePolicy{
			TemporaryResourceTtlSeconds: proto.Int64(1), TemporaryResourceCleanupIntervalSeconds: proto.Int64(1),
		}},
	})
	resourceSweepDeadline := time.NewTimer(5 * time.Second)
	resourceSweepTicker := time.NewTicker(10 * time.Millisecond)
	defer resourceSweepDeadline.Stop()
	defer resourceSweepTicker.Stop()
	for {
		_, statErr := os.Stat(expiredWorkspace)
		if os.IsNotExist(statErr) {
			break
		}
		if statErr != nil {
			t.Fatalf("inspect expired temporary resource: %v", statErr)
		}
		select {
		case <-resourceSweepTicker.C:
		case <-resourceSweepDeadline.C:
			t.Fatal("production runtime did not sweep expired temporary resources")
		}
	}

	const missingInvocation = "123e4567-e89b-42d3-a456-426614174006"
	writeControlEnvelope(t, bodyWriter, &protocol.Envelope{
		MessageType: protocol.MessageType_RECOVER, EnvironmentId: proto.String(e2eEnvironmentID), InvocationId: proto.String(missingInvocation),
		Payload: &protocol.Envelope_Recover{Recover: &protocol.Recover{}},
	})
	recovered, err := readControlEnvelopeResult(response.Body)
	if err != nil || recovered.MessageType != protocol.MessageType_RECOVER_NOT_FOUND || recovered.GetInvocationId() != missingInvocation {
		t.Fatalf("missing invocation recovery=%v err=%v, want RECOVER_NOT_FOUND", recovered, err)
	}

	const ideInvocation = "123e4567-e89b-42d3-a456-426614174007"
	writeControlEnvelope(t, bodyWriter, &protocol.Envelope{
		MessageType: protocol.MessageType_IDE_COMMAND, EnvironmentId: proto.String(e2eEnvironmentID), InvocationId: proto.String(ideInvocation),
		Payload: &protocol.Envelope_IdeCommand{IdeCommand: &protocol.IdeCommand{Action: protocol.IdeAction_LIST_VIEWS}},
	})
	ideResult, err := readControlEnvelopeResult(response.Body)
	if err != nil || ideResult.MessageType != protocol.MessageType_IDE_RESULT || ideResult.GetInvocationId() != ideInvocation || !ideResult.GetIdeResult().GetOk() || ideResult.GetIdeResult().Revision == nil {
		t.Fatalf("production IDE command result=%v err=%v", ideResult, err)
	}
	const directoryInvocation = "123e4567-e89b-42d3-a456-426614174008"
	writeControlEnvelope(t, bodyWriter, &protocol.Envelope{
		MessageType: protocol.MessageType_IDE_COMMAND, EnvironmentId: proto.String(e2eEnvironmentID), InvocationId: proto.String(directoryInvocation),
		Payload: &protocol.Envelope_IdeCommand{IdeCommand: &protocol.IdeCommand{Action: protocol.IdeAction_LIST_DIRECTORIES, Directory: proto.String(directory)}},
	})
	directoryResult, err := readControlEnvelopeResult(response.Body)
	if err != nil {
		t.Fatalf("read production directory listing result: %v", err)
	}
	listing := directoryResult.GetIdeResult().GetDirectoryListing()
	if directoryResult.MessageType != protocol.MessageType_IDE_RESULT || directoryResult.GetInvocationId() != directoryInvocation || !directoryResult.GetIdeResult().GetOk() || listing == nil || listing.GetPath() != directory {
		t.Fatalf("production directory listing result=%v", directoryResult)
	}
	foundWorkspace := false
	for _, child := range listing.GetChildren() {
		if child.GetName() == "workspace" {
			foundWorkspace = true
			break
		}
	}
	if !foundWorkspace {
		t.Fatalf("production directory listing omitted workspace: %v", listing.GetChildren())
	}

	cases := []struct {
		name, capability, arguments, invocation, want string
	}{
		{"read", "fs.read", fmt.Sprintf(`{"path":%q}`, file), "123e4567-e89b-42d3-a456-426614174001", "needle original"},
		{"grep", "fs.grep", fmt.Sprintf(`{"path":%q,"pattern":"needle","literal":true}`, directory), "123e4567-e89b-42d3-a456-426614174002", "needle original"},
		{"edit", "fs.edit", fmt.Sprintf(`{"path":%q,"old_string":"original","new_string":"edited"}`, file), "123e4567-e89b-42d3-a456-426614174003", "edited"},
		{"process", "process.exec", fmt.Sprintf(`{"command":"printf protocol4-e2e","workdir":%q}`, directory), "123e4567-e89b-42d3-a456-426614174004", "protocol4-e2e"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			invoke := &protocol.Envelope{
				MessageType: protocol.MessageType_INVOKE, EnvironmentId: proto.String(e2eEnvironmentID), InvocationId: proto.String(tc.invocation),
				Payload: &protocol.Envelope_Invoke{Invoke: &protocol.Invoke{
					CapabilityId: proto.String(tc.capability), ArgumentsJson: proto.String(tc.arguments),
					TimeoutMillis: proto.Int64(10000), MaxOutputBytes: proto.Int64(10 << 20), ImageInputTier: imageTier(protocol.ImageInputTier_P720),
				}},
			}
			writeControlEnvelope(t, bodyWriter, invoke)
			var terminal *protocol.Envelope
			var executionExited bool
			var output strings.Builder
			for terminal == nil || tc.capability == "process.exec" && !executionExited {
				e, readErr := readControlEnvelopeResult(response.Body)
				if readErr != nil {
					select {
					case runtimeErr := <-runtimeDone:
						t.Fatalf("read invocation result: %v; daemon exited: %v", readErr, runtimeErr)
					default:
						t.Fatalf("read invocation result: %v; daemon is reconnecting", readErr)
					}
				}
				if e.GetInvocationId() != tc.invocation {
					continue
				}
				switch e.MessageType {
				case protocol.MessageType_STARTED, protocol.MessageType_PROGRESS:
					appendE2EContents(&output, e)
				case protocol.MessageType_COMPLETED:
					appendE2EContents(&output, e)
					terminal = e
				case protocol.MessageType_EXECUTION_EXITED:
					executionExited = true
				case protocol.MessageType_FAILED:
					t.Fatalf("%s invocation failed: %s", tc.capability, e.GetFailed().GetMessage())
				}
			}
			if !strings.Contains(output.String(), tc.want) {
				t.Fatalf("%s output=%q, want substring %q", tc.capability, output.String(), tc.want)
			}
			if tc.capability == "fs.read" && !strings.Contains(output.String(), "lsp: supported (text)") {
				t.Fatalf("fs.read did not report the configured LSP status: %q", output.String())
			}
		})
	}
	t.Run("output-limit", func(t *testing.T) {
		const invocation = "123e4567-e89b-42d3-a456-426614174005"
		writeControlEnvelope(t, bodyWriter, &protocol.Envelope{
			MessageType: protocol.MessageType_INVOKE, EnvironmentId: proto.String(e2eEnvironmentID), InvocationId: proto.String(invocation),
			Payload: &protocol.Envelope_Invoke{Invoke: &protocol.Invoke{
				CapabilityId: proto.String("fs.read"), ArgumentsJson: proto.String(fmt.Sprintf(`{"path":%q}`, file)),
				TimeoutMillis: proto.Int64(10000), MaxOutputBytes: proto.Int64(1), ImageInputTier: imageTier(protocol.ImageInputTier_P720),
			}},
		})
		for {
			e, readErr := readControlEnvelopeResult(response.Body)
			if readErr != nil {
				t.Fatalf("read over-limit invocation result: %v", readErr)
			}
			if e.GetInvocationId() != invocation {
				continue
			}
			if e.MessageType == protocol.MessageType_STARTED || e.MessageType == protocol.MessageType_PROGRESS {
				continue
			}
			if e.MessageType != protocol.MessageType_FAILED {
				t.Fatalf("over-limit terminal=%v, want FAILED", e)
			}
			if message := e.GetFailed().GetMessage(); !strings.Contains(message, "Tool output is ") || !strings.Contains(message, "exceeds the tool output size limit of 0 MiB") {
				t.Fatalf("over-limit failure message=%q", message)
			}
			break
		}
	})
	if got, err := os.ReadFile(file); err != nil || string(got) != "needle edited\n" {
		t.Fatalf("edited file=%q err=%v", got, err)
	}

	cancel()
	_ = bodyWriter.Close()
	select {
	case err := <-runtimeDone:
		if err != nil {
			t.Fatalf("daemon shutdown: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("daemon did not shut down after context cancellation")
	}
}

func writeControlEnvelope(t *testing.T, writer *io.PipeWriter, envelope *protocol.Envelope) {
	t.Helper()
	frame, err := protocol.EncodeEnvelopeFrame(envelope)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := writer.Write(frame); err != nil {
		t.Fatal(err)
	}
}

func readControlEnvelopeResult(reader io.Reader) (*protocol.Envelope, error) {
	frame, err := protocol.ReadFrame(reader)
	if err != nil {
		return nil, err
	}
	return protocol.UnmarshalEnvelope(frame)
}

func appendE2EContents(output *strings.Builder, envelope *protocol.Envelope) {
	var result *protocol.CapabilityResult
	switch envelope.MessageType {
	case protocol.MessageType_PROGRESS:
		result = envelope.GetProgress().GetResult()
	case protocol.MessageType_COMPLETED:
		result = envelope.GetCompleted().GetResult()
	}
	if result == nil {
		return
	}
	for _, content := range result.Contents {
		if text := content.GetText(); text != nil {
			output.WriteString(text.GetText())
		}
		if jsonContent := content.GetJson(); jsonContent != nil {
			output.WriteString(jsonContent.GetJson())
		}
	}
	var details map[string]any
	if json.Unmarshal([]byte(result.GetDetailsJson()), &details) == nil {
		output.WriteString(fmt.Sprint(details))
	}
}

func imageTier(value protocol.ImageInputTier) *protocol.ImageInputTier { return &value }
