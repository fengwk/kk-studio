package lsp

import "fmt"

// LspError marks a stable, safe-to-expose LSP failure.
type LspError struct {
	Message string
	Cause   error
}

func (e *LspError) Error() string { return e.Message }
func (e *LspError) Unwrap() error { return e.Cause }

// LspBootstrapError reports whether a failed launch was proven cleaned up.
type LspBootstrapError struct {
	Message   string
	Converged bool
	Cause     error
}

func (e *LspBootstrapError) Error() string { return e.Message }
func (e *LspBootstrapError) Unwrap() error { return e.Cause }

// LspTimeoutError identifies an LSP request timeout.
type LspTimeoutError struct {
	Message string
	Cause   error
}

func (e *LspTimeoutError) Error() string { return e.Message }
func (e *LspTimeoutError) Unwrap() error { return e.Cause }

// LspAggregateError retains a stable aggregate message and all underlying failures.
type LspAggregateError struct {
	Message string
	Errors  []error
}

func (e *LspAggregateError) Error() string   { return e.Message }
func (e *LspAggregateError) Unwrap() []error { return e.Errors }

// LspCleanupError contains distinct failures encountered while closing a pool.
type LspCleanupError struct{ Failures []error }

func (e *LspCleanupError) Error() string {
	return fmt.Sprintf("LSP client pool failed to release all resources (%d cleanup failure(s))", len(e.Failures))
}
func (e *LspCleanupError) Unwrap() error {
	return &LspAggregateError{Message: "LSP client pool cleanup failures", Errors: e.Failures}
}
