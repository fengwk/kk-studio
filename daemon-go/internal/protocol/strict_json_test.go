package protocol

import "testing"

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
	for _, input := range []string{"", `{"x":}`, `{"x":"\\uD800"}`, "// comment\n{}", "{\"x\":1,}"} {
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
