package protocol

import (
	"bytes"
	"testing"

	"google.golang.org/protobuf/proto"
)

const testEnvironmentID = "123e4567-e89b-12d3-a456-426614174000"
const testInvocationID = "runtime-call-1"
const testSHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

func testEnvelope(kind MessageType, invocation string, payload isEnvelope_Payload) *Envelope {
	e := &Envelope{MessageType: kind, EnvironmentId: proto.String(testEnvironmentID), Payload: payload}
	if invocation != "" {
		e.InvocationId = proto.String(invocation)
	}
	return e
}

// TRN-T-135
func TestCodec_NilPayloadRejected(t *testing.T) {
	e := testEnvelope(MessageType_SETTINGS, "", &Envelope_Settings{})
	if _, err := MarshalEnvelope(e); err == nil {
		t.Fatal("nil payload object accepted")
	}
}

// TRN-T-128
func TestCodec_ProtocolV4(t *testing.T) {
	if ControlProtocolVersion != 4 {
		t.Fatalf("version=%d", ControlProtocolVersion)
	}
}

// TRN-T-132
func TestCodec_OpaqueJSON(t *testing.T) {
	text := ` { "b" : 2, "a":1 } `
	e := testEnvelope(MessageType_INVOKE, testInvocationID, &Envelope_Invoke{Invoke: &Invoke{CapabilityId: proto.String("fs.read"), ArgumentsJson: &text, TimeoutMillis: proto.Int64(0)}})
	data, err := MarshalEnvelope(e)
	if err != nil {
		t.Fatal(err)
	}
	got, err := UnmarshalEnvelope(data)
	if err != nil {
		t.Fatal(err)
	}
	if got.GetInvoke().GetArgumentsJson() != text {
		t.Fatalf("JSON changed: %q", got.GetInvoke().GetArgumentsJson())
	}
}

// TRN-T-134
func TestCodec_UnknownProtobuf(t *testing.T) {
	e := testEnvelope(MessageType_ERROR, "", &Envelope_Error{Error: &Error{Message: proto.String("failure")}})
	data, err := proto.Marshal(e)
	if err != nil {
		t.Fatal(err)
	}
	data = append(data, 0xf8, 0x07, 0x01)
	if _, err := UnmarshalEnvelope(data); err == nil {
		t.Fatal("unknown envelope field accepted")
	}
	e.GetError().ProtoReflect().SetUnknown([]byte{0xf8, 0x07, 0x01})
	data, err = proto.Marshal(e)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := UnmarshalEnvelope(data); err == nil {
		t.Fatal("unknown nested field accepted")
	}
}

// TRN-T-135
func TestCodec_JSONRequired(t *testing.T) {
	bad := testEnvelope(MessageType_INVOKE, testInvocationID, &Envelope_Invoke{Invoke: &Invoke{CapabilityId: proto.String("fs.read"), ArgumentsJson: proto.String(""), TimeoutMillis: proto.Int64(0)}})
	if _, err := MarshalEnvelope(bad); err == nil {
		t.Fatal("empty arguments JSON accepted")
	}
	dup := testEnvelope(MessageType_INVOKE, testInvocationID, &Envelope_Invoke{Invoke: &Invoke{CapabilityId: proto.String("fs.read"), ArgumentsJson: proto.String(`{"a":1,"a":2}`), TimeoutMillis: proto.Int64(0)}})
	if _, err := MarshalEnvelope(dup); err == nil {
		t.Fatal("duplicate JSON key accepted")
	}
}

// TRN-T-137
func TestCodec_ScopeMismatch(t *testing.T) {
	valid := testEnvelope(MessageType_INVOKE, testInvocationID, &Envelope_Invoke{Invoke: &Invoke{CapabilityId: proto.String("fs.read"), ArgumentsJson: proto.String(`{}`), TimeoutMillis: proto.Int64(0)}})
	if _, err := MarshalEnvelope(valid); err != nil {
		t.Fatalf("valid invocation scope rejected: %v", err)
	}
	wrong := testEnvelope(MessageType_ERROR, "", &Envelope_Invoke{Invoke: &Invoke{}})
	if _, err := MarshalEnvelope(wrong); err == nil {
		t.Fatal("payload mismatch accepted")
	}
	missing := testEnvelope(MessageType_ERROR, "", &Envelope_Error{Error: &Error{Message: proto.String("x")}})
	missing.EnvironmentId = nil
	if _, err := MarshalEnvelope(missing); err == nil {
		t.Fatal("missing environment id accepted")
	}
}

// TRN-T-139
func TestCodec_Settings(t *testing.T) {
	for _, v := range []int64{0, -1} {
		e := testEnvelope(MessageType_SETTINGS, "", &Envelope_Settings{Settings: &Settings{ResourceBudgets: &ResourceBudgets{MaxResourceBytes: proto.Int64(v), TemporaryResourceTtlSeconds: proto.Int64(1), TemporaryResourceCleanupIntervalSeconds: proto.Int64(1)}}})
		if _, err := MarshalEnvelope(e); err == nil {
			t.Fatalf("accepted max resource bytes %d", v)
		}
	}
}

