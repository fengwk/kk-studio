package main

import (
	"bytes"
	"context"
	"testing"
)

// RUN-T-135 verifies that the single-argument help/version commands are side-effect free.
func TestInformationCommands(t *testing.T) {
	for _, tc := range []struct{ arg, want string }{{"--help", "Usage:"}, {"--version", "kk-studio-daemon 1.0.0"}, {"-h", "Usage:"}} {
		var out, errOut bytes.Buffer
		if code := RunCLI(context.Background(), []string{tc.arg}, Dependencies{}, &out, &errOut); code != 0 || !bytes.Contains(out.Bytes(), []byte(tc.want)) || errOut.Len() != 0 {
			t.Fatalf("%s: code=%d out=%q err=%q", tc.arg, code, out.String(), errOut.String())
		}
	}
}

// RUN-T-153 verifies internal helper arguments are dispatched without being re-parsed as service flags.
func TestInternalHelperDispatch(t *testing.T) {
	var out, errOut bytes.Buffer
	called := false
	deps := Dependencies{HelperMain: func(args []string) int {
		called = true
		if len(args) != 2 || args[1] != "raw value" {
			t.Fatalf("helper args changed: %#v", args)
		}
		return 7
	}}
	if code := RunCLI(context.Background(), []string{"--internal-install-lock", "raw value"}, deps, &out, &errOut); code != 7 || !called || out.Len() != 0 || errOut.Len() != 0 {
		t.Fatalf("helper dispatch code=%d called=%t stdout=%q stderr=%q", code, called, out.String(), errOut.String())
	}
}

// RUN-T-136 checks malformed command forms fail safely without echoing arguments.
func TestInvalidCommandsDoNotEchoArguments(t *testing.T) {
	var out, stderr bytes.Buffer
	code := RunCLI(context.Background(), []string{"--unknown", "sensitive"}, Dependencies{}, &out, &stderr)
	if code != 1 || bytes.Contains(stderr.Bytes(), []byte("sensitive")) {
		t.Fatalf("code=%d stderr=%q", code, stderr.String())
	}
}
