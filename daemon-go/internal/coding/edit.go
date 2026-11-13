package coding

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	udiff "github.com/aymanbagabas/go-udiff"
	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

const (
	maxReviewBytes = 1 << 20
	maxShownLines  = 10
	maxShownRanges = 3
)

var (
	hunkHeader     = regexp.MustCompile(`^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@`)
	readLinePrefix = regexp.MustCompile(`^\s*\d+\|`)
)

// editEntry is one exact replacement. Every entry is matched against the original file content,
// never against the result of an earlier entry.
type editEntry struct {
	OldString  *string `json:"old_string"`
	NewString  *string `json:"new_string"`
	ReplaceAll bool    `json:"replace_all"`
}

// editArguments is the argument set of fs.edit. The schema model cannot express minItems, so the
// non-empty entry list is enforced at run time.
type editArguments struct {
	Path  string      `json:"path"`
	Edits []editEntry `json:"edits"`
}

// editReplacement is one exact occurrence of an entry, expressed in normalized-text coordinates.
type editReplacement struct {
	entry   int
	start   int
	end     int
	newText string
}

// editSource is the LF view of the decoded file plus the facts needed to write it back unchanged.
type editSource struct {
	text string
	// collapsed holds the normalized index of every CRLF collapse; each entry maps a normalized
	// boundary at or after it to one extra byte of the original text.
	collapsed []int
	lines     *lineIndex
}

func editFile(ctx context.Context, call runtime.CapabilityCall, capability runtime.CapabilityContext, preview bool) (runtime.CapabilityResult, error) {
	args, err := decodeEditArguments(call.Arguments)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if len(args.Edits) == 0 {
		return errorResult(call.InvocationID, rejectInput("edits must contain at least one entry; use one entry for a single change")), nil
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
		return errorResult(call.InvocationID, rejectInput(fmt.Sprintf("path must be a file: %s", path))), nil
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
		return errorResult(call.InvocationID, rejectInput(fmt.Sprintf("path is not a regular file: %s", path))), nil
	}
	original, err := os.ReadFile(path)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	originalHash := sha256.Sum256(original)
	hash := hex.EncodeToString(originalHash[:])
	if call.ExpectedFile != nil && (call.ExpectedFile.Path != path || call.ExpectedFile.SHA256 != hash) {
		return errorResult(call.InvocationID, rejectInput(staleWriteReviewMessage)), nil
	}
	decoded, err := DecodeText(original)
	if err != nil {
		return errorResult(call.InvocationID, failRun(err.Error(), "CODEC")), nil
	}
	source := newEditSource(decoded.Text)
	replacements, err := planEdits(path, source, args.Edits)
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	updated := applyEdits(decoded.Text, source, replacements)
	encoded, err := EncodeText(updated, decoded.Charset, decoded.BOMBytes)
	if err != nil {
		return errorResult(call.InvocationID, failRun(err.Error(), "CODEC")), nil
	}
	if bytes.Equal(encoded, original) {
		return errorResult(call.InvocationID, rejectInput("No changes to apply: the edits would not change the file")), nil
	}
	diff, err := numberedDiff(source.text, normalizeEOL(updated))
	if err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	review := map[string]string{"path": path, "diff": diff, "sha256": hash}
	completed := textResult(call.InvocationID, fmt.Sprintf("Edited successfully.\nReplacements: %d", len(replacements)))
	resultJSON := mustJSON(map[string]any{"toolCallId": completed.CallID, "contents": completed.Contents, "error": completed.Error, "details": map[string]any{"editReview": review}})
	if len(resultJSON) > maxReviewBytes {
		return errorResult(call.InvocationID, rejectInput("Complete edit diff exceeds the review transport limit; split the change into several smaller edit calls.")), nil
	}
	if err = ctx.Err(); err != nil {
		return errorResult(call.InvocationID, err), nil
	}
	if preview {
		return runtime.CapabilityResult{CallID: call.InvocationID, Details: []byte(`{}`), Contents: []runtime.ResultContent{{Type: "json", JSON: mustJSON(review)}}}, nil
	}
	if err = atomicWrite(path, encoded, info.Mode().Perm()); err != nil {
		code, message := classifyFileSystemError(err)
		return errorResult(call.InvocationID, failRun(message, code)), nil
	}
	if capability.FileChanged != nil {
		capability.FileChanged(path)
	}
	completed.Details = mustJSON(map[string]any{"editReview": review})
	return completed, nil
}