// TRN-T-140
func TestCodec_EnvironmentConfig(t *testing.T) {
	e := testEnvelope(MessageType_ENVIRONMENT_CONFIG, "", &Envelope_EnvironmentConfig{EnvironmentConfig: &EnvironmentConfig{Version: proto.Int64(0), Config: &DaemonConfiguration{StudioUrl: "https://studio.example"}}})
	if _, err := MarshalEnvelope(e); err != nil {
		t.Fatalf("valid snapshot rejected: %v", err)
	}
	bad := testEnvelope(MessageType_ENVIRONMENT_CONFIG, "", &Envelope_EnvironmentConfig{EnvironmentConfig: &EnvironmentConfig{Version: proto.Int64(-1), Config: &DaemonConfiguration{StudioUrl: "https://studio.example"}}})
	if _, err := MarshalEnvelope(bad); err == nil {
		t.Fatal("negative version accepted")
	}
	server := &LspServer{Id: "gopls", Command: []string{"gopls"}, Extensions: []string{".go"}, RootMarkers: []string{"go.mod"}}
	withLsp := testEnvelope(MessageType_ENVIRONMENT_CONFIG, "", &Envelope_EnvironmentConfig{EnvironmentConfig: &EnvironmentConfig{Version: proto.Int64(1), Config: &DaemonConfiguration{StudioUrl: "https://studio.example", Lsp: &LspConfiguration{Servers: []*LspServer{server}}}}})
	if _, err := MarshalEnvelope(withLsp); err != nil {
		t.Fatalf("valid LSP config rejected: %v", err)
	}
	duplicate := proto.Clone(withLsp).(*Envelope)
	duplicate.GetEnvironmentConfig().Config.Lsp.Servers = append(duplicate.GetEnvironmentConfig().Config.Lsp.Servers, proto.Clone(server).(*LspServer))
	if _, err := MarshalEnvelope(duplicate); err == nil {
		t.Fatal("duplicate LSP server id accepted")
	}
}

// TRN-T-142
func TestCodec_Ready(t *testing.T) {
	e := testEnvelope(MessageType_READY, "", &Envelope_Ready{Ready: &Ready{DaemonVersion: proto.String("1.0.0"), HostInfo: &HostInfo{OperatingSystem: OperatingSystem_LINUX, Architecture: Architecture_AMD64}, RuntimeStatus: &RuntimeSnapshot{Revision: proto.Int64(1), Status: &RuntimeBody{DaemonVersion: proto.String("1.0.0"), Configuration: &ConfigurationStatus{AppliedVersion: proto.Int64(0)}}}}})
	if _, err := MarshalEnvelope(e); err != nil {
		t.Fatal(err)
	}
	bad := testEnvelope(MessageType_READY, "", &Envelope_Ready{Ready: &Ready{DaemonVersion: proto.String("not-version")}})
	if _, err := MarshalEnvelope(bad); err == nil {
		t.Fatal("invalid READY accepted")
	}
}

// TRN-T-143
func TestCodec_HeartbeatRuntimeStatus(t *testing.T) {
	e := testEnvelope(MessageType_HEARTBEAT, "", &Envelope_Heartbeat{Heartbeat: &Heartbeat{RuntimeStatus: &RuntimeSnapshot{Revision: proto.Int64(1), Status: &RuntimeBody{DaemonVersion: proto.String("1.0.0")}}}})
	if _, err := MarshalEnvelope(e); err != nil {
		t.Fatal(err)
	}
	bad := testEnvelope(MessageType_RUNTIME_STATUS, "", &Envelope_RuntimeStatus{RuntimeStatus: &RuntimeStatus{RuntimeStatus: &RuntimeSnapshot{Revision: proto.Int64(0), Status: &RuntimeBody{DaemonVersion: proto.String("1.0.0")}}}})
	if _, err := MarshalEnvelope(bad); err == nil {
		t.Fatal("zero revision accepted")
	}
}

// TRN-T-144
func TestCodec_InvokeStarted(t *testing.T) {
	valid := testEnvelope(MessageType_INVOKE, testInvocationID, &Envelope_Invoke{Invoke: &Invoke{CapabilityId: proto.String("fs.read"), ArgumentsJson: proto.String(`{"x":1}`), TimeoutMillis: proto.Int64(0)}})
	if _, err := MarshalEnvelope(valid); err != nil {
		t.Fatal(err)
	}
	bad := testEnvelope(MessageType_STARTED, testInvocationID, &Envelope_Started{Started: &Started{Replayed: proto.Bool(false)}})
	if _, err := MarshalEnvelope(bad); err == nil {
		t.Fatal("false replayed accepted")
	}
}

// TRN-T-146
func TestCodec_Upload(t *testing.T) {
	e := testEnvelope(MessageType_RESOURCE_UPLOAD_REQUEST, testInvocationID, &Envelope_ResourceUploadRequest{ResourceUploadRequest: &ResourceUploadRequest{TransferId: proto.String(testEnvironmentID), MediaType: proto.String("application/zip"), Name: proto.String("skill.zip"), Size: proto.Int64(1), Sha256: proto.String(testSHA256)}})
	if _, err := MarshalEnvelope(e); err != nil {
		t.Fatalf("valid upload request rejected: %v", err)
	}
	bad := proto.Clone(e).(*Envelope)
	bad.GetResourceUploadRequest().Sha256 = proto.String("ABC")
	if _, err := MarshalEnvelope(bad); err == nil {
		t.Fatal("invalid sha256 accepted")
	}
}

// TRN-T-156
func TestCodec_MaxMessage(t *testing.T) {
	payload := bytes.Repeat([]byte{1}, MaxFrameSize)
	if _, err := EncodeFrame(payload); err != nil {
		t.Fatalf("maximum frame rejected: %v", err)
	}
	if _, err := EncodeFrame(append(payload, 1)); err == nil {
		t.Fatal("oversized frame accepted")
	}
}

// TRN-T-157
func TestCodec_BigEndianPrefix(t *testing.T) {
	frame, err := EncodeFrame([]byte{0xaa, 0xbb})
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(frame[:4], []byte{0, 0, 0, 2}) {
		t.Fatalf("prefix=%x", frame[:4])
	}
}
