package protocol

import (
	"strings"
	"testing"
)

// TRN-T-119
func TestStrictJSON_UTF8Length(t *testing.T) {
	for input, want := range map[string]int{"ascii": 5, "你": 3, "😀": 4} {
		got, err := UTF8ByteLength(input)
		if err != nil || got != want {
			t.Fatalf("UTF8ByteLength(%q) = %d, %v; want %d", input, got, err, want)
		}
	}
}

// TRN-T-120
func TestStrictJSON_LoneSurrogates(t *testing.T) {
	for _, input := range []string{string([]byte{34, 92, 117, 68, 56, 48, 48, 34}), string([]byte{34, 92, 117, 68, 67, 48, 48, 34}), string([]byte{34, 92, 117, 68, 56, 48, 48, 92, 117, 48, 48, 52, 49, 34})} {
		if err := ValidateJSON(input, JSONLimits{}); err == nil {
			t.Errorf("accepted lone surrogate %s", input)
		}
	}
	for _, input := range []string{string([]byte{34, 92, 117, 68, 56, 51, 68, 92, 117, 68, 69, 48, 48, 34}), `"\\uD800"`} {
		if err := ValidateJSON(input, JSONLimits{}); err != nil {
			t.Errorf("valid surrogate/string literal rejected %s: %v", input, err)
		}
	}
	if _, err := UTF8ByteLength(string([]byte{0xff})); err == nil {
		t.Fatal("invalid UTF-8 accepted")
	}
}

// TRN-T-121
func TestStrictJSON_RejectsNonString(t *testing.T) {
	if err := ValidateJSON(42, JSONLimits{}); err == nil || !strings.Contains(err.Error(), "must be a string") {
		t.Fatalf("err=%v", err)
	}
}

// TRN-T-122
func TestStrictJSON_MaxBytes(t *testing.T) {
	if err := ValidateJSON(`{"x":1}`, JSONLimits{MaxBytes: 6}); err == nil || err.Error() != "exceeds 6 UTF-8 bytes" {
		t.Fatalf("err=%v", err)
	}
}

// TRN-T-123
func TestStrictJSON_DuplicateKeys(t *testing.T) {
	if err := ValidateJSON([]byte(`{"x":1,"x":2}`), JSONLimits{}); err == nil {
		t.Fatal("duplicate key accepted")
	}
}

// TRN-T-124
func TestStrictJSON_Depth(t *testing.T) {
	if err := ValidateJSON([]byte(`[[[]]]`), JSONLimits{MaxNestingDepth: 2}); err == nil {
		t.Fatal("excessive nesting accepted")
	}
}

// TRN-T-125
func TestStrictJSON_NumberLength(t *testing.T) {
	if err := ValidateJSON([]byte(`12345`), JSONLimits{MaxNumberLength: 4}); err == nil {
		t.Fatal("long number accepted")
	}
}

// TRN-T-126
func TestStrictJSON_SyntaxAndSurrogate(t *testing.T) {
	for _, input := range []string{"", `{"x":}`, `{"x":"\uD800"}`, "// comment\n{}", "{\"x\":1,}"} {
		if err := ValidateJSON([]byte(input), JSONLimits{}); err == nil {
			t.Fatalf("accepted %q", input)
		}
	}
}

// TRN-T-127
func TestStrictJSON_ValidOpaque(t *testing.T) {
	data := []byte(` { "x" : [1, true] } `)
	if err := ValidateJSON(data, JSONLimits{MaxBytes: 64}); err != nil {
		t.Fatal(err)
	}
	if err := ValidateJSON(data, JSONLimits{MaxBytes: 3}); err == nil {
		t.Fatal("byte limit ignored")
	}
}