// decodeEditArguments rejects unknown fields at both levels, so a call written for the replaced
// single-replacement contract fails instead of being silently ignored.
func decodeEditArguments(raw json.RawMessage) (editArguments, error) {
	var args editArguments
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&args); err != nil {
		return args, rejectInput("invalid arguments")
	}
	var trailing json.RawMessage
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return args, rejectInput("invalid arguments")
	}
	if err := validatePathArgument(args.Path); err != nil {
		return args, err
	}
	for index, entry := range args.Edits {
		if entry.OldString == nil {
			return args, rejectInput(fmt.Sprintf("edits[%d].old_string must be a string", index))
		}
		if entry.NewString == nil {
			return args, rejectInput(fmt.Sprintf("edits[%d].new_string must be a string", index))
		}
	}
	return args, nil
}

// newEditSource builds the LF view of text and remembers the original spelling of every line
// break, which is what the spliced result and the diagnostics need.
func newEditSource(text string) *editSource {
	source := &editSource{lines: &lineIndex{starts: []int{0}}}
	var builder strings.Builder
	builder.Grow(len(text))
	for offset := 0; offset < len(text); {
		lineBreak := lineBreakAt(text, offset)
		if lineBreak == "" {
			builder.WriteByte(text[offset])
			offset++
			continue
		}
		if lineBreak == "\r\n" {
			source.collapsed = append(source.collapsed, builder.Len())
		}
		builder.WriteByte('\n')
		source.lines.ends = append(source.lines.ends, lineBreak)
		offset += len(lineBreak)
		if offset < len(text) {
			source.lines.starts = append(source.lines.starts, builder.Len())
		}
	}
	source.text = builder.String()
	for len(source.lines.ends) < len(source.lines.starts) {
		source.lines.ends = append(source.lines.ends, "")
	}
	return source
}

// originalOffset maps a boundary of the normalized text back to the untouched original bytes.
func (s *editSource) originalOffset(index int) int {
	return index + sort.SearchInts(s.collapsed, index)
}

// planEdits validates every entry against the original snapshot and returns the replacements in
// ascending order, or one error listing every problem the call has to fix.
func planEdits(path string, source *editSource, entries []editEntry) ([]editReplacement, error) {
	var problems []string
	var replacements []editReplacement
	for index, entry := range entries {
		number := index + 1
		oldText := normalizeEOL(*entry.OldString)
		newText := normalizeEOL(*entry.NewString)
		if oldText == "" {
			problems = append(problems, editProblem(number, "old_string must not be empty"))
			continue
		}
		if oldText == newText {
			problems = append(problems, editProblem(number, "old_string and new_string must differ after line-ending normalization"))
			continue
		}
		starts := findOverlappingText(source.text, oldText)
		if len(starts) == 0 {
			problems = append(problems, editProblem(number, missingOldStringMessage(path, source, oldText)))
			continue
		}
		if !entry.ReplaceAll && len(starts) > 1 {
			problems = append(problems, editProblem(number, duplicateOldStringMessage(path, source.lines, starts)))
			continue
		}
		if entry.ReplaceAll && overlappingStarts(starts, len(oldText)) {
			problems = append(problems, editProblem(number, overlappingOldStringMessage(path, source.lines, starts)))
			continue
		}
		for _, start := range starts {
			replacements = append(replacements, editReplacement{entry: number, start: start, end: start + len(oldText), newText: newText})
		}
	}
	problems = append(problems, overlappingEditMessages(path, source.lines, replacements)...)
	if len(problems) > 0 {
		return nil, rejectInput(strings.Join(problems, "\n"))
	}
	sort.SliceStable(replacements, func(i, j int) bool { return replacements[i].start < replacements[j].start })
	return replacements, nil
}

// applyEdits splices the replacements into the original text in one linear pass, so untouched
// bytes stay identical and the cost stays proportional to the file size.
func applyEdits(text string, source *editSource, replacements []editReplacement) string {
	var builder strings.Builder
	builder.Grow(len(text))
	previous := 0
	for _, replacement := range replacements {
		start := source.originalOffset(replacement.start)
		end := source.originalOffset(replacement.end)
		builder.WriteString(text[previous:start])
		builder.WriteString(replaceLineBreaks(replacement.newText, source.spanLineBreak(text, replacement, start, end)))
		previous = end
	}
	builder.WriteString(text[previous:])
	return builder.String()
}

