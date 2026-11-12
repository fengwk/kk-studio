package protocol

import (
	"strings"
	"testing"

	"google.golang.org/protobuf/proto"
)

// TRN-T-136
func TestCodec_UnsafeValues(t *testing.T) {
	for _, tc := range []struct {
		name   string
		invoke *Invoke
	}{
		{"negative-timeout", &Invoke{CapabilityId: proto.String("fs.read"), ArgumentsJson: proto.String(`{}`), TimeoutMillis: proto.Int64(-1)}},
		{"invalid-capability", &Invoke{CapabilityId: proto.String("FS.READ"), ArgumentsJson: proto.String(`{}`), TimeoutMillis: proto.Int64(0)}},
		{"non-object-arguments", &Invoke{CapabilityId: proto.String("fs.read"), ArgumentsJson: proto.String(`[]`), TimeoutMillis: proto.Int64(0)}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			e := testEnvelope(MessageType_INVOKE, testInvocationID, &Envelope_Invoke{Invoke: tc.invoke})
			if _, err := MarshalEnvelope(e); err == nil {
				t.Fatal("unsafe invoke accepted")
			}
		})
	}
}

// TRN-T-138
func TestCodec_WireBounds(t *testing.T) {
	for _, b := range [][]byte{nil, {0, 0, 0, 0}, {0, 0, 0, 1, 0, 0}} {
		if _, err := UnmarshalEnvelope(b); err == nil {
			t.Errorf("accepted wire %x", b)
		}
	}
	tooMany := make([]*Content, 65)
	for i := range tooMany {
		tooMany[i] = &Content{Body: &Content_Text{Text: &TextContent{Text: proto.String("")}}}
	}
	e := testEnvelope(MessageType_COMPLETED, testInvocationID, &Envelope_Completed{Completed: &Completed{Result: &CapabilityResult{CallId: proto.String("call"), Error: proto.Bool(false), DetailsJson: proto.String(`{}`), Contents: tooMany}}})
	if _, err := MarshalEnvelope(e); err == nil {
		t.Fatal("oversized contents accepted")
	}
}

// TRN-T-138
func TestCodec_EncodedEnvelopeFrameLimit(t *testing.T) {
	arguments := `{"x":"` + strings.Repeat("x", MaxFrameSize-9) + `"}`
	envelope := testEnvelope(MessageType_INVOKE, testInvocationID, &Envelope_Invoke{Invoke: &Invoke{CapabilityId: proto.String("fs.read"), ArgumentsJson: &arguments, TimeoutMillis: proto.Int64(0)}})
	if _, err := MarshalEnvelope(envelope); err == nil {
		t.Fatal("envelope larger than the frame limit accepted")
	}
	if _, err := EncodeEnvelopeFrame(envelope); err == nil {
		t.Fatal("oversized envelope frame accepted")
	}
	if _, err := DecodeEnvelopeFrame([]byte{0, 0, 0, 1, 0}); err == nil {
		t.Fatal("invalid framed protobuf accepted")
	}
}

// TRN-T-141
func TestCodec_RuntimeConfigStatus(t *testing.T) {
	s := &RuntimeSnapshot{Revision: proto.Int64(1), Status: &RuntimeBody{DaemonVersion: proto.String("1.0.0"), Configuration: &ConfigurationStatus{ProxyUrl: proto.String("http://proxy:8080")}}}
	e := testEnvelope(MessageType_RUNTIME_STATUS, "", &Envelope_RuntimeStatus{RuntimeStatus: &RuntimeStatus{RuntimeStatus: s}})
	if _, err := MarshalEnvelope(e); err == nil {
		t.Fatal("proxy without applied version accepted")
	}
}

