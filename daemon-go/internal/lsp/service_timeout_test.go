package lsp

import (
	"context"
	"testing"
)

// LSP-T-109 verifies timeoutMillis=0 leaves request timeout selection to the caller context.
func TestRequestContext_ZeroTimeoutDoesNotAddDeadline(t *testing.T) {
	ctx, cancel := requestContext(context.Background(), 0)
	defer cancel()
	if _, ok := ctx.Deadline(); ok {
		t.Fatal("zero timeout unexpectedly added a deadline")
	}
}
