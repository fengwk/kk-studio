package native

import (
	"errors"
	"testing"
)

// Convergence errors expose the fixed public message and retain the underlying cause.
func TestProcessScopeNotConvergedError(t *testing.T) {
	cause := errors.New("scope remained populated")
	err := &ProcessScopeNotConvergedError{Cause: cause}
	if got := err.Error(); got != ErrScopeNotConverged.Error() {
		t.Fatalf("Error() = %q, want %q", got, ErrScopeNotConverged)
	}
	if !errors.Is(err, ErrScopeNotConverged) || !errors.Is(err, cause) {
		t.Fatalf("error chain does not preserve sentinel and cause: %v", err)
	}
}
