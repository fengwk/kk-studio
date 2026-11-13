package protocol

import (
	"encoding/hex"
	"strings"
	"testing"

	"google.golang.org/protobuf/proto"
)

func TestInvokeExpectedFileWriteOptionalSHA256Validation(t *testing.T) {
	validCases := []struct {
		name       string
		capability string
		sha256     *string
	}{
		{"edit with matching snapshot", "fs.edit", proto.String(strings.Repeat("a", 64))},
		{"write with matching snapshot", "fs.write", proto.String(strings.Repeat("a", 64))},
		{"write create-only snapshot", "fs.write", nil},
	}
	for _, tc := range validCases {
		t.Run(tc.name, func(t *testing.T) {
			if err := ValidateEnvelope(invokeExpectedFileEnvelope(tc.capability, &ExpectedFile{Path: proto.String("/tmp/file"), Sha256: tc.sha256})); err != nil {
				t.Fatalf("valid expectedFile rejected: %v", err)
			}
		})
	}

	invalidCases := []struct {
		name       string
		capability string
		expected   *ExpectedFile
		want       string
	}{
		{"edit without SHA", "fs.edit", &ExpectedFile{Path: proto.String("/tmp/file")}, "invoke.expectedFile.sha256 is required"},
		{"write with empty SHA", "fs.write", &ExpectedFile{Path: proto.String("/tmp/file"), Sha256: proto.String("")}, "invoke.expectedFile.sha256 must be a lowercase SHA-256"},
		{"write with uppercase SHA", "fs.write", &ExpectedFile{Path: proto.String("/tmp/file"), Sha256: proto.String(strings.Repeat("A", 64))}, "invoke.expectedFile.sha256 must be a lowercase SHA-256"},
		{"write with malformed SHA", "fs.write", &ExpectedFile{Path: proto.String("/tmp/file"), Sha256: proto.String("abc")}, "invoke.expectedFile.sha256 must be a lowercase SHA-256"},
		{"write without path", "fs.write", &ExpectedFile{Sha256: proto.String(strings.Repeat("a", 64))}, "invoke.expectedFile.path must be a string"},
		{"other capability", "fs.read", &ExpectedFile{Path: proto.String("/tmp/file")}, "invoke.expectedFile is only valid for fs.edit and fs.write"},
	}
	for _, tc := range invalidCases {
		t.Run(tc.name, func(t *testing.T) {
			if err := ValidateEnvelope(invokeExpectedFileEnvelope(tc.capability, tc.expected)); err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("validation error = %v, want containing %q", err, tc.want)
			}
		})
	}
}

func TestInvokeWriteExpectedFileWithoutSHA256Golden(t *testing.T) {
	envelope := invokeExpectedFileEnvelope("fs.write", &ExpectedFile{Path: proto.String("/tmp/new-file")})
	got, err := MarshalEnvelope(envelope)
	if err != nil {
		t.Fatal(err)
	}
	const want = "0805122431323365343536372d653839622d343264332d613435362d3432363631343137343030301a1365787065637465642d66696c652d777269746542270a0866732e777269746512027b7d1800220f0a0d2f746d702f6e65772d66696c65288080403001"
	if hex.EncodeToString(got) != want {
		t.Fatalf("expectedFile create-only golden = %s", hex.EncodeToString(got))
	}
	decoded, err := UnmarshalEnvelope(got)
	if err != nil {
		t.Fatal(err)
	}
	if decoded.GetInvoke().GetExpectedFile().Sha256 != nil {
		t.Fatalf("absent SHA-256 gained presence after round trip: %q", decoded.GetInvoke().GetExpectedFile().GetSha256())
	}
}

func invokeExpectedFileEnvelope(capability string, expected *ExpectedFile) *Envelope {
	imageTier := ImageInputTier_P720
	return &Envelope{
		MessageType:   MessageType_INVOKE,
		EnvironmentId: proto.String("123e4567-e89b-42d3-a456-426614174000"),
		InvocationId:  proto.String("expected-file-write"),
		Payload: &Envelope_Invoke{Invoke: &Invoke{
			CapabilityId: proto.String(capability), ArgumentsJson: proto.String(`{}`), TimeoutMillis: proto.Int64(0),
			MaxOutputBytes: proto.Int64(1 << 20), ImageInputTier: &imageTier, ExpectedFile: expected,
		}},
	}
}
