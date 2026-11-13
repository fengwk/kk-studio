package protocol

import (
	"reflect"
	"strings"
	"testing"

	"google.golang.org/protobuf/reflect/protoreflect"
	"google.golang.org/protobuf/reflect/protoregistry"
)

// TRN-T-128 exercises generated protobuf message and enum APIs used by the codec.
func TestGeneratedProtocolAPIs(t *testing.T) {
	protoregistry.GlobalTypes.RangeMessages(func(messageType protoreflect.MessageType) bool {
		message := messageType.New().Interface()
		messageValue := reflect.ValueOf(message)
		messageTypeOf := messageValue.Type()
		populateGeneratedFields(message.ProtoReflect())
		for _, name := range []string{"String", "ProtoReflect", "Descriptor"} {
			method := messageValue.MethodByName(name)
			if !method.IsValid() {
				t.Errorf("%s has no generated %s method", messageTypeOf, name)
				continue
			}
			method.Call(nil)
		}
		reflect.Zero(messageTypeOf).MethodByName("ProtoReflect").Call(nil)
		for i := 0; i < messageTypeOf.NumMethod(); i++ {
			name := messageTypeOf.Method(i).Name
			if !strings.HasPrefix(name, "Get") {
				continue
			}
			zero := reflect.Zero(messageTypeOf).MethodByName(name)
			zero.Call(nil)
			messageValue.MethodByName(name).Call(nil)
		}
		messageValue.MethodByName("Reset").Call(nil)
		return true
	})
	protoregistry.GlobalTypes.RangeEnums(func(enumType protoreflect.EnumType) bool {
		for i := 0; i < enumType.Descriptor().Values().Len(); i++ {
			enum := reflect.ValueOf(enumType.New(enumType.Descriptor().Values().Get(i).Number()))
			for _, name := range []string{"Enum", "String", "Descriptor", "Type", "Number", "EnumDescriptor"} {
				if method := enum.MethodByName(name); method.IsValid() {
					method.Call(nil)
				}
			}
		}
		return true
	})
}

func populateGeneratedFields(message protoreflect.Message) {
	usedOneofs := make(map[protoreflect.Name]bool)
	fields := message.Descriptor().Fields()
	for i := 0; i < fields.Len(); i++ {
		field := fields.Get(i)
		if field.IsList() || field.IsMap() {
			continue
		}
		if oneof := field.ContainingOneof(); oneof != nil {
			if usedOneofs[oneof.Name()] {
				continue
			}
			usedOneofs[oneof.Name()] = true
		}
		var value protoreflect.Value
		switch field.Kind() {
		case protoreflect.BoolKind:
			value = protoreflect.ValueOfBool(true)
		case protoreflect.EnumKind:
			if field.Enum().Values().Len() == 0 {
				continue
			}
			value = protoreflect.ValueOfEnum(field.Enum().Values().Get(0).Number())
		case protoreflect.Int32Kind, protoreflect.Sint32Kind, protoreflect.Sfixed32Kind:
			value = protoreflect.ValueOfInt32(1)
		case protoreflect.Int64Kind, protoreflect.Sint64Kind, protoreflect.Sfixed64Kind:
			value = protoreflect.ValueOfInt64(1)
		case protoreflect.Uint32Kind, protoreflect.Fixed32Kind:
			value = protoreflect.ValueOfUint32(1)
		case protoreflect.Uint64Kind, protoreflect.Fixed64Kind:
			value = protoreflect.ValueOfUint64(1)
		case protoreflect.FloatKind:
			value = protoreflect.ValueOfFloat32(1)
		case protoreflect.DoubleKind:
			value = protoreflect.ValueOfFloat64(1)
		case protoreflect.StringKind:
			value = protoreflect.ValueOfString("covered")
		case protoreflect.BytesKind:
			value = protoreflect.ValueOfBytes([]byte{1})
		case protoreflect.MessageKind, protoreflect.GroupKind:
			value = message.NewField(field)
		default:
			continue
		}
		message.Set(field, value)
	}
}
