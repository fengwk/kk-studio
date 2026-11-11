package coding

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/fengwk/kk-studio/daemon/internal/coding/search"
	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

type arguments struct {
	OldString    string `json:"old_string"`
	NewString    string `json:"new_string"`
	ReplaceAll   bool   `json:"replace_all"`
	Command      string `json:"command"`
	Workdir      string `json:"workdir"`
	Path         string `json:"path"`
	Content      string `json:"content"`
	Preview      bool   `json:"preview"`
	Pattern      string `json:"pattern"`
	Glob         string `json:"glob"`
	Limit        int    `json:"limit"`
	Offset       int    `json:"offset"`
	ColumnOffset int    `json:"column_offset"`
	Literal      bool   `json:"literal"`
	IgnoreCase   bool   `json:"ignore_case"`
}

// Provider supplies the filesystem and in-process search capability handlers.
type Provider struct{}

func (Provider) Capabilities() map[string]runtime.CapabilityExecutor {
	return map[string]runtime.CapabilityExecutor{
		"fs.read":          readCapability,
		"fs.write":         writeCapability,
		"fs.write.preview": previewWriteCapability,
		"fs.edit":          editCapability,
		"fs.edit.preview":  editPreviewCapability,
		"fs.find":          findCapability,
		"fs.grep":          grepCapability,
		"process.exec":     processCapability,
	}
}

func decodeArguments(call runtime.CapabilityCall) (arguments, error) {
	var args arguments
	if err := json.Unmarshal(call.Arguments, &args); err != nil {
		return args, errors.New("invalid arguments")
	}
	if args.Path == "" {
		return args, errors.New("path must be a non-empty string")
	}
	if !filepath.IsAbs(args.Path) {
		return args, errors.New("path must be absolute")
	}
	return args, nil
}

func readCapability(ctx context.Context, call runtime.CapabilityCall, _ runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	args, err := decodeArguments(call)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if args.Offset == 0 {
		args.Offset = 1
	}
	if args.Limit == 0 {
		args.Limit = DefaultReadLimit
	}
	if args.Offset < 1 || args.Limit < 1 || args.Limit > MaxReadLimit || args.ColumnOffset < 0 {
		return errorResult(call.InvocationID, errors.New("invalid read window")), nil
	}
	if err := ctx.Err(); err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	info, err := os.Stat(args.Path)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if info.IsDir() {
		entries, err := os.ReadDir(args.Path)
		if err != nil {
			return errorResult(call.InvocationID, err), nil
		}
		var names []string
		for _, entry := range entries {
			names = append(names, entry.Name())
		}
		text := strings.Join(names, "\n")
		return textResult(call.InvocationID, text), nil
	}
	if !info.Mode().IsRegular() {
		return errorResult(call.InvocationID, errors.New("path is not a regular file")), nil
	}
	data, err := os.ReadFile(args.Path)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if mime := DetectImageType(data); mime != "" {
		return runtime.CapabilityResult{CallID: call.InvocationID, Contents: []runtime.ResultContent{{Type: "binary", Bytes: data, MediaType: mime, Size: int64(len(data))}}}, nil
	}
	if mime := DetectMediaType(data); mime != "" {
		name := filepath.Base(args.Path)
		return runtime.CapabilityResult{CallID: call.InvocationID, Contents: []runtime.ResultContent{{Type: "binary", Bytes: data, MediaType: mime, Name: &name, Size: int64(len(data))}}}, nil
	}
	decoded, err := DecodeText(data)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	window, err := ReadTextWindow(decoded.Text, WindowOptions{Offset: args.Offset, Limit: args.Limit, ColumnOffset: args.ColumnOffset})
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	return textResult(call.InvocationID, fmt.Sprintf("path: %s\nrange: %d:%d-%d:%d\n\n%s", args.Path, window.StartLine, 1, window.EndLine, 1, window.Body)), nil
}

