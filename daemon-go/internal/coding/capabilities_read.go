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
	"strings"

	imagescaler "github.com/fengwk/kk-studio/daemon/internal/coding/image"
	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// lspStatusFunc reports the language the optional fs.read header names for a file.
type lspStatusFunc func(file string) (language string, available bool)

func readCapability(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext) (runtime.CapabilityResult, error) {
	return readCapabilityWithLSPStatus(ctx, call, capability, nil)
}

func readCapabilityWithLSPStatus(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext, lspStatus lspStatusFunc) (runtime.CapabilityResult, error) {
	args, err := decodeReadArguments(call)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
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
		return readDirectory(call, args)
	}
	if !info.Mode().IsRegular() {
		return errorResult(call.InvocationID, failRun("path is not a regular file", "INVALID_ARGUMENT")), nil
	}
	return readFile(ctx, call, args, info.Size(), lspStatus)
}

// decodeReadArguments decodes the fs.read arguments and applies the defaults and range limits
// that read enforces on top of the shared argument shape.
func decodeReadArguments(call runtime.CapabilityCall) (arguments, error) {
	args, err := decodeArguments(call)
	if err != nil {
		return args, err
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(call.Arguments, &raw); err != nil {
		return args, rejectInput("invalid arguments")
	}
	if _, exists := raw["offset"]; !exists {
		args.Offset = 1
	}
	if _, exists := raw["limit"]; !exists {
		args.Limit = DefaultReadLimit
	}
	if args.Offset < 1 || args.Offset > 2147483647 {
		return args, rejectInput("offset must be a positive integer")
	}
	if args.Limit < 1 || args.Limit > MaxReadLimit {
		return args, rejectInput("limit must be a positive integer no greater than 2000")
	}
	if column, exists := raw["column_offset"]; exists && string(column) != "null" {
		if err := json.Unmarshal(column, &args.ColumnOffset); err != nil || args.ColumnOffset < 1 {
			return args, rejectInput("column_offset must be a positive integer")
		}
	} else {
		args.ColumnOffset = 1
	}
	return args, nil
}

// readDirectory renders one page of a directory listing, bounded by maxDirectoryResponseBytes.
func readDirectory(call runtime.CapabilityCall, args arguments) (runtime.CapabilityResult, error) {
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
	page, actualEnd, err := directoryPage(names, args.Offset, args.Limit, len(prefix))
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	result := prefix + "\n" + strings.Join(page, "\n")
	if actualEnd < len(names) {
		result += "\n\n" + directoryContinuation(args.Offset, actualEnd, len(names))
	}
	return textResult(call.InvocationID, result), nil
}

// directoryPage collects the entries that fit in one response and reports the last entry shown.
// An entry that overflows the bound on its own is an error, not a silently empty page.
func directoryPage(names []string, offset, limit, prefixBytes int) ([]string, int, error) {
	start := offset - 1
	end := min(len(names), start+limit)
	page := make([]string, 0, end-start)
	actualEnd := start
	for index := start; index < end; index++ {
		candidateEnd := index + 1
		candidateBytes := prefixBytes + 1 + len(names[index])
		if candidateEnd < len(names) {
			candidateBytes += 2 + len(directoryContinuation(offset, candidateEnd, len(names)))
		}
		if candidateBytes > maxDirectoryResponseBytes {
			if index == start {
				return nil, start, fmt.Errorf("directory read response exceeds %d bytes on first entry: %d bytes", maxDirectoryResponseBytes, candidateBytes)
			}
			break
		}
		page = append(page, names[index])
		actualEnd = candidateEnd
	}
	return page, actualEnd, nil
}

// directoryContinuation renders the notice that more entries follow the page.
func directoryContinuation(offset, shownEnd, total int) string {
	return fmt.Sprintf("[Showing entries %d-%d of %d. Re-run read with offset=%d to continue.]", offset, shownEnd, total, shownEnd+1)
}

// readFile dispatches a regular file to its image, other-media or text reader.
func readFile(ctx context.Context, call runtime.CapabilityCall, args arguments, size int64, lspStatus lspStatusFunc) (runtime.CapabilityResult, error) {
	file, err := os.Open(args.Path)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	probe, err := probeMedia(file)
	if err != nil {
		file.Close()
		return errorResult(call.InvocationID, err), nil
	}
	imageType := DetectImageType(probe)
	mediaType := DetectMediaType(probe)
	if (imageType != "" || mediaType != "") && call.MaxOutputBytes > 0 && size > call.MaxOutputBytes {
		file.Close()
		message := fmt.Sprintf("File is %s MiB, which exceeds the tool output size limit of %d MiB. The file was not read. Use a smaller file, for example by converting or splitting it with bash, or ask the user to raise the limit in system settings.", formatMiB(size, call.MaxOutputBytes), call.MaxOutputBytes/(1<<20))
		return codedErrorResult(call.InvocationID, "INVALID_ARGUMENT", message), nil
	}
	if imageType != "" || mediaType != "" {
		data, err := readAllAndClose(file)
		if err != nil {
			return errorResult(call.InvocationID, err), nil
		}
		if imageType != "" {
			return readImage(ctx, call, imageType, data)
		}
		return readMedia(call, args.Path, mediaType, data)
	}
	return readText(file, call, args, lspStatus)
}

// probeMedia reads the leading bytes that identify a file's media type.
func probeMedia(file *os.File) ([]byte, error) {
	probe := make([]byte, 12)
	n, err := io.ReadFull(file, probe)
	if err != nil && !errors.Is(err, io.EOF) && !errors.Is(err, io.ErrUnexpectedEOF) {
		return nil, err
	}
	return probe[:n], nil
}

// readAllAndClose rewinds file and returns its whole content.
func readAllAndClose(file *os.File) ([]byte, error) {
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		file.Close()
		return nil, err
	}
	data, readErr := io.ReadAll(file)
	closeErr := file.Close()
	if readErr != nil {
		return nil, readErr
	}
	if closeErr != nil {
		return nil, closeErr
	}
	return data, nil
}

