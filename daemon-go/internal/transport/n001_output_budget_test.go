package transport

import (
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"google.golang.org/protobuf/proto"
)

const outputLimitFailureMessage = "Tool output is 1.000001 MiB, which exceeds the tool output size limit of 1 MiB. The tool ran but its output was discarded; side effects may have occurred. Request a smaller result, for example a narrower read range or filtered command output, or ask the user to raise the limit in system settings."

// TRN-N-001 verifies a runtime FAILED result following an over-limit INVOKE is invocation-scoped
// and leaves the HTTP/2 control stream usable for the next invocation.
func TestTransport_OutputLimitFailureKeepsControlConnection(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	accepted := make(chan net.Conn, 1)
	shutdownPlatform := make(chan struct{})
	platform := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
		if err != nil {
			return
		}
		conn := websocket.NetConn(r.Context(), ws, websocket.MessageBinary)
		accepted <- conn
		select {
		case <-shutdownPlatform:
		case <-r.Context().Done():
		}
	}))
	defer platform.Close()
	defer close(shutdownPlatform)

	cc, err := Dial(ctx, DialConfig{
		Applied:    config.DaemonConfig{TokenPath: "token", StudioURL: platform.URL, EnvironmentID: testEnvironment},
		InstanceID: "daemon-instance", Platform: "linux",
		ReadToken: func(string) (string, error) { return "secret-token", nil },
	})
	if err != nil {
		t.Fatal(err)
	}
	defer cc.Close()

	peer := <-accepted
	protocols := new(http.Protocols)
	protocols.SetUnencryptedHTTP2(true)
	transport := &http.Transport{Protocols: protocols, DialContext: func(context.Context, string, string) (net.Conn, error) { return peer, nil }}
	client := &http.Client{Transport: transport}
	defer client.CloseIdleConnections()
	bodyReader, bodyWriter := io.Pipe()
	defer bodyWriter.Close()
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, "http://daemon/control", bodyReader)
	if err != nil {
		t.Fatal(err)
	}
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.ProtoMajor != 2 {
		t.Fatalf("control protocol = %s, want HTTP/2", response.Proto)
	}

	firstInvoke := n001Invoke("123e4567-e89b-12d3-a456-426614174001", 1<<20)
	writeN001Frame(t, bodyWriter, firstInvoke)
	received, err := cc.Receive(ctx)
	if err != nil {
		t.Fatalf("receive over-limit INVOKE: %v", err)
	}
	if received.GetInvocationId() != firstInvoke.GetInvocationId() || received.GetInvoke().GetMaxOutputBytes() != 1<<20 {
		t.Fatalf("received INVOKE = %v", received)
	}

	failed := &protocol.Envelope{
		MessageType:   protocol.MessageType_FAILED,
		EnvironmentId: proto.String(testEnvironment),
		InvocationId:  proto.String(firstInvoke.GetInvocationId()),
		Payload:       &protocol.Envelope_Failed{Failed: &protocol.Failed{Message: proto.String(outputLimitFailureMessage)}},
	}
	if err := cc.Send(ctx, failed); err != nil {
		t.Fatalf("send invocation FAILED: %v", err)
	}
	returnedFailure := readN001Envelope(t, response.Body)
	if returnedFailure.GetMessageType() != protocol.MessageType_FAILED || returnedFailure.GetInvocationId() != firstInvoke.GetInvocationId() || returnedFailure.GetFailed().GetMessage() != outputLimitFailureMessage {
		t.Fatalf("platform received failure = %v", returnedFailure)
	}

	secondInvoke := n001Invoke("123e4567-e89b-12d3-a456-426614174002", 2<<20)
	writeN001Frame(t, bodyWriter, secondInvoke)
	next, err := cc.Receive(ctx)
	if err != nil {
		t.Fatalf("control connection closed after invocation failure: %v", err)
	}
	if next.GetInvocationId() != secondInvoke.GetInvocationId() || next.GetInvoke().GetMaxOutputBytes() != 2<<20 {
		t.Fatalf("next INVOKE = %v", next)
	}

	nextFailure := &protocol.Envelope{
		MessageType:   protocol.MessageType_FAILED,
		EnvironmentId: proto.String(testEnvironment),
		InvocationId:  proto.String(secondInvoke.GetInvocationId()),
		Payload:       &protocol.Envelope_Failed{Failed: &protocol.Failed{Message: proto.String("next invocation failed")}},
	}
	if err := cc.Send(ctx, nextFailure); err != nil {
		t.Fatalf("send result on reused control connection: %v", err)
	}
	if got := readN001Envelope(t, response.Body); got.GetInvocationId() != secondInvoke.GetInvocationId() || got.GetFailed().GetMessage() != "next invocation failed" {
		t.Fatalf("platform received next result = %v", got)
	}
}

func n001Invoke(invocationID string, maxOutputBytes int64) *protocol.Envelope {
	return &protocol.Envelope{
		MessageType:   protocol.MessageType_INVOKE,
		EnvironmentId: proto.String(testEnvironment),
		InvocationId:  proto.String(invocationID),
		Payload: &protocol.Envelope_Invoke{Invoke: &protocol.Invoke{
			CapabilityId:   proto.String("fs.read"),
			ArgumentsJson:  proto.String(`{"path":"output.txt"}`),
			TimeoutMillis:  proto.Int64(1000),
			MaxOutputBytes: proto.Int64(maxOutputBytes),
		}},
	}
}

func writeN001Frame(t *testing.T, writer io.Writer, envelope *protocol.Envelope) {
	t.Helper()
	frame, err := protocol.EncodeEnvelopeFrame(envelope)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := writer.Write(frame); err != nil {
		t.Fatal(err)
	}
}

func readN001Envelope(t *testing.T, reader io.Reader) *protocol.Envelope {
	t.Helper()
	frame, err := protocol.ReadFrame(reader)
	if err != nil {
		t.Fatal(err)
	}
	envelope, err := protocol.UnmarshalEnvelope(frame)
	if err != nil {
		t.Fatal(err)
	}
	return envelope
}
