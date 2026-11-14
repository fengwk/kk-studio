package coding

import (
	"context"
	"errors"
	"fmt"
	"os"
	pathpkg "path"
	"path/filepath"
	"strings"
	"syscall"

	"github.com/fengwk/kk-studio/daemon/internal/coding/search"
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
	var undecodable *search.UndecodableError
	if errors.As(err, &undecodable) {
		return codecError(undecodable.Path, undecodable.Err, codecSearch)
	}
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
		return failRun("search failed; results are incomplete", "FAILED")
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
	case errors.Is(err, syscall.ENOTDIR):
		return "INVALID_ARGUMENT", "path is not a directory"
	case errors.Is(err, syscall.EISDIR):
		return "INVALID_ARGUMENT", "path is a directory"
	case errors.Is(err, syscall.ENOENT), errors.Is(err, os.ErrNotExist):
		return "NOT_FOUND", "path does not exist"
	case errors.Is(err, os.ErrExist), errors.Is(err, syscall.EEXIST):
		return "ALREADY_EXISTS", "path already exists"
	case errors.Is(err, syscall.ENOSPC):
		return "NO_SPACE", "no space left on the device"
	default:
		return "IO", "file system operation failed"
	}
}

const windowsSeparator = "\x5c"

func isAbsolutePath(path string) bool {
	if strings.HasPrefix(path, "/") {
		return true
	}
	return hasWindowsDriveRoot(path) || strings.HasPrefix(path, windowsSeparator+windowsSeparator)
}

func hasWindowsDriveRoot(path string) bool {
	return len(path) >= 3 && isASCIIAlpha(path[0]) && path[1] == ':' && isPathSeparator(path[2])
}

func isASCIIAlpha(value byte) bool {
	return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
}

func isPathSeparator(value byte) bool {
	return value == '/' || value == windowsSeparator[0]
}

func normalizePath(path string) string {
	if hasWindowsDriveRoot(path) || strings.HasPrefix(path, windowsSeparator) || strings.Contains(path, windowsSeparator) && !strings.HasPrefix(path, "/") {
		return normalizeWindowsPath(path)
	}
	return pathpkg.Clean(path)
}

// windowsRoot is the parsed volume of a Windows path: its spelling, whether the path is rooted
// above the remainder, and whether the volume is a UNC share.
type windowsRoot struct {
	display   string
	rooted    bool
	unc       bool
	remainder string
}

func normalizeWindowsPath(path string) string {
	root := splitWindowsRoot(strings.ReplaceAll(path, "/", windowsSeparator))
	clean := strings.Join(cleanWindowsParts(root.remainder, root.rooted), windowsSeparator)
	return joinWindowsRoot(root, clean)
}

// splitWindowsRoot separates the volume prefix of a Windows path from its unresolved remainder.
func splitWindowsRoot(value string) windowsRoot {
	switch {
	case strings.HasPrefix(value, windowsSeparator+windowsSeparator):
		return splitUNC(value)
	case hasWindowsDriveRoot(value):
		return windowsRoot{display: value[:2] + windowsSeparator, rooted: true, remainder: value[3:]}
	case strings.HasPrefix(value, windowsSeparator):
		return windowsRoot{display: windowsSeparator, rooted: true, remainder: value[1:]}
	default:
		return windowsRoot{remainder: value}
	}
}

// splitUNC separates the server and share of a UNC path from the rest of the path.
func splitUNC(value string) windowsRoot {
	parts := strings.Split(strings.TrimLeft(value, windowsSeparator), windowsSeparator)
	if len(parts) < 2 {
		return windowsRoot{display: windowsSeparator + windowsSeparator, rooted: true, remainder: strings.Join(parts, windowsSeparator)}
	}
	display := windowsSeparator + windowsSeparator + parts[0] + windowsSeparator + parts[1]
	return windowsRoot{display: display, rooted: true, unc: true, remainder: strings.Join(parts[2:], windowsSeparator)}
}

// cleanWindowsParts resolves the dot segments of a remainder, never climbing above a rooted path.
func cleanWindowsParts(remainder string, rooted bool) []string {
	var parts []string
	for _, part := range strings.Split(remainder, windowsSeparator) {
		switch part {
		case "", ".":
			continue
		case "..":
			if len(parts) > 0 && parts[len(parts)-1] != ".." {
				parts = parts[:len(parts)-1]
			} else if !rooted {
				parts = append(parts, part)
			}
		default:
			parts = append(parts, part)
		}
	}
	return parts
}

// joinWindowsRoot attaches the cleaned segments to their volume, keeping the trailing separator a
// bare volume or a bare UNC share spells with.
func joinWindowsRoot(root windowsRoot, clean string) string {
	if root.display == "" {
		if clean == "" {
			return "."
		}
		return clean
	}
	if clean == "" {
		if root.unc {
			return root.display + windowsSeparator
		}
		return root.display
	}
	if strings.HasSuffix(root.display, windowsSeparator) {
		return root.display + clean
	}
	return root.display + windowsSeparator + clean
}

func toPosix(path string) string {
	return strings.ReplaceAll(path, windowsSeparator, "/")
}

func displayPath(target, rawPath *string) string {
	if rawPath != nil && strings.TrimSpace(*rawPath) != "" && isAbsolutePath(*rawPath) {
		return toPosix(normalizePath(*rawPath))
	}
	if target != nil {
		return toPosix(*target)
	}
	if rawPath == nil {
		return "<missing>"
	}
	return *rawPath
}

func resolveExistingPath(path string) (string, error) {
	if !filepath.IsAbs(path) {
		return "", rejectInput("path must be an absolute path")
	}
	resolved, err := filepath.EvalSymlinks(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return "", rejectInputWithCode("path does not exist", "NOT_FOUND")
		}
		return "", err
	}
	return resolved, nil
}

func isSymbolicLink(path string) bool {
	info, err := os.Lstat(path)
	return err == nil && info.Mode()&os.ModeSymlink != 0
}