func writeCapability(ctx context.Context, call runtime.CapabilityCall, _ runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	args, err := decodeArguments(call)
	if err == nil && args.Content == "" { // Empty content is a valid file; distinguish absent from present.
		var raw map[string]json.RawMessage
		_ = json.Unmarshal(call.Arguments, &raw)
		if _, ok := raw["content"]; !ok {
			err = errors.New("content must be a string")
		}
	}
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if err = ctx.Err(); err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	var expected *ExpectedFile
	if call.ExpectedFile != nil {
		expected = &ExpectedFile{Path: call.ExpectedFile.Path, SHA256: call.ExpectedFile.SHA256}
	}
	review, err := Write(args.Path, args.Content, expected)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	action := "Overwrote "
	if review.NewFile {
		action = "Created "
	}
	result := textResult(call.InvocationID, action+review.Path+" successfully.")
	result.Details = mustJSON(map[string]any{"writeReview": review})
	return result, nil
}

func editCapability(ctx context.Context, call runtime.CapabilityCall, _ runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	return editFile(ctx, call, false)
}

func editPreviewCapability(ctx context.Context, call runtime.CapabilityCall, _ runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	return editFile(ctx, call, true)
}

func previewWriteCapability(ctx context.Context, call runtime.CapabilityCall, _ runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	args, err := decodeArguments(call)
	if err == nil && args.Content == "" {
		var raw map[string]json.RawMessage
		_ = json.Unmarshal(call.Arguments, &raw)
		if _, ok := raw["content"]; !ok {
			err = errors.New("content must be a string")
		}
	}
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if err = ctx.Err(); err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	review, err := PreviewWrite(args.Path, args.Content)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	return runtime.CapabilityResult{CallID: call.InvocationID, Details: json.RawMessage(`{}`), Contents: []runtime.ResultContent{{Type: "json", JSON: mustJSON(review)}}}, nil
}

func findCapability(ctx context.Context, call runtime.CapabilityCall, _ runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	args, err := decodeArguments(call)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	paths, err := search.Find(args.Path, args.Pattern, args.Limit)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if err = ctx.Err(); err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if len(paths) == 0 {
		return textResult(call.InvocationID, "No files found matching pattern"), nil
	}
	return textResult(call.InvocationID, strings.Join(paths, "\n")), nil
}

func grepCapability(ctx context.Context, call runtime.CapabilityCall, _ runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	args, err := decodeArguments(call)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	matches, err := search.Grep(args.Path, args.Pattern, args.Literal, args.IgnoreCase, args.Glob, args.Limit)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if err = ctx.Err(); err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if len(matches) == 0 {
		return textResult(call.InvocationID, "No matches found"), nil
	}
	return textResult(call.InvocationID, strings.Join(matches, "\n")), nil
}

func processCapability(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	var args arguments
	if err := json.Unmarshal(call.Arguments, &args); err != nil {
		return errorResult(call.InvocationID, errors.New("invalid arguments")), nil
	}
	if strings.TrimSpace(args.Command) == "" {
		return errorResult(call.InvocationID, errors.New("command must be a non-empty string")), nil
	}
	if args.Workdir == "" {
		args.Workdir = "."
	}
	if !filepath.IsAbs(args.Workdir) {
		absolute, err := filepath.Abs(args.Workdir)
		if err != nil {
			return errorResult(call.InvocationID, err), nil
		}
		args.Workdir = absolute
	}
	if info, err := os.Stat(args.Workdir); err != nil || !info.IsDir() {
		return errorResult(call.InvocationID, errors.New("workdir is not a directory")), nil
	}
	return ExecuteProcess(ctx, call, capability, args.Command, args.Workdir)
}

func textResult(id, text string) runtime.CapabilityResult {
	return runtime.CapabilityResult{CallID: id, Contents: []runtime.ResultContent{{Type: "text", Text: text}}}
}

func errorResult(id string, err error) runtime.CapabilityResult {
	code := "FAILED"
	if errors.Is(err, context.Canceled) {
		code = "CANCELLED"
	}
	if errors.Is(err, context.DeadlineExceeded) {
		code = "TIMEOUT"
	}
	return runtime.CapabilityResult{CallID: id, Error: true, Details: mustJSON(map[string]string{"code": code}), Contents: []runtime.ResultContent{{Type: "text", Text: err.Error()}}}
}

func mustJSON(v any) json.RawMessage {
	data, _ := json.Marshal(v)
	return data
}