// TRN-T-145
func TestCodec_InvocationEvents(t *testing.T) {
	for _, e := range []*Envelope{
		testEnvelope(MessageType_PROGRESS, testInvocationID, &Envelope_Progress{Progress: &Progress{Result: &CapabilityResult{CallId: proto.String("call"), Error: proto.Bool(false), DetailsJson: proto.String(`{}`), Contents: []*Content{{Body: &Content_Resource{Resource: &ResourceContent{UploadId: proto.String(testEnvironmentID), MediaType: proto.String("application/zip"), Name: proto.String("a.zip"), Size: proto.Int64(1), Sha256: proto.String(testSHA256)}}}}}}}),
		testEnvelope(MessageType_FAILED, testInvocationID, &Envelope_Failed{Failed: &Failed{Message: proto.String("")}}),
		testEnvelope(MessageType_CANCELLED, testInvocationID, &Envelope_Cancelled{Cancelled: &Cancelled{Reason: proto.String("")}}),
	} {
		if _, err := MarshalEnvelope(e); err == nil {
			t.Errorf("accepted invalid event %s", e.MessageType)
		}
	}
}

// TRN-T-147
func TestCodec_Download(t *testing.T) {
	e := testEnvelope(MessageType_RESOURCE_DOWNLOAD_TICKET, testInvocationID, &Envelope_ResourceDownloadTicket{ResourceDownloadTicket: &ResourceDownloadTicket{TransferId: proto.String(testEnvironmentID), State: TicketState_TICKET_READY, PresignedGet: &PresignedGet{Method: proto.String("GET"), Url: proto.String("https://store.example/object?token=x"), ExpiresAtEpochMs: proto.Int64(1)}, Size: proto.Int64(3), Sha256: proto.String(testSHA256)}})
	if _, err := MarshalEnvelope(e); err != nil {
		t.Fatal(err)
	}
	bad := proto.Clone(e).(*Envelope)
	bad.GetResourceDownloadTicket().PresignedGet.Method = proto.String("PUT")
	if _, err := MarshalEnvelope(bad); err == nil {
		t.Fatal("invalid GET method accepted")
	}
}

// TRN-T-148
func TestCodec_UpdatePolicy(t *testing.T) {
	for _, e := range []*Envelope{
		testEnvelope(MessageType_UPDATE, "", &Envelope_Update{Update: &Update{OperationId: proto.String(testEnvironmentID), TargetVersion: proto.String("1.0.0")}}),
		testEnvelope(MessageType_TEMPORARY_RESOURCE_POLICY, "", &Envelope_TemporaryResourcePolicy{TemporaryResourcePolicy: &TemporaryResourcePolicy{TemporaryResourceTtlSeconds: proto.Int64(1), TemporaryResourceCleanupIntervalSeconds: proto.Int64(1)}}),
	} {
		if _, err := MarshalEnvelope(e); err != nil {
			t.Fatalf("valid message rejected: %v", err)
		}
	}
	bad := testEnvelope(MessageType_UPDATE_RESULT, "", &Envelope_UpdateResult{UpdateResult: &UpdateResult{OperationId: proto.String("op"), Phase: UpdatePhase_UPDATE_FAILED, Message: proto.String("bad\nmessage")}})
	if _, err := MarshalEnvelope(bad); err == nil {
		t.Fatal("control character in update result accepted")
	}
}

// TRN-T-149
func TestCodec_IDEAndError(t *testing.T) {
	bad := testEnvelope(MessageType_IDE_LEASE, "", &Envelope_IdeLease{IdeLease: &IdeLease{Round: proto.Int64(1), Bindings: []*IdeLeaseBinding{{ViewId: proto.String(testEnvironmentID), BrowserSessionId: proto.String(testEnvironmentID), BindingEpoch: proto.Int64(1)}, {ViewId: proto.String(testEnvironmentID), BrowserSessionId: proto.String(testEnvironmentID), BindingEpoch: proto.Int64(2)}}}})
	if _, err := MarshalEnvelope(bad); err == nil {
		t.Fatal("duplicate IDE lease binding accepted")
	}
	e := testEnvelope(MessageType_ERROR, "", &Envelope_Error{Error: &Error{Message: proto.String("")}})
	if _, err := MarshalEnvelope(e); err == nil {
		t.Fatal("empty ERROR accepted")
	}
}

