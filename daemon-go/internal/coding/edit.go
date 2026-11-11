package coding

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"

	udiff "github.com/aymanbagabas/go-udiff"
	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

const maxReviewBytes = 1 << 20

var hunkHeader = regexp.MustCompile(`^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@`)

func editFile(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext, preview bool) (runtime.CapabilityResult, error) {
	args, err := decodeArguments(call)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	var request arguments
	if err = json.Unmarshal(call.Arguments, &request); err != nil {
		return errorResult(call.InvocationID, errors.New("invalid arguments")), nil
	}
	var raw map[string]json.RawMessage
	if err = json.Unmarshal(call.Arguments, &raw); err != nil {
		return errorResult(call.InvocationID, errors.New("invalid arguments")), nil
	}
	if _, ok := raw["old_string"]; !ok {
		return errorResult(call.InvocationID, errors.New("old_string must be a string")), nil
	}
	if _, ok := raw["new_string"]; !ok {
		return errorResult(call.InvocationID, errors.New("new_string must be a string")), nil
	}
	if request.OldString == "" {
		return errorResult(call.InvocationID, errors.New("old_string must not be empty")), nil
	}
	oldText := normalizeEOL(request.OldString)
	newText := normalizeEOL(request.NewString)
	if oldText == newText {
		//lint:ignore ST1005 This message is model-visible and must match the established capability contract.
		return errorResult(call.InvocationID, errors.New("No changes to apply: old_string and new_string must differ after line-ending normalization")), nil
	}
	if err = ctx.Err(); err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	path, err := filepath.EvalSymlinks(args.Path)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	unlock := lockPath(path)
	defer unlock()
	info, err := os.Lstat(path)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if info.IsDir() {
		return errorResult(call.InvocationID, fmt.Errorf("path must be a file: %s", path)), nil
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
		return errorResult(call.InvocationID, fmt.Errorf("path is not a regular file: %s", path)), nil
	}
	original, err := os.ReadFile(path)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	originalHash := sha256.Sum256(original)
	hash := hex.EncodeToString(originalHash[:])
	if call.ExpectedFile != nil && (call.ExpectedFile.Path != path || call.ExpectedFile.SHA256 != hash) {
		//lint:ignore ST1005 The stale-review text is a fixed model-visible contract.
		return errorResult(call.InvocationID, errors.New(staleWriteReviewMessage)), nil
	}
	decoded, err := DecodeText(original)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	source := normalizeEOL(decoded.Text)
	occurrences := strings.Count(source, oldText)
	if occurrences == 0 {
		//lint:ignore ST1005 This message is model-visible and follows the existing capability contract.
		return errorResult(call.InvocationID, fmt.Errorf("Could not find old_string in %s", path)), nil
	}
	if !request.ReplaceAll && occurrences > 1 {
		//lint:ignore ST1005 This message is model-visible and follows the existing capability contract.
		return errorResult(call.InvocationID, fmt.Errorf("Found %d exact matches; use replace_all to change every match or provide more context", occurrences)), nil
	}
	if request.ReplaceAll && hasOverlappingText(source, oldText) {
		//lint:ignore ST1005 This message is model-visible and follows the existing capability contract.
		return errorResult(call.InvocationID, fmt.Errorf("Found overlapping exact matches for old_string in %s; replace_all cannot safely apply overlapping replacements", path)), nil
	}
	count := 1
	if request.ReplaceAll {
		count = occurrences
	}
	updated := strings.Replace(source, oldText, newText, 1)
	if request.ReplaceAll {
		updated = strings.ReplaceAll(source, oldText, newText)
	}
	updated = preserveLineEndings(updated, decoded.Text)
	encoded, err := EncodeText(updated, decoded.Charset, decoded.BOMBytes)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if string(encoded) == string(original) {
		//lint:ignore ST1005 This message is model-visible and follows the existing capability contract.
		return errorResult(call.InvocationID, errors.New("No changes to apply: edit would not change the file")), nil
	}
	diff, err := numberedDiff(source, normalizeEOL(updated))
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	review := map[string]string{"path": path, "diff": diff, "sha256": hash}
	completed := textResult(call.InvocationID, fmt.Sprintf("Edited successfully.\nReplacements: %d", count))
	resultJSON := mustJSON(map[string]any{"toolCallId": completed.CallID, "contents": completed.Contents, "error": completed.Error, "details": map[string]any{"editReview": review}})
	if len(resultJSON) > maxReviewBytes {
		//lint:ignore ST1005 This message is model-visible and follows the existing capability contract.
		return errorResult(call.InvocationID, errors.New("Complete edit diff exceeds the review transport limit; split this edit into smaller changes.")), nil
	}
	if err = ctx.Err(); err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if preview {
		return runtime.CapabilityResult{CallID: call.InvocationID, Details: []byte(`{}`), Contents: []runtime.ResultContent{{Type: "json", JSON: mustJSON(review)}}}, nil
	}
	if err = atomicWrite(path, encoded, info.Mode().Perm()); err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if capability.FileChanged != nil {
		capability.FileChanged(path)
	}
	completed.Details = mustJSON(map[string]any{"editReview": review})
	return completed, nil
}

