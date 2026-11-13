package protocol

import (
	"strings"
	"testing"
)

// TRN-T-135 verifies each envelope payload reaches its corresponding field validator.
func TestValidateMessageDispatch(t *testing.T) {
	fields := (&Envelope{}).ProtoReflect().Descriptor().Fields()
	payload := (&Envelope{}).ProtoReflect().Descriptor().Oneofs().ByName("payload")
	for i := 0; i < fields.Len(); i++ {
		field := fields.Get(i)
		if field.ContainingOneof() != payload {
			continue
		}
		envelope := new(Envelope)
		message := envelope.ProtoReflect()
		message.Set(field, message.NewField(field))
		if err := validateMessage(envelope); err == nil && field.Name() != "cancel" && field.Name() != "started" && field.Name() != "recover" && field.Name() != "recover_not_found" && field.Name() != "execution_exited" {
			t.Errorf("empty %s payload passed validation", field.Name())
		}
	}
	if payload == nil || payload.Fields().Len() == 0 {
		t.Fatal("envelope payload oneof is empty")
	}
}

// TRN-T-137 verifies invocation scope classification for every protocol message type.
func TestValidateScopeMessageClassification(t *testing.T) {
	for _, messageType := range []MessageType{
		MessageType_SETTINGS, MessageType_READY, MessageType_HEARTBEAT, MessageType_RUNTIME_STATUS, MessageType_TEMPORARY_RESOURCE_POLICY, MessageType_UPDATE, MessageType_UPDATE_RESULT, MessageType_IDE_LEASE, MessageType_ENVIRONMENT_CONFIG,
	} {
		if err := validateScope(&Envelope{MessageType: messageType}); err != nil {
			t.Errorf("%s rejected without invocation id: %v", messageType, err)
		}
		if err := validateScope(&Envelope{MessageType: messageType, InvocationId: stringPointer("invocation")}); err == nil {
			t.Errorf("%s accepted invocation id", messageType)
		}
	}
	for _, messageType := range []MessageType{
		MessageType_INVOKE, MessageType_STARTED, MessageType_PROGRESS, MessageType_COMPLETED, MessageType_FAILED, MessageType_CANCEL, MessageType_CANCELLED, MessageType_RECOVER, MessageType_RECOVER_NOT_FOUND, MessageType_EXECUTION_EXITED, MessageType_RESOURCE_UPLOAD_REQUEST, MessageType_RESOURCE_UPLOAD_TICKET, MessageType_RESOURCE_UPLOAD_COMMIT, MessageType_RESOURCE_DOWNLOAD_REQUEST, MessageType_RESOURCE_DOWNLOAD_TICKET, MessageType_IDE_COMMAND, MessageType_IDE_RESULT,
	} {
		if err := validateScope(&Envelope{MessageType: messageType}); err == nil {
			t.Errorf("%s accepted missing invocation id", messageType)
		}
		if err := validateScope(&Envelope{MessageType: messageType, InvocationId: stringPointer("invocation")}); err != nil {
			t.Errorf("%s rejected invocation id: %v", messageType, err)
		}
	}
	if err := validateScope(&Envelope{MessageType: MessageType_MESSAGE_TYPE_UNSPECIFIED, InvocationId: stringPointer("😀")}); err != nil {
		t.Fatalf("unclassified message scope rejected: %v", err)
	}
	if err := validateScope(&Envelope{MessageType: MessageType_MESSAGE_TYPE_UNSPECIFIED, InvocationId: stringPointer(" \t")}); err == nil {
		t.Fatal("blank invocation id accepted")
	}
	if err := validateScope(&Envelope{MessageType: MessageType_MESSAGE_TYPE_UNSPECIFIED, InvocationId: stringPointer(strings.Repeat("😀", 129))}); err == nil {
		t.Fatal("overlong UTF-16 invocation id accepted")
	}
}

func stringPointer(s string) *string { return &s }
