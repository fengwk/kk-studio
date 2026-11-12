package protocol

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"

	"google.golang.org/protobuf/proto"
)

// TRN-T-129
func TestCodec_PayloadRoundtrip(t *testing.T) {
	e := testEnvelope(MessageType_IDE_RESULT, "invocation-1", &Envelope_IdeResult{IdeResult: &IdeResult{Ok: proto.Bool(true), DirectoryListing: &IdeDirectoryListing{Path: proto.String("/"), Truncated: proto.Bool(false), Children: []*IdeDirectoryEntry{{Name: proto.String("src"), Path: proto.String("/src")}}}}})
	data, err := proto.Marshal(e)
	if err != nil {
		t.Fatal(err)
	}
	var got Envelope
	if err := proto.Unmarshal(data, &got); err != nil {
		t.Fatal(err)
	}
	if !proto.Equal(e, &got) {
		t.Fatalf("payload changed: %v", &got)
	}
}

func readGolden(t *testing.T) ([]string, map[string][]byte) {
	t.Helper()
	const dir = "../../../platform/daemon-server/src/main/protocol/golden"
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	files := []string{}
	data := map[string][]byte{}
	for _, e := range entries {
		if e.IsDir() || filepath.Ext(e.Name()) != ".bin" {
			continue
		}
		b, err := os.ReadFile(filepath.Join(dir, e.Name()))
		if err != nil {
			t.Fatal(err)
		}
		files = append(files, e.Name())
		data[e.Name()] = b
	}
	return files, data
}

// TRN-T-130
func TestCodec_GoldenEncode(t *testing.T) {
	files, data := readGolden(t)
	if len(files) != 28 {
		t.Fatalf("golden count=%d", len(files))
	}
	for _, name := range files {
		t.Run(name, func(t *testing.T) {
			var e Envelope
			if err := proto.Unmarshal(data[name], &e); err != nil {
				t.Fatal(err)
			}
			encoded, err := MarshalEnvelope(&e)
			if err != nil {
				t.Fatalf("validate/encode: %v", err)
			}
			if !bytes.Equal(encoded, data[name]) {
				t.Fatal("encoded bytes differ")
			}
		})
	}
}

// TRN-T-131
func TestCodec_GoldenFramed(t *testing.T) {
	files, data := readGolden(t)
	for _, name := range files {
		t.Run(name, func(t *testing.T) {
			var e Envelope
			if err := proto.Unmarshal(data[name], &e); err != nil {
				t.Fatal(err)
			}
			frame, err := EncodeEnvelopeFrame(&e)
			if err != nil {
				t.Fatal(err)
			}
			got, err := DecodeEnvelopeFrame(frame)
			if err != nil {
				t.Fatal(err)
			}
			if !proto.Equal(&e, got) {
				t.Fatal("framed envelope changed")
			}
		})
	}
}

// TRN-T-133
func TestCodec_EmptyText(t *testing.T) {
	e := testEnvelope(MessageType_COMPLETED, testInvocationID, &Envelope_Completed{Completed: &Completed{Result: &CapabilityResult{CallId: proto.String("call"), Error: proto.Bool(false), DetailsJson: proto.String(`{}`), Contents: []*Content{{Body: &Content_Text{Text: &TextContent{Text: proto.String("")}}}}}}})
	if _, err := MarshalEnvelope(e); err != nil {
		t.Fatalf("empty text rejected: %v", err)
	}
}

// TRN-T-150
func TestCodec_UnicodeJSON(t *testing.T) {
	for _, s := range []string{`{"x":"é"}`, `{"x":"😀"}`, `["ok",null]`} {
		if err := ValidateJSON(s, JSONLimits{MaxBytes: 64}); err != nil {
			t.Errorf("valid JSON %q rejected: %v", s, err)
		}
	}
	for _, s := range []string{string([]byte{0xff}), `{"x":"\uD800"}`, `{"x": NaN}`} {
		if err := ValidateJSON(s, JSONLimits{}); err == nil {
			t.Errorf("invalid JSON %q accepted", s)
		}
	}
}

// TRN-T-153
func TestCodec_UnknownProtobufFields(t *testing.T) {
	e := testEnvelope(MessageType_ERROR, "", &Envelope_Error{Error: &Error{Message: proto.String("failure")}})
	e.GetError().ProtoReflect().SetUnknown([]byte{0xf8, 0x07, 0x01})
	data, err := proto.Marshal(e)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := UnmarshalEnvelope(data); err == nil {
		t.Fatal("nested unknown field accepted")
	}
}

// TRN-T-154
func TestCodec_RuntimeHelpers(t *testing.T) {
	for _, tc := range []struct {
		value  any
		limits JSONLimits
		want   string
	}{{7, JSONLimits{}, "must be a string"}, {"", JSONLimits{}, "must be valid JSON"}, {`{"x":1,"x":2}`, JSONLimits{}, "contains duplicate field: x"}, {"[[[]]]", JSONLimits{MaxNestingDepth: 2}, "exceeds maximum nesting depth 2"}, {"12345", JSONLimits{MaxNumberLength: 4}, "number exceeds 4 characters"}} {
		err := ValidateJSON(tc.value, tc.limits)
		if err == nil || err.Error() != tc.want {
			t.Errorf("ValidateJSON(%v) error=%v want=%q", tc.value, err, tc.want)
		}
	}
}