func normalizeEOL(text string) string {
	return strings.ReplaceAll(strings.ReplaceAll(text, "\r\n", "\n"), "\r", "\n")
}

func preserveLineEndings(updated, original string) string {
	oldLines, oldEnds := splitEOLDocument(original)
	newLines := strings.Split(updated, "\n")
	if strings.HasSuffix(updated, "\n") {
		newLines = newLines[:len(newLines)-1]
	}
	defaultEOL := "\n"
	for _, ending := range oldEnds {
		if ending != "" {
			defaultEOL = ending
			break
		}
	}
	var out strings.Builder
	oldCursor := 0
	for i, line := range newLines {
		out.WriteString(line)
		if i >= len(newLines)-1 && !strings.HasSuffix(updated, "\n") {
			continue
		}
		ending := defaultEOL
		matched := false
		for j := oldCursor; j < len(oldLines); j++ {
			if oldLines[j] == line {
				if oldEnds[j] != "" {
					ending = oldEnds[j]
				}
				oldCursor = j + 1
				matched = true
				break
			}
		}
		if !matched && oldCursor < len(oldEnds) && oldEnds[oldCursor] != "" {
			ending = oldEnds[oldCursor]
		}
		out.WriteString(ending)
	}
	return out.String()
}

func splitEOLDocument(text string) ([]string, []string) {
	var lines, endings []string
	start := 0
	for i := 0; i < len(text); i++ {
		if text[i] == '\n' {
			end := i
			ending := "\n"
			if end > start && text[end-1] == '\r' {
				end--
				ending = "\r\n"
			}
			lines = append(lines, text[start:end])
			endings = append(endings, ending)
			start = i + 1
		}
	}
	if start < len(text) || len(lines) == 0 {
		lines = append(lines, text[start:])
		endings = append(endings, "")
	}
	return lines, endings
}

func hasOverlappingText(text, needle string) bool {
	previousEnd := -1
	for offset := 0; offset <= len(text)-len(needle); {
		i := strings.Index(text[offset:], needle)
		if i < 0 {
			return false
		}
		start := offset + i
		if start < previousEnd {
			return true
		}
		previousEnd = start + len(needle)
		offset = start + 1
	}
	return false
}

func diffLineCount(text string) []string {
	lines := strings.Split(text, "\n")
	if strings.HasSuffix(text, "\n") {
		lines = lines[:len(lines)-1]
	}
	return lines
}

func numberedDiff(oldText, newText string) (string, error) {
	patch, err := udiff.ToUnified("old", "new", oldText, udiff.Lines(oldText, newText), 4)
	if err != nil {
		return "", err
	}
	oldLines := diffLineCount(oldText)
	newLines := diffLineCount(newText)
	width := max(2, len(fmt.Sprint(max(len(oldLines), len(newLines)))))
	var out []string
	oldCursor, newCursor := 1, 1
	inHunk := false
	seen := false
	for _, line := range strings.Split(strings.ReplaceAll(patch, "\r\n", "\n"), "\n") {
		if strings.HasPrefix(line, "--- ") || strings.HasPrefix(line, "+++ ") || line == "" {
			continue
		}
		if strings.HasPrefix(line, "@@ ") {
			match := hunkHeader.FindStringSubmatch(line)
			if match == nil {
				return "", fmt.Errorf("invalid unified diff hunk: %s", line)
			}
			oldStart, _ := strconv.Atoi(match[1])
			newStart, _ := strconv.Atoi(match[3])
			if seen && (oldStart > oldCursor || newStart > newCursor) {
				out = append(out, "...")
			}
			if !seen && (oldStart > 1 || newStart > 1) {
				out = append(out, "...")
			}
			oldCursor, newCursor, inHunk, seen = oldStart, newStart, true, true
			continue
		}
		if !inHunk {
			continue
		}
		if line == `\ No newline at end of file` {
			out = append(out, line)
			continue
		}
		if line == "\\" {
			continue
		}
		if len(line) == 0 {
			continue
		}
		switch line[0] {
		case ' ':
			out = append(out, fmt.Sprintf(" %*d|%s", width, oldCursor, line[1:]))
			oldCursor++
			newCursor++
		case '-':
			out = append(out, fmt.Sprintf("-%*d|%s", width, oldCursor, line[1:]))
			oldCursor++
		case '+':
			out = append(out, fmt.Sprintf("+%*d|%s", width, newCursor, line[1:]))
			newCursor++
		default:
			return "", fmt.Errorf("invalid unified diff line: %s", line)
		}
	}
	if !seen {
		return "", errors.New("edit produced an empty diff")
	}
	if oldCursor <= len(oldLines) || newCursor <= len(newLines) {
		out = append(out, "...")
	}
	return strings.Join(out, "\n"), nil
}