// TRN-T-151
func TestCodec_IDEActions(t *testing.T) {
	valid := testEnvelope(MessageType_IDE_COMMAND, "invocation-1", &Envelope_IdeCommand{IdeCommand: &IdeCommand{Action: IdeAction_OPEN_VIEW, ViewId: proto.String(testEnvironmentID), BrowserSessionId: proto.String(testEnvironmentID), EventNodeId: proto.String(testEnvironmentID), ConnectionId: proto.String(testEnvironmentID), ClaimId: proto.String(testEnvironmentID), ExpectedBindingEpoch: proto.Int64(0), ExpectedDaemonInstanceId: proto.String(testEnvironmentID), Mode: IdeOpenMode_CREATE}})
	if _, err := MarshalEnvelope(valid); err != nil {
		t.Fatal(err)
	}
	bad := proto.Clone(valid).(*Envelope)
	bad.GetIdeCommand().Directory = proto.String("bad\x00path")
	if _, err := MarshalEnvelope(bad); err == nil {
		t.Fatal("invalid IDE path accepted")
	}
	bound := func(action IdeAction) *IdeCommand {
		return &IdeCommand{Action: action, ViewId: proto.String(testEnvironmentID), BrowserSessionId: proto.String(testEnvironmentID), BindingEpoch: proto.Int64(1), ExpectedDaemonInstanceId: proto.String(testEnvironmentID)}
	}
	commands := []*IdeCommand{bound(IdeAction_CHANGE_DIRECTORY), bound(IdeAction_FOCUS_TERMINAL), bound(IdeAction_COLLAPSE_MOBILE), bound(IdeAction_CLOSE_VIEW), {Action: IdeAction_LIST_VIEWS}, {Action: IdeAction_LIST_DIRECTORIES, Directory: proto.String("workspace")}}
	commands[0].Directory = proto.String("workspace")
	commands[3].Discard = proto.Bool(false)
	for _, command := range commands {
		e := testEnvelope(MessageType_IDE_COMMAND, "invocation-1", &Envelope_IdeCommand{IdeCommand: command})
		if _, err := MarshalEnvelope(e); err != nil {
			t.Errorf("action %s rejected: %v", command.Action, err)
		}
	}
	results := []*IdeResult{{Ok: proto.Bool(true), ViewId: proto.String(testEnvironmentID)}, {Ok: proto.Bool(true), ViewId: proto.String(testEnvironmentID), Directory: proto.String("workspace")}, {Ok: proto.Bool(true), DirectoryListing: &IdeDirectoryListing{Path: proto.String("workspace"), Truncated: proto.Bool(false)}}, {Ok: proto.Bool(false), Code: proto.String("VIEW_GONE"), Message: proto.String("gone")}}
	for _, result := range results {
		e := testEnvelope(MessageType_IDE_RESULT, "invocation-1", &Envelope_IdeResult{IdeResult: result})
		if _, err := MarshalEnvelope(e); err != nil {
			t.Errorf("IDE result rejected: %v", err)
		}
	}
}

// TRN-T-152
func TestCodec_UploadTicketStates(t *testing.T) {
	ready := testEnvelope(MessageType_RESOURCE_UPLOAD_TICKET, testInvocationID, &Envelope_ResourceUploadTicket{ResourceUploadTicket: &ResourceUploadTicket{TransferId: proto.String(testEnvironmentID), State: TicketState_TICKET_READY, UploadId: proto.String(testEnvironmentID)}})
	if _, err := MarshalEnvelope(ready); err != nil {
		t.Fatal(err)
	}
	failed := proto.Clone(ready).(*Envelope)
	failed.GetResourceUploadTicket().State = TicketState_TICKET_FAILED
	failed.GetResourceUploadTicket().UploadId = nil
	failed.GetResourceUploadTicket().Message = proto.String("storage failed")
	if _, err := MarshalEnvelope(failed); err != nil {
		t.Fatal(err)
	}
	failed.GetResourceUploadTicket().UploadId = proto.String(testEnvironmentID)
	if _, err := MarshalEnvelope(failed); err == nil {
		t.Fatal("FAILED ticket with upload id accepted")
	}
}

