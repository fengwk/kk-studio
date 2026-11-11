package protocol

import (
	"fmt"
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
		return nil, fmt.Errorf("control envelope exceeds %d bytes", MaxFrameSize)
	}
	return b, nil
}

// UnmarshalEnvelope decodes, rejects unknown fields and validates one control envelope.
func UnmarshalEnvelope(b []byte) (*Envelope, error) {
	if len(b) < 1 || len(b) > MaxFrameSize {
		return nil, fmt.Errorf("invalid control envelope length")
	}
	e := new(Envelope)
	if err := proto.Unmarshal(b, e); err != nil {
		return nil, fmt.Errorf("decode control envelope: %w", err)
	}
	if err := ValidateEnvelope(e); err != nil {
		return nil, err
	}
	return e, nil
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
	if e.EnvironmentId == nil || !canonicalUUID.MatchString(e.GetEnvironmentId()) {
		return fmt.Errorf("environment_id must be a canonical lowercase UUID")
	}
	invocationRequired := map[MessageType]bool{MessageType_INVOKE: true, MessageType_STARTED: true, MessageType_PROGRESS: true, MessageType_COMPLETED: true, MessageType_FAILED: true, MessageType_CANCEL: true, MessageType_CANCELLED: true, MessageType_RECOVER: true, MessageType_RECOVER_NOT_FOUND: true, MessageType_EXECUTION_EXITED: true}
	if invocationRequired[e.MessageType] {
		if e.InvocationId == nil || !canonicalUUID.MatchString(e.GetInvocationId()) {
			return fmt.Errorf("invocation_id must be a canonical lowercase UUID")
		}
	} else if e.InvocationId != nil {
		return fmt.Errorf("invocation_id is not allowed for %s", messageName)
	}
	if err := checkUnknown(e.ProtoReflect()); err != nil {
		return err
	}
	return validateJSONFields(e.ProtoReflect())
}

func toSnake(s string) string { return strings.ToLower(s) }

func checkUnknown(m protoreflect.Message) error {
	if len(m.GetUnknown()) != 0 {
		return fmt.Errorf("unknown protobuf fields are not allowed")
	}
	var err error
	m.Range(func(fd protoreflect.FieldDescriptor, v protoreflect.Value) bool {
		if fd.Kind() != protoreflect.MessageKind {
			return true
		}
		if fd.IsList() {
			l := v.List()
			for i := 0; i < l.Len(); i++ {
				if e := checkUnknown(l.Get(i).Message()); e != nil {
					err = e
					return false
				}
			}
		} else if fd.IsMap() {
			v.Map().Range(func(_ protoreflect.MapKey, mv protoreflect.Value) bool {
				err = checkUnknown(mv.Message())
				return err == nil
			})
		} else {
			err = checkUnknown(v.Message())
		}
		return err == nil
	})
	return err
}

func validateJSONFields(m protoreflect.Message) error {
	var err error
	m.Range(func(fd protoreflect.FieldDescriptor, v protoreflect.Value) bool {
		if fd.Kind() == protoreflect.StringKind && strings.HasSuffix(string(fd.Name()), "_json") {
			text := v.String()
			if text == "" {
				err = fmt.Errorf("%s must be valid JSON", fd.Name())
				return false
			}
			if e := ValidateJSON([]byte(text), JSONLimits{MaxBytes: 1 << 20}); e != nil {
				err = fmt.Errorf("%s: %w", fd.Name(), e)
				return false
			}
		}
		if fd.Kind() == protoreflect.MessageKind && !fd.IsList() && !fd.IsMap() {
			err = validateJSONFields(v.Message())
			return err == nil
		}
		return true
	})
	return err
}
