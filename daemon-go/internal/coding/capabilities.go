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

const (
	defaultFindLimit = 1000
	defaultGrepLimit = 100
	maxSearchLimit   = 100000
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
			contentSize := int64(0)
			switch content.Type {
			case "text":
				contentSize = int64(len(content.Text))
			case "json":
				contentSize = int64(len(content.JSON))
			case "binary":
				contentSize = int64(len(content.Bytes))
			}
			if content.Size > contentSize {
				contentSize = content.Size
			}
			size += contentSize
		}
		if size > call.MaxOutputBytes {
			message := fmt.Sprintf("Tool output is %s MiB, which exceeds the tool output size limit of %d MiB. The tool ran but its output was discarded; side effects may have occurred. Request a smaller result, for example a narrower read range or filtered command output, or ask the user to raise the limit in system settings.", formatMiB(size, call.MaxOutputBytes), call.MaxOutputBytes/(1<<20))
			return codedErrorResult(call.InvocationID, "FAILED", message), nil
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

func parseImageTier(value runtime.ImageInputTier) (imagescaler.Tier, error) {
	switch value {
	case runtime.ImageInputTierP720:
		return imagescaler.P720, nil
	case runtime.ImageInputTierP1080:
		return imagescaler.P1080, nil
	case runtime.ImageInputTierOriginal:
		return imagescaler.Original, nil
	case "":
		return 0, rejectInput("image input tier is required for fs.read")
	default:
		return 0, rejectInput(fmt.Sprintf("invalid image input tier %q", value))
	}
}

func decodeArguments(call runtime.CapabilityCall) (arguments, error) {
	var args arguments
	if err := json.Unmarshal(call.Arguments, &args); err != nil {
		return args, rejectInput("invalid arguments")
	}
	if args.Path == "" {
		return args, rejectInput("path must be a non-empty string")
	}
	if !filepath.IsAbs(args.Path) {
		return args, rejectInput("path must be absolute")
	}
	return args, nil
}

func readCapability(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	args, err := decodeArguments(call)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(call.Arguments, &raw); err != nil {
		return errorResult(call.InvocationID, rejectInput("invalid arguments")), nil
	}
	if _, exists := raw["offset"]; !exists {
		args.Offset = 1
	}
	if _, exists := raw["limit"]; !exists {
		args.Limit = DefaultReadLimit
	}
	if args.Offset < 1 || args.Offset > 2147483647 {
		return errorResult(call.InvocationID, rejectInput("offset must be a positive integer")), nil
	}
	if args.Limit < 1 || args.Limit > MaxReadLimit {
		return errorResult(call.InvocationID, rejectInput("limit must be a positive integer no greater than 2000")), nil
	}
	if column, exists := raw["column_offset"]; exists && string(column) != "null" {
		if err := json.Unmarshal(column, &args.ColumnOffset); err != nil || args.ColumnOffset < 1 {
			return errorResult(call.InvocationID, rejectInput("column_offset must be a positive integer")), nil
		}
	} else {
		args.ColumnOffset = 1
	}
	if err := ctx.Err(); err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if capability.Config.DataDir != "" {
		store, err := TextOutputStoreForDataDir(capability.Config.DataDir)
		if err != nil {
			return errorResult(call.InvocationID, err), nil
		}
		release := store.Acquire(args.Path)
		defer release()
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
		return errorResult(call.InvocationID, failRun("path is not a regular file", "INVALID_ARGUMENT")), nil
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
		return codedErrorResult(call.InvocationID, "INVALID_ARGUMENT", message), nil
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
		tier, err := parseImageTier(call.ImageInputTier)
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
		return errorResult(call.InvocationID, failRun(err.Error(), "CODEC")), nil
	}
	window, err := ReadTextWindow(decoded.Text, WindowOptions{Offset: args.Offset, Limit: args.Limit, ColumnOffset: args.ColumnOffset})
	if err != nil {
		return errorResult(call.InvocationID, rejectInput(err.Error())), nil
	}
	return textResult(call.InvocationID, formatTextWindow(args.Path, window)), nil
}

func formatTextWindow(path string, window TextWindow) string {
	lines := []string{"path: " + filepath.ToSlash(path)}
	if window.EndsWithNewline != nil {
		value := "no"
		if *window.EndsWithNewline {
			value = "yes"
		}
		lines = append(lines, "ends_with_newline: "+value)
	}
	if window.Empty {
		lines = append(lines, "range: empty")
	} else {
		lines = append(lines, fmt.Sprintf("range: %d:%d-%d:%d", window.StartLine, window.StartColumn, window.EndLine, window.EndColumn))
	}
	if window.Truncated {
		lines = append(lines, "truncated: yes", "truncation_reason: "+window.TruncationReason, fmt.Sprintf("next: %d:%d", window.NextLine, window.NextColumn))
	}
	if window.Body != "" {
		lines = append(lines, "", window.Body)
	}
	if window.Truncated {
		if window.Body != "" {
			lines = append(lines, "")
		}
		lines = append(lines, fmt.Sprintf("[TRUNCATED: More file content remains. Next position: line %d, column %d.]", window.NextLine, window.NextColumn))
	}
	return strings.Join(lines, "\n")
}

func writeCapability(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	args, err := decodeArguments(call)
	if err == nil && args.Content == "" { // Empty content is a valid file; distinguish absent from present.
		var raw map[string]json.RawMessage
		_ = json.Unmarshal(call.Arguments, &raw)
		if _, ok := raw["content"]; !ok {
			err = rejectInput("content must be a string")
		}
	}
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if err = ctx.Err(); err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if _, err := os.Lstat(args.Path); err == nil {
		info, statErr := os.Lstat(args.Path)
		if statErr == nil && info.Mode()&os.ModeSymlink != 0 {
			return errorResult(call.InvocationID, rejectInput("path is a symbolic link: "+filepath.ToSlash(args.Path))), nil
		}
		if statErr == nil && !info.Mode().IsRegular() {
			if info.IsDir() {
				return errorResult(call.InvocationID, rejectInput("path is a directory: "+filepath.ToSlash(args.Path))), nil
			}
			return errorResult(call.InvocationID, rejectInput("path is not a regular file: "+filepath.ToSlash(args.Path))), nil
		}
	}
	var expected *ExpectedFile
	if call.ExpectedFile != nil {
		expected = &ExpectedFile{Path: call.ExpectedFile.Path, SHA256: call.ExpectedFile.SHA256}
	}
	review, err := Write(args.Path, args.Content, expected)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if capability.FileChanged != nil {
		capability.FileChanged(review.Path)
	}
	action := "Overwrote "
	if review.NewFile {
		action = "Created "
	}
	result := textResult(call.InvocationID, action+review.Path+" successfully.")
	result.Details = mustJSON(map[string]any{"writeReview": review})
	return result, nil
}

func editCapability(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	return editFile(ctx, call, capability, false)
}

func editPreviewCapability(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	return editFile(ctx, call, capability, true)
}

func previewWriteCapability(ctx context.Context, call runtime.CapabilityCall, _ runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	args, err := decodeArguments(call)
	if err == nil && args.Content == "" {
		var raw map[string]json.RawMessage
		_ = json.Unmarshal(call.Arguments, &raw)
		if _, ok := raw["content"]; !ok {
			err = rejectInput("content must be a string")
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

func findCapability(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	args, err := decodeArguments(call)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if args.Limit == 0 {
		args.Limit = defaultFindLimit
	}
	if args.Limit < 0 || args.Limit > maxSearchLimit {
		return errorResult(call.InvocationID, rejectInput("limit must be between 1 and 100000")), nil
	}
	outcome, err := search.FindContext(ctx, args.Path, args.Pattern, args.Limit)
	if err != nil {
		result := errorResult(call.InvocationID, classifySearchError(err))
		if errors.Is(err, context.Canceled) {
			result.Details = mustJSON(map[string]any{"code": "CANCELLED", "search": map[string]any{"outcome": "CANCELLED", "incomplete": true}})
			result.Contents = append(result.Contents, runtime.ResultContent{Type: "text", Text: "[Search cancelled; results are incomplete.]"})
		}
		return result, nil
	}
	return formatSearchResult(call, capability.Config.DataDir, outcome, args.Limit, "No files found matching pattern", "results"), nil
}

func grepCapability(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	args, err := decodeArguments(call)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if args.Limit == 0 {
		args.Limit = defaultGrepLimit
	}
	if args.Limit < 0 || args.Limit > maxSearchLimit {
		return errorResult(call.InvocationID, rejectInput("limit must be between 1 and 100000")), nil
	}
	outcome, err := search.GrepContext(ctx, args.Path, args.Pattern, args.Literal, args.IgnoreCase, args.Glob, args.Limit)
	if err != nil {
		result := errorResult(call.InvocationID, classifySearchError(err))
		if errors.Is(err, context.Canceled) {
			result.Details = mustJSON(map[string]any{"code": "CANCELLED", "search": map[string]any{"outcome": "CANCELLED", "incomplete": true}})
			result.Contents = append(result.Contents, runtime.ResultContent{Type: "text", Text: "[Search cancelled; results are incomplete.]"})
		}
		return result, nil
	}
	return formatSearchResult(call, capability.Config.DataDir, outcome, args.Limit, "No matches found", "matches"), nil
}

func formatSearchResult(call runtime.CapabilityCall, dataDir string, outcome search.SearchOutcome, limit int, emptyText, limitUnit string) runtime.CapabilityResult {
	if len(outcome.Results) == 0 && !outcome.Incomplete && !outcome.LimitReached {
		return textResult(call.InvocationID, emptyText)
	}
	text := strings.Join(outcome.Results, "\n")
	if outcome.LimitReached {
		if text != "" {
			text += "\n\n"
		}
		text += fmt.Sprintf("[%d %s limit reached. Refine the pattern or raise limit.]", limit, limitUnit)
	}
	if outcome.StopReason == "TIMED_OUT" {
		if text != "" {
			text += "\n\n"
		}
		seconds := strconv.FormatFloat(float64(call.TimeoutMillis)/1000, 'f', -1, 64)
		text += "[Search timed out after " + seconds + "s; results are incomplete. Narrow the path or pattern, or raise timeout_seconds.]"
	}
	result := textResult(call.InvocationID, text)
	if exceedsInlineOutput(text) {
		store, err := TextOutputStoreForDataDir(dataDir)
		if err != nil {
			return errorResult(call.InvocationID, failService("temporary output storage is unavailable"))
		}
		spool, err := NewOutputSpool(store, call.InvocationID, defaultCaptureBudget)
		if err != nil {
			return errorResult(call.InvocationID, failService("temporary output storage is unavailable"))
		}
		spool.Write([]byte(text))
		result = spool.Finish(false, "")
		_ = spool.Close()
	}
	if outcome.StopReason == "TIMED_OUT" {
		var details map[string]any
		if err := json.Unmarshal(result.Details, &details); err != nil {
			details = make(map[string]any)
		}
		details["search"] = map[string]any{"outcome": "TIMED_OUT", "incomplete": true}
		result.Details = mustJSON(details)
	}
	return result
}

func exceedsInlineOutput(text string) bool {
	if len(text) > inlineOutputBytes {
		return true
	}
	var breaks int64
	var last byte
	for i := 0; i < len(text); i++ {
		if text[i] == '\r' || text[i] == '\n' && last != '\r' {
			breaks++
		}
		last = text[i]
	}
	if len(text) > 0 && last != '\r' && last != '\n' {
		breaks++
	}
	return breaks > inlineOutputLines
}

func processCapability(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	var args arguments
	if err := json.Unmarshal(call.Arguments, &args); err != nil {
		return errorResult(call.InvocationID, rejectInput("invalid arguments")), nil
	}
	if strings.TrimSpace(args.Command) == "" {
		return errorResult(call.InvocationID, rejectInput("command must be a non-empty string")), nil
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
		return errorResult(call.InvocationID, rejectInput("workdir is not a directory")), nil
	}
	return ExecuteProcess(ctx, call, capability, args.Command, args.Workdir)
}

func textResult(id, text string) runtime.CapabilityResult {
	return runtime.CapabilityResult{CallID: id, Contents: []runtime.ResultContent{{Type: "text", Text: text}}}
}

func errorResult(id string, err error) runtime.CapabilityResult {
	return toCapabilityErrorResult(id, err)
}

func codedErrorResult(id, code, message string) runtime.CapabilityResult {
	return runtime.CapabilityResult{CallID: id, Error: true, Details: mustJSON(map[string]string{"code": code}), Contents: []runtime.ResultContent{{Type: "text", Text: message}}}
}

func mustJSON(v any) json.RawMessage {
	data, _ := json.Marshal(v)
	return data
}