// TRN-T-155
func TestCodec_TargetVersion(t *testing.T) {
	if err := ValidateUpgrade(&UpgradeRequired{TargetVersion: proto.String("1.2.3")}); err != nil {
		t.Fatal(err)
	}
	if err := ValidateUpgrade(&UpgradeRequired{TargetVersion: proto.String("bad version")}); err == nil {
		t.Fatal("invalid target version accepted")
	}
}

// TRN-T-158
func TestCodec_IDESnapshot(t *testing.T) {
	views := []*IdeViewSummary{{ViewId: proto.String("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), BrowserSessionId: proto.String(testEnvironmentID), EventNodeId: proto.String(testEnvironmentID), ConnectionId: proto.String(testEnvironmentID), ClaimId: proto.String(testEnvironmentID), BindingEpoch: proto.Int64(1), Directory: proto.String("/"), WorkspacePath: proto.String("/"), State: IdeViewState_OPEN}, {ViewId: proto.String("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), BrowserSessionId: proto.String(testEnvironmentID), EventNodeId: proto.String(testEnvironmentID), ConnectionId: proto.String(testEnvironmentID), ClaimId: proto.String(testEnvironmentID), BindingEpoch: proto.Int64(1), Directory: proto.String("/"), WorkspacePath: proto.String("/"), State: IdeViewState_OPEN}}
	e := testEnvelope(MessageType_IDE_RESULT, "invocation-1", &Envelope_IdeResult{IdeResult: &IdeResult{Ok: proto.Bool(true), Revision: proto.Int64(1), Views: views}})
	if _, err := MarshalEnvelope(e); err != nil {
		t.Fatal(err)
	}
	bad := proto.Clone(e).(*Envelope)
	bad.GetIdeResult().Views[0], bad.GetIdeResult().Views[1] = bad.GetIdeResult().Views[1], bad.GetIdeResult().Views[0]
	if _, err := MarshalEnvelope(bad); err == nil {
		t.Fatal("unsorted snapshot accepted")
	}
}

// TRN-T-159
func TestCodec_IDEBinding(t *testing.T) {
	id := "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
	view := &IdeViewSummary{ViewId: proto.String(id), BrowserSessionId: proto.String(testEnvironmentID), EventNodeId: proto.String(testEnvironmentID), ConnectionId: proto.String(testEnvironmentID), ClaimId: proto.String(testEnvironmentID), BindingEpoch: proto.Int64(1), Directory: proto.String("/"), WorkspacePath: proto.String("/"), State: IdeViewState_OPEN}
	e := testEnvelope(MessageType_IDE_RESULT, "invocation-1", &Envelope_IdeResult{IdeResult: &IdeResult{Ok: proto.Bool(true), ViewId: proto.String(id), BrowserSessionId: proto.String(testEnvironmentID), EventNodeId: proto.String(testEnvironmentID), ConnectionId: proto.String(testEnvironmentID), ClaimId: proto.String(testEnvironmentID), BindingEpoch: proto.Int64(1), Directory: proto.String("/"), WorkspacePath: proto.String("/"), Revision: proto.Int64(1), Views: []*IdeViewSummary{view}}})
	if _, err := MarshalEnvelope(e); err != nil {
		t.Fatal(err)
	}
	bad := proto.Clone(e).(*Envelope)
	bad.GetIdeResult().Views[0].BindingEpoch = proto.Int64(2)
	if _, err := MarshalEnvelope(bad); err == nil {
		t.Fatal("snapshot epoch beyond revision accepted")
	}
	bad = proto.Clone(e).(*Envelope)
	bad.GetIdeResult().BrowserSessionId = proto.String("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
	if _, err := MarshalEnvelope(bad); err == nil {
		t.Fatal("snapshot binding mismatch accepted")
	}
}