func editProblem(number int, message string) string {
	return fmt.Sprintf("Edit %d: %s", number, message)
}

// findOverlappingText returns every exact occurrence start, advancing one byte after each hit so
// self-overlapping occurrences stay visible to the caller.
func findOverlappingText(text, needle string) []int {
	var starts []int
	for offset := 0; offset <= len(text)-len(needle); {
		found := strings.Index(text[offset:], needle)
		if found < 0 {
			break
		}
		start := offset + found
		starts = append(starts, start)
		offset = start + 1
	}
	return starts
}

func overlappingStarts(starts []int, length int) bool {
	for index := 1; index < len(starts); index++ {
		if starts[index] < starts[index-1]+length {
			return true
		}
	}
	return false
}

// missingOldStringMessage adds at most one hint, in a fixed order, so a failing entry is actionable
// without ever applying a fuzzy match.
func missingOldStringMessage(path string, source *editSource, oldText string) string {
	message := fmt.Sprintf("Could not find old_string in %s.", path)
	if prefixHint := readPrefixHint(oldText); prefixHint != "" {
		return message + prefixHint
	}
	return message + whitespaceHint(source, oldText)
}

func readPrefixHint(oldText string) string {
	first := ""
	for _, line := range strings.Split(oldText, "\n") {
		if line == "" {
			continue
		}
		prefix := readLinePrefix.FindString(line)
		if prefix == "" {
			return ""
		}
		if first == "" {
			first = prefix
		}
	}
	if first == "" {
		return ""
	}
	return fmt.Sprintf(" It includes read line-number prefixes such as %q; copy only the text after the first \"|\" on each line.", first)
}

// whitespaceHint reports the original line ranges that match once Unicode whitespace is removed
// from both the entry and the file; the hint never performs the replacement itself.
func whitespaceHint(source *editSource, oldText string) string {
	needle := stripWhitespace(oldText).text
	if needle == "" {
		return ""
	}
	haystack := stripWhitespace(source.text)
	var ranges []lineSpan
	total := 0
	first := lineCursor{starts: haystack.lineStarts}
	last := lineCursor{starts: haystack.lineStarts}
	var previous lineSpan
	for offset := 0; offset <= len(haystack.text)-len(needle); {
		found := strings.Index(haystack.text[offset:], needle)
		if found < 0 {
			break
		}
		start := offset + found
		offset = start + len(needle)
		span := lineSpan{first: first.lineOf(start), last: last.lineOf(start + len(needle) - 1)}
		// Whitespace differences inside one line collapse into the same range; report it once.
		if total > 0 && span == previous {
			continue
		}
		previous = span
		total++
		if len(ranges) < maxShownRanges {
			ranges = append(ranges, span)
		}
	}
	if total == 0 {
		return ""
	}
	shown := make([]string, 0, len(ranges))
	for _, span := range ranges {
		if span.first == span.last {
			shown = append(shown, strconv.Itoa(span.first))
			continue
		}
		shown = append(shown, fmt.Sprintf("%d-%d", span.first, span.last))
	}
	message := " Lines " + strings.Join(shown, ", ")
	if total > len(ranges) {
		message += fmt.Sprintf(" and %d more", total-len(ranges))
	}
	return message + " match it when whitespace is ignored; copy the exact text from read."
}

func duplicateOldStringMessage(path string, lines *lineIndex, starts []int) string {
	return fmt.Sprintf("Found %d exact matches for old_string in %s at lines %s. Add surrounding context to make it unique, or set replace_all to true.", len(starts), path, shownLines(lines, starts))
}

func overlappingOldStringMessage(path string, lines *lineIndex, starts []int) string {
	return fmt.Sprintf("Found overlapping exact matches for old_string in %s at lines %s; replace_all cannot apply overlapping replacements. Use a more specific old_string.", path, shownLines(lines, starts))
}

func shownLines(lines *lineIndex, starts []int) string {
	shown := make([]string, 0, min(maxShownLines, len(starts)))
	for index, start := range starts {
		if index == maxShownLines {
			break
		}
		shown = append(shown, strconv.Itoa(lines.lineOf(start)))
	}
	text := strings.Join(shown, ", ")
	if len(starts) > maxShownLines {
		text += fmt.Sprintf(" and %d more", len(starts)-maxShownLines)
	}
	return text
}

