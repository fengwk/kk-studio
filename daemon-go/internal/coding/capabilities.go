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
	defaultFindLimit          = 1000
	defaultGrepLimit          = 100
	maxSearchLimit            = 100000
	maxDirectoryResponseBytes = 48 * 1024
)

type arguments struct {
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
type Provider struct {
	// LSPStatus reports the available language for the optional fs.read header.
	LSPStatus func(file string) (language string, available bool)
}

func (p Provider) Capabilities() map[string]runtime.CapabilityExecutor {
	read := runtime.CapabilityExecutor(readCapability)
	if p.LSPStatus != nil {
		read = func(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext) (runtime.CapabilityResult, error) {
			return readCapabilityWithLSPStatus(ctx, call, capability, p.LSPStatus)
		}
	}
	handlers := map[string]runtime.CapabilityExecutor{
		"fs.read":          read,
		"fs.write":         writeCapability,
		"fs.write.preview": previewWriteCapability,
		"fs.edit":          editCapability,
		"fs.edit.preview":  editPreviewCapability,
		"fs.find":          findCapability,
		"fs.grep":          grepCapability,
		"process.exec":     processCapability,
	}
	for id, handler := range handlers {
		handler = limitOutput(handler)
		if id != "process.exec" {
			handler = confirmExecutionExit(handler)
		}
		handlers[id] = handler
	}
	return handlers
}

// confirmExecutionExit reports that an in-process capability has no surviving
// execution scope. process.exec and LSP capabilities confirm their own process
// lifetimes instead.
func confirmExecutionExit(handler runtime.CapabilityExecutor) runtime.CapabilityExecutor {
	return func(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext) (runtime.CapabilityResult, error) {
		if capability.ConfirmExecutionExit != nil {
			defer capability.ConfirmExecutionExit()
		}
		return handler(ctx, call, capability)
	}
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

func validateRequiredString(rawJSON json.RawMessage, name string) error {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(rawJSON, &raw); err != nil {
		return rejectInput("invalid arguments")
	}
	value, ok := raw[name]
	if !ok || strings.TrimSpace(string(value)) == "null" {
		return rejectInput(name + " must be a string")
	}
	var text string
	if err := json.Unmarshal(value, &text); err != nil {
		return rejectInput(name + " must be a string")
	}
	return nil
}

func decodeArguments(call runtime.CapabilityCall) (arguments, error) {
	var args arguments
	if err := json.Unmarshal(call.Arguments, &args); err != nil {
		return args, rejectInput("invalid arguments")
	}
	if err := validatePathArgument(args.Path); err != nil {
		return args, err
	}
	return args, nil
}

func validatePathArgument(path string) error {
	if path == "" {
		return rejectInput("path must be a non-empty string")
	}
	if !filepath.IsAbs(path) {
		return rejectInput("path must be an absolute path")
	}
	return nil
}

func readCapability(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	return readCapabilityWithLSPStatus(ctx, call, capability, nil)
}

func readCapabilityWithLSPStatus(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext, lspStatus func(file string) (language string, available bool)) (runtime.CapabilityResult, error) {
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
	args.Path, err = resolveExistingPath(args.Path)
	if err != nil {
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
		prefix := fmt.Sprintf("path: %s\nkind: directory\n", filepath.ToSlash(args.Path))
		if args.Offset > len(names) {
			return textResult(call.InvocationID, prefix+fmt.Sprintf("\n[Showing 0 entries of %d.]", len(names))), nil
		}
		start := args.Offset - 1
		end := min(len(names), start+args.Limit)
		page := make([]string, 0, end-start)
		actualEnd := start
		for index := start; index < end; index++ {
			candidateEnd := index + 1
			candidateBytes := len(prefix) + 1 + len(names[index])
			if candidateEnd < len(names) {
				tail := fmt.Sprintf("[Showing entries %d-%d of %d. Re-run read with offset=%d to continue.]", args.Offset, candidateEnd, len(names), candidateEnd+1)
				candidateBytes += 2 + len(tail)
			}
			if candidateBytes > maxDirectoryResponseBytes {
				if index == start {
					message := fmt.Sprintf("directory read response exceeds %d bytes on first entry: %d bytes", maxDirectoryResponseBytes, candidateBytes)
					return errorResult(call.InvocationID, errors.New(message)), nil
				}
				break
			}
			page = append(page, names[index])
			actualEnd = candidateEnd
		}
		result := prefix + "\n" + strings.Join(page, "\n")
		if actualEnd < len(names) {
			tail := fmt.Sprintf("[Showing entries %d-%d of %d. Re-run read with offset=%d to continue.]", args.Offset, actualEnd, len(names), actualEnd+1)
			result += "\n\n" + tail
		}
		return textResult(call.InvocationID, result), nil
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
	if (imageType != "" || mediaType != "") && call.MaxOutputBytes > 0 && info.Size() > call.MaxOutputBytes {
		file.Close()
		message := fmt.Sprintf("File is %s MiB, which exceeds the tool output size limit of %d MiB. The file was not read. Use a smaller file, for example by converting or splitting it with bash, or ask the user to raise the limit in system settings.", formatMiB(info.Size(), call.MaxOutputBytes), call.MaxOutputBytes/(1<<20))
		return codedErrorResult(call.InvocationID, "INVALID_ARGUMENT", message), nil
	}
	if imageType != "" || mediaType != "" {
		if _, err = file.Seek(0, io.SeekStart); err != nil {
			file.Close()
			return errorResult(call.InvocationID, err), nil
		}
		data, readErr := io.ReadAll(file)
		closeErr := file.Close()
		if readErr != nil {
			return errorResult(call.InvocationID, readErr), nil
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
		name := filepath.Base(args.Path)
		content := runtime.ResultContent{Type: "binary", Bytes: data, MediaType: mediaType, Name: &name, Size: int64(len(data))}
		return runtime.CapabilityResult{CallID: call.InvocationID, Contents: []runtime.ResultContent{content}}, nil
	}
	if _, err = file.Seek(0, io.SeekStart); err != nil {
		file.Close()
		return errorResult(call.InvocationID, err), nil
	}
	window, readErr := ReadTextWindowReader(file, WindowOptions{Offset: args.Offset, Limit: args.Limit, ColumnOffset: args.ColumnOffset})
	closeErr := file.Close()
	if readErr != nil {
		var typed *toolError
		if errors.As(readErr, &typed) {
			return errorResult(call.InvocationID, readErr), nil
		}
		return errorResult(call.InvocationID, rejectInput(readErr.Error())), nil
	}
	if closeErr != nil {
		return errorResult(call.InvocationID, closeErr), nil
	}
	if lspStatus != nil {
		language, available := lspStatus(args.Path)
		if available && language != "" {
			window.LSPStatus = "supported (" + language + ")"
		}
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
	if window.LSPStatus != "" {
		lines = append(lines, "lsp: "+window.LSPStatus)
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
	if err := validateRequiredString(call.Arguments, "content"); err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	args, err := decodeArguments(call)
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
	if err := validateRequiredString(call.Arguments, "content"); err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	args, err := decodeArguments(call)
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
	info, err := os.Stat(args.Path)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if !info.IsDir() {
		return errorResult(call.InvocationID, rejectInput("path must be a directory")), nil
	}
	outcome, err := search.FindContext(ctx, args.Path, args.Pattern, args.Limit)
	if err != nil {
		result := errorResult(call.InvocationID, classifySearchError(err))
		if errors.Is(err, context.Canceled) {
			result.Details = mustJSON(map[string]any{"code": "CANCELLED", "search": map[string]any{"outcome": "CANCELLED", "incomplete": true}})
			result.Contents = []runtime.ResultContent{{Type: "text", Text: "[Search cancelled; results are incomplete.]"}}
		}
		return result, nil
	}
	return formatSearchResult(call, capability.Config.DataDir, outcome, args.Limit, "No files found matching pattern", "results"), nil
}

func grepCapability(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(call.Arguments, &raw); err != nil {
		return errorResult(call.InvocationID, rejectInput("invalid arguments")), nil
	}
	for _, removed := range []string{"multiline", "include"} {
		if _, exists := raw[removed]; exists {
			return errorResult(call.InvocationID, rejectInput("unknown argument: "+removed)), nil
		}
	}
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
	if strings.ContainsRune(args.Pattern, '\u0000') {
		return errorResult(call.InvocationID, rejectInput("pattern must not contain NUL")), nil
	}
	if strings.ContainsRune(args.Glob, '\u0000') {
		return errorResult(call.InvocationID, rejectInput("glob must not contain NUL")), nil
	}
	outcome, err := search.GrepContext(ctx, args.Path, args.Pattern, args.Literal, args.IgnoreCase, args.Glob, args.Limit)
	if err != nil {
		result := errorResult(call.InvocationID, classifySearchError(err))
		if errors.Is(err, context.Canceled) {
			result.Details = mustJSON(map[string]any{"code": "CANCELLED", "search": map[string]any{"outcome": "CANCELLED", "incomplete": true}})
			result.Contents = []runtime.ResultContent{{Type: "text", Text: "[Search cancelled; results are incomplete.]"}}
		}
		return result, nil
	}
	return formatSearchResult(call, capability.Config.DataDir, outcome, args.Limit, "No matches found", "matches"), nil
}

func formatSearchResult(call runtime.CapabilityCall, dataDir string, outcome search.SearchOutcome, limit int, emptyText, limitUnit string) runtime.CapabilityResult {
	text := strings.Join(outcome.Results, "\n")
	if text == "" && len(outcome.Skipped) == 0 && !outcome.Incomplete && !outcome.LimitReached {
		text = emptyText
	}
	if len(outcome.Skipped) > 0 {
		describe := strings.Join(outcome.Skipped[:min(len(outcome.Skipped), 10)], ", ")
		if len(outcome.Skipped) > 10 {
			describe += ", ..."
		}
		if text != "" {
			text += "\n\n"
		}
		text += fmt.Sprintf("[%d path(s) could not be searched: %s]", len(outcome.Skipped), describe)
	}
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
	if len(outcome.Results) == 0 && outcome.IgnoreSkippedCount > 0 {
		if text != "" {
			text += "\n"
		}
		text += formatIgnoreSkippedHint(outcome.IgnoreSkipped, outcome.IgnoreSkippedCount)
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

func formatIgnoreSkippedHint(paths []string, count int) string {
	shown := paths[:min(len(paths), 10)]
	entries := strings.Join(shown, ", ")
	if count > len(shown) {
		if entries != "" {
			entries += ", "
		}
		entries += fmt.Sprintf("… and %d more", count-len(shown))
	}
	return "[No matches. Not searched because of ignore rules: " + entries + "]"
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
	executing := false
	defer func() {
		if !executing && capability.ConfirmExecutionExit != nil {
			capability.ConfirmExecutionExit()
		}
	}()
	var args arguments
	if err := json.Unmarshal(call.Arguments, &args); err != nil {
		return errorResult(call.InvocationID, rejectInput("invalid arguments")), nil
	}
	if strings.TrimSpace(args.Command) == "" {
		return errorResult(call.InvocationID, rejectInput("command must be a non-empty string")), nil
	}
	if strings.TrimSpace(args.Workdir) == "" {
		return errorResult(call.InvocationID, rejectInput("workdir is required")), nil
	}
	if !filepath.IsAbs(args.Workdir) {
		return errorResult(call.InvocationID, rejectInput("workdir must be an absolute path (a relative or non-absolute value was given)")), nil
	}
	args.Workdir = filepath.Clean(args.Workdir)
	resolvedWorkdir, err := filepath.EvalSymlinks(args.Workdir)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return errorResult(call.InvocationID, rejectInputWithCode("workdir does not exist: "+displayPath(nil, &args.Workdir), "NOT_FOUND")), nil
		}
		return errorResult(call.InvocationID, err), nil
	}
	info, err := os.Stat(resolvedWorkdir)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if !info.IsDir() {
		return errorResult(call.InvocationID, rejectInput("workdir is not a directory: "+displayPath(&resolvedWorkdir, &args.Workdir))), nil
	}
	args.Workdir = resolvedWorkdir
	executing = true
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
