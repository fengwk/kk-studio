package protocol

import (
	"fmt"
	"reflect"
	"regexp"
	"strings"

	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/reflect/protoreflect"
)

const ControlProtocolVersion = 4

var canonicalUUID = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// MarshalEnvelope validates and protobuf-encodes one control envelope.
func MarshalEnvelope(e *Envelope) ([]byte, error) {
	if err := ValidateEnvelope(e); err != nil {
		return nil, err
	}
	b, err := proto.Marshal(e)
	if err != nil {
		return nil, fmt.Errorf("encode control envelope: %w", err)
	}
	if len(b) < 1 || len(b) > MaxFrameSize {
		return nil, fmt.Errorf("control message length must be in 1..%d bytes", MaxFrameSize)
	}
	return b, nil
}

// UnmarshalEnvelope decodes, rejects unknown fields and validates one control envelope.
func UnmarshalEnvelope(b []byte) (*Envelope, error) {
	if len(b) < 1 || len(b) > MaxFrameSize {
		return nil, fmt.Errorf("control message length must be in 1..%d bytes", MaxFrameSize)
	}
	e := new(Envelope)
	if err := (proto.UnmarshalOptions{DiscardUnknown: false}).Unmarshal(b, e); err != nil {
		return nil, fmt.Errorf("decode control envelope: %w", err)
	}
	if err := ValidateEnvelope(e); err != nil {
		return nil, err
	}
	return e, nil
}

// ValidateUpgrade validates the optional daemon version advertised in HTTP 426 responses.
func ValidateUpgrade(upgrade *UpgradeRequired) error {
	if upgrade == nil {
		return fmt.Errorf("upgradeRequired is required")
	}
	if err := checkUnknownAt(upgrade.ProtoReflect(), "upgradeRequired"); err != nil {
		return err
	}
	if upgrade.TargetVersion == nil || !versionPattern.MatchString(upgrade.GetTargetVersion()) {
		return fmt.Errorf("upgradeRequired.targetVersion has invalid syntax")
	}
	return nil
}

// ValidateEnvelope enforces protocol version, payload, scope, and protobuf unknown-field rules.
func ValidateEnvelope(e *Envelope) error {
	if e == nil {
		return fmt.Errorf("envelope is required")
	}
	if e.MessageType <= MessageType_MESSAGE_TYPE_UNSPECIFIED {
		return fmt.Errorf("message_type is invalid")
	}
	desc := e.ProtoReflect().Descriptor()
	messageName := e.MessageType.String()
	fd := desc.Fields().ByName(protoreflect.Name(toSnake(messageName)))
	if fd == nil {
		return fmt.Errorf("unsupported message_type %s", messageName)
	}
	payload := e.ProtoReflect().WhichOneof(desc.Oneofs().ByName("payload"))
	if payload == nil || payload.Name() != fd.Name() {
		return fmt.Errorf("message_type and payload mismatch")
	}
	payloadValue := reflect.ValueOf(e.Payload)
	if payloadValue.Kind() != reflect.Pointer || payloadValue.IsNil() || payloadValue.Elem().Field(0).IsNil() {
		return fmt.Errorf("envelope.%s is required", payload.Name())
	}
	if e.EnvironmentId == nil || !canonicalUUID.MatchString(e.GetEnvironmentId()) {
		return fmt.Errorf("environment_id must be a canonical lowercase UUID")
	}
	if err := validateScope(e); err != nil {
		return err
	}
	if err := checkUnknown(e.ProtoReflect()); err != nil {
		return err
	}
	return validateMessage(e)
}

func toSnake(s string) string { return strings.ToLower(s) }

func validateScope(e *Envelope) error {
	switch e.MessageType {
	case MessageType_SETTINGS, MessageType_READY, MessageType_HEARTBEAT, MessageType_RUNTIME_STATUS, MessageType_TEMPORARY_RESOURCE_POLICY, MessageType_UPDATE, MessageType_UPDATE_RESULT, MessageType_IDE_LEASE, MessageType_ENVIRONMENT_CONFIG:
		if e.InvocationId != nil {
			return fmt.Errorf("%s must not declare invocationId", e.MessageType)
		}
	case MessageType_INVOKE, MessageType_STARTED, MessageType_PROGRESS, MessageType_COMPLETED, MessageType_FAILED, MessageType_CANCEL, MessageType_CANCELLED, MessageType_RECOVER, MessageType_RECOVER_NOT_FOUND, MessageType_EXECUTION_EXITED, MessageType_RESOURCE_UPLOAD_REQUEST, MessageType_RESOURCE_UPLOAD_TICKET, MessageType_RESOURCE_UPLOAD_COMMIT, MessageType_RESOURCE_DOWNLOAD_REQUEST, MessageType_RESOURCE_DOWNLOAD_TICKET, MessageType_IDE_COMMAND, MessageType_IDE_RESULT:
		if e.InvocationId == nil {
			return fmt.Errorf("%s requires invocationId", e.MessageType)
		}
	}
	if e.InvocationId != nil && (strings.TrimSpace(e.GetInvocationId()) == "" || utf16Length(e.GetInvocationId()) > 256) {
		return fmt.Errorf("invocationId must be non-blank and at most 256 characters")
	}
	return nil
}

func checkUnknown(m protoreflect.Message) error { return checkUnknownAt(m, "envelope") }

func checkUnknownAt(m protoreflect.Message, path string) error {
	if len(m.GetUnknown()) != 0 {
		return fmt.Errorf("%s contains unknown protobuf fields", path)
	}
	var err error
	m.Range(func(fd protoreflect.FieldDescriptor, v protoreflect.Value) bool {
		if fd.Kind() != protoreflect.MessageKind {
			return true
		}
		next := path + "." + string(fd.Name())
		if fd.IsMap() {
			if fd.MapValue().Kind() != protoreflect.MessageKind {
				return true
			}
			v.Map().Range(func(key protoreflect.MapKey, mv protoreflect.Value) bool {
				err = checkUnknownAt(mv.Message(), fmt.Sprintf("%s[%v]", next, key.Interface()))
				return err == nil
			})
		} else if fd.IsList() {
			l := v.List()
			for i := 0; i < l.Len(); i++ {
				if e := checkUnknownAt(l.Get(i).Message(), fmt.Sprintf("%s[%d]", next, i)); e != nil {
					err = e
					return false
				}
			}
		} else {
			err = checkUnknownAt(v.Message(), next)
		}
		return err == nil
	})
	return err
}
