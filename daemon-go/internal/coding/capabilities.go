package coding

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"

	imagescaler "github.com/fengwk/kk-studio/daemon/internal/coding/image"
	"github.com/fengwk/kk-studio/daemon/internal/coding/search"
	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

type arguments struct {
	OldString      string `json:"old_string"`
	NewString      string `json:"new_string"`
	ReplaceAll     bool   `json:"replace_all"`
	Command        string `json:"command"`
	Workdir        string `json:"workdir"`
	Path           string `json:"path"`
	Content        string `json:"content"`
	Preview        bool   `json:"preview"`
	Pattern        string `json:"pattern"`
	Glob           string `json:"glob"`
	Limit          int    `json:"limit"`
	Offset         int    `json:"offset"`
	ColumnOffset   int    `json:"column_offset"`
	Literal        bool   `json:"literal"`
	IgnoreCase     bool   `json:"ignore_case"`
	ImageInputTier string `json:"image_input_tier"`
}

// Provider supplies the filesystem and in-process search capability handlers.
type Provider struct{}

func (Provider) Capabilities() map[string]runtime.CapabilityExecutor {
	handlers := map[string]runtime.CapabilityExecutor{
		"fs.read":          readCapability,
		"fs.write":         writeCapability,
		"fs.write.preview": previewWriteCapability,
		"fs.edit":          editCapability,
		"fs.edit.preview":  editPreviewCapability,
		"fs.find":          findCapability,
		"fs.grep":          grepCapability,
		"process.exec":     processCapability,
	}
	for id, handler := range handlers {
		handlers[id] = limitOutput(handler)
	}
	return handlers
}

func limitOutput(handler runtime.CapabilityExecutor) runtime.CapabilityExecutor {
	return func(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext) (runtime.CapabilityResult, error) {
		result, err := handler(ctx, call, capability)
		if err != nil || result.Error || call.MaxOutputBytes <= 0 {
			return result, err
		}
		var size int64
		for _, content := range result.Contents {
			switch content.Type {
			case "text":
				size += int64(len(content.Text))
			case "json":
				size += int64(len(content.JSON))
			case "binary":
				size += int64(len(content.Bytes))
			}
		}
		if size > call.MaxOutputBytes {
			message := fmt.Sprintf("Tool output is %s MiB, which exceeds the tool output size limit of %d MiB. The tool ran but its output was discarded; side effects may have occurred. Request a smaller result, for example a narrower read range or filtered command output, or ask the user to raise the limit in system settings.", formatMiB(size, call.MaxOutputBytes), call.MaxOutputBytes/(1<<20))
			return errorResult(call.InvocationID, errors.New(message)), nil
		}
		return result, nil
	}
}

func formatMiB(sizeBytes, limitBytes int64) string {
	limit := float64(limitBytes) / (1 << 20)
	value := float64(sizeBytes) / (1 << 20)
	for digits := 1; digits <= 6; digits++ {
		text := strconv.FormatFloat(value, 'f', digits, 64)
		if parsed, err := strconv.ParseFloat(text, 64); err == nil && parsed != limit {
			return trimMiB(text)
		}
	}
	text := strconv.FormatFloat(value, 'f', 6, 64)
	return trimMiB(text)
}

func trimMiB(text string) string {
	text = strings.TrimRight(strings.TrimRight(text, "0"), ".")
	if !strings.Contains(text, ".") {
		return text + ".0"
	}
	return text
}

func parseImageTier(value string) (imagescaler.Tier, error) {
	switch value {
	case "", "P720":
		return imagescaler.P720, nil
	case "P1080":
		return imagescaler.P1080, nil
	case "ORIGINAL":
		return imagescaler.Original, nil
	default:
		return 0, fmt.Errorf("unknown image input tier %q", value)
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
			name := entry.Name()
			if entry.IsDir() {
				name += "/"
			}
			names = append(names, name)
		}
		sort.Strings(names)
		return textResult(call.InvocationID, strings.Join(names, "\n")), nil
	}
	if !info.Mode().IsRegular() {
		return errorResult(call.InvocationID, errors.New("path is not a regular file")), nil
	}
	file, err := os.Open(args.Path)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	probe := make([]byte, 12)
	n, probeErr := io.ReadFull(file, probe)
	if probeErr != nil && !errors.Is(probeErr, io.EOF) && !errors.Is(probeErr, io.ErrUnexpectedEOF) {
		file.Close()
		return errorResult(call.InvocationID, probeErr), nil
	}
	probe = probe[:n]
	imageType := DetectImageType(probe)
	mediaType := DetectMediaType(probe)
	if imageType == "" && mediaType != "" && call.MaxOutputBytes > 0 && info.Size() > call.MaxOutputBytes {
		file.Close()
		message := fmt.Sprintf("File is %s MiB, which exceeds the tool output size limit of %d MiB. The file was not read. Use a smaller file, for example by converting or splitting it with bash, or ask the user to raise the limit in system settings.", formatMiB(info.Size(), call.MaxOutputBytes), call.MaxOutputBytes/(1<<20))
		return errorResult(call.InvocationID, errors.New(message)), nil
	}
	if _, err = file.Seek(0, io.SeekStart); err != nil {
		file.Close()
		return errorResult(call.InvocationID, err), nil
	}
	data, err := io.ReadAll(file)
	closeErr := file.Close()
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if closeErr != nil {
		return errorResult(call.InvocationID, closeErr), nil
	}
	if imageType != "" {
		tier, err := parseImageTier(args.ImageInputTier)
		if err != nil {
			return errorResult(call.InvocationID, err), nil
		}
		result, err := imagescaler.ScaleContext(ctx, imageType, data, tier, call.MaxOutputBytes)
		if err != nil {
			return errorResult(call.InvocationID, err), nil
		}
		content := runtime.ResultContent{Type: "binary", Bytes: result.Bytes, MediaType: result.MediaType, Size: int64(len(result.Bytes))}
		return runtime.CapabilityResult{CallID: call.InvocationID, Details: mustJSON(map[string]any{"image": map[string]int{"originalWidth": result.OriginalWidth, "originalHeight": result.OriginalHeight, "displayWidth": result.DisplayWidth, "displayHeight": result.DisplayHeight}}), Contents: []runtime.ResultContent{content}}, nil
	}
	if mediaType != "" {
		name := filepath.Base(args.Path)
		content := runtime.ResultContent{Type: "binary", Bytes: data, MediaType: mediaType, Name: &name, Size: int64(len(data))}
		return runtime.CapabilityResult{CallID: call.InvocationID, Contents: []runtime.ResultContent{content}}, nil
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
