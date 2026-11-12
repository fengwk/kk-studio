package coding

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strings"
	"syscall"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

type toolErrorKind uint8

const (
	toolInputRejected toolErrorKind = iota + 1
	toolRunFailed
	toolServiceFailed
)

type toolError struct {
	kind    toolErrorKind
	code    string
	message string
}

func (e *toolError) Error() string { return e.message }

func rejectInput(message string) error {
	return rejectInputWithCode(message, "INVALID_ARGUMENT")
}

func rejectInputWithCode(message, code string) error {
	return &toolError{kind: toolInputRejected, code: code, message: message}
}

func failRun(message, code string) error {
	return &toolError{kind: toolRunFailed, code: code, message: message}
}

func failService(message string) error {
	return &toolError{kind: toolServiceFailed, code: "SERVICE_FAILURE", message: message}
}

func toCapabilityErrorResult(id string, err error) runtime.CapabilityResult {
	code, message, fact, next := "INTERNAL", "The capability failed while running and the underlying error is not repeated to avoid leaking details", "UNCERTAIN", verifyEffectNextAction
	var typed *toolError
	switch {
	case errors.As(err, &typed):
		code, message = typed.code, typed.message
		switch typed.kind {
		case toolInputRejected:
			fact, next = "NOT_EXECUTED", "Correct the arguments to match the tool schema, then call the tool again"
		case toolRunFailed, toolServiceFailed:
			fact = "UNCERTAIN"
		}
	case errors.Is(err, context.Canceled):
		code, message, fact, next = "CANCELLED", "Operation cancelled", "UNCERTAIN", "Check whether the operation already took effect before deciding what to do next"
	case errors.Is(err, context.DeadlineExceeded):
		code, message, fact = "TIMEOUT", "Operation timed out", "UNCERTAIN"
		next = "Check whether the operation already took effect before deciding what to do next"
	case isFileSystemError(err):
		code, message = classifyFileSystemError(err)
		fact = "UNCERTAIN"
	}
	text := guideText(message, fact, next)
	return runtime.CapabilityResult{CallID: id, Error: true, Details: mustJSON(map[string]string{"code": code}), Contents: []runtime.ResultContent{{Type: "text", Text: text}}}
}

func guideText(whatFailed, fact, next string) string {
	factText := map[string]string{
		"NOT_EXECUTED": "The tool was not executed.",
		"FAILED":       "The tool ran but failed; side effects may have occurred.",
		"UNCERTAIN":    "Whether the tool took effect cannot be confirmed.",
	}[fact]
	return fmt.Sprintf("%s %s %s", errorSentence(whatFailed), factText, errorSentence(next))
}

func errorSentence(text string) string {
	text = strings.TrimSpace(text)
	if text == "" {
		return ""
	}
	last := text[len(text)-1]
	if last == '.' || last == '!' || last == '?' {
		return text
	}
	return text + "."
}

const verifyEffectNextAction = "Verify whether the intended effect already took place before deciding what to do next"

func classifySearchError(err error) error {
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) || isFileSystemError(err) {
		return err
	}
	message := err.Error()
	lower := strings.ToLower(message)
	switch {
	case strings.HasPrefix(lower, "invalid regex:"):
		diagnostic := strings.TrimSpace(message[len("invalid regex:"):])
		return rejectInput("Invalid regex: " + diagnostic + ". Use literal=true for exact text, or escape regex metacharacters.")
	case strings.HasPrefix(lower, "invalid glob:"):
		diagnostic := strings.TrimSpace(message[len("invalid glob:"):])
		return rejectInput("Invalid glob: " + diagnostic)
	case lower == "root is not a directory":
		return rejectInput("path is not a directory")
	default:
		return failRun("ripgrep failed while searching; results are incomplete", "FAILED")
	}
}

func isFileSystemError(err error) bool {
	var pathErr *os.PathError
	return errors.As(err, &pathErr)
}

func classifyFileSystemError(err error) (string, string) {
	switch {
	case errors.Is(err, os.ErrPermission), errors.Is(err, syscall.EACCES), errors.Is(err, syscall.EPERM):
		return "PERMISSION_DENIED", "permission denied while accessing the path"
	case errors.Is(err, os.ErrNotExist), errors.Is(err, syscall.ENOENT):
		return "NOT_FOUND", "path does not exist"
	case errors.Is(err, os.ErrExist), errors.Is(err, syscall.EEXIST):
		return "ALREADY_EXISTS", "path already exists"
	case errors.Is(err, syscall.EISDIR):
		return "INVALID_ARGUMENT", "path is a directory"
	case errors.Is(err, syscall.ENOTDIR):
		return "INVALID_ARGUMENT", "path is not a directory"
	case errors.Is(err, os.ErrExist), errors.Is(err, syscall.EEXIST):
		return "ALREADY_EXISTS", "path already exists"
	case errors.Is(err, syscall.ENOSPC):
		return "NO_SPACE", "no space left on the device"
	default:
		return "IO", "file system operation failed"
	}
}