// overlappingEditMessages reports every pair of entries whose replacements intersect; ranges that
// only touch stay valid because they change separate parts of the original file.
func overlappingEditMessages(path string, lines *lineIndex, replacements []editReplacement) []string {
	sorted := make([]editReplacement, len(replacements))
	copy(sorted, replacements)
	sort.SliceStable(sorted, func(i, j int) bool {
		if sorted[i].start != sorted[j].start {
			return sorted[i].start < sorted[j].start
		}
		return sorted[i].end < sorted[j].end
	})
	var problems []string
	reported := make(map[[2]int]bool, len(sorted))
	var active []editReplacement
	for _, current := range sorted {
		kept := active[:0]
		for _, previous := range active {
			if previous.end > current.start {
				kept = append(kept, previous)
			}
		}
		active = kept
		for _, previous := range active {
			pair := [2]int{min(previous.entry, current.entry), max(previous.entry, current.entry)}
			if reported[pair] {
				continue
			}
			reported[pair] = true
			problems = append(problems, fmt.Sprintf("Edits %d and %d overlap at line %d in %s. Entries must change separate parts of the original file.", pair[0], pair[1], lines.lineOf(current.start), path))
		}
		active = append(active, current)
	}
	return problems
}

// replaceLineBreaks writes the line breaks of the new text with the spelling the original uses.
func replaceLineBreaks(text, lineBreak string) string {
	if lineBreak == "\n" {
		return text
	}
	return strings.ReplaceAll(text, "\n", lineBreak)
}

// spanLineBreak picks the spelling for line breaks introduced inside the replaced span: its own
// first break, else the break ending its line, else the file's first break, else LF.
func (s *editSource) spanLineBreak(text string, replacement editReplacement, start, end int) string {
	for offset := start; offset < end; offset++ {
		if lineBreak := lineBreakAt(text, offset); lineBreak != "" {
			return lineBreak
		}
	}
	if lineBreak := s.lines.ends[s.lines.lineOf(replacement.end-1)-1]; lineBreak != "" {
		return lineBreak
	}
	if lineBreak := s.lines.ends[0]; lineBreak != "" {
		return lineBreak
	}
	return "\n"
}

// lineBreakAt returns the line-break spelling starting at index, or "" for any other byte.
func lineBreakAt(text string, index int) string {
	switch text[index] {
	case '\r':
		if index+1 < len(text) && text[index+1] == '\n' {
			return "\r\n"
		}
		return "\r"
	case '\n':
		return "\n"
	}
	return ""
}

// lineIndex resolves normalized offsets to the 1-based lines that read shows and keeps the original
// spelling of every line break.
type lineIndex struct {
	starts []int
	ends   []string
}

func (l *lineIndex) lineOf(index int) int {
	return sort.SearchInts(l.starts, index+1)
}

// lineSpan is one inclusive 1-based line range.
type lineSpan struct {
	first int
	last  int
}

// strippedText is text without Unicode whitespace plus the offsets where its lines start. Removing
// line breaks keeps line k aligned with line k of the original file.
type strippedText struct {
	text       string
	lineStarts []int
}

func stripWhitespace(text string) strippedText {
	var builder strings.Builder
	builder.Grow(len(text))
	lineStarts := []int{0}
	for offset := 0; offset < len(text); {
		value, size := utf8.DecodeRuneInString(text[offset:])
		if unicode.IsSpace(value) {
			if value == '\n' {
				lineStarts = append(lineStarts, builder.Len())
			}
			offset += size
			continue
		}
		builder.WriteString(text[offset : offset+size])
		offset += size
	}
	return strippedText{text: builder.String(), lineStarts: lineStarts}
}

// lineCursor resolves monotonically increasing stripped offsets without rescanning line starts.
type lineCursor struct {
	starts []int
	line   int
}

func (c *lineCursor) lineOf(index int) int {
	for c.line < len(c.starts)-1 && c.starts[c.line+1] <= index {
		c.line++
	}
	return c.line + 1
}

func normalizeEOL(text string) string {
	return strings.ReplaceAll(strings.ReplaceAll(text, "\r\n", "\n"), "\r", "\n")
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
			if len(out) > 0 {
				previous := out[len(out)-1]
				if strings.HasPrefix(previous, "-") || strings.HasPrefix(previous, "+") {
					out = append(out, line)
				}
			}
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
	if oldCursor > 0 && oldCursor <= len(oldLines) || newCursor > 0 && newCursor <= len(newLines) {
		out = append(out, "...")
	}
	return strings.Join(out, "\n"), nil
}