// readImage scales one image to the requested input tier.
func readImage(ctx context.Context, call runtime.CapabilityCall, imageType string, data []byte) (runtime.CapabilityResult, error) {
	tier, err := parseImageTier(call.ImageInputTier)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	result, err := imagescaler.ScaleContext(ctx, imageType, data, tier, call.MaxOutputBytes)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	content := runtime.ResultContent{Type: "binary", Bytes: result.Bytes, MediaType: result.MediaType, Size: int64(len(result.Bytes))}
	details := map[string]int{"originalWidth": result.OriginalWidth, "originalHeight": result.OriginalHeight, "displayWidth": result.DisplayWidth, "displayHeight": result.DisplayHeight}
	return runtime.CapabilityResult{CallID: call.InvocationID, Details: mustJSON(map[string]any{"image": details}), Contents: []runtime.ResultContent{content}}, nil
}

// readMedia returns one non-image media file unchanged.
func readMedia(call runtime.CapabilityCall, path, mediaType string, data []byte) (runtime.CapabilityResult, error) {
	name := filepath.Base(path)
	content := runtime.ResultContent{Type: "binary", Bytes: data, MediaType: mediaType, Name: &name, Size: int64(len(data))}
	return runtime.CapabilityResult{CallID: call.InvocationID, Contents: []runtime.ResultContent{content}}, nil
}

// readText returns one window of a text file and, when the caller supplies lspStatus, the
// language a configured server would handle the file with.
func readText(file *os.File, call runtime.CapabilityCall, args arguments, lspStatus lspStatusFunc) (runtime.CapabilityResult, error) {
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		file.Close()
		return errorResult(call.InvocationID, err), nil
	}
	window, readErr := ReadTextWindowReader(file, WindowOptions{Path: args.Path, Offset: args.Offset, Limit: args.Limit, ColumnOffset: args.ColumnOffset})
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
