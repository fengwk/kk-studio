package lsp

import (
	"context"
	"errors"
	"testing"
)

// LSP-F-028 verifies timeout errors retain their concrete type, LspError ancestry, and context cause.
func TestErrors_TimeoutIsLspError(t *testing.T) {
	err := newLspTimeoutError("Operation timed out", context.DeadlineExceeded)
	var timeout *LspTimeoutError
	var base *LspError
	if !errors.As(err, &timeout) || !errors.As(err, &base) || base.Error() != "Operation timed out" {
		t.Fatalf("timeout classification = timeout:%v base:%v error:%v", timeout != nil, base != nil, err)
	}
	if !errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
		t.Fatalf("timeout causes = deadline:%v canceled:%v", errors.Is(err, context.DeadlineExceeded), errors.Is(err, context.Canceled))
	}
}

// LSP-T-014 ensures arbitrary internal errors are mapped without leaking details.
func TestCapabilityErrorMessage_SanitizesUnknownErrors(t *testing.T) {
	const safe = "The capability failed while running and the underlying error is not repeated to avoid leaking details"
	if got := capabilityErrorMessage(errors.New("private internal detail")); got != safe {
		t.Fatalf("unknown error message = %q, want %q", got, safe)
	}
}
