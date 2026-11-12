package ide

import (
	"errors"
)

// Error is a safe, model-visible IDE failure with a stable protocol code.
type Error struct {
	Code    string
	Message string
}

func (e *Error) Error() string { return e.Message }

// ErrorCode exposes the stable routing code without leaking it into the safe error message.
func (e *Error) ErrorCode() string { return e.Code }

// NewError constructs a safe IDE error. An empty code defaults to UNAVAILABLE.
func NewError(message, code string) *Error {
	if code == "" {
		code = "UNAVAILABLE"
	}
	return &Error{Code: code, Message: message}
}

// ErrCancelled represents an aborted IDE request.
var ErrCancelled = errors.New("ide request was cancelled")

// SafeMessage prevents implementation details and paths from crossing the control boundary.
func SafeMessage(err error) string {
	var ideErr *Error
	if errors.As(err, &ideErr) {
		return ideErr.Message
	}
	if errors.Is(err, ErrCancelled) {
		return "ide request was cancelled"
	}
	return "ide request failed"
}

// FailureCode maps cancellation and unknown failures to the stable IDE result codes.
func FailureCode(err error) string {
	var ideErr *Error
	if errors.As(err, &ideErr) {
		return ideErr.Code
	}
	if errors.Is(err, ErrCancelled) {
		return "BUSY"
	}
	return "UNAVAILABLE"
}
